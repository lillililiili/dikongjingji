#!/usr/bin/env node
'use strict';

// E05 履约和退款：发货、确认收货、部分退款、自提取货码和输错锁定、取消即退款、拒绝后重退、退款异常、退款查询、自动确认。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉 dev-customer 的订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/fulfilment.cjs

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { BASE, FAKE, FAKE_DIR, BUYER, offerId, check, call, fake, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./lib.cjs');
const KIT = offerId('101', '1'); // 示例机器人商家：快递、自提都行，129900
const PRICE = 129900;

const status = (id) => psql(`SELECT status FROM trade_order WHERE id='${id}'`);
const trail = (id) => psql(`SELECT string_agg(cause, ',' ORDER BY occurred_at, created_at) FROM trade_state_transition WHERE order_id='${id}'`);
const refundNos = (id) => psql(`SELECT string_agg(out_refund_no, ',' ORDER BY created_at) FROM trade_refund WHERE order_id='${id}'`).split(',').filter(Boolean);

function cleanup() {
  const ids = `SELECT id FROM trade_order WHERE buyer_id='${BUYER}'`;
  psql(`DELETE FROM trade_channel_notification WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (${ids}))`);
  psql(`DELETE FROM trade_refund WHERE order_id IN (${ids})`);
  psql(`DELETE FROM trade_payment WHERE order_id IN (${ids})`);
  for (const table of ['trade_state_transition', 'trade_order_line', 'trade_order_delivery']) psql(`DELETE FROM ${table} WHERE order_id IN (${ids})`);
  psql(`DELETE FROM trade_order WHERE buyer_id='${BUYER}'`);
  psql(`DELETE FROM account_address WHERE owner_id='${BUYER}'`);
}

let customer;
let addressId;
async function paidOrder(mode) {
  const body = { cityCode: '370500', offerId: KIT, quantity: 1, deliveryMode: mode, expectedPayableMinor: PRICE, agreementAccepted: true, ...(mode === 'EXPRESS' ? { addressId } : {}) };
  const submitted = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body });
  if (submitted.status !== 201) throw new Error('submit ' + JSON.stringify(submitted.json));
  const id = submitted.json.orderId;
  const launched = await call('POST', `/api/v1/trade/orders/${id}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  if (launched.status !== 200) throw new Error('launch ' + JSON.stringify(launched.json));
  const no = psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${id}'`);
  const paid = await fake('POST', `/_control/pay/${no}?notify=1`);
  if (paid.json?.notification?.status !== 204 || status(id) !== 'PAID') throw new Error('pay ' + JSON.stringify(paid.json));
  return (await call('GET', `/api/v1/trade/orders/${id}`, { token: customer })).json;
}
const settleRefund = (no, outcome = 'SUCCESS', notify = 1) => fake('POST', `/_control/refund/${no}?status=${outcome}&notify=${notify}`);

async function main() {
  cleanup();
  customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  const provider = await login('dev-provider');
  addressId = (await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '东营区 示例路 88 号', isDefault: true } })).json.addressId;

  check('merchant list needs login', (await call('GET', '/api/v1/provider/trade/orders')).status === 401);
  check('customer cannot use merchant routes', (await call('GET', '/api/v1/provider/trade/orders', { token: customer })).status === 403);
  check('bad status filter is 400', (await call('GET', '/api/v1/provider/trade/orders?status=NOPE', { token: merchant })).status === 400);

  // 1. 快递：发货、确认收货
  const a = await paidOrder('EXPRESS');
  let r = await call('GET', '/api/v1/provider/trade/orders?status=PAID', { token: merchant });
  const seen = r.json.items.find((o) => o.orderId === a.orderId);
  check('merchant sees the paid order with the full phone', seen && seen.delivery.recipientPhone === '13800002026' && seen.delivery.phoneMasked === '138****2026' && seen.delivery.pickupCode === null, seen?.delivery);
  check('buyer view has no full phone', a.delivery.recipientPhone === null && a.cancellable === true, a.delivery);
  check('another provider cannot see it', (await call('GET', '/api/v1/provider/trade/orders?status=PAID', { token: provider })).json.items.every((o) => o.orderId !== a.orderId));
  const ship = (token, id, version, tracking = 'SF1234567890') => call('POST', `/api/v1/provider/trade/orders/${id}/shipment`, { token, headers: { 'Idempotency-Key': key(), 'If-Match': `"${version}"` }, body: { carrierName: '顺丰速运', trackingNo: tracking } });
  check('another provider cannot ship it', (await ship(provider, a.orderId, a.version)).status === 404);
  check('stale version is 412', (await ship(merchant, a.orderId, a.version + 5)).status === 412);
  r = await ship(merchant, a.orderId, a.version);
  check('ship', r.status === 200 && r.json.status === 'SHIPPED' && r.json.delivery.trackingNo === 'SF1234567890' && r.json.delivery.shippedAt && r.etag === `"${a.version + 1}"`, r.json);
  check('shipping again with the same tracking number changes nothing', (await ship(merchant, a.orderId, a.version)).status === 200);
  check('shipping again with another tracking number is refused', (await ship(merchant, a.orderId, a.version + 1, 'SF0000000000')).json?.code === 'TRADE_ORDER_STATE_INVALID');
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
  check('buyer sees the tracking number and can no longer cancel', r.json.delivery.carrierName === '顺丰速运' && r.json.cancellable === false);
  r = await call('POST', `/api/v1/trade/orders/${a.orderId}/receipt`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': r.etag } });
  check('buyer confirms receipt', r.status === 200 && r.json.status === 'COMPLETED' && r.json.completedAt, r.json);
  check('confirming again changes nothing', (await call('POST', `/api/v1/trade/orders/${a.orderId}/receipt`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"0"' } })).json?.status === 'COMPLETED');
  check('express trail', trail(a.orderId) === 'SUBMIT,PAYMENT_NOTIFIED,SHIPMENT,RECEIPT', trail(a.orderId));

  // 2. 商家部分退款，再退完
  const refund = (id, amount, reason, k = key()) => call('POST', `/api/v1/provider/trade/orders/${id}/refunds`, { token: merchant, headers: { 'Idempotency-Key': k }, body: { amountMinor: amount, reason } });
  const k1 = key();
  r = await refund(a.orderId, 10000, '少发一个配件', k1);
  check('partial refund accepted by wechat', r.status === 201 && r.json.status === 'PROCESSING' && r.json.amountMinor === 10000, r.json);
  const firstRefundId = r.json.refundId;
  check('partial refund keeps the order completed', status(a.orderId) === 'COMPLETED');
  check('replaying the same refund returns it', (await refund(a.orderId, 10000, '少发一个配件', k1)).json?.refundId === firstRefundId);
  check('same key, other amount is refused', (await refund(a.orderId, 20000, '少发一个配件', k1)).json?.code === 'TRADE_IDEMPOTENCY_KEY_REUSED');
  check('more than what is left is refused', (await refund(a.orderId, PRICE, '全退')).json?.code === 'TRADE_REFUND_EXCEEDS_PAID');
  await settleRefund(refundNos(a.orderId)[0]);
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
  check('partial refund arrived', r.json.amounts.refundedMinor === 10000 && r.json.status === 'COMPLETED' && r.json.refunds[0].status === 'SUCCEEDED' && r.json.refunds[0].succeededAt, r.json);
  r = await refund(a.orderId, PRICE - 10000, '退货');
  check('refunding the rest moves the order to refunding', r.status === 201 && status(a.orderId) === 'REFUNDING');
  await settleRefund(refundNos(a.orderId)[1]);
  check('all refunded', status(a.orderId) === 'REFUNDED' && psql(`SELECT refunded_minor FROM trade_order WHERE id='${a.orderId}'`) === String(PRICE));
  check('refund trail', trail(a.orderId).endsWith('RECEIPT,MERCHANT_REFUND,REFUND_NOTIFIED'), trail(a.orderId));

  // 3. 自提：取货码、输错、核销
  const b = await paidOrder('PICKUP');
  check('buyer sees a 6-digit pickup code', /^\d{6}$/.test(b.delivery.pickupCode || ''), b.delivery);
  r = await call('GET', '/api/v1/provider/trade/orders?status=PAID', { token: merchant });
  check('merchant does not see the pickup code', r.json.items.find((o) => o.orderId === b.orderId)?.delivery.pickupCode === null);
  const pickup = (code, version = b.version) => call('POST', `/api/v1/provider/trade/orders/${b.orderId}/pickup`, { token: merchant, headers: { 'Idempotency-Key': key(), 'If-Match': `"${version}"` }, body: { pickupCode: code } });
  const wrong = b.delivery.pickupCode === '000000' ? '111111' : '000000';
  r = await pickup(wrong);
  check('wrong pickup code is refused and counted', r.json?.code === 'TRADE_PICKUP_CODE_INVALID' && psql(`SELECT pickup_failures FROM trade_order_delivery WHERE order_id='${b.orderId}'`) === '1', r.json);
  check('shipping a pickup order is refused', (await ship(merchant, b.orderId, b.version)).json?.code === 'TRADE_ORDER_STATE_INVALID');
  check('malformed code is 400', (await pickup('12ab')).status === 400);
  r = await pickup(b.delivery.pickupCode);
  check('right pickup code completes the order', r.status === 200 && r.json.status === 'COMPLETED' && trail(b.orderId) === 'SUBMIT,PAYMENT_NOTIFIED,PICKUP', [r.json?.status, trail(b.orderId)]);

  const c = await paidOrder('PICKUP');
  psql(`UPDATE trade_order_delivery SET pickup_failures = 10 WHERE order_id='${c.orderId}'`);
  r = await call('POST', `/api/v1/provider/trade/orders/${c.orderId}/pickup`, { token: merchant, headers: { 'Idempotency-Key': key(), 'If-Match': `"${c.version}"` }, body: { pickupCode: c.delivery.pickupCode } });
  check('after 10 wrong codes even the right one is refused', r.json?.code === 'TRADE_PICKUP_CODE_INVALID' && status(c.orderId) === 'PAID');

  // 4. 买家取消已付款的单：全额退款
  const d = await paidOrder('EXPRESS');
  const kc = key();
  r = await call('POST', `/api/v1/trade/orders/${d.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': kc, 'If-Match': `"${d.version}"` } });
  check('cancelling a paid order starts a full refund', r.status === 200 && r.json.status === 'REFUNDING' && r.json.refunds.length === 1 && r.json.refunds[0].amountMinor === PRICE && r.json.refunds[0].status === 'PROCESSING' && r.json.cancellable === false, r.json);
  r = await call('POST', `/api/v1/trade/orders/${d.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': kc, 'If-Match': `"${d.version}"` } });
  check('cancelling again makes no second refund', r.status === 200 && psql(`SELECT count(*) FROM trade_refund WHERE order_id='${d.orderId}'`) === '1');
  check('a refunding order cannot be shipped', (await ship(merchant, d.orderId, d.version + 1)).json?.code === 'TRADE_ORDER_STATE_INVALID');
  const noD = refundNos(d.orderId)[0];
  r = await settleRefund(noD);
  check('refund notification answered 204', r.json?.notification?.status === 204, r.json);
  check('order refunded', status(d.orderId) === 'REFUNDED' && trail(d.orderId) === 'SUBMIT,PAYMENT_NOTIFIED,BUYER_CANCEL,REFUND_NOTIFIED', trail(d.orderId));
  r = await fake('POST', `/_control/renotify/${noD}`);
  check('repeated refund notification counted once', r.json?.status === 204 && psql(`SELECT refunded_minor FROM trade_order WHERE id='${d.orderId}'`) === String(PRICE) && psql(`SELECT outcome FROM trade_channel_notification WHERE out_refund_no='${noD}'`) === 'APPLIED');

  // 5. 微信当场拒绝退款，商家再退
  const e = await paidOrder('EXPRESS');
  await fake('POST', '/_control/reject-next-refund?code=NOT_ENOUGH');
  r = await refund(e.orderId, PRICE, '缺货退款');
  check('rejected refund is closed and the order needs attention', r.status === 201 && r.json.status === 'CLOSED' && status(e.orderId) === 'REFUND_FAILED' && trail(e.orderId).endsWith('MERCHANT_REFUND,REFUND_REJECTED'), [r.json, status(e.orderId), trail(e.orderId)]);
  r = await refund(e.orderId, PRICE, '缺货退款，重新申请');
  check('merchant can refund again after a failure', r.status === 201 && r.json.status === 'PROCESSING' && status(e.orderId) === 'REFUNDING', r.json);
  await settleRefund(refundNos(e.orderId)[1]);
  check('second try refunded', status(e.orderId) === 'REFUNDED');

  // 6. 退款异常
  const f = await paidOrder('PICKUP');
  await call('POST', `/api/v1/trade/orders/${f.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': `"${f.version}"` } });
  await settleRefund(refundNos(f.orderId)[0], 'ABNORMAL');
  check('abnormal refund marks the order for manual handling', status(f.orderId) === 'REFUND_FAILED' && psql(`SELECT status FROM trade_refund WHERE order_id='${f.orderId}'`) === 'ABNORMAL');

  // 7. 退款回调丢了：定时查询
  const g = await paidOrder('EXPRESS');
  await call('POST', `/api/v1/trade/orders/${g.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': `"${g.version}"` } });
  await settleRefund(refundNos(g.orderId)[0], 'SUCCESS', 0);
  psql(`UPDATE trade_refund SET updated_at = now() - interval '2 minutes' WHERE order_id='${g.orderId}'`);
  // 8. 发货满 10 天自动确认
  const h = await paidOrder('EXPRESS');
  await ship(merchant, h.orderId, h.version);
  psql(`UPDATE trade_order SET shipped_at = now() - interval '11 days' WHERE id='${h.orderId}'`);
  const settled = await waitFor(() => status(g.orderId) === 'REFUNDED' && status(h.orderId) === 'COMPLETED', 150);
  check('refund polling finds the refund', status(g.orderId) === 'REFUNDED' && trail(g.orderId).endsWith('BUYER_CANCEL,REFUND_QUERIED'), trail(g.orderId));
  check('receipt confirmed automatically after 10 days', settled && trail(h.orderId).endsWith('SHIPMENT,AUTO_RECEIPT'), trail(h.orderId));

}
const done = finish(cleanup);
main().then(() => done(), done);
