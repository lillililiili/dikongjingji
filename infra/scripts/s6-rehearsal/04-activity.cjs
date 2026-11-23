#!/usr/bin/env node
'use strict';

// S6 场景 4 活动闭环，全部用本脚本经运营端新建的机构、收费活动和场馆（不动开发种子的场次）：
//   收费报名付款 → 凭证 → 开场前退一位（原路退款）；
//   满员候补 → 占位的人取消 → 候补转正 → 15 分钟内付款 → 凭证 → 工作台核销（受权接口）→ 重复核销被拦 → 主办录结果 → 客户看到结果；
//   到馆预约付款 → 改期到今天的场次（换码）→ 工作台核销 → 旧码不能用；别家机构核销被拦。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/04-activity.cjs
// 跑完删掉本脚本建的全部数据并查残留（--keep 保留）。

const { call, fake, psql, login, key, waitFor, step, known, note, track, purgeAndReport, loginOperator, beijingDate, done } = require('./lib.cjs');
const fx = require('./fixtures.cjs');

const CITY = '370500';
const TAG = `S6活动${Date.now() % 100000}`;
const UNIT = 9900;
const VENUE_UNIT = 2900;
const iso = (ms) => new Date(ms).toISOString();
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const seats = (session) => Number(psql(`SELECT enrolled_count FROM activity_session WHERE id='${session}'`));
const notices = (code, target) => (!target ? -1 : Number(psql(`SELECT count(*) FROM notification_message WHERE event_code='${code}' AND target_id='${target}'`)));

async function pay(token, orderId) {
  await call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  const no = track(outTradeNo(orderId));
  return fake('POST', `/_control/pay/${no}?notify=1`);
}

async function main() {
  const op = await loginOperator();
  const customer = await login('dev-customer');
  const otherOrganizer = await login('dev-organizer');

  // ---- 准备：机构、收费活动（今天马上开始的一场 + 后天一场）、场馆 ----
  const org = await fx.createOrg(op, { name: `${TAG}主办机构`, cityCode: CITY });
  await fx.submitQualification(op, org.orgId);
  await fx.approveQualification(op, org.orgId);
  const account = await fx.registerMerchantAccount(op, org.orgId);
  const staff = await fx.createOperator(op, org.orgId, 's6c');
  const cover = await fx.mediaUrl(op, '/api/v1/ops/media');
  const now = Date.now();
  const soon = now + 20 * 60_000; // 20 分钟后开始，签到已开放
  const later = now + 2 * 86_400_000;
  const activity = await fx.createActivity(op, { orgId: org.orgId, cityCode: CITY, title: `${TAG}研学`, priceMinor: UNIT, coverUrl: cover.url, sessions: [
    { label: '今天', startsAt: iso(soon), endsAt: iso(soon + 2 * 3600_000), checkInOpensAt: iso(now + 60_000), enrollmentClosesAt: iso(soon - 60_000), capacity: 1 },
    { label: '后天', startsAt: iso(later), endsAt: iso(later + 2 * 3600_000), checkInOpensAt: iso(later - 3600_000), enrollmentClosesAt: iso(later - 3600_000), capacity: 5 },
  ] });
  const [today, dayAfter] = activity.sessionIds;
  const hhmm = new Date(soon + 8 * 3600_000).toISOString().slice(11, 16);
  const venue = await fx.createVenue(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}体验馆`, coverUrl: cover.url, priceMinor: VENUE_UNIT, capacity: 4,
    fromDate: beijingDate(0), toDate: beijingDate(1), startTimes: [hhmm] });
  step('准备：运营端开机构（资质、收款账户、操作员）、上线收费活动两场、场馆两天的场次', { req: 'ops organizations / merchant-accounts / activities / venues', expect: '活动和场馆都已上线' },
    activity.act.status === 'PUBLISHED' && venue.venue.status === 'PUBLISHED', { activity: activity.act.status, venue: venue.venue.status, subMchid: account.subMchid, venueSessionsAt: hhmm, generated: venue.generated });
  // 后面核销要 1 分钟后签到才开放
  await new Promise((resolve) => setTimeout(resolve, 65_000));

  const kids = [];
  for (const name of ['S6演练甲', 'S6演练乙']) {
    const r = await call('POST', '/api/v1/activity-participants', { token: customer, body: { name, birthMonth: '2015-03', guardianConfirmed: true } });
    kids.push(track(r.json?.participantId));
  }

  // ---- 1. 收费报名付款 → 凭证 → 退一位 ----
  let r = await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: activity.activityId, sessionId: dayAfter, participantIds: kids, expectedAmountMinor: UNIT * 2, noticeAgreed: true } });
  const paidEnrollment = r.json;
  track(paidEnrollment?.enrollmentId, paidEnrollment?.payment?.tradeOrderId);
  step('收费报名两人：待付款，占两个名额，建交易订单', { req: 'POST /api/v1/activity-enrollments', expect: '201 PENDING_PAYMENT 19800' },
    r.status === 201 && paidEnrollment.payment.status === 'PENDING_PAYMENT' && paidEnrollment.payment.amountMinor === UNIT * 2 && seats(dayAfter) === 2, [r.status, paidEnrollment?.payment]);
  await pay(customer, paidEnrollment.payment.tradeOrderId);
  r = await call('GET', `/api/v1/activity-enrollments/${paidEnrollment.enrollmentId}`, { token: customer });
  step('付款后两张凭证生效（一人一码），钱进主办机构的子商户号', { req: '模拟微信付款回调；GET /api/v1/activity-enrollments/{id}', expect: '凭证 ACTIVE、ACT- 开头；sub_mchid 是主办机构的' },
    r.json?.tickets?.length === 2 && r.json.tickets.every((t) => t.status === 'ACTIVE' && /^ACT-/.test(t.ticketCode))
      && psql(`SELECT sub_mchid FROM trade_payment WHERE order_id='${paidEnrollment.payment.tradeOrderId}' AND status='SUCCEEDED'`) === account.subMchid, r.json?.tickets);
  step('报名成功站内信一条', { req: '查 notification_message', expect: '1' }, await waitFor(() => notices('ACTIVITY_ENROLLED', paidEnrollment.enrollmentId) >= 1, 15) && notices('ACTIVITY_ENROLLED', paidEnrollment.enrollmentId) === 1,
    psql(`SELECT coalesce(string_agg(event_code, ','), '-') FROM notification_message WHERE target_id='${paidEnrollment.enrollmentId}'`));
  const second = r.json.tickets[1];
  r = await call('POST', `/api/v1/activity-enrollments/${paidEnrollment.enrollmentId}/cancel`, { token: customer, body: { ticketIds: [second.ticketId] } });
  const cancelled = r.json?.tickets?.find((t) => t.ticketId === second.ticketId);
  step('开场前退一位：按单价原路退款、让出名额', { req: 'POST /api/v1/activity-enrollments/{id}/cancel ticketIds=[一位]', expect: 'CANCELLED，退款 9900，名额 1' },
    r.status === 200 && cancelled?.status === 'CANCELLED' && cancelled.refund?.amountMinor === UNIT && seats(dayAfter) === 1, [r.status, cancelled?.refund, seats(dayAfter)]);
  const refundNo = track(psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${paidEnrollment.payment.tradeOrderId}'`));
  await fake('POST', `/_control/refund/${refundNo}?status=SUCCESS&notify=1`);
  const refunded = await waitFor(() => psql(`SELECT status FROM trade_refund WHERE out_refund_no='${refundNo}'`) === 'SUCCEEDED', 15);
  step('退款回调到账，交易订单已退金额 9900', { req: '模拟微信退款回调', expect: 'refund SUCCEEDED，refunded_minor 9900' },
    refunded && psql(`SELECT refunded_minor FROM trade_order WHERE id='${paidEnrollment.payment.tradeOrderId}'`) === String(UNIT), psql(`SELECT status || ':' || refunded_minor FROM trade_order WHERE id='${paidEnrollment.payment.tradeOrderId}'`));

  // ---- 2. 候补转正付款 ----
  // S6-02：运营端契约 1.1.0 起活动有“允许候补”开关，按页面的做法读详情、带全部字段和版本号改一次（不再用 SQL 绕过）。
  const opsDetail = (await call('GET', `/api/v1/ops/activities/${activity.activityId}`, { token: op })).json;
  r = await call('PUT', `/api/v1/ops/activities/${activity.activityId}`, { token: op, body: {
    type: opsDetail.type, title: opsDetail.title, subtitle: opsDetail.subtitle, coverUrl: opsDetail.coverUrl,
    galleryUrls: opsDetail.galleryUrls, organizerId: opsDetail.organizer.organizationId,
    coOrganizerId: opsDetail.coOrganizer?.organizationId ?? null, cityCode: opsDetail.cityCode, venueName: opsDetail.venueName,
    venueAddress: opsDetail.venueAddress, scheduleNote: opsDetail.scheduleNote, priceMode: opsDetail.priceMode,
    priceAmountMinor: opsDetail.priceAmountMinor, minAge: opsDetail.minAge, maxAge: opsDetail.maxAge,
    eligibilityNote: opsDetail.eligibilityNote, seatNote: opsDetail.seatNote, refundNote: opsDetail.refundNote,
    enrollmentNotice: opsDetail.enrollmentNotice, waitlistAllowed: true, teamEnrollmentAllowed: false, version: opsDetail.version } });
  const switched = r;
  const lastChange = (await call('GET', `/api/v1/ops/activities/${activity.activityId}/changes`, { token: op })).json?.[0]?.summary;
  const appDetail = (await call('GET', `/api/v1/activities/${activity.activityId}`)).json;
  const waitlistColumn = psql(`SELECT waitlist_allowed FROM activity WHERE id='${activity.activityId}'`);
  known('S6-02', '运营端给活动打开“允许候补”（S5-3 候补已上线）',
    { req: 'PUT /api/v1/ops/activities/{id} waitlistAllowed=true（运营端契约 1.1.0）；查改动记录、activity.waitlist_allowed、App 详情', expect: '200 waitlistAllowed=true；改动记录“打开候补”；App 详情 waitlistAllowed=true' },
    switched.status === 200 && switched.json?.waitlistAllowed === true && waitlistColumn === 't'
      && String(lastChange || '').includes('打开候补') && appDetail?.waitlistAllowed === true,
    { status: switched.status, code: switched.json?.code, waitlistAllowed: waitlistColumn, lastChange, app: appDetail?.waitlistAllowed });
  r = await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: activity.activityId, sessionId: today, participantIds: [kids[1]], expectedAmountMinor: UNIT, noticeAgreed: true } });
  const holder = r.json;
  track(holder?.enrollmentId, holder?.payment?.tradeOrderId);
  step('乙报今天这一场（名额 1），占满', { req: 'POST /api/v1/activity-enrollments', expect: '201，场次满员' }, r.status === 201 && seats(today) === 1, [r.status, r.json?.code]);
  r = await call('POST', '/api/v1/activity-waitlists', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: activity.activityId, sessionId: today, participantIds: [kids[0]], noticeAgreed: true } });
  const waitlist = r.json;
  track(waitlist?.waitlistId);
  step('甲排候补，显示以后要付的价格', { req: 'POST /api/v1/activity-waitlists', expect: '201 WAITING，unitAmountMinor 9900' }, r.status === 201 && waitlist.status === 'WAITING' && waitlist.unitAmountMinor === UNIT, [r.status, waitlist?.status, r.json?.code]);
  r = await call('POST', `/api/v1/activity-enrollments/${holder.enrollmentId}/cancel`, { token: customer, body: {} });
  step('乙没付款就取消，交易订单关闭、名额让出', { req: 'POST /api/v1/activity-enrollments/{乙}/cancel', expect: 'payment CLOSED' }, r.status === 200 && r.json.payment.status === 'CLOSED', [r.status, r.json?.payment?.status]);
  r = await call('GET', `/api/v1/activity-waitlists/${waitlist.waitlistId}`, { token: customer });
  step('候补按顺序转正（OFFERED），站内信一条', { req: 'GET /api/v1/activity-waitlists/{id}', expect: 'OFFERED' },
    r.json?.status === 'OFFERED' && notices('ACTIVITY_WAITLIST_OFFERED', waitlist.waitlistId) === 1, [r.json?.status, notices('ACTIVITY_WAITLIST_OFFERED', waitlist.waitlistId)]);
  r = await call('POST', `/api/v1/activity-waitlists/${waitlist.waitlistId}/confirm`, { token: customer, body: { expectedAmountMinor: UNIT } });
  const offered = r.json;
  track(offered?.enrollmentId, offered?.tradeOrderId);
  const due = (await call('GET', `/api/v1/activity-enrollments/${offered.enrollmentId}`, { token: customer })).json?.payment;
  step('确认转正：建交易订单，付款截止 = 保留截止（15 分钟内）', { req: 'POST /api/v1/activity-waitlists/{id}/confirm', expect: '200，tradeOrderId，payBefore ≤ 15 分钟后' },
    r.status === 200 && offered.tradeOrderId && due?.status === 'PENDING_PAYMENT' && new Date(due.payBefore) - Date.now() <= 15 * 60_000 + 5000, [r.status, due]);
  await pay(customer, offered.tradeOrderId);
  r = await call('GET', `/api/v1/activity-waitlists/${waitlist.waitlistId}`, { token: customer });
  const enrolled = (await call('GET', `/api/v1/activity-enrollments/${offered.enrollmentId}`, { token: customer })).json;
  const ticket = enrolled?.tickets?.[0];
  step('付款后候补变为报名成功，凭证生效', { req: '模拟微信付款回调；GET 候补和报名', expect: 'ENROLLED，凭证 ACTIVE' }, r.json?.status === 'ENROLLED' && ticket?.status === 'ACTIVE', [r.json?.status, ticket?.status]);

  // ---- 3. 工作台核销 → 结果记录 ----
  r = await call('GET', '/api/v1/checkin-workbench', { token: staff.token });
  const todaySession = r.json?.sessions?.find((s) => s.sessionId === today);
  step('主办机构操作员的工作台：能核销活动，今天这一场正在核销', { req: 'GET /api/v1/checkin-workbench', expect: 'kinds 含 ACTIVITY，场次 phase OPEN' },
    r.status === 200 && r.json.kinds.includes('ACTIVITY') && todaySession?.phase === 'OPEN', [r.status, r.json?.kinds, todaySession]);
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: otherOrganizer, body: { code: ticket.ticketCode, method: 'MANUAL' } });
  if (r.json?.details?.record?.recordId) track(r.json.details.record.recordId);
  step('别家机构的核销员核销这张凭证被拦下', { req: 'POST /api/v1/checkin-workbench/verifications（dev-organizer）', expect: '409 CHECKIN_OTHER_ORGANIZATION' }, r.status === 409 && r.json.code === 'CHECKIN_OTHER_ORGANIZATION', [r.status, r.json?.code]);
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: staff.token, body: { code: ` ${ticket.ticketCode.toLowerCase()} `, method: 'SCAN', sessionKind: 'ACTIVITY', sessionId: today } });
  track(r.json?.recordId);
  step('主办机构操作员扫码核销成功（不分大小写、去空格）', { req: 'POST /api/v1/checkin-workbench/verifications（主办操作员）', expect: '200 CHECKED_IN，持有人只给姓' },
    r.status === 200 && r.json.outcome === 'CHECKED_IN' && /^\S\*\*$/.test(r.json.holderName || ''), [r.status, r.json?.outcome, r.json?.holderName]);
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: staff.token, body: { code: ticket.ticketCode, method: 'MANUAL', sessionKind: 'ACTIVITY', sessionId: today } });
  if (r.json?.details?.record?.recordId) track(r.json.details.record.recordId);
  step('同一张凭证再核销被拦下（重复）', { req: '再次 verifications', expect: '409 CHECKIN_REPEATED' }, r.status === 409 && r.json.code === 'CHECKIN_REPEATED', [r.status, r.json?.code]);
  r = await call('GET', '/api/v1/checkin-workbench/records', { token: staff.token });
  step('本人核销记录里有这两条', { req: 'GET /api/v1/checkin-workbench/records', expect: '≥2 条，含 CHECKED_IN 和 REPEATED' },
    r.status === 200 && ['CHECKED_IN', 'REPEATED'].every((o) => (r.json.records || r.json.items || []).some((x) => x.outcome === o)), (r.json?.records || r.json?.items || []).map((x) => x.outcome));
  r = await call('PUT', `/api/v1/provider/activity-tickets/${ticket.ticketId}/result`, { token: otherOrganizer, body: { completion: 'COMPLETED' } });
  step('别家机构不能录结果', { req: 'PUT /api/v1/provider/activity-tickets/{id}/result（dev-organizer）', expect: '404' }, r.status === 404, r.status);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${ticket.ticketId}/result`, { token: staff.token, body: { completion: 'COMPLETED', scoreText: 'S6 演练：完成任务 5/5', certificateNote: '证书现场发放（演练）' } });
  step('主办机构给已签到的孩子录结果', { req: 'PUT /api/v1/provider/activity-tickets/{id}/result', expect: '200 COMPLETED' }, r.status === 200 && r.json.result?.completion === 'COMPLETED', [r.status, r.json?.result]);
  r = await call('GET', `/api/v1/activity-enrollments/${offered.enrollmentId}`, { token: customer });
  step('客户在报名详情看到结果', { req: 'GET /api/v1/activity-enrollments/{id}', expect: 'ticket.result.completion COMPLETED' }, r.json?.tickets?.[0]?.result?.completion === 'COMPLETED', r.json?.tickets?.[0]?.result);

  // ---- 4. 到馆预约改期与核销 ----
  const dateOf = (offset) => beijingDate(offset);
  const sessionsOn = async (date) => (await call('GET', `/api/v1/venues/${venue.venueId}/sessions?cityCode=${CITY}&projectId=${venue.projectId}&date=${date}`)).json?.sessions ?? [];
  const todayVenue = (await sessionsOn(dateOf(0)))[0];
  const tomorrowVenue = (await sessionsOn(dateOf(1)))[0];
  track(todayVenue?.sessionId, tomorrowVenue?.sessionId);
  r = await call('POST', '/api/v1/venue-bookings', { token: customer, headers: { 'Idempotency-Key': key() }, body: { sessionId: tomorrowVenue.sessionId, participantIds: [kids[0]], expectedAmountMinor: VENUE_UNIT, noticeAgreed: true } });
  const booking = r.json;
  track(booking?.bookingId, booking?.payment?.tradeOrderId);
  step('预约明天的场次：待付款', { req: 'POST /api/v1/venue-bookings', expect: '201 待付款' }, r.status === 201 && booking.payment?.tradeOrderId, [r.status, r.json?.code, booking?.status]);
  await pay(customer, booking.payment.tradeOrderId);
  r = await call('GET', `/api/v1/venue-bookings/${booking.bookingId}`, { token: customer });
  const oldCode = r.json?.code;
  step('付款后预约成功，一单一码', { req: '模拟微信付款回调；GET /api/v1/venue-bookings/{id}', expect: 'CONFIRMED，VEN- 码，可改期' }, r.json?.status === 'CONFIRMED' && /^VEN-/.test(oldCode || '') && r.json.reschedulable, [r.json?.status, r.json?.reschedulable]);
  r = await call('POST', `/api/v1/venue-bookings/${booking.bookingId}/reschedule`, { token: customer, headers: { 'If-Match': r.etag }, body: { sessionId: todayVenue.sessionId } });
  const newCode = r.json?.code;
  step('改期到今天的场次：换码，名额跟着走', { req: 'POST /api/v1/venue-bookings/{id}/reschedule', expect: '200，新码 ≠ 旧码' },
    r.status === 200 && r.json.session?.sessionId === todayVenue.sessionId && newCode && newCode !== oldCode, [r.status, r.json?.code === oldCode, r.json?.code]);
  step('改期站内信一条', { req: '查 notification_message', expect: 'VENUE_RESCHEDULED 1 条' }, await waitFor(() => notices('VENUE_RESCHEDULED', booking.bookingId) === 1, 10), notices('VENUE_RESCHEDULED', booking.bookingId));
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: staff.token, body: { code: oldCode, method: 'MANUAL' } });
  if (r.json?.details?.record?.recordId) track(r.json.details.record.recordId);
  step('旧码不能再核销', { req: 'verifications 旧码', expect: '404 或 409' }, [404, 409].includes(r.status), [r.status, r.json?.code]);
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: staff.token, body: { code: newCode, method: 'SCAN', sessionKind: 'VENUE', sessionId: todayVenue.sessionId } });
  track(r.json?.recordId);
  step('场馆操作员用工作台核销新码', { req: 'POST /api/v1/checkin-workbench/verifications（VENUE）', expect: '200 CHECKED_IN' }, r.status === 200 && r.json.outcome === 'CHECKED_IN' && r.json.kind === 'VENUE', [r.status, r.json?.outcome, r.json?.code]);
  r = await call('GET', `/api/v1/venue-bookings/${booking.bookingId}`, { token: customer });
  step('客户看到已到馆', { req: 'GET /api/v1/venue-bookings/{id}', expect: '已核销（CHECKED_IN）' }, r.json?.status === 'CHECKED_IN', r.json?.status);
  note(`机构 ${org.orgId}，活动 ${activity.activityId}，场馆 ${venue.venueId}`);
}

main().then(() => done(() => purgeAndReport())(), (error) => done(() => purgeAndReport())(error));
