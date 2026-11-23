#!/usr/bin/env node
'use strict';

// S6 场景 6 数据一致：
//   一、租赁、课程、服务订单、巡检四个来源：依次跑对应冒烟脚本并保留数据（--keep），马上用只读 SQL（consistency.cjs）核对
//       业务订单状态和交易订单、支付、退款状态，再把这一段的数据清掉；
//   二、造“一天”的演练单：商城两单（一单部分退款）、一单没付款关闭、收费活动报名（两人付款、退一人）、到馆预约，
//       连同活动、场馆、商城一起做只读核对；
//   三、把这一天的支付和退款挪到昨天（平台库和模拟微信一起挪），按日对账：差异为空，平台金额合计 = 渠道账单合计，退款笔数一致；
//       CSV 导出。
// 模拟微信会先清空（/_control/reset，和 trade-smoke/reconciliation.cjs 一样），账单里才只有这一天的演练单。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/06-consistency.cjs
// 跑完删掉全部演练数据、昨天的对账结果（只删本次导入的）并查残留。

const { BUYER, SINCE, call, fake, psql, login, key, offerId, waitFor, step, note, track, purge, purgeAndReport, runSmoke,
  loginOperator, beijingDate, sqlText, done } = require('./lib.cjs');
const { crossCheck } = require('./consistency.cjs');
const fx = require('./fixtures.cjs');

const KIT = offerId('101', '1');
const PRICE = 129900;
const UNIT = 9900;
const VENUE_UNIT = 2900;
const yesterday = beijingDate(-1);
const today = beijingDate(0);
const iso = (ms) => new Date(ms).toISOString();
const ids = (sql) => psql(sql).split('\n').filter(Boolean);

function checkSource(label, ruleName, since) {
  const result = crossCheck(since);
  const rule = result.find((x) => x.rule === ruleName);
  const bad = result.filter((x) => x.violations > 0);
  step(`${label}：业务订单和交易订单、支付、退款状态一致`, { req: `consistency.cjs 只读核对（${since} 之后的数据）`, expect: `“${ruleName}”核对到行，全部规则 0 违反` },
    rule && rule.checked > 0 && bad.length === 0, { [ruleName]: rule, violations: bad });
}

/** 清掉一段冒烟脚本 --keep 留下的数据：从这段时间里 dev-customer 的交易订单、预约、需求、询价、课程开通、新建课程出发。 */
function purgeSmokeData(since) {
  const t = `${sqlText(since)}::timestamptz`;
  const seeds = [
    ...ids(`SELECT id FROM trade_order WHERE buyer_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM inspection_reservation WHERE customer_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM demand WHERE account_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM rental_inquiry WHERE account_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM mall_inquiry WHERE account_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM course_enrollment WHERE account_id='${BUYER}' AND created_at >= ${t}`),
    ...ids(`SELECT id FROM course WHERE created_at >= ${t}`),
    ...ids(`SELECT id FROM account_address WHERE owner_id='${BUYER}' AND created_at >= ${t}`),
  ];
  // service-order.cjs 的工作台一步给云翼科技建了服务商资料（它自己的清理会删，--keep 时不删）：只删这段时间里建的。
  psql(`BEGIN;
    DELETE FROM pro_provider_profile_change WHERE changed_at >= ${t};
    DELETE FROM pro_provider_profile WHERE created_at >= ${t};
    COMMIT;`);
  const result = purge(seeds);
  note(`清掉冒烟数据：起点 ${seeds.length} 个，删 ${result.deleted} 行，残留 ${result.residue}`);
  return result;
}

async function smokeWithCheck(name, label, ruleName) {
  const since = psql('SELECT now()');
  runSmoke(name, { args: ['--keep'], label: `${label}（${name}.cjs --keep）` });
  checkSource(label, ruleName, since);
  const result = purgeSmokeData(since);
  step(`${label}：这一段的数据清理干净`, { req: '闭包清理', expect: 'residue none' }, result.residue === 'none', result);
}

async function pay(token, orderId) {
  await call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  const no = track(psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`));
  const r = await fake('POST', `/_control/pay/${no}?notify=1`);
  if (r.json?.notification?.status !== 204) throw new Error(`付款回调失败 ${JSON.stringify(r.json)}`);
  return no;
}

async function settleRefund(orderId) {
  const nos = ids(`SELECT out_refund_no FROM trade_refund WHERE order_id='${orderId}' AND status <> 'SUCCEEDED'`);
  for (const no of nos) {
    track(no);
    await fake('POST', `/_control/refund/${no}?status=SUCCESS&notify=1`);
  }
  await waitFor(() => psql(`SELECT count(*) FROM trade_refund WHERE order_id='${orderId}' AND status <> 'SUCCEEDED'`) === '0', 15);
}

async function main() {
  // ---- 一、四个来源各跑一遍、核对、清掉 ----
  await smokeWithCheck('rental', '设备租赁', '租赁订单 ↔ 交易订单');
  await smokeWithCheck('course', '课程', '课程开通 ↔ 交易订单');
  await smokeWithCheck('service-order', '通用服务订单', '服务订单付款 ↔ 交易订单');
  await smokeWithCheck('inspection', '巡检付款节点', '巡检付款 ↔ 交易订单');

  // ---- 二、一天的演练单 ----
  const existing = psql(`SELECT count(*) FROM trade_reconciliation WHERE bill_date='${yesterday}'`);
  if (existing !== '0') throw new Error(`${yesterday} 已经有对账结果（${existing} 条），不在这一天演练，免得盖掉`);
  await fake('POST', '/_control/reset');
  note('模拟微信已清空（/_control/reset），账单里只会有这一天的演练单');
  const op = await loginOperator();
  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  const org = await fx.createOrg(op, { name: `S6对账${Date.now() % 100000}机构`, cityCode: '370500' });
  await fx.submitQualification(op, org.orgId);
  await fx.approveQualification(op, org.orgId);
  const account = await fx.registerMerchantAccount(op, org.orgId);
  const cover = await fx.mediaUrl(op, '/api/v1/ops/media');
  const later = Date.now() + 2 * 86_400_000;
  const activity = await fx.createActivity(op, { orgId: org.orgId, cityCode: '370500', title: 'S6 对账演练活动', priceMinor: UNIT, coverUrl: cover.url,
    sessions: [{ startsAt: iso(later), endsAt: iso(later + 3600_000), checkInOpensAt: iso(later - 3600_000), enrollmentClosesAt: iso(later - 3600_000), capacity: 10 }] });
  const venue = await fx.createVenue(op, { orgId: org.orgId, cityCode: '370500', name: 'S6 对账演练场馆', coverUrl: cover.url, priceMinor: VENUE_UNIT,
    fromDate: beijingDate(1), toDate: beijingDate(1), startTimes: ['10:00'] });
  const addr = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: '370500', detail: '东营区 演练路 6 号', isDefault: false } });
  track(addr.json?.addressId);
  const mallOrder = async () => {
    const r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: '370500', offerId: KIT, quantity: 1, deliveryMode: 'EXPRESS', addressId: addr.json.addressId, expectedPayableMinor: PRICE, agreementAccepted: true } });
    return track(r.json.orderId);
  };
  const m1 = await mallOrder();
  await pay(customer, m1);
  const m2 = await mallOrder();
  await pay(customer, m2);
  const partial = await call('POST', `/api/v1/provider/trade/orders/${m2}/refunds`, { token: merchant, headers: { 'Idempotency-Key': key() }, body: { amountMinor: 10000, reason: 'S6 对账演练：少发配件' } });
  await settleRefund(m2);
  const m3 = await mallOrder();
  const m3Read = await call('GET', `/api/v1/trade/orders/${m3}`, { token: customer });
  const cancelled = await call('POST', `/api/v1/trade/orders/${m3}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': m3Read.etag } });
  const kids = [];
  for (const name of ['S6对账甲', 'S6对账乙']) kids.push(track((await call('POST', '/api/v1/activity-participants', { token: customer, body: { name, birthMonth: '2015-03', guardianConfirmed: true } })).json?.participantId));
  const enrollment = (await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: activity.activityId, sessionId: activity.sessionIds[0], participantIds: kids, expectedAmountMinor: UNIT * 2, noticeAgreed: true } })).json;
  track(enrollment.enrollmentId, enrollment.payment.tradeOrderId);
  await pay(customer, enrollment.payment.tradeOrderId);
  const tickets = (await call('GET', `/api/v1/activity-enrollments/${enrollment.enrollmentId}`, { token: customer })).json.tickets;
  await call('POST', `/api/v1/activity-enrollments/${enrollment.enrollmentId}/cancel`, { token: customer, body: { ticketIds: [tickets[1].ticketId] } });
  await settleRefund(enrollment.payment.tradeOrderId);
  const session = (await call('GET', `/api/v1/venues/${venue.venueId}/sessions?cityCode=370500&projectId=${venue.projectId}&date=${beijingDate(1)}`)).json.sessions[0];
  const booking = (await call('POST', '/api/v1/venue-bookings', { token: customer, headers: { 'Idempotency-Key': key() }, body: { sessionId: session.sessionId, participantIds: [kids[0]], expectedAmountMinor: VENUE_UNIT, noticeAgreed: true } })).json;
  track(booking.bookingId, booking.payment.tradeOrderId);
  await pay(customer, booking.payment.tradeOrderId);
  const orders = [m1, m2, m3, enrollment.payment.tradeOrderId, booking.payment.tradeOrderId];
  const list = orders.map((id) => `'${id}'`).join(',');
  step('造好一天的演练单：商城 3 单（1 单部分退款、1 单未付款取消）、收费报名 2 人退 1 人、到馆预约 1 单', { req: '真实接口下单、付款、退款', expect: '部分退款 201、取消 200' },
    partial.status === 201 && cancelled.status === 200, psql(`SELECT string_agg(source || ':' || status || ':' || paid_minor || ':' || refunded_minor, ' ') FROM trade_order WHERE id IN (${list})`));
  const scoped = crossCheck(SINCE);
  const bad = scoped.filter((x) => x.violations > 0);
  step('商城、活动、场馆：业务订单和交易订单、支付、退款状态一致', { req: 'consistency.cjs 只读核对（本次演练的数据）', expect: '全部规则 0 违反' },
    bad.length === 0 && scoped.find((x) => x.rule === '活动报名 ↔ 交易订单').checked > 0 && scoped.find((x) => x.rule === '到馆预约 ↔ 交易订单').checked > 0,
    scoped.filter((x) => x.checked > 0).map((x) => `${x.rule} ${x.checked}/${x.violations}`));

  // ---- 三、挪到昨天，按日对账 ----
  psql(`BEGIN;
    UPDATE trade_payment SET succeeded_at = succeeded_at - interval '1 day' WHERE order_id IN (${list}) AND succeeded_at IS NOT NULL;
    UPDATE trade_refund SET accepted_at = accepted_at - interval '1 day' WHERE order_id IN (${list}) AND accepted_at IS NOT NULL;
    COMMIT;`);
  await fake('POST', '/_control/shift-days?days=-1');
  const platformPaid = psql(`SELECT count(*) || '|' || coalesce(sum(amount_minor), 0) FROM trade_payment WHERE status='SUCCEEDED' AND (succeeded_at AT TIME ZONE 'Asia/Shanghai')::date = '${yesterday}'`).split('|').map(Number);
  const platformRefund = psql(`SELECT count(*) || '|' || coalesce(sum(amount_minor), 0) FROM trade_refund WHERE (accepted_at AT TIME ZONE 'Asia/Shanghai')::date = '${yesterday}'`).split('|').map(Number);
  const channelOrders = (await fake('GET', '/_control/orders')).json.filter((o) => o.success_time && o.success_time.slice(0, 10) === yesterday);
  const channelRefunds = (await fake('GET', '/_control/refunds')).json.filter((x) => x.create_time.slice(0, 10) === yesterday);
  const channelPaid = [channelOrders.length, channelOrders.reduce((s, o) => s + (o.paid_total ?? o.total), 0)];
  const channelRefund = [channelRefunds.length, channelRefunds.reduce((s, x) => s + x.refund, 0)];
  note(`昨天（${yesterday}）平台：付款 ${platformPaid[0]} 笔 ${platformPaid[1]} 分，退款 ${platformRefund[0]} 笔 ${platformRefund[1]} 分；渠道：付款 ${channelPaid[0]} 笔 ${channelPaid[1]} 分，退款 ${channelRefund[0]} 笔 ${channelRefund[1]} 分`);
  step('平台付款金额合计 = 渠道账单付款合计', { req: '平台 trade_payment 与模拟微信账单数据，按北京时间昨天', expect: `都是 4 笔 ${PRICE * 2 + UNIT * 2 + VENUE_UNIT} 分` },
    platformPaid[0] === channelPaid[0] && platformPaid[1] === channelPaid[1] && platformPaid[0] === 4 && platformPaid[1] === PRICE * 2 + UNIT * 2 + VENUE_UNIT, { platformPaid, channelPaid });
  step('退款笔数、金额一致', { req: '平台 trade_refund 与模拟微信退款', expect: `都是 2 笔 ${10000 + UNIT} 分` },
    platformRefund[0] === channelRefund[0] && platformRefund[1] === channelRefund[1] && platformRefund[0] === 2 && platformRefund[1] === 10000 + UNIT, { platformRefund, channelRefund });

  let r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, { token: op });
  step('运营端跑昨天的对账：每个商户的账单都导入，没有差异', { req: `POST /api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, expect: 'imported = merchants，failed 0，differences 0' },
    r.status === 200 && r.json.imported === r.json.merchants && r.json.failed === 0 && r.json.differences === 0, r.json);
  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: op });
  step('对账结果：6 笔对上（4 笔付款 + 2 笔退款），差异表为空', { req: `GET /api/v1/platform/trade/reconciliation?date=${yesterday}`, expect: 'matchedCount 6，differences []' },
    r.status === 200 && r.json.matchedCount === 6 && r.json.differences.length === 0, { matched: r.json?.matchedCount, differences: r.json?.differences });
  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}&format=CSV`, { token: op });
  const lines = r.text.replace(/^﻿/, '').trim().split('\r\n');
  step('对账 CSV 导出：只有表头，没有差异行', { req: `GET …/reconciliation?date=${yesterday}&format=CSV`, expect: 'text/csv，1 行' },
    r.status === 200 && (r.type || '').startsWith('text/csv') && lines.length === 1, { status: r.status, type: r.type, lines });
  const perMerchant = psql(`SELECT string_agg(sub_mchid || ':' || matched_count, ' ' ORDER BY sub_mchid) FROM trade_reconciliation WHERE bill_date='${yesterday}' AND matched_count > 0`);
  step('对上的笔数落在收款的两个子商户号上', { req: '查 trade_reconciliation', expect: `1900000101:3（商城 2 笔付款 + 1 笔退款），${account.subMchid}:3（报名付款、报名退款、到馆付款）` },
    perMerchant === [`1900000101:3`, `${account.subMchid}:3`].sort().join(' '), perMerchant);
  r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${today}`, { token: op });
  step('今天不能对账', { req: `POST …/runs?date=${today}`, expect: '400' }, r.status === 400, r.status);
}

function cleanup() {
  psql(`BEGIN;
    DELETE FROM trade_reconciliation_difference WHERE reconciliation_id IN (SELECT id FROM trade_reconciliation WHERE bill_date='${yesterday}' AND imported_at >= ${sqlText(SINCE)}::timestamptz);
    DELETE FROM trade_reconciliation WHERE bill_date='${yesterday}' AND imported_at >= ${sqlText(SINCE)}::timestamptz;
    COMMIT;`);
  step('昨天的对账结果（本次导入的）已删除', { req: 'DELETE trade_reconciliation*', expect: '0 条' }, psql(`SELECT count(*) FROM trade_reconciliation WHERE bill_date='${yesterday}'`) === '0', psql(`SELECT count(*) FROM trade_reconciliation WHERE bill_date='${yesterday}'`));
  fake('POST', '/_control/bill?reset=1').catch(() => {});
  purgeAndReport();
}

// 补清一次中断的运行：S6_SINCE=<那次开始的数据库时间> node 06-consistency.cjs --purge-only
if (process.argv.includes('--purge-only')) {
  const result = purgeSmokeData(SINCE);
  console.log(JSON.stringify(result));
  process.exit(result.residue === 'none' ? 0 : 1);
}
main().then(() => done(cleanup)(), (error) => done(cleanup)(error));
