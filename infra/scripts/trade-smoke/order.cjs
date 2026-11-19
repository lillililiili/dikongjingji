#!/usr/bin/env node
'use strict';

// E02 订单：提交、重放、金额变化、商家没开通、缺地址、取消、超时关闭。不需要模拟微信。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉 dev-customer 的订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/order.cjs

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { BASE, FAKE, FAKE_DIR, BUYER, offerId, check, call, fake, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./lib.cjs');
const KIT_FACTORY = offerId('101', '1');
const KIT_STORE = offerId('101', '3');
const FIELD = offerId('302', '1');
const DRONE_FACTORY = offerId('201', '4'); // 示例无人机厂家：没有登记收款账户


const cleanup = cleanupBuyer;

async function main() {
  const customer = await login('dev-customer');
  const provider = await login('dev-provider');
  psql(`DELETE FROM trade_state_transition WHERE order_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}')`);
  psql(`DELETE FROM trade_order_line WHERE order_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}')`);
  psql(`DELETE FROM trade_order_delivery WHERE order_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}')`);
  psql(`DELETE FROM trade_order WHERE buyer_id='${BUYER}'`);
  psql(`DELETE FROM mall_cart_item WHERE account_id='${BUYER}'`);
  psql(`DELETE FROM account_address WHERE owner_id='${BUYER}'`);
  // 商城的三个商家（19000001xx）；活动主办方、场馆的收款账户另有编号段。
  check('dev merchant accounts seeded', psql("SELECT count(*) FROM trade_merchant_account WHERE sub_mchid LIKE '19000001%'") === '3');

  check('orders need login', (await call('GET', '/api/v1/trade/orders')).status === 401);
  check('provider cannot read buyer orders', (await call('GET', '/api/v1/trade/orders', { token: provider })).status === 403);

  // buy now, pickup, no address needed
  const pickupBody = { cityCode: '370500', offerId: KIT_STORE, quantity: 2, deliveryMode: 'PICKUP', expectedPayableMinor: 276000, agreementAccepted: true, buyerNote: ' 周六下午来取 ' };
  const k1 = key();
  let r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': k1 }, body: pickupBody });
  check('submit pickup order', r.status === 201 && r.json.status === 'PENDING_PAYMENT' && r.json.amounts.payableMinor === 276000 && r.json.delivery.mode === 'PICKUP' && r.json.delivery.pickupPoint.includes('示例科创器材店') && r.json.buyerNote === '周六下午来取' && r.json.cancellable && /^MO\d{16}$/.test(r.json.orderNo) && r.etag === '"0"', JSON.stringify(r.json).slice(0, 500));
  const pickupOrder = r.json;
  const payBefore = new Date(pickupOrder.payBefore).getTime() - new Date(pickupOrder.createdAt).getTime();
  check('pay within 30 minutes', payBefore === 30 * 60 * 1000, String(payBefore));
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': k1 }, body: pickupBody });
  check('replay returns the same order with 200', r.status === 200 && r.json.orderId === pickupOrder.orderId);
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': k1 }, body: { ...pickupBody, quantity: 3, expectedPayableMinor: 414000 } });
  check('same key, other content is 409', r.status === 409 && r.json.code === 'TRADE_IDEMPOTENCY_KEY_REUSED', JSON.stringify(r.json));
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { ...pickupBody, expectedPayableMinor: 270000 } });
  check('wrong expected amount is refused', r.status === 409 && r.json.code === 'TRADE_AMOUNT_CHANGED', JSON.stringify(r.json));
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { ...pickupBody, deliveryMode: 'EXPRESS' } });
  check('express on a pickup-only offer is refused', r.status === 409 && r.json.code === 'TRADE_DELIVERY_UNAVAILABLE', JSON.stringify(r.json));
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: '370500', offerId: DRONE_FACTORY, quantity: 1, deliveryMode: 'EXPRESS', expectedPayableMinor: 89900, agreementAccepted: true } });
  check('merchant without payment account is refused first', r.status === 409 && r.json.code === 'TRADE_MERCHANT_PAYMENT_UNAVAILABLE', JSON.stringify(r.json));
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { ...pickupBody, agreementAccepted: false } });
  check('agreement required', r.status === 400);
  check('missing idempotency key is 400', (await call('POST', '/api/v1/trade/orders', { token: customer, body: pickupBody })).status === 400);

  // express from cart, needs an address
  const kitLine = (await call('POST', '/api/v1/mall/cart/items', { token: customer, body: { offerId: KIT_FACTORY, quantity: 1, cityCode: '370500' } })).json.cartItemId;
  const fieldLine = (await call('POST', '/api/v1/mall/cart/items', { token: customer, body: { offerId: FIELD, quantity: 2, cityCode: '370500' } })).json.cartItemId;
  const cartBody = { cityCode: '370500', cartItemIds: [kitLine, fieldLine], deliveryMode: 'EXPRESS', expectedPayableMinor: 129900 + 92000, agreementAccepted: true };
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: cartBody });
  check('express without address is refused', r.status === 409 && r.json.code === 'TRADE_ADDRESS_REQUIRED', JSON.stringify(r.json));
  await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '东营区 示例路 88 号', isDefault: true } });
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: cartBody });
  check('submit express order from cart', r.status === 201 && r.json.lines.length === 2 && r.json.delivery.recipientName === '王女士' && r.json.delivery.phoneMasked === '138****2026' && r.json.delivery.addressLine === '山东省东营市 东营区 示例路 88 号' && r.json.merchant.name === '示例机器人商家', JSON.stringify(r.json).slice(0, 600));
  const expressOrder = r.json;
  const cart = (await call('GET', '/api/v1/mall/cart?cityCode=370500', { token: customer })).json;
  check('ordered cart lines are removed', cart.groups.length === 0 && cart.invalidItems.length === 0, JSON.stringify(cart).slice(0, 200));
  check('snapshot keeps the sub-merchant', psql(`SELECT sub_mchid FROM trade_order WHERE id='${expressOrder.orderId}'`) === '1900000101');

  // address deleted later: snapshot stays
  const addr = (await call('GET', '/api/v1/account/addresses', { token: customer })).json.items[0];
  await call('DELETE', `/api/v1/account/addresses/${addr.addressId}`, { token: customer });
  r = await call('GET', `/api/v1/trade/orders/${expressOrder.orderId}`, { token: customer });
  check('address snapshot survives deleting the address', r.status === 200 && r.json.delivery.addressLine.includes('示例路 88 号'));

  // lists
  r = await call('GET', '/api/v1/trade/orders?tab=TO_PAY', { token: customer });
  check('to-pay tab lists both, newest first', r.status === 200 && r.json.total === 2 && r.json.items[0].orderId === expressOrder.orderId);
  r = await call('GET', '/api/v1/trade/orders?tab=DONE', { token: customer });
  check('done tab empty', r.json.total === 0);
  check('bad tab is 400', (await call('GET', '/api/v1/trade/orders?tab=X', { token: customer })).status === 400);
  check('provider cannot read the order', (await call('GET', `/api/v1/trade/orders/${expressOrder.orderId}`, { token: provider })).status === 403);
  check('unknown order is 404', (await call('GET', `/api/v1/trade/orders/${crypto.randomUUID()}`, { token: customer })).status === 404);

  // cancel
  r = await call('POST', `/api/v1/trade/orders/${pickupOrder.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"5"' }, body: {} });
  check('stale version is 412', r.status === 412 && r.json.code === 'TRADE_VERSION_CONFLICT', JSON.stringify(r.json));
  r = await call('POST', `/api/v1/trade/orders/${pickupOrder.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"0"' }, body: { reason: '不要了' } });
  check('cancel unpaid', r.status === 200 && r.json.status === 'CLOSED' && r.json.closedAt && !r.json.cancellable && r.json.payBefore === null && r.json.version === 1, JSON.stringify(r.json).slice(0, 300));
  r = await call('POST', `/api/v1/trade/orders/${pickupOrder.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"0"' }, body: {} });
  check('cancelling twice changes nothing', r.status === 200 && r.json.status === 'CLOSED' && r.json.version === 1);

  // timeout: move pay_before into the past, wait for the job
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 minute' WHERE id='${expressOrder.orderId}'`);
  let closed = false;
  for (let i = 0; i < 50 && !closed; i++) { await new Promise((res) => setTimeout(res, 2000)); closed = psql(`SELECT status FROM trade_order WHERE id='${expressOrder.orderId}'`) === 'CLOSED'; }
  check('timeout job closes the expired order', closed);
  check('audit trail', psql(`SELECT string_agg(cause, ',' ORDER BY occurred_at) FROM trade_state_transition WHERE order_id='${expressOrder.orderId}'`) === 'SUBMIT,TIMEOUT');

}
const done = finish(cleanup);
main().then(() => done(), done);
