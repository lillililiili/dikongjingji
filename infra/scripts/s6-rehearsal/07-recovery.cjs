#!/usr/bin/env node
'use strict';

// S6 场景 7 异常与恢复。分几段跑（中间要重启后端），段名放在第一个参数：
//   replay           同一笔付款回调重放 3 次不重复入账、不重复发站内信；退款回调重放 3 次同样；付款超时自动关单、释放名额。
//   prepare-restart  下一单、在模拟微信付款但不发回调（回调丢了），把要核对的东西写进 .runtime/s6-rehearsal/07-restart-state.json。
//                    然后由人按 18420 端口的 PID 停后端、带 IDENTITY_ACCOUNT_JOBS_CLOSURE_INTERVAL_MS=5000 重新启动。
//   verify-restart   后端重启后，这一单由查单（轮询）恢复为已付款；清理这一段的数据。
//   identity         跑 infra/scripts/identity-account-smoke.cjs（“注销到期完成”要上面那个变量），再清掉它注册的号码、企业、导出文件。
// 备份恢复演练在 07-backup-restore.cjs。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/07-recovery.cjs replay

const fs = require('node:fs');
const path = require('node:path');

const PHASE = process.argv[2] || 'replay';
const STATE = path.join(__dirname, '../../../.runtime/s6-rehearsal/07-restart-state.json');
// verify-restart 清理的是 prepare-restart 那一段建的数据：清理的时间下限要用那一段的开始时间（lib 载入时读 S6_SINCE）。
if (PHASE === 'verify-restart') process.env.S6_SINCE = JSON.parse(fs.readFileSync(STATE, 'utf8')).since;
const lib = require('./lib.cjs');
const { BUYER, SINCE, REPO, call, fake, psql, login, key, offerId, waitFor, step, note, track, created, purge, purgeAndReport, runSmoke,
  loginOperator, removeLocalFiles, sqlText, done } = lib;
const fx = require('./fixtures.cjs');

const KIT = offerId('101', '1');
const PRICE = 129900;
const UNIT = 9900;
const iso = (ms) => new Date(ms).toISOString();
const one = (sql) => psql(sql);
const notices = (target, code) => Number(one(`SELECT count(*) FROM notification_message WHERE target_id='${target}'${code ? ` AND event_code='${code}'` : ''}`));

async function mallOrder(customer, addressId) {
  const r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: '370500', offerId: KIT, quantity: 1, deliveryMode: 'EXPRESS', addressId, expectedPayableMinor: PRICE, agreementAccepted: true } });
  if (r.status !== 201) throw new Error(`下单失败 ${r.status} ${JSON.stringify(r.json)}`);
  track(r.json.orderId);
  await call('POST', `/api/v1/trade/orders/${r.json.orderId}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  return { orderId: r.json.orderId, no: track(one(`SELECT out_trade_no FROM trade_payment WHERE order_id='${r.json.orderId}'`)) };
}

const ledger = (orderId) => one(`SELECT o.status || '|' || o.paid_minor || '|' || o.refunded_minor
  || '|' || (SELECT count(*) FROM trade_payment p WHERE p.order_id = o.id AND p.status = 'SUCCEEDED')
  || '|' || (SELECT count(*) FROM trade_refund r WHERE r.order_id = o.id AND r.status = 'SUCCEEDED')
  || '|' || (SELECT count(*) FROM trade_state_transition t WHERE t.order_id = o.id) FROM trade_order o WHERE o.id='${orderId}'`);

async function replay() {
  const op = await loginOperator();
  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  const addr = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: '370500', detail: '东营区 演练路 7 号', isDefault: false } });
  track(addr.json?.addressId);

  // 1. 付款回调重放 3 次
  const a = await mallOrder(customer, addr.json.addressId);
  let r = await fake('POST', `/_control/pay/${a.no}?notify=1`);
  const afterFirst = ledger(a.orderId);
  step('第一次付款回调入账', { req: `POST /_control/pay/${a.no}?notify=1`, expect: '回调 204，订单 PAID，成功支付 1 笔' }, r.json?.notification?.status === 204 && afterFirst.startsWith(`PAID|${PRICE}|0|1|0|`), afterFirst);
  const statuses = [];
  for (let i = 0; i < 3; i += 1) statuses.push((await fake('POST', `/_control/renotify/${a.no}`)).json?.status);
  const afterReplay = ledger(a.orderId);
  step('同一付款回调再发 3 次：都应答成功，不重复入账、不多记状态', { req: `POST /_control/renotify/${a.no} ×3`, expect: `应答 2xx；订单、支付、状态流转和第一次后一样（${afterFirst}）` },
    statuses.every((s) => s >= 200 && s < 300) && afterReplay === afterFirst, { statuses, afterFirst, afterReplay });
  step('渠道回调记录：同一个通知只记一条', { req: '查 trade_channel_notification', expect: '这笔付款 1 条' },
    one(`SELECT count(*) FROM trade_channel_notification WHERE out_trade_no='${a.no}' AND out_refund_no IS NULL`) === '1',
    one(`SELECT string_agg(outcome, ',') FROM trade_channel_notification WHERE out_trade_no='${a.no}'`));

  // 2. 退款回调重放 3 次
  const order = (await call('GET', `/api/v1/provider/trade/orders/${a.orderId}`, { token: merchant })).json;
  r = await call('POST', `/api/v1/provider/trade/orders/${a.orderId}/refunds`, { token: merchant, headers: { 'Idempotency-Key': key() }, body: { amountMinor: 20000, reason: 'S6 演练：退款回调重放' } });
  const refundNo = track(one(`SELECT out_refund_no FROM trade_refund WHERE order_id='${a.orderId}'`));
  r = await fake('POST', `/_control/refund/${refundNo}?status=SUCCESS&notify=1`);
  await waitFor(() => one(`SELECT status FROM trade_refund WHERE out_refund_no='${refundNo}'`) === 'SUCCEEDED', 15);
  await waitFor(() => notices(a.orderId, 'TRADE_REFUND_SUCCEEDED') === 1, 10);
  const refundFirst = `${ledger(a.orderId)}|${notices(a.orderId, 'TRADE_REFUND_SUCCEEDED')}`;
  step('第一次退款回调：退款成功、已退 200 元、站内信一条', { req: `POST /_control/refund/${refundNo}?status=SUCCESS&notify=1`, expect: '…|20000|1|1|…|1' },
    r.json?.notification?.status === 204 && refundFirst.split('|')[2] === '20000' && refundFirst.endsWith('|1'), { order: order?.status, refundFirst });
  const refundStatuses = [];
  for (let i = 0; i < 3; i += 1) refundStatuses.push((await fake('POST', `/_control/renotify/${refundNo}`)).json?.status);
  await new Promise((resolve) => setTimeout(resolve, 3000));
  const refundAfter = `${ledger(a.orderId)}|${notices(a.orderId, 'TRADE_REFUND_SUCCEEDED')}`;
  step('同一退款回调再发 3 次：不重复记退款、不重复发站内信', { req: `POST /_control/renotify/${refundNo} ×3`, expect: `和第一次后一样（${refundFirst}）` },
    refundStatuses.every((s) => s >= 200 && s < 300) && refundAfter === refundFirst, { refundStatuses, refundFirst, refundAfter });

  // 3. 活动报名：付款回调重放不重复发“报名成功”；付款超时自动关单、释放名额
  const org = await fx.createOrg(op, { name: `S6恢复${Date.now() % 100000}机构`, cityCode: '370500' });
  await fx.submitQualification(op, org.orgId);
  await fx.approveQualification(op, org.orgId);
  await fx.registerMerchantAccount(op, org.orgId);
  const cover = await fx.mediaUrl(op, '/api/v1/ops/media');
  const later = Date.now() + 2 * 86_400_000;
  const act = await fx.createActivity(op, { orgId: org.orgId, cityCode: '370500', title: 'S6 恢复演练活动', priceMinor: UNIT, coverUrl: cover.url,
    sessions: [{ startsAt: iso(later), endsAt: iso(later + 3600_000), checkInOpensAt: iso(later - 3600_000), enrollmentClosesAt: iso(later - 3600_000), capacity: 3 }] });
  const sessionId = act.sessionIds[0];
  const seats = () => Number(one(`SELECT enrolled_count FROM activity_session WHERE id='${sessionId}'`));
  const kids = [];
  for (const name of ['S6恢复甲', 'S6恢复乙']) kids.push(track((await call('POST', '/api/v1/activity-participants', { token: customer, body: { name, birthMonth: '2015-03', guardianConfirmed: true } })).json?.participantId));
  const e1 = (await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: act.activityId, sessionId, participantIds: [kids[0]], expectedAmountMinor: UNIT, noticeAgreed: true } })).json;
  track(e1.enrollmentId, e1.payment.tradeOrderId);
  await call('POST', `/api/v1/trade/orders/${e1.payment.tradeOrderId}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  const e1No = track(one(`SELECT out_trade_no FROM trade_payment WHERE order_id='${e1.payment.tradeOrderId}'`));
  await fake('POST', `/_control/pay/${e1No}?notify=1`);
  await waitFor(() => notices(e1.enrollmentId, 'ACTIVITY_ENROLLED') === 1, 10);
  const enrolledFirst = `${ledger(e1.payment.tradeOrderId)}|${notices(e1.enrollmentId, 'ACTIVITY_ENROLLED')}|${seats()}`;
  const enrollReplays = [];
  for (let i = 0; i < 3; i += 1) enrollReplays.push((await fake('POST', `/_control/renotify/${e1No}`)).json?.status);
  await new Promise((resolve) => setTimeout(resolve, 3000));
  const enrolledAfter = `${ledger(e1.payment.tradeOrderId)}|${notices(e1.enrollmentId, 'ACTIVITY_ENROLLED')}|${seats()}`;
  step('收费报名的付款回调重放 3 次：不重复入账、“报名成功”站内信只有一条、名额不变', { req: `POST /_control/renotify/${e1No} ×3`, expect: `和第一次后一样（${enrolledFirst}）` },
    enrolledFirst.endsWith('|1|1') && enrolledAfter === enrolledFirst && enrollReplays.every((x) => x >= 200 && x < 300), { enrollReplays, enrolledFirst, enrolledAfter });

  const before = seats();
  const e2 = (await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: act.activityId, sessionId, participantIds: [kids[1]], expectedAmountMinor: UNIT, noticeAgreed: true } })).json;
  track(e2.enrollmentId, e2.payment.tradeOrderId);
  step('未付款的报名占着名额', { req: 'POST /api/v1/activity-enrollments', expect: `名额 ${before + 1}` }, seats() === before + 1, seats());
  one(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id='${e2.payment.tradeOrderId}'`);
  const started = Date.now();
  const closed = await waitFor(() => one(`SELECT status FROM activity_enrollment WHERE id='${e2.enrollmentId}'`) === 'CLOSED', 150);
  step('付款超时：定时任务关单，报名关闭，名额释放', { req: '把付款截止改到过去，等超时关单任务', expect: `交易 CLOSED、报名 CLOSED、名额回到 ${before}` },
    closed && one(`SELECT status FROM trade_order WHERE id='${e2.payment.tradeOrderId}'`) === 'CLOSED' && seats() === before,
    { enrollment: one(`SELECT status FROM activity_enrollment WHERE id='${e2.enrollmentId}'`), seats: seats(), seconds: Math.round((Date.now() - started) / 1000) });
}

async function prepareRestart() {
  const customer = await login('dev-customer');
  const addr = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: '370500', detail: '东营区 演练路 8 号', isDefault: false } });
  track(addr.json?.addressId);
  const lost = await mallOrder(customer, addr.json.addressId);
  const paid = await fake('POST', `/_control/pay/${lost.no}?notify=0`);
  step('付款成功但回调丢了：平台还是待付款，微信那边已付', { req: `POST /_control/pay/${lost.no}?notify=0`, expect: '订单 PENDING_PAYMENT' },
    paid.status === 200 && one(`SELECT status FROM trade_order WHERE id='${lost.orderId}'`) === 'PENDING_PAYMENT', one(`SELECT status FROM trade_order WHERE id='${lost.orderId}'`));
  fs.writeFileSync(STATE, JSON.stringify({ since: SINCE, ids: [...created], orderId: lost.orderId, outTradeNo: lost.no }, null, 2));
  note(`状态写在 ${path.relative(REPO, STATE)}；现在按 18420 端口的 PID 停后端，带 IDENTITY_ACCOUNT_JOBS_CLOSURE_INTERVAL_MS=5000 重启，再跑 verify-restart`);
}

async function verifyRestart() {
  const state = JSON.parse(fs.readFileSync(STATE, 'utf8'));
  state.ids.forEach((id) => track(id));
  const customer = await login('dev-customer');
  const me = await call('GET', '/api/v1/auth/me', { token: customer });
  step('后端重启后能正常登录', { req: 'POST /api/v1/auth/login + GET /api/v1/auth/me', expect: '200' }, me.status === 200, me.status);
  const started = Date.now();
  const recovered = await waitFor(() => one(`SELECT status FROM trade_order WHERE id='${state.orderId}'`) === 'PAID', 180);
  const trail = one(`SELECT string_agg(cause, ',' ORDER BY occurred_at, created_at) FROM trade_state_transition WHERE order_id='${state.orderId}'`);
  step('重启前没收到回调的订单，重启后由查单恢复为已付款', { req: '等后端的支付轮询任务（不发回调）', expect: 'PAID，状态流转 SUBMIT,PAYMENT_QUERIED' },
    recovered && trail === 'SUBMIT,PAYMENT_QUERIED', { status: one(`SELECT status FROM trade_order WHERE id='${state.orderId}'`), trail, seconds: Math.round((Date.now() - started) / 1000) });
  const r = await call('GET', `/api/v1/trade/orders/${state.orderId}`, { token: customer });
  step('买家看到已付款', { req: 'GET /api/v1/trade/orders/{id}', expect: 'PAID' }, r.json?.status === 'PAID', r.json?.status);
}

/** identity-account-smoke.cjs 注册的号码、个人客户机构、企业、导出：从这段时间里建的这些行出发清理。 */
function purgeIdentitySmoke(since) {
  const t = `${sqlText(since)}::timestamptz`;
  const list = (sql) => psql(sql).split('\n').filter(Boolean);
  const users = list(`SELECT id FROM identity_user WHERE created_at >= ${t} AND (mobile LIKE '199%' OR login_name LIKE 'closed-%' OR login_name LIKE 'p-%')`);
  const seeds = [
    ...users,
    ...list(`SELECT default_organization_id FROM identity_user WHERE id IN (${users.map(sqlText).join(',') || "NULL"}) AND default_organization_id IS NOT NULL`),
    ...list(`SELECT id FROM identity_organization WHERE created_at >= ${t} AND (organization_code LIKE 'PERSONAL-%' OR organization_code LIKE 'ENT-%')`),
    ...list(`SELECT id FROM identity_data_export WHERE requested_at >= ${t} AND user_id='${BUYER}'`),
    ...list(`SELECT id FROM trade_order WHERE buyer_id='${BUYER}' AND created_at >= ${t}`),
    // 新注册的号码（199 号段）：验证码、短信发件箱、号码锁定都按号码记，这些表没有 created_at，按号码删。
    ...list(`SELECT DISTINCT phone FROM identity_sms_code WHERE phone LIKE '199%' AND expires_at >= ${t}`),
    // dev-customer 自己的号码（导出要验证码）：只删这段时间发的那几条，不按号码整个删。冒烟会把发送时间往前挪一天，所以按过期时间找。
    ...list(`SELECT id FROM identity_sms_code WHERE phone NOT LIKE '199%' AND expires_at >= ${t}`),
    ...list(`SELECT id FROM identity_dev_sms_outbox WHERE phone NOT LIKE '199%' AND sent_at >= ${t}`),
  ];
  const result = purge(seeds);
  const removedFiles = !result.dryRun && result.residue === 'none' ? removeLocalFiles(new Set(seeds)).removed : 0;
  note(`清掉 identity-account-smoke 的数据：起点 ${seeds.length} 个（其中账号 ${users.length} 个），删 ${result.deleted} 行，导出文件删 ${removedFiles} 个，残留 ${result.residue}`);
  return result;
}

async function identity() {
  const since = psql('SELECT now()');
  runSmoke('identity-account-smoke', { label: '账号冒烟（含“注销到期完成”，后端带 IDENTITY_ACCOUNT_JOBS_CLOSURE_INTERVAL_MS=5000）' });
  const result = purgeIdentitySmoke(since);
  step('账号冒烟注册的号码、企业、导出等清理干净', { req: '闭包清理', expect: 'residue none' }, result.residue === 'none', result);
}

const phases = { replay, 'prepare-restart': prepareRestart, 'verify-restart': verifyRestart, identity };
if (!phases[PHASE]) {
  console.error(`不认识的段：${PHASE}（${Object.keys(phases).join(' / ')}）`);
  process.exit(2);
}
// prepare-restart 不清理（数据要留到重启后核对）；verify-restart 用那一段的开始时间清理。
const cleanup = () => {
  if (PHASE === 'prepare-restart') return;
  purgeAndReport();
};
phases[PHASE]().then(() => done(cleanup)(), (error) => done(cleanup)(error));
