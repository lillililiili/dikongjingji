#!/usr/bin/env node
'use strict';

// S5-3 活动候补、团队报名、改期、退改记录、结果记录（docs/redesign/S5-3-活动候补团队改期与结果.md）。
// 对着本机后端 + 本机模拟微信跑：
//   免费候补：满员排队、重复/已报名的拒绝、重放、按顺序“放得下的先转正”、站内信、确认报名、放弃、保留超时作废；
//   收费候补：转正后去付款（建交易订单，付款截止 = 保留截止）、付款后报名成功；
//   改期：整单改到同一活动另一场（换新凭证、名额转移、原时间）、重复提交不变、满员和别的活动被拒；
//   退改记录：带申请原因的取消、退款进度到“已到账”带到账时间；
//   团队报名：核对名单（年龄、重复）、2–20 人、队员不进参与人页、领队手机打码、一次付清、按人退；
//   结果记录：只能给已签到的录、版本号、别的机构看不到、客户看得到、站内信只发一次。
// 脚本会临时改两三个示例场次的名额和时间（结束时改回去），所以最好对着自己的临时数据库跑；跑完清掉自己建的数据。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> \
//     node infra/scripts/trade-smoke/activity-waitlist.cjs
// 后端要带 --activity.waitlist.expire-delay-ms=2000（候补保留超时的定时任务调短；默认 60 秒也能跑，只是慢）。

const { BUYER, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子 R__dev_activity_catalog.sql
const OPEN_DAY = 'a2000000-0000-4000-8000-000000000005'; // 无人机开放体验日，免费，主办：东营低空与机器人体验馆（dev-organizer）
const FREE_SESSION = 'a3000000-0000-4000-8000-000000000503';
const FREE_SESSION_2 = 'a3000000-0000-4000-8000-000000000502';
const STUDY = 'a2000000-0000-4000-8000-000000000002'; // 机器人研学体验，¥168/人，6–12 岁，主办已登记收款账户
const PAID_A = 'a3000000-0000-4000-8000-000000000201';
const PAID_C = 'a3000000-0000-4000-8000-000000000203';
const CONTEST_SESSION = 'a3000000-0000-4000-8000-000000000101'; // 别的活动的场次
const UNIT = 16800;
const KIDS = ['候补甲', '候补乙', '候补丙', '候补丁'];
const MEMBERS = ['冒烟队员一', '冒烟队员二', '冒烟队员三'];
const SESSIONS = [FREE_SESSION, FREE_SESSION_2, PAID_A, PAID_C];

const createdEnrollments = new Set();
const savedSessions = {};

const seats = (session) => Number(psql(`SELECT enrolled_count FROM activity_session WHERE id='${session}'`));
const capacity = (session) => Number(psql(`SELECT capacity FROM activity_session WHERE id='${session}'`));
const setCapacity = (session, value) => psql(`UPDATE activity_session SET capacity=${value} WHERE id='${session}'`);
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
const readEnrollment = (token, id) => call('GET', `/api/v1/activity-enrollments/${id}`, { token });
const readWaitlist = (token, id) => call('GET', `/api/v1/activity-waitlists/${id}`, { token });
const notices = (eventCode, targetId) => Number(psql(`SELECT count(*) FROM notification_message WHERE event_code='${eventCode}' AND target_id='${targetId}'`));

async function enroll(token, activityId, sessionId, participantIds, amount) {
  const body = { activityId, sessionId, participantIds, noticeAgreed: true };
  if (amount !== undefined) body.expectedAmountMinor = amount;
  const r = await call('POST', '/api/v1/activity-enrollments', { token, headers: { 'Idempotency-Key': key() }, body });
  if (r.json?.enrollmentId) createdEnrollments.add(r.json.enrollmentId);
  return r;
}

async function joinWaitlist(token, activityId, sessionId, participantIds, idempotencyKey = key()) {
  return call('POST', '/api/v1/activity-waitlists', {
    token,
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { activityId, sessionId, participantIds, noticeAgreed: true },
  });
}

async function pay(token, orderId) {
  await launch(token, orderId);
  await fake('POST', `/_control/pay/${outTradeNo(orderId)}?notify=1`);
}

async function kids(token) {
  for (const name of KIDS) {
    await call('POST', '/api/v1/activity-participants', { token, body: { name, birthMonth: '2016-05', guardianConfirmed: true } });
  }
  const r = await call('GET', '/api/v1/activity-participants', { token });
  return KIDS.map((name) => r.json.items.find((p) => p.name === name)?.participantId);
}

function saveSessions() {
  for (const session of SESSIONS) {
    savedSessions[session] = psql(`SELECT capacity || '|' || starts_at || '|' || COALESCE(ends_at::text, '') || '|' || COALESCE(check_in_opens_at::text, '') || '|' || enrollment_closes_at FROM activity_session WHERE id='${session}'`);
  }
}

function cleanup() {
  psql(`
    BEGIN;
    CREATE TEMP TABLE smoke_enrollments ON COMMIT DROP AS
      SELECT DISTINCT e.id FROM activity_enrollment e
      LEFT JOIN activity_enrollment_ticket t ON t.enrollment_id = e.id
      LEFT JOIN activity_participant p ON p.id = t.participant_id
      WHERE e.account_id = '${BUYER}'
        AND (e.id IN (${[...createdEnrollments].map((id) => `'${id}'`).join(',') || 'NULL'})
             OR p.name IN (${[...KIDS, ...MEMBERS].map((n) => `'${n}'`).join(',')})
             OR e.id IN (SELECT enrollment_id FROM activity_waitlist WHERE account_id = '${BUYER}' AND enrollment_id IS NOT NULL));
    UPDATE activity_session s SET enrolled_count = s.enrolled_count - t.n
      FROM (SELECT session_id, count(*) AS n FROM activity_enrollment_ticket
            WHERE enrollment_id IN (SELECT id FROM smoke_enrollments) AND status <> 'CANCELLED' GROUP BY session_id) t
     WHERE s.id = t.session_id;
    UPDATE activity_session s SET enrolled_count = s.enrolled_count - w.n
      FROM (SELECT session_id, sum(people) AS n FROM activity_waitlist
            WHERE account_id = '${BUYER}' AND status = 'OFFERED' GROUP BY session_id) w
     WHERE s.id = w.session_id;
    DELETE FROM activity_waitlist_member WHERE waitlist_id IN (SELECT id FROM activity_waitlist WHERE account_id = '${BUYER}');
    DELETE FROM activity_waitlist WHERE account_id = '${BUYER}';
    DELETE FROM activity_ticket_result WHERE ticket_id IN (SELECT id FROM activity_enrollment_ticket WHERE enrollment_id IN (SELECT id FROM smoke_enrollments));
    DELETE FROM activity_checkin_record WHERE ticket_id IN (SELECT id FROM activity_enrollment_ticket WHERE enrollment_id IN (SELECT id FROM smoke_enrollments));
    DELETE FROM activity_enrollment_event WHERE enrollment_id IN (SELECT id FROM smoke_enrollments);
    -- 凭证引用退改记录，先删凭证。
    DELETE FROM activity_enrollment_ticket WHERE enrollment_id IN (SELECT id FROM smoke_enrollments);
    DELETE FROM activity_enrollment_change WHERE enrollment_id IN (SELECT id FROM smoke_enrollments);
    DELETE FROM activity_enrollment_team WHERE enrollment_id IN (SELECT id FROM smoke_enrollments);
    DELETE FROM activity_enrollment WHERE id IN (SELECT id FROM smoke_enrollments);
    DELETE FROM activity_participant WHERE owner_id = '${BUYER}' AND name IN (${[...KIDS, ...MEMBERS].map((n) => `'${n}'`).join(',')});
    DELETE FROM notification_message WHERE recipient_id = '${BUYER}'
      AND event_code IN ('ACTIVITY_WAITLIST_OFFERED', 'ACTIVITY_RESCHEDULED', 'ACTIVITY_RESULT_RECORDED', 'ACTIVITY_ENROLLED', 'ACTIVITY_REFUNDED');
    COMMIT;
  `);
  for (const [session, saved] of Object.entries(savedSessions)) {
    const [cap, starts, ends, opens, closes] = saved.split('|');
    psql(`UPDATE activity_session SET capacity=${cap}, starts_at='${starts}', ends_at=${ends ? `'${ends}'` : 'NULL'},
          check_in_opens_at=${opens ? `'${opens}'` : 'NULL'}, enrollment_closes_at='${closes}' WHERE id='${session}'`);
  }
  cleanupBuyer();
}

async function main() {
  saveSessions();
  const customer = await login('dev-customer');
  const organizer = await login('dev-organizer');
  const otherOperator = await login('dev-provider');
  const [k1, k2, k3, k4] = await kids(customer);
  check('four smoke children exist', k1 && k2 && k3 && k4, [k1, k2, k3, k4]);

  // ---- 1. 免费候补 ----
  setCapacity(FREE_SESSION, seats(FREE_SESSION) + 1);
  let r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k2]);
  check('a session with seats left cannot be waited for', r.status === 409 && r.json.code === 'ACT_WAITLIST_NOT_AVAILABLE', r.json);
  r = await enroll(customer, OPEN_DAY, FREE_SESSION, [k1]);
  const e1 = r.json;
  check('the last seat is taken by a free sign-up', r.status === 201 && seats(FREE_SESSION) === capacity(FREE_SESSION), r.json);
  r = await call('GET', `/api/v1/activities/${OPEN_DAY}?cityCode=370500`);
  check('the session now reads full with a waitlist', r.json.sessions.find((s) => s.sessionId === FREE_SESSION)?.status === 'FULL_WAITLIST', r.json.sessions);

  const w1Key = key();
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k2, k3], w1Key);
  const w1 = r.json;
  check('two children join the waitlist first', r.status === 201 && w1.status === 'WAITING' && w1.position === 1 && w1.ahead === 0 && w1.people === 2 && w1.unitAmountMinor === null, r.json);
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k2, k3], w1Key);
  check('replaying the same key returns the same waitlist', r.status === 200 && r.json.waitlistId === w1.waitlistId, r.json);
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k3], w1Key);
  check('the same key for another request is refused', r.status === 409 && r.json.code === 'ACT_IDEMPOTENCY_KEY_REUSED', r.json);
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k2]);
  check('a child already waiting cannot wait twice', r.status === 409 && r.json.code === 'ACT_WAITLIST_ALREADY_JOINED', r.json);
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k1]);
  check('a child already signed up cannot wait', r.status === 409 && r.json.code === 'ACT_PARTICIPANT_ALREADY_ENROLLED', r.json);
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k4]);
  const w2 = r.json;
  check('a second waitlist queues behind', r.status === 201 && w2.position === 2 && w2.ahead === 1, r.json);
  r = await call('GET', '/api/v1/activity-waitlists?active=true&page=1&size=20', { token: customer });
  check('both show among the active waitlists', r.status === 200 && r.json.items.filter((w) => [w1.waitlistId, w2.waitlistId].includes(w.waitlistId)).length === 2, r.json);

  // 一个名额放回来：排在最前的要 2 个放不下，后面要 1 个的先转正。
  r = await call('POST', `/api/v1/activity-enrollments/${e1.enrollmentId}/cancel`, { token: customer, body: { reason: 'TIME_CONFLICT' } });
  check('cancelling with a reason records a change', r.status === 200 && r.json.changes?.[0]?.kind === 'CANCEL' && r.json.changes[0].reason === 'TIME_CONFLICT' && r.json.changes[0].refundAmountMinor === null, r.json?.changes);
  const freeChange = r.json.changes?.[0]?.changeId;
  r = await call('GET', `/api/v1/activity-enrollments/${e1.enrollmentId}/changes/${freeChange}`, { token: customer });
  check('the change detail lists the cancelled ticket', r.status === 200 && r.json.tickets.length === 1 && r.json.tickets[0].issued === false && r.json.tickets[0].status === 'CANCELLED', r.json);
  r = await readWaitlist(customer, w1.waitlistId);
  check('the first waitlist (2 people) keeps waiting at the front', r.json.status === 'WAITING' && r.json.position === 1, r.json);
  r = await readWaitlist(customer, w2.waitlistId);
  check('the second waitlist (1 person) is offered the seat', r.json.status === 'OFFERED' && r.json.holdUntil && seats(FREE_SESSION) === capacity(FREE_SESSION), r.json);
  const hold = new Date(r.json.holdUntil).getTime() - Date.now();
  check('the seat is held for about 15 minutes', hold > 13 * 60_000 && hold <= 15 * 60_000 + 5_000, r.json.holdUntil);
  check('a waitlist-offered message was written once', notices('ACTIVITY_WAITLIST_OFFERED', w2.waitlistId) === 1);
  r = await enroll(customer, OPEN_DAY, FREE_SESSION, [k1]);
  check('nobody can take the held seat', r.status === 409, r.json);

  // 放弃：名额再放回来，排在最前的仍然放不下。
  r = await call('POST', `/api/v1/activity-waitlists/${w2.waitlistId}/abandon`, { token: customer });
  check('abandoning an offer gives the seat back', r.status === 200 && r.json.status === 'ABANDONED' && seats(FREE_SESSION) === capacity(FREE_SESSION) - 1, r.json);
  r = await readWaitlist(customer, w1.waitlistId);
  check('one free seat is still not enough for the first waitlist', r.json.status === 'WAITING', r.json);

  // 再放一个：两个名额，排在最前的转正。
  setCapacity(FREE_SESSION, capacity(FREE_SESSION) + 1);
  r = await enroll(customer, OPEN_DAY, FREE_SESSION, [k1]);
  const e2 = r.json;
  check('a newcomer can take a seat the queue cannot use', r.status === 201, r.json);
  r = await call('POST', `/api/v1/activity-enrollments/${e2.enrollmentId}/cancel`, { token: customer, body: {} });
  r = await readWaitlist(customer, w1.waitlistId);
  check('with two seats back the first waitlist is offered', r.json.status === 'OFFERED' && seats(FREE_SESSION) === capacity(FREE_SESSION), r.json);

  // 保留超时：定时任务作废，名额放回。
  psql(`UPDATE activity_waitlist SET offer_expires_at = now() - interval '1 second' WHERE id='${w1.waitlistId}'`);
  const expired = await waitFor(() => psql(`SELECT status FROM activity_waitlist WHERE id='${w1.waitlistId}'`) === 'EXPIRED', 90);
  r = await readWaitlist(customer, w1.waitlistId);
  check('an offer not taken in time expires and frees its seats', expired && r.json.status === 'EXPIRED' && seats(FREE_SESSION) === capacity(FREE_SESSION) - 2, [r.json.status, seats(FREE_SESSION), capacity(FREE_SESSION)]);
  r = await call('POST', `/api/v1/activity-waitlists/${w1.waitlistId}/confirm`, { token: customer, body: {} });
  check('an expired offer cannot be confirmed', r.status === 409 && r.json.code === 'ACT_WAITLIST_STATE_INVALID', r.json);

  // 免费的确认报名
  setCapacity(FREE_SESSION, seats(FREE_SESSION));
  r = await joinWaitlist(customer, OPEN_DAY, FREE_SESSION, [k4]);
  const w3 = r.json;
  r = await enroll(customer, OPEN_DAY, FREE_SESSION, [k1]);
  check('the session is full again', r.status === 409 && r.json.code === 'ACT_SESSION_FULL', r.json);
  setCapacity(FREE_SESSION, capacity(FREE_SESSION) + 1);
  r = await enroll(customer, OPEN_DAY, FREE_SESSION, [k1]);
  const e3 = r.json;
  await call('POST', `/api/v1/activity-enrollments/${e3.enrollmentId}/cancel`, { token: customer, body: {} });
  r = await readWaitlist(customer, w3.waitlistId);
  check('the third waitlist is offered', r.json.status === 'OFFERED', r.json);
  r = await call('POST', `/api/v1/activity-waitlists/${w3.waitlistId}/confirm`, { token: customer, body: {} });
  check('confirming a free offer signs up at once', r.status === 200 && r.json.status === 'ENROLLED' && r.json.enrollmentId, r.json);
  createdEnrollments.add(r.json.enrollmentId);
  const fromWaitlist = await readEnrollment(customer, r.json.enrollmentId);
  check('the new sign-up has a valid ticket and took no extra seat', fromWaitlist.json.tickets[0]?.status === 'ACTIVE' && seats(FREE_SESSION) === capacity(FREE_SESSION), [fromWaitlist.json.tickets, seats(FREE_SESSION)]);
  r = await call('POST', `/api/v1/activity-waitlists/${w3.waitlistId}/confirm`, { token: customer, body: {} });
  check('confirming again returns the same sign-up', r.status === 200 && r.json.enrollmentId === fromWaitlist.json.enrollmentId, r.json);

  // ---- 2. 收费候补：去付款 ----
  setCapacity(PAID_C, seats(PAID_C) + 1);
  r = await enroll(customer, STUDY, PAID_C, [k2], UNIT);
  const holder = r.json;
  check('a paid sign-up takes the last seat', r.status === 201 && holder.payment.status === 'PENDING_PAYMENT', r.json);
  r = await joinWaitlist(customer, STUDY, PAID_C, [k3]);
  const w4 = r.json;
  check('paid waitlist shows the price to pay later', r.status === 201 && w4.unitAmountMinor === UNIT && w4.amountMinor === UNIT, r.json);
  r = await call('POST', `/api/v1/activity-enrollments/${holder.enrollmentId}/cancel`, { token: customer, body: {} });
  check('cancelling the unpaid sign-up closes it', r.status === 200 && r.json.payment.status === 'CLOSED', r.json);
  r = await readWaitlist(customer, w4.waitlistId);
  check('the closed order frees the seat for the paid waitlist', r.json.status === 'OFFERED' && r.json.tradeOrderId === null, r.json);
  r = await call('POST', `/api/v1/activity-waitlists/${w4.waitlistId}/confirm`, { token: customer, body: {} });
  check('a paid offer needs the expected amount', r.status === 400, r.json);
  r = await call('POST', `/api/v1/activity-waitlists/${w4.waitlistId}/confirm`, { token: customer, body: { expectedAmountMinor: UNIT } });
  const w4Hold = r.json.holdUntil;
  check('confirming a paid offer opens a trade order due when the hold ends', r.status === 200 && r.json.status === 'OFFERED' && r.json.tradeOrderId && r.json.enrollmentId, r.json);
  createdEnrollments.add(r.json.enrollmentId);
  const paidFromWaitlist = r.json.enrollmentId;
  const due = (await readEnrollment(customer, paidFromWaitlist)).json.payment;
  check('the payment deadline is the hold deadline', due.status === 'PENDING_PAYMENT' && Math.abs(new Date(due.payBefore) - new Date(w4Hold)) < 1000, [due, w4Hold]);
  await pay(customer, r.json.tradeOrderId);
  r = await readWaitlist(customer, w4.waitlistId);
  check('paying turns the waitlist into a sign-up', r.json.status === 'ENROLLED' && seats(PAID_C) === capacity(PAID_C), r.json);

  // ---- 3. 改期 ----
  const beforeA = seats(PAID_A);
  const beforeC = seats(PAID_C);
  let paid = (await readEnrollment(customer, paidFromWaitlist)).json;
  const oldCode = paid.tickets[0].ticketCode;
  check('a paid sign-up can be rescheduled', paid.reschedulable === true, paid);
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/reschedule`, { token: customer, body: { targetSessionId: CONTEST_SESSION } });
  check('another activity\'s session is refused', r.status === 409 && r.json.code === 'ACT_RESCHEDULE_NOT_ALLOWED', r.json);
  setCapacity(PAID_A, seats(PAID_A));
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/reschedule`, { token: customer, body: { targetSessionId: PAID_A } });
  check('a full session is refused', r.status === 409 && r.json.code === 'ACT_SESSION_FULL', r.json);
  setCapacity(PAID_A, seats(PAID_A) + 5);
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/reschedule`, { token: customer, body: { targetSessionId: PAID_A, reason: 'PLAN_CHANGED' } });
  paid = r.json;
  check('rescheduling moves the sign-up to the new session', r.status === 200 && paid.session.sessionId === PAID_A && paid.reschedule?.previousSession?.sessionId === PAID_C, r.json);
  check('the old ticket is gone from the list and the new one has a new code', paid.tickets.length === 1 && paid.tickets[0].status === 'ACTIVE' && paid.tickets[0].ticketCode !== oldCode, paid.tickets);
  check('the seat moved with it', seats(PAID_A) === beforeA + 1 && seats(PAID_C) === beforeC - 1, [seats(PAID_A), beforeA, seats(PAID_C), beforeC]);
  check('a reschedule change is recorded', paid.changes[0]?.kind === 'RESCHEDULE' && paid.changes[0].reason === 'PLAN_CHANGED' && paid.changes[0].toSession?.sessionId === PAID_A, paid.changes);
  check('the old ticket was voided as rescheduled', psql(`SELECT count(*) FROM activity_enrollment_ticket WHERE enrollment_id='${paidFromWaitlist}' AND cancel_cause='RESCHEDULED'`) === '1');
  r = await call('GET', `/api/v1/activity-enrollments/${paidFromWaitlist}/changes/${paid.changes[0].changeId}`, { token: customer });
  check('the reschedule detail has the voided and the new ticket', r.status === 200 && r.json.tickets.length === 2 && r.json.tickets.some((t) => t.issued && /^ACT-/.test(t.ticketCode)) && r.json.tickets.some((t) => !t.issued && t.ticketCode === null), r.json);
  check('a rescheduled message was written', notices('ACTIVITY_RESCHEDULED', paidFromWaitlist) === 1);
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/reschedule`, { token: customer, body: { targetSessionId: PAID_A } });
  check('rescheduling to where it already is changes nothing', r.status === 200 && r.json.changes.length === paid.changes.length && seats(PAID_A) === beforeA + 1, r.json?.changes);
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/reschedule`, { token: customer, body: { targetSessionId: PAID_C, reason: 'NOT_A_REASON' } });
  check('an unknown reason is a bad request', r.status === 400, r.json);

  // 退款进度
  r = await call('POST', `/api/v1/activity-enrollments/${paidFromWaitlist}/cancel`, { token: customer, body: { reason: 'UNWELL' } });
  const refundChange = r.json.changes?.[0];
  check('cancelling the rescheduled ticket refunds one unit with the reason', r.status === 200 && refundChange?.kind === 'CANCEL' && refundChange.reason === 'UNWELL' && refundChange.refundAmountMinor === UNIT && ['REQUESTED', 'PROCESSING'].includes(refundChange.refundStatus), refundChange);
  const tradeOrder = paid.payment.tradeOrderId;
  await waitFor(() => psql(`SELECT status FROM trade_refund WHERE order_id='${tradeOrder}'`) === 'PROCESSING', 30);
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${tradeOrder}'`);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  r = await call('GET', `/api/v1/activity-enrollments/${paidFromWaitlist}/changes/${refundChange.changeId}`, { token: customer });
  check('the refund detail shows the money back with its time', r.status === 200 && r.json.change.refundStatus === 'SUCCEEDED' && r.json.change.refundSucceededAt, r.json.change);

  // ---- 4. 团队报名 ----
  const roster = [
    { name: MEMBERS[0], birthMonth: '2016-03' },
    { name: MEMBERS[1], birthMonth: '2015-08' },
    { name: MEMBERS[2], birthMonth: '2023-01' },
    { name: MEMBERS[0], birthMonth: '2016-03' },
  ];
  r = await call('POST', '/api/v1/activity-team-enrollments/check', { token: customer, body: { sessionId: PAID_A, members: roster } });
  check('roster check counts two eligible members', r.status === 200 && r.json.eligibleCount === 2, r.json);
  check('the toddler is too young and the repeat is a duplicate', r.json.members?.[2]?.reason === 'UNDER_MIN_AGE' && r.json.members?.[3]?.reason === 'DUPLICATED', r.json.members);
  const team = {
    activityId: STUDY, sessionId: PAID_A, teamName: '冒烟物流职工子女队', leaderName: '王女士', leaderPhone: '13800002026',
    members: roster.slice(0, 2), noticeAgreed: true, guardianConsent: true, expectedAmountMinor: UNIT * 2,
  };
  r = await call('POST', '/api/v1/activity-team-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { ...team, members: roster.slice(0, 1), expectedAmountMinor: UNIT } });
  check('a team needs at least two people', r.status === 400, r.json);
  r = await call('POST', '/api/v1/activity-team-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { ...team, leaderPhone: '12345' } });
  check('a bad leader phone is refused', r.status === 400, r.json);
  const teamKey = key();
  const beforeTeam = seats(PAID_A);
  r = await call('POST', '/api/v1/activity-team-enrollments', { token: customer, headers: { 'Idempotency-Key': teamKey }, body: team });
  const teamEnrollment = r.json;
  if (teamEnrollment?.enrollmentId) createdEnrollments.add(teamEnrollment.enrollmentId);
  check('a team of two signs up with one trade order', r.status === 201 && teamEnrollment.kind === 'TEAM' && teamEnrollment.tickets.length === 2 && teamEnrollment.payment.amountMinor === UNIT * 2 && seats(PAID_A) === beforeTeam + 2, r.json);
  check('team details come back with the phone masked', teamEnrollment.team?.teamName === '冒烟物流职工子女队' && teamEnrollment.team.leaderPhoneMasked === '138****2026', teamEnrollment.team);
  check('team tickets are members', teamEnrollment.tickets.every((t) => t.relation === 'MEMBER' && t.age >= 10), teamEnrollment.tickets);
  r = await call('POST', '/api/v1/activity-team-enrollments', { token: customer, headers: { 'Idempotency-Key': teamKey }, body: team });
  check('replaying the team sign-up returns it', r.status === 200 && r.json.enrollmentId === teamEnrollment.enrollmentId && seats(PAID_A) === beforeTeam + 2, r.json);
  r = await call('GET', '/api/v1/activity-participants', { token: customer });
  check('team members stay off the participants page', !r.json.items.some((p) => MEMBERS.includes(p.name)), r.json.items.map((p) => p.name));
  r = await call('POST', '/api/v1/activity-team-enrollments/check', { token: customer, body: { sessionId: PAID_A, members: roster.slice(0, 1) } });
  check('a member who signed up is reported as already enrolled', r.json.members?.[0]?.reason === 'ALREADY_ENROLLED' && r.json.members[0].participantId, r.json);
  await pay(customer, teamEnrollment.payment.tradeOrderId);
  r = await readEnrollment(customer, teamEnrollment.enrollmentId);
  check('the leader pays once for the team', r.json.payment.status === 'PAID' && r.json.tickets.every((t) => t.status === 'ACTIVE'), r.json.payment);
  r = await call('POST', `/api/v1/activity-enrollments/${teamEnrollment.enrollmentId}/cancel`, { token: customer, body: { ticketIds: [r.json.tickets[0].ticketId] } });
  check('one team member is refunded alone', r.status === 200 && r.json.tickets.filter((t) => t.status === 'CANCELLED').length === 1 && r.json.changes[0].refundAmountMinor === UNIT, r.json?.changes);

  // ---- 5. 结果记录 ----
  // 在开放日的另一场报名，再把这一场挪到“已开始、签到中”。
  r = await enroll(customer, OPEN_DAY, FREE_SESSION_2, [k1, k2]);
  const resultEnrollment = r.json;
  check('two children sign up for the open day', r.status === 201, r.json);
  psql(`UPDATE activity_session SET starts_at = now() - interval '5 minutes', ends_at = now() + interval '2 hours',
        check_in_opens_at = now() - interval '1 hour', enrollment_closes_at = now() - interval '10 minutes' WHERE id='${FREE_SESSION_2}'`);
  const [t1, t2] = resultEnrollment.tickets;
  r = await call('POST', '/api/v1/activity-checkins', { token: organizer, body: { ticketCode: t1.ticketCode } });
  check('the organiser checks one child in', r.status === 200, r.json);
  r = await call('GET', '/api/v1/provider/activity-sessions?page=1&size=20', { token: organizer });
  const listed = r.json?.items?.find((s) => s.sessionId === FREE_SESSION_2);
  check('the started session shows on the organiser workbench', r.status === 200 && listed && listed.checkedInPeople >= 1, listed);
  r = await call('GET', `/api/v1/provider/activity-sessions/${FREE_SESSION_2}/roster`, { token: organizer });
  const row1 = r.json?.entries?.find((e) => e.ticketId === t1.ticketId);
  check('the roster masks the name and shows the code tail', r.status === 200 && r.json.started && row1?.holderName === '候**' && row1.codeTail === t1.ticketCode.slice(-4) && row1.checkedIn, row1);
  r = await call('GET', `/api/v1/provider/activity-sessions/${FREE_SESSION_2}/roster`, { token: otherOperator });
  check('another organisation cannot see the roster', r.status === 404, r.json);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t2.ticketId}/result`, { token: organizer, body: { completion: 'COMPLETED' } });
  check('a child not checked in cannot get a result', r.status === 409 && r.json.code === 'ACT_RESULT_NOT_ALLOWED', r.json);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t1.ticketId}/result`, { token: otherOperator, body: { completion: 'COMPLETED' } });
  check('another organisation cannot record a result', r.status === 404, r.json);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t1.ticketId}/result`, { token: organizer, body: { completion: 'COMPLETED', scoreText: '完成任务 5/5 · 用时 18′42″', certificateNote: '完赛证书由主办方现场发放' } });
  check('the organiser records the result', r.status === 200 && r.json.result?.completion === 'COMPLETED' && r.json.resultVersion === 0, r.json);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t1.ticketId}/result`, { token: organizer, body: { completion: 'COMPLETED' } });
  check('recording again without the version is a conflict', r.status === 409 && r.json.code === 'ACT_RESULT_VERSION_CONFLICT', r.json);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t1.ticketId}/result`, { token: organizer, body: { completion: 'INCOMPLETE', scoreText: '完成任务 4/5', expectedVersion: 0 } });
  check('the organiser corrects it with the version', r.status === 200 && r.json.result.completion === 'INCOMPLETE' && r.json.resultVersion === 1, r.json);
  r = await readEnrollment(customer, resultEnrollment.enrollmentId);
  const seen = r.json.tickets.find((t) => t.ticketId === t1.ticketId)?.result;
  check('the customer sees the result on the ticket', seen?.completion === 'INCOMPLETE' && seen.scoreText === '完成任务 4/5' && seen.certificateNote === null, seen);
  check('the result message was written once', notices('ACTIVITY_RESULT_RECORDED', resultEnrollment.enrollmentId) === 1);
  r = await call('PUT', `/api/v1/provider/activity-tickets/${t1.ticketId}/result`, { token: customer, body: { completion: 'COMPLETED' } });
  check('a customer cannot record results', r.status === 403, r.json);
  r = await call('POST', `/api/v1/activity-enrollments/${resultEnrollment.enrollmentId}/reschedule`, { token: customer, body: { targetSessionId: FREE_SESSION } });
  check('a started sign-up cannot be rescheduled', r.status === 409 && r.json.code === 'ACT_RESCHEDULE_NOT_ALLOWED', r.json);
}

const done = finish(cleanup);
main().then(() => done(), done);
