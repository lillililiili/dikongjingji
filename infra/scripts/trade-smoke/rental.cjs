#!/usr/bin/env node
'use strict';

// F3 设备租赁：询价（能不能租、收款、地址、幂等）、商家报价和改价、确认合同（版本、报价版本、勾选核对）、
// 交易订单（来源 RENTAL、不进商品订单）、付款、交接和归还（商家录入、客户确认）、交接前商家取消整单退款、
// 未付款超时关闭、关闭之后才到账的整笔自动退款、定时任务关掉 7 天没报价的询价单和标出过期报价。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉脚本自己建的询价单、租赁订单和 dev-customer 的订单、地址（--keep 保留数据）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/rental.cjs

const { offerId, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子：示例机器人商家出租的巡检机器狗（R__dev_mall_rental_offers.sql，¥380/天，东营，收款子商户号 1900000101）；
// 示例设备租赁商出租的同一款没有登记收款账户；编程机器人套装是“购买”商品。
const RENTAL_OFFER = offerId('401', '1');
const UNPAID_MERCHANT_OFFER = offerId('401', '6');
const BUY_OFFER = offerId('101', '1');
const DAILY = 38000;
// 同一份报价内容要完全一样（含有效期），才能看出“重复报价没有变化”。
const VALID_UNTIL = new Date(Date.now() + 3 * 86_400_000).toISOString();

const created = new Set();
const beijingDate = (days) => new Date(Date.now() + days * 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const inquiryStatus = (id) => psql(`SELECT status || ':' || coalesce(close_reason, '') FROM rental_inquiry WHERE id='${id}'`);
const orderStatus = (id) => psql(`SELECT status FROM rental_order WHERE id='${id}'`);
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });

async function submit(token, addressId, overrides = {}, idempotencyKey = key()) {
  const r = await call('POST', '/api/v1/rental-inquiries', {
    token,
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { offerId: RENTAL_OFFER, quantity: 1, startDate: beijingDate(3), days: 5, handoverMode: 'DELIVERY', returnMode: 'COLLECT', addressId, note: '冒烟租赁\n夜间巡逻', ...overrides },
  });
  if (r.json?.inquiryId) created.add(r.json.inquiryId);
  return r;
}

function quoteBody(daily, overrides = {}) {
  return {
    dailyRentMinor: daily,
    days: 5,
    quantity: 1,
    fees: [{ name: '送达与取回', note: '东营市内', amountMinor: 0 }, { name: '保险', note: null, amountMinor: 5000 }],
    scheduleAvailable: true,
    validUntil: VALID_UNTIL,
    terms: '押金：当前不在线收取。\n超期：按天计费。',
    ...overrides,
  };
}

const quote = (merchant, id, daily, overrides) => call('PUT', `/api/v1/provider/rental-inquiries/${id}/quote`, { token: merchant, body: quoteBody(daily, overrides) });

/** 询价、报价、确认合同，返回确认结果。 */
async function contracted(customer, merchant, addressId) {
  const inquiry = (await submit(customer, addressId)).json;
  await quote(merchant, inquiry.inquiryId, DAILY);
  const read = await call('GET', `/api/v1/rental-inquiries/${inquiry.inquiryId}`, { token: customer });
  const r = await call('POST', `/api/v1/rental-inquiries/${inquiry.inquiryId}/confirm`, { token: customer, headers: { 'If-Match': read.etag }, body: { quoteVersion: read.json.quote.version, agreed: true } });
  return r.json;
}

/** 模拟“本地已经超时关闭、微信那边其实付了”：按关闭时的样子改租赁订单和交易订单。 */
function closeLocally(rentalOrderId, tradeOrderId) {
  psql(`
    BEGIN;
    UPDATE trade_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${tradeOrderId}';
    UPDATE trade_payment SET status = 'CLOSED', updated_at = now() WHERE order_id = '${tradeOrderId}' AND status IN ('CREATED', 'PREPAID');
    INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, occurred_at)
      VALUES ('${tradeOrderId}', 'PENDING_PAYMENT', 'CLOSED', 'TIMEOUT', now());
    UPDATE rental_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${rentalOrderId}';
    COMMIT;
  `);
}

function cleanup() {
  const ids = [...created].map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      DELETE FROM rental_order WHERE inquiry_id IN (${ids});
      DELETE FROM rental_inquiry WHERE id IN (${ids});
      COMMIT;
    `);
  }
  cleanupBuyer();
}

async function main() {
  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  let r = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '冒烟租客', phone: '13800001234', cityCode: '370500', detail: '东营港 示例港路 6 号', isDefault: true } });
  const addressId = r.json?.addressId;
  check('a delivery address in 东营', r.status === 201 && addressId, r.json);

  // 1. 询价：能不能租、收款、地址、起租日、幂等
  r = await submit(customer, addressId, { offerId: BUY_OFFER });
  check('a goods offer cannot be rented', r.status === 409 && r.json.code === 'RENTAL_OFFER_UNAVAILABLE', r.json);
  r = await submit(customer, addressId, { offerId: UNPAID_MERCHANT_OFFER });
  check('a lessor without a payment account is refused', r.status === 409 && r.json.code === 'RENTAL_PAYMENT_UNAVAILABLE', r.json);
  r = await submit(customer, '00000000-0000-4000-8000-000000000000');
  check('someone else\'s or a missing address is refused', r.status === 422 && r.json.code === 'RENTAL_ADDRESS_NOT_FOUND', r.json);
  r = await submit(customer, addressId, { startDate: beijingDate(0) });
  check('the start date must be tomorrow or later', r.status === 400, r.json);
  r = await submit(customer, addressId, { quantity: 11 });
  check('at most 10 units', r.status === 400, r.json);
  const k1 = key();
  r = await submit(customer, addressId, {}, k1);
  const inquiry = r.json;
  check('inquiry submitted and pending', r.status === 201 && inquiry.status === 'PENDING' && inquiry.place.phone === '13800001234' && inquiry.item.referenceAmountMinor === DAILY && inquiry.quote === null && r.etag === '"0"', inquiry);
  check('lessee is the account\'s customer organization', inquiry.lessee.kind === 'ORGANIZATION' && inquiry.lessee.organizationName && inquiry.lessee.contactName, inquiry.lessee);
  r = await submit(customer, addressId, {}, k1);
  check('replay returns the same inquiry', r.status === 200 && r.json.inquiryId === inquiry.inquiryId, r.json);
  r = await submit(customer, addressId, { days: 6 }, k1);
  check('reusing the key for other content is refused', r.status === 409 && r.json.code === 'RENTAL_IDEMPOTENCY_KEY_REUSED', r.json);

  // 2. 商家：看询价（手机号打码）、报价、改价
  r = await call('GET', '/api/v1/provider/rental-inquiries?status=PENDING', { token: merchant });
  const seen = r.json?.items?.find((i) => i.inquiryId === inquiry.inquiryId);
  check('the lessor sees the inquiry with a masked phone', seen && seen.place.phone === '138****1234' && seen.confirmable === false, seen);
  check('a customer cannot use merchant routes', (await call('GET', '/api/v1/provider/rental-inquiries', { token: customer })).status === 403);
  r = await quote(merchant, inquiry.inquiryId, DAILY, { validUntil: new Date(Date.now() + 8 * 86_400_000).toISOString() });
  check('validity over 7 days is refused', r.status === 400, r.json);
  r = await quote(merchant, inquiry.inquiryId, DAILY);
  check('first quote is version 1 with the total', r.status === 200 && r.json.status === 'QUOTED' && r.json.quote.version === 1 && r.json.quote.rentMinor === DAILY * 5 && r.json.quote.totalMinor === DAILY * 5 + 5000, r.json.quote);
  r = await quote(merchant, inquiry.inquiryId, DAILY);
  check('the same quote again changes nothing', r.json.quote.version === 1, r.json.quote);
  r = await quote(merchant, inquiry.inquiryId, 36000);
  check('revised quote is version 2', r.json.quote.version === 2 && r.json.quote.totalMinor === 36000 * 5 + 5000, r.json.quote);

  // 3. 确认合同：版本、报价版本、勾选核对
  let read = await call('GET', `/api/v1/rental-inquiries/${inquiry.inquiryId}`, { token: customer });
  check('customer reads the latest quote and may confirm', read.json.quote.version === 2 && read.json.confirmable && read.json.closable, read.json);
  const confirm = (headers, body) => call('POST', `/api/v1/rental-inquiries/${inquiry.inquiryId}/confirm`, { token: customer, headers, body });
  r = await confirm({ 'If-Match': '"0"' }, { quoteVersion: 2, agreed: true });
  check('a stale inquiry version is 412', r.status === 412 && r.json.code === 'RENTAL_VERSION_CONFLICT', r.json);
  r = await confirm({ 'If-Match': read.etag }, { quoteVersion: 1, agreed: true });
  check('an old quote version is 409', r.status === 409 && r.json.code === 'RENTAL_QUOTE_CHANGED', r.json);
  r = await confirm({ 'If-Match': read.etag }, { quoteVersion: 2, agreed: false });
  check('terms must be agreed', r.status === 422 && r.json.code === 'RENTAL_TERMS_NOT_AGREED', r.json);
  r = await confirm({ 'If-Match': read.etag }, { quoteVersion: 2, agreed: true });
  const c1 = r.json;
  check('confirmed: rental order and trade order', r.status === 200 && c1.rentalOrderId && c1.tradeOrderId && c1.payBefore, c1);
  r = await confirm({ 'If-Match': read.etag }, { quoteVersion: 2, agreed: true });
  check('confirming again returns the same order', r.status === 200 && r.json.rentalOrderId === c1.rentalOrderId, r.json);
  r = await quote(merchant, inquiry.inquiryId, 30000);
  check('no more quotes after the contract', r.status === 409 && r.json.code === 'RENTAL_STATE_INVALID', r.json);
  r = await call('GET', `/api/v1/trade/orders/${c1.tradeOrderId}`, { token: customer });
  const minutes = (new Date(r.json.payBefore) - Date.now()) / 60000;
  check('trade order is a RENTAL order paying the lessor the total within 30 minutes', r.json.source === 'RENTAL' && r.json.sourceRef === c1.rentalOrderId && /^RO/.test(r.json.orderNo) && r.json.amounts.payableMinor === 36000 * 5 + 5000 && minutes > 28 && minutes <= 30, r.json);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods-order list leaves rental orders out', !(r.json.items ?? []).some((o) => o.orderId === c1.tradeOrderId));
  r = await call('GET', `/api/v1/rental-orders/${c1.rentalOrderId}`, { token: customer });
  check('rental order waits for payment with the contract', r.json.status === 'PENDING_PAYMENT' && /^RC\d{14}$/.test(r.json.contract.contractNo) && r.json.contract.quoteVersion === 2 && r.json.contract.totalMinor === 36000 * 5 + 5000 && r.json.contract.fees.length === 2 && r.json.contract.endDate === beijingDate(7), r.json);

  // 4. 付款：待交接，商家看得到完整手机号
  await launch(customer, c1.tradeOrderId);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === outTradeNo(c1.tradeOrderId));
  check('wechat order goes to the lessor sub merchant', fakeOrder?.sub_mchid === '1900000101' && fakeOrder.total === 36000 * 5 + 5000, fakeOrder);
  await fake('POST', `/_control/pay/${outTradeNo(c1.tradeOrderId)}?notify=1`);
  r = await call('GET', `/api/v1/rental-orders/${c1.rentalOrderId}`, { token: customer });
  check('paid order awaits handover', r.json.status === 'AWAITING_HANDOVER' && r.json.payment.paidAt && !r.json.handoverConfirmable, r.json);
  r = await call('GET', `/api/v1/provider/rental-orders/${c1.rentalOrderId}`, { token: merchant });
  check('the lessor sees the full phone after payment', r.json.contract.place.phone === '13800001234', r.json.contract.place);

  // 5. 交接、归还
  r = await call('POST', `/api/v1/rental-orders/${c1.rentalOrderId}/handover-confirmation`, { token: customer });
  check('nothing to confirm before the lessor records the handover', r.status === 409 && r.json.code === 'RENTAL_STATE_INVALID', r.json);
  r = await call('POST', `/api/v1/provider/rental-orders/${c1.rentalOrderId}/handover`, { token: merchant, body: { note: '编号 DOG-0118，配件齐全，外观无损伤' } });
  check('handover recorded', r.status === 200 && r.json.status === 'HANDOVER_RECORDED' && r.json.handoverRecord.confirmedAt === null, r.json);
  r = await call('POST', `/api/v1/rental-orders/${c1.rentalOrderId}/handover-confirmation`, { token: customer });
  check('customer confirms the handover: in use, rent starts', r.json.status === 'IN_USE' && r.json.rentStartedAt && r.json.handoverRecord.confirmedAt, r.json);
  r = await call('POST', `/api/v1/provider/rental-orders/${c1.rentalOrderId}/cancel`, { token: merchant, body: { reason: '档期冲突' } });
  check('no cancelling after the handover', r.status === 409 && r.json.code === 'RENTAL_STATE_INVALID', r.json);
  r = await call('POST', `/api/v1/provider/rental-orders/${c1.rentalOrderId}/return`, { token: merchant, body: { note: '配件齐全，无新增损伤，未超期' } });
  check('return recorded', r.json.status === 'RETURN_RECORDED', r.json);
  r = await call('POST', `/api/v1/rental-orders/${c1.rentalOrderId}/return-confirmation`, { token: customer });
  check('customer confirms the return: ended', r.json.status === 'ENDED' && r.json.endedAt && r.json.returnRecord.confirmedAt, r.json);
  r = await call('POST', `/api/v1/rental-orders/${c1.rentalOrderId}/return-confirmation`, { token: customer });
  check('confirming again changes nothing', r.status === 200 && r.json.status === 'ENDED', r.json);
  r = await call('GET', '/api/v1/rental-orders?status=ENDED', { token: customer });
  check('ended order is listed', r.json.items.some((o) => o.rentalOrderId === c1.rentalOrderId));

  // 6. 交接前商家取消：整单原路退
  const c2 = await contracted(customer, merchant, addressId);
  await launch(customer, c2.tradeOrderId);
  await fake('POST', `/_control/pay/${outTradeNo(c2.tradeOrderId)}?notify=1`);
  await call('POST', `/api/v1/provider/rental-orders/${c2.rentalOrderId}/handover`, { token: merchant, body: { note: '已到场' } });
  r = await call('POST', `/api/v1/provider/rental-orders/${c2.rentalOrderId}/cancel`, { token: merchant, body: { reason: '档期临时冲突' } });
  check('the lessor cancels before the handover with a full refund', r.status === 200 && r.json.status === 'CANCELLED' && r.json.refund?.amountMinor === DAILY * 5 + 5000, r.json);
  check('refund initiated by the source for the whole amount', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${c2.tradeOrderId}'`) === `SOURCE:${DAILY * 5 + 5000}`);
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${c2.tradeOrderId}'`);
  await waitFor(() => psql(`SELECT status FROM trade_refund WHERE order_id='${c2.tradeOrderId}'`) === 'PROCESSING', 30);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  r = await call('GET', `/api/v1/rental-orders/${c2.rentalOrderId}`, { token: customer });
  check('refund shows as succeeded and the trade order is refunded', r.json.refund?.status === 'SUCCEEDED' && tradeStatus(c2.tradeOrderId) === 'REFUNDED', [r.json.refund, tradeStatus(c2.tradeOrderId)]);

  // 7. 关闭之后才到账：不恢复，整笔自动退
  const c3 = await contracted(customer, merchant, addressId);
  await launch(customer, c3.tradeOrderId);
  closeLocally(c3.rentalOrderId, c3.tradeOrderId);
  await fake('POST', `/_control/pay/${outTradeNo(c3.tradeOrderId)}?notify=1`);
  check('late payment leaves the rental order closed', orderStatus(c3.rentalOrderId) === 'CLOSED');
  check('and refunds the whole amount automatically', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${c3.tradeOrderId}'`) === `SOURCE:${DAILY * 5 + 5000}` && tradeStatus(c3.tradeOrderId) === 'REFUNDING');

  // 8. 定时任务：未付款超时关闭；7 天没报价关闭；报价过期
  const c4 = await contracted(customer, merchant, addressId);
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id='${c4.tradeOrderId}'`);
  const lonely = (await submit(customer, addressId)).json;
  psql(`UPDATE rental_inquiry SET quote_deadline = now() - interval '1 second' WHERE id='${lonely.inquiryId}'`);
  const lapsing = (await submit(customer, addressId)).json;
  await quote(merchant, lapsing.inquiryId, DAILY);
  psql(`UPDATE rental_quote SET quoted_at = now() - interval '2 days', valid_until = now() - interval '1 second' WHERE inquiry_id='${lapsing.inquiryId}'`);
  const closed = await waitFor(() => orderStatus(c4.rentalOrderId) === 'CLOSED' && inquiryStatus(lonely.inquiryId) === 'CLOSED:NO_QUOTE' && inquiryStatus(lapsing.inquiryId) === 'EXPIRED:', 180);
  check('jobs close the unpaid order, the unquoted inquiry and expire the quote', closed, [orderStatus(c4.rentalOrderId), inquiryStatus(lonely.inquiryId), inquiryStatus(lapsing.inquiryId)]);
  r = await call('GET', `/api/v1/rental-inquiries/${lapsing.inquiryId}`, { token: customer });
  check('an expired quote cannot be confirmed but can be closed', r.json.status === 'EXPIRED' && r.json.quote.expired && !r.json.confirmable && r.json.closable, r.json);
  r = await call('POST', `/api/v1/rental-inquiries/${lapsing.inquiryId}/confirm`, { token: customer, headers: { 'If-Match': r.etag }, body: { quoteVersion: 1, agreed: true } });
  check('confirming an expired quote is refused', r.status === 409 && r.json.code === 'RENTAL_QUOTE_EXPIRED', r.json);
  r = await quote(merchant, lapsing.inquiryId, 35000);
  check('the lessor may quote again after expiry', r.json.status === 'QUOTED' && r.json.quote.version === 2, r.json);
  r = await call('POST', `/api/v1/rental-inquiries/${lapsing.inquiryId}/close`, { token: customer });
  check('the customer closes the inquiry before confirming', r.json.status === 'CLOSED' && r.json.closeReason === 'CUSTOMER', r.json);
  r = await call('POST', `/api/v1/provider/rental-inquiries/${lonely.inquiryId}/decline`, { token: merchant, body: { reason: '档期已满' } });
  check('a closed inquiry cannot be declined', r.status === 409, r.json);
  r = await call('GET', '/api/v1/rental-inquiries?status=CLOSED', { token: customer });
  check('closed inquiries are listed', r.json.items.some((i) => i.inquiryId === lonely.inquiryId));
}

const done = finish(cleanup);
main().then(() => done(), done);
