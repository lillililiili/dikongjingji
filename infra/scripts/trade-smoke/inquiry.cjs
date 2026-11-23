#!/usr/bin/env node
'use strict';

// F4 询价商品：询价（能发给谁、幂等）、两家报价、改价、比选（排序、版本）、按报价转为订单（确认订单预览、提交、金额按报价）、
// 付款到这家商家、发货；一份报价不能同时下两张单，没付款关闭后可以再下；过期的报价不能下单；
// 定时任务关掉 72 小时没人报价的询价单；加 --reconcile 时再按报价金额对账（见下）。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉脚本自己建的询价单和 dev-customer 的订单、地址（--keep 保留数据）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/inquiry.cjs [--reconcile]
// --reconcile：把这张按报价付款的订单挪到昨天，跑一次昨天的对账，看它按报价金额对上。会把模拟微信里所有记录的时间往前挪一天，
// 并删掉昨天的对账结果、临时建一个平台运营账号（跑完删掉）：只在自己单独起的模拟微信和一次性库上用，不要对共用的那一套用。

const crypto = require('node:crypto');
const { offerId, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子：行业测绘无人机（询价商品）在东营有示例机器人商家（dev-merchant，子商户号 1900000101）和示例行业装备代理
// （dev-merchant-2，1900000105）两家能收款的供给；示例无人机厂家（全国，1900000104）在第 1 步临时停用收款。编程机器人套装是“购买”商品。
const PRODUCT = 'c2000000-0000-4000-8000-000000000203';
const ROBOT_OFFER = offerId('203', '1');
const AGENT_OFFER = offerId('203', '5');
const MAKER_OFFER = offerId('203', '4');
const BUY_OFFER = offerId('101', '1');
const CITY = '370500';

const created = new Set();
const RECONCILE = process.argv.includes('--reconcile');
const OPERATOR = 'f4000000-0000-4000-8000-0000000000e6';
const OPERATOR_ORG = 'f4000000-0000-4000-8000-0000000000e0';
const tempPassword = crypto.randomBytes(18).toString('base64url');
const yesterday = new Date(Date.now() + 8 * 3600 * 1000 - 86400000).toISOString().slice(0, 10);
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const inquiryStatus = (id) => psql(`SELECT status || ':' || coalesce(close_reason, '') FROM mall_inquiry WHERE id='${id}'`);
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
const validUntil = (days) => new Date(Date.now() + days * 86_400_000).toISOString();

async function submit(token, offers, overrides = {}, idempotencyKey = key()) {
  const r = await call('POST', '/api/v1/mall/inquiries', {
    token,
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { productId: PRODUCT, requirement: '测绘版 · RTK · 五镜头倾斜相机\n需要正射影像与三维建模', quantity: 2, cityCode: CITY, expectedDate: null, offerIds: offers, ...overrides },
  });
  if (r.json?.inquiryId) created.add(r.json.inquiryId);
  return r;
}

const quote = (token, id, unit, lead, overrides = {}) => call('PUT', `/api/v1/provider/mall/inquiries/${id}/quote`, {
  token, body: { unitAmountMinor: unit, model: '测绘版 · RTK · 五镜头倾斜相机', leadDays: lead, includes: '含 2 名飞手培训', validUntil: validUntil(3), ...overrides },
});

async function choose(token, id, quoteId, quoteVersion) {
  const read = await call('GET', `/api/v1/mall/inquiries/${id}`, { token });
  return call('POST', `/api/v1/mall/inquiries/${id}/choice`, { token, headers: { 'If-Match': read.etag }, body: { quoteId, quoteVersion } });
}

const preview = (token, quoteId) => call('GET', `/api/v1/mall/checkout-preview?cityCode=${CITY}&inquiryQuoteId=${quoteId}&deliveryMode=EXPRESS`, { token });
const order = (token, quoteId, payable, overrides = {}) => call('POST', '/api/v1/trade/orders', {
  token, headers: { 'Idempotency-Key': key() },
  body: { cityCode: CITY, inquiryQuoteId: quoteId, deliveryMode: 'EXPRESS', expectedPayableMinor: payable, agreementAccepted: true, ...overrides },
});

/** 询价、一家报价、选定，返回选定的报价。 */
async function chosenQuote(customer, merchant, unit) {
  const inquiry = (await submit(customer, [ROBOT_OFFER])).json;
  const quoted = (await quote(merchant, inquiry.inquiryId, unit, 7)).json;
  await choose(customer, inquiry.inquiryId, quoted.myQuote.quoteId, quoted.myQuote.version);
  return { inquiryId: inquiry.inquiryId, quoteId: quoted.myQuote.quoteId };
}

function cleanup() {
  const ids = [...created].map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      UPDATE mall_inquiry SET status = 'CLOSED', close_reason = 'CUSTOMER', closed_at = now(), chosen_quote_id = NULL, chosen_at = NULL WHERE id IN (${ids});
      DELETE FROM mall_inquiry WHERE id IN (${ids});
      COMMIT;
    `);
  }
  if (RECONCILE) {
    psql(`DELETE FROM trade_reconciliation_difference WHERE reconciliation_id IN (SELECT id FROM trade_reconciliation WHERE bill_date = '${yesterday}')`);
    psql(`DELETE FROM trade_reconciliation WHERE bill_date = '${yesterday}'`);
    psql(`DELETE FROM identity_user_role WHERE user_id='${OPERATOR}'`);
    psql(`DELETE FROM identity_organization_member WHERE user_id='${OPERATOR}'`);
    // Refresh tokens (identity V008) reference the user.
    psql(`DELETE FROM identity_refresh_token WHERE user_id='${OPERATOR}'`);
    psql(`DELETE FROM identity_user WHERE id='${OPERATOR}'`);
    psql(`DELETE FROM identity_organization WHERE id='${OPERATOR_ORG}'`);
  }
  cleanupBuyer();
}

/** 按报价金额对账：这张订单挪到昨天，跑昨天的对账，它要对得上（不在差异里），平台金额就是报价合计。 */
async function reconcile(paidOrderId, payable) {
  psql(`INSERT INTO identity_organization (id, organization_code, organization_name, organization_type) VALUES ('${OPERATOR_ORG}', 'TMP-F4-PLATFORM', '临时平台运营（F4 冒烟）', 'PLATFORM')`);
  psql(`INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id) VALUES ('${OPERATOR}', 'tmp-f4-platform', '临时平台运营', crypt('${tempPassword}', gen_salt('bf', 10)), '${OPERATOR_ORG}')`);
  psql(`INSERT INTO identity_organization_member (organization_id, user_id) VALUES ('${OPERATOR_ORG}', '${OPERATOR}')`);
  psql(`INSERT INTO identity_user_role (user_id, role_id, organization_id) SELECT '${OPERATOR}', id, NULL FROM identity_role WHERE role_code = 'PLATFORM_OPERATOR'`);
  const platform = await login('tmp-f4-platform', tempPassword);
  psql(`UPDATE trade_payment SET succeeded_at = succeeded_at - interval '1 day' WHERE order_id = '${paidOrderId}' AND succeeded_at IS NOT NULL`);
  await fake('POST', '/_control/shift-days?days=-1');
  const no = outTradeNo(paidOrderId);
  let r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, { token: platform });
  check('reconciliation imports yesterday', r.status === 200 && r.json.failed === 0, r.json);
  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: platform });
  check('the order paid at the quoted amount reconciles', r.status === 200 && r.json.matchedCount >= 1 && !(r.json.differences || []).some((x) => x.outTradeNo === no), r.json);
  check('the platform recorded the quoted amount', Number(psql(`SELECT amount_minor FROM trade_payment WHERE order_id='${paidOrderId}' AND status='SUCCEEDED'`)) === payable);
}

async function main() {
  const customer = await login('dev-customer');
  const robot = await login('dev-merchant');
  const agent = await login('dev-merchant-2');
  let r = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '冒烟询价', phone: '13800005678', cityCode: CITY, detail: '东营港 示例港路 8 号', isDefault: true } });
  check('a delivery address in 东营', r.status === 201, r.json);

  // 1. 能发给谁。开发种子 2026-11-20 起也给示例无人机厂家登记了收款账户（商品详情要能看到可付款的商家），
  // 这里临时停用它的账户来测“没开通收款的商家不能询价”，测完恢复。
  psql("UPDATE trade_merchant_account SET active = false WHERE sub_mchid = '1900000104'");
  try {
    r = await call('GET', `/api/v1/mall/inquiry-offers?productId=${PRODUCT}&cityCode=${CITY}`, { token: customer });
    const canInquire = Object.fromEntries((r.json?.items ?? []).map((o) => [o.offerId, o.canInquire]));
    check('three offers; the two collecting merchants can be asked', r.status === 200 && r.json.items.length === 3 && canInquire[ROBOT_OFFER] && canInquire[AGENT_OFFER] && canInquire[MAKER_OFFER] === false, r.json);
    r = await submit(customer, [MAKER_OFFER]);
    check('a merchant without payment cannot be asked', r.status === 409 && r.json.code === 'MALL_INQUIRY_MERCHANT_UNAVAILABLE', r.json);
  } finally {
    psql("UPDATE trade_merchant_account SET active = true WHERE sub_mchid = '1900000104'");
  }
  r = await submit(customer, [BUY_OFFER]);
  check('a goods offer is not an inquiry offer', r.status === 409 && r.json.code === 'MALL_INQUIRY_OFFER_UNAVAILABLE', r.json);
  r = await submit(customer, [ROBOT_OFFER, AGENT_OFFER, MAKER_OFFER, BUY_OFFER]);
  check('at most three merchants', r.status === 400, r.json);
  r = await submit(customer, [ROBOT_OFFER], { requirement: ' ' });
  check('the requirement is required', r.status === 400, r.json);

  // 2. 询价单
  const k1 = key();
  r = await submit(customer, [ROBOT_OFFER, AGENT_OFFER], {}, k1);
  const inquiry = r.json;
  check('inquiry submitted to two merchants', r.status === 201 && inquiry.status === 'OPEN' && inquiry.merchants.length === 2 && inquiry.quotingOpen && r.etag === '"0"', inquiry);
  const hours = (new Date(inquiry.quoteDeadline) - new Date(inquiry.submittedAt)) / 3_600_000;
  check('quotes close 72 hours after submitting', hours === 72, inquiry);
  r = await submit(customer, [ROBOT_OFFER, AGENT_OFFER], {}, k1);
  check('replay returns the same inquiry', r.status === 200 && r.json.inquiryId === inquiry.inquiryId, r.json);
  r = await submit(customer, [ROBOT_OFFER], {}, k1);
  check('reusing the key is refused', r.status === 409 && r.json.code === 'MALL_INQUIRY_IDEMPOTENCY_KEY_REUSED', r.json);

  // 3. 两家报价、改价；商家看不到客户
  r = await call('GET', `/api/v1/provider/mall/inquiries/${inquiry.inquiryId}`, { token: robot });
  check('the merchant sees the inquiry without the buyer', r.status === 200 && !('buyer' in r.json) && r.json.invitationStatus === 'INVITED' && r.json.myQuote === null, r.json);
  r = await quote(robot, inquiry.inquiryId, 2980000, 7, { validUntil: validUntil(8) });
  check('validity over 7 days is refused', r.status === 400, r.json);
  r = await quote(robot, inquiry.inquiryId, 2980000, 7);
  const robotV1 = r.json.myQuote;
  check('first quote: v1, total = unit × 2', r.status === 200 && robotV1.version === 1 && robotV1.totalMinor === 5960000, r.json);
  r = await quote(agent, inquiry.inquiryId, 3150000, 10);
  const agentQuote = r.json.myQuote;
  check('second merchant quotes', r.status === 200 && agentQuote.version === 1, r.json);
  r = await quote(robot, inquiry.inquiryId, 2880000, 12);
  const robotV2 = r.json.myQuote;
  check('revised quote is v2', robotV2.version === 2 && robotV2.totalMinor === 5760000, robotV2);

  // 4. 比选
  r = await call('GET', `/api/v1/mall/inquiries/${inquiry.inquiryId}?sort=PRICE`, { token: customer });
  check('two quotes by price: the robot maker first', r.json.inquiry.status === 'QUOTED' && r.json.quotes.length === 2 && r.json.quotes[0].quoteId === robotV2.quoteId && r.json.quotes.every((q) => q.choosable), r.json);
  r = await call('GET', `/api/v1/mall/inquiries/${inquiry.inquiryId}?sort=LEAD_TIME`, { token: customer });
  check('by lead time: the agent first', r.json.quotes[0].quoteId === agentQuote.quoteId, r.json.quotes);
  const etag = r.etag;
  const pick = (headers, body) => call('POST', `/api/v1/mall/inquiries/${inquiry.inquiryId}/choice`, { token: customer, headers, body });
  r = await pick({ 'If-Match': '"0"' }, { quoteId: robotV2.quoteId, quoteVersion: 2 });
  check('a stale inquiry version is 412', r.status === 412 && r.json.code === 'MALL_INQUIRY_VERSION_CONFLICT', r.json);
  r = await pick({ 'If-Match': etag }, { quoteId: robotV1.quoteId, quoteVersion: 1 });
  check('an old quote version is refused', r.status === 409 && r.json.code === 'MALL_INQUIRY_QUOTE_CHANGED', r.json);
  r = await pick({ 'If-Match': etag }, { quoteId: robotV2.quoteId, quoteVersion: 2 });
  check('the robot maker quote is chosen', r.status === 200 && r.json.status === 'CHOSEN' && r.json.chosen.quoteId === robotV2.quoteId && r.json.chosen.orderId === null, r.json);
  r = await call('GET', `/api/v1/provider/mall/inquiries/${inquiry.inquiryId}`, { token: agent });
  check('the other quote lapsed', r.json.myQuote.status === 'LAPSED', r.json.myQuote);
  r = await quote(agent, inquiry.inquiryId, 2000000, 5);
  check('no more quotes after the choice', r.status === 409 && r.json.code === 'MALL_INQUIRY_STATE_INVALID', r.json);

  // 5. 转为订单
  r = await preview(customer, agentQuote.quoteId);
  check('only the chosen quote can be ordered', r.status === 409 && r.json.code === 'MALL_INQUIRY_QUOTE_NOT_CHOSEN', r.json);
  r = await preview(customer, robotV2.quoteId);
  const line = r.json?.lines?.[0];
  check('checkout preview prices the quote: 2 × ¥28,800, model as the sku', r.status === 200 && line.unitAmountMinor === 2880000 && line.quantity === 2 && line.skuName === '测绘版 · RTK · 五镜头倾斜相机' && line.cartItemId === null && r.json.merchant.name === '示例机器人商家', r.json);
  const payable = r.json.amounts.payableMinor;
  r = await order(customer, robotV2.quoteId, payable + 1);
  check('a stale amount is refused', r.status === 409 && r.json.code === 'TRADE_AMOUNT_CHANGED', r.json);
  r = await order(customer, robotV2.quoteId, payable, { quantity: 5 });
  const o1 = r.json;
  check('a plain mall order at the quoted amount; the quantity cannot be changed', r.status === 201 && o1.source === 'MALL' && /^MO/.test(o1.orderNo) && o1.amounts.goodsMinor === 5760000 && o1.lines[0].quantity === 2, r.json);
  r = await order(customer, robotV2.quoteId, payable);
  check('one quote, one open order', r.status === 409 && r.json.code === 'TRADE_INQUIRY_QUOTE_ORDERED', r.json);
  r = await call('GET', `/api/v1/mall/inquiries/${inquiry.inquiryId}`, { token: customer });
  check('the inquiry points at the order', r.json.inquiry.chosen.orderId === o1.orderId, r.json.inquiry.chosen);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('the order is in the goods orders', r.json.items.some((o) => o.orderId === o1.orderId));

  // 6. 付款到这家商家、发货
  await launch(customer, o1.orderId);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === outTradeNo(o1.orderId));
  check('wechat order goes to the chosen merchant for the quoted amount', fakeOrder?.sub_mchid === '1900000101' && fakeOrder.total === payable, fakeOrder);
  await fake('POST', `/_control/pay/${outTradeNo(o1.orderId)}?notify=1`);
  r = await call('GET', `/api/v1/trade/orders/${o1.orderId}`, { token: customer });
  check('paid for the quoted amount', r.json.status === 'PAID' && r.json.amounts.paidMinor === payable, r.json.amounts);
  r = await call('POST', `/api/v1/provider/trade/orders/${o1.orderId}/shipment`, { token: robot, headers: { 'Idempotency-Key': key(), 'If-Match': `"${r.json.version}"` }, body: { carrierName: '示例快递', trackingNo: 'SL-INQUIRY-1' } });
  check('the merchant ships it like any mall order', r.status === 200 && r.json.status === 'SHIPPED', r.json);
  r = await order(customer, robotV2.quoteId, payable);
  check('still one order after it is paid', r.status === 409 && r.json.code === 'TRADE_INQUIRY_QUOTE_ORDERED', r.json);
  if (RECONCILE) await reconcile(o1.orderId, payable);

  // 7. 没付款关闭后可以再下一次
  const second = await chosenQuote(customer, robot, 1000000);
  r = await order(customer, second.quoteId, 2000000);
  const unpaid = r.json;
  check('an order for the second quote', r.status === 201, r.json);
  r = await call('POST', `/api/v1/trade/orders/${unpaid.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': `"${unpaid.version}"` } });
  check('cancelled unpaid', r.status === 200 && tradeStatus(unpaid.orderId) === 'CLOSED', r.json);
  r = await order(customer, second.quoteId, 2000000);
  check('after an unpaid close the quote can be ordered again', r.status === 201 && r.json.orderId !== unpaid.orderId, r.json);

  // 8. 过期的报价不能下单
  const third = await chosenQuote(customer, robot, 1200000);
  psql(`UPDATE mall_inquiry_quote SET quoted_at = now() - interval '2 days', valid_until = now() - interval '1 second' WHERE id='${third.quoteId}'`);
  r = await preview(customer, third.quoteId);
  check('an expired quote cannot be previewed', r.status === 409 && r.json.code === 'MALL_INQUIRY_QUOTE_EXPIRED', r.json);
  r = await order(customer, third.quoteId, 2400000);
  check('nor ordered', r.status === 409 && r.json.code === 'MALL_INQUIRY_QUOTE_EXPIRED', r.json);

  // 9. 定时任务：72 小时没人报价的关闭；截止前报价都过期的回到待报价；关闭询价
  const lonely = (await submit(customer, [AGENT_OFFER])).json;
  psql(`UPDATE mall_inquiry SET quote_deadline = now() - interval '1 second' WHERE id='${lonely.inquiryId}'`);
  const lapsing = (await submit(customer, [AGENT_OFFER])).json;
  await quote(agent, lapsing.inquiryId, 3000000, 10);
  psql(`UPDATE mall_inquiry_quote SET quoted_at = now() - interval '2 days', valid_until = now() - interval '1 second' WHERE inquiry_id='${lapsing.inquiryId}'`);
  const done = await waitFor(() => inquiryStatus(lonely.inquiryId) === 'CLOSED:NO_QUOTES' && inquiryStatus(lapsing.inquiryId) === 'OPEN:', 180);
  check('the job closes the unquoted inquiry and reopens the one whose quote expired', done, [inquiryStatus(lonely.inquiryId), inquiryStatus(lapsing.inquiryId)]);
  r = await call('GET', `/api/v1/provider/mall/inquiries/${lapsing.inquiryId}`, { token: agent });
  check('the merchant sees the quote as expired', r.json.myQuote.status === 'EXPIRED', r.json.myQuote);
  r = await call('POST', `/api/v1/mall/inquiries/${lapsing.inquiryId}/close`, { token: customer });
  check('the customer closes it', r.json.status === 'CLOSED' && r.json.closeReason === 'CUSTOMER', r.json);
  r = await call('POST', `/api/v1/provider/mall/inquiries/${lapsing.inquiryId}/decline`, { token: agent, body: { reason: '缺货' } });
  check('no decline after closing', r.status === 409, r.json);
  r = await call('GET', '/api/v1/mall/inquiries?status=CLOSED', { token: customer });
  check('closed inquiries are listed', r.json.items.some((i) => i.inquiryId === lonely.inquiryId));
}

const finished = finish(cleanup);
main().then(() => finished(), finished);
