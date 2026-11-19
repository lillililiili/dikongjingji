#!/usr/bin/env node
'use strict';

// E04 支付：发起支付和调起签名、回调入账、重复回调、伪造回调、查单、金额不符、取消前查单、过期查单后关、回调丢失时轮询。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉 dev-customer 的订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/payment.cjs

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { BASE, FAKE, FAKE_DIR, BUYER, offerId, check, call, fake, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./lib.cjs');
const merchantPublic = crypto.createPublicKey(fs.readFileSync(`${path.join(FAKE_DIR, 'secrets', 'merchant_pub.pem')}`));
const KIT_STORE = offerId('101', '3');
const PRICE = 138000;

async function newOrder(token) {
  const r = await call('POST', '/api/v1/trade/orders', { token, headers: { 'Idempotency-Key': key() }, body: { cityCode: '370500', offerId: KIT_STORE, quantity: 1, deliveryMode: 'PICKUP', expectedPayableMinor: PRICE, agreementAccepted: true } });
  if (r.status !== 201) throw new Error('submit ' + r.status + ' ' + JSON.stringify(r.json));
  return r.json;
}
const launch = (token, orderId, k = key()) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': k }, body: { channel: 'WECHAT_APP' } });
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const orderStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const transitions = (orderId) => psql(`SELECT string_agg(cause, ',' ORDER BY occurred_at, created_at) FROM trade_state_transition WHERE order_id='${orderId}'`);

function cleanup() {
  const ids = `SELECT id FROM trade_order WHERE buyer_id='${BUYER}'`;
  psql(`DELETE FROM trade_channel_notification WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (${ids}))`);
  psql(`DELETE FROM trade_payment WHERE order_id IN (${ids})`);
  for (const table of ['trade_state_transition', 'trade_order_line', 'trade_order_delivery']) psql(`DELETE FROM ${table} WHERE order_id IN (${ids})`);
  psql(`DELETE FROM trade_order WHERE buyer_id='${BUYER}'`);
}

async function main() {
  cleanup();
  const customer = await login('dev-customer');
  const provider = await login('dev-provider');

  // 1. 发起支付
  const a = await newOrder(customer);
  check('payments need login', (await call('POST', `/api/v1/trade/orders/${a.orderId}/payments`, { body: { channel: 'WECHAT_APP' }, headers: { 'Idempotency-Key': key() } })).status === 401);
  check('provider cannot pay a buyer order', (await launch(provider, a.orderId)).status === 403);
  check('unknown order is 404', (await launch(customer, crypto.randomUUID())).status === 404);
  check('unknown channel is 400', (await call('POST', `/api/v1/trade/orders/${a.orderId}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'ALIPAY' } })).status === 400);
  let r = await launch(customer, a.orderId);
  const call1 = r.json?.appCall;
  check('launch returns app call parameters', r.status === 200 && r.json.channel === 'WECHAT_APP' && call1 && call1.appId === 'wxdevfakeapp000001' && call1.partnerId === '1900000001' && call1.packageValue === 'Sign=WXPay' && /^\d{10}$/.test(call1.timeStamp), r.json);
  const signed = call1 && crypto.verify('sha256', Buffer.from(`${call1.appId}\n${call1.timeStamp}\n${call1.nonceStr}\n${call1.prepayId}\n`), merchantPublic, Buffer.from(call1.sign, 'base64'));
  check('app call signature verifies with the merchant key', signed);
  check('expiresAt is the order deadline', r.json && new Date(r.json.expiresAt).getTime() === new Date(a.payBefore).getTime(), [r.json?.expiresAt, a.payBefore]);
  const noA = outTradeNo(a.orderId);
  const fakeOrders = (await fake('GET', '/_control/orders')).json;
  const fa = fakeOrders.find((o) => o.out_trade_no === noA);
  check('wechat got the order with amount, sub merchant, no profit sharing and the Beijing deadline', fa && fa.total === PRICE && fa.sub_mchid === '1900000103' && fa.profit_sharing === false && fa.time_expire.endsWith('+08:00') && new Date(fa.time_expire).getTime() === Math.floor(new Date(a.payBefore).getTime() / 1000) * 1000 && fa.description.includes('示例科创器材店'), fa);
  r = await launch(customer, a.orderId);
  check('launching again reuses the payment and prepay id', r.status === 200 && r.json.paymentId === undefined ? false : r.json.appCall.prepayId === call1.prepayId, r.json);
  check('only one payment row', psql(`SELECT count(*) FROM trade_payment WHERE order_id='${a.orderId}'`) === '1');
  r = await call('POST', `/api/v1/trade/orders/${a.orderId}/payment-check`, { token: customer });
  check('check before paying leaves it pending', r.status === 200 && r.json.status === 'PENDING_PAYMENT');

  // 2. 付款回调
  r = await fake('POST', `/_control/pay/${noA}?notify=1`);
  check('notification answered 204', r.json?.notification?.status === 204, r.json?.notification);
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
  check('order is paid', r.json.status === 'PAID' && r.json.paidAt && r.json.amounts.paidMinor === PRICE && r.json.payBefore === null && r.etag === '"1"', r.json);
  r = await fake('POST', `/_control/renotify/${noA}`);
  check('repeated notification answered 204', r.json?.status === 204, r.json);
  check('notification recorded once as applied', psql(`SELECT string_agg(outcome, ',') FROM trade_channel_notification WHERE out_trade_no='${noA}'`) === 'APPLIED');
  check('paid once in the audit trail', transitions(a.orderId) === 'SUBMIT,PAYMENT_NOTIFIED', transitions(a.orderId));
  check('payment succeeded with the transaction id', psql(`SELECT status || ':' || (transaction_id IS NOT NULL) FROM trade_payment WHERE order_id='${a.orderId}'`) === 'SUCCEEDED:true');
  check('paying a paid order is refused', (await launch(customer, a.orderId)).json?.code === 'TRADE_ORDER_STATE_INVALID');
  r = await call('POST', `/api/v1/trade/orders/${a.orderId}/payment-check`, { token: customer });
  check('check on a paid order just returns it', r.status === 200 && r.json.status === 'PAID');

  // 3. 回调伪造
  const forged = await fetch(`${BASE}/api/v1/trade/channels/wechat/notifications`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Wechatpay-Serial': 'PUB_KEY_ID_0000000000000000000000000001', 'Wechatpay-Signature': 'AAAA', 'Wechatpay-Timestamp': String(Math.floor(Date.now() / 1000)), 'Wechatpay-Nonce': 'x' }, body: '{"id":"forged-1","event_type":"TRANSACTION.SUCCESS"}' });
  check('forged notification is 401 with FAIL', forged.status === 401 && (await forged.json()).code === 'FAIL');
  check('forged notification not recorded', psql(`SELECT count(*) FROM trade_channel_notification WHERE notification_id='forged-1'`) === '0');
  check('notification without headers is 401, not a login redirect', (await fetch(`${BASE}/api/v1/trade/channels/wechat/notifications`, { method: 'POST', body: '{}' })).status === 401);

  // 4. 没收到回调：App 回来查一次单
  const b = await newOrder(customer);
  await launch(customer, b.orderId);
  await fake('POST', `/_control/pay/${outTradeNo(b.orderId)}?notify=0`);
  r = await call('POST', `/api/v1/trade/orders/${b.orderId}/payment-check`, { token: customer });
  check('payment-check finds the payment', r.status === 200 && r.json.status === 'PAID' && r.etag === '"1"', r.json);
  check('queried in the audit trail', transitions(b.orderId) === 'SUBMIT,PAYMENT_QUERIED', transitions(b.orderId));

  // 5. 金额不符
  const c = await newOrder(customer);
  await launch(customer, c.orderId);
  const noC = outTradeNo(c.orderId);
  r = await fake('POST', `/_control/pay/${noC}?notify=1&total=1`);
  check('mismatching notification still answered 204', r.json?.notification?.status === 204, r.json?.notification);
  check('order stays pending on mismatch', orderStatus(c.orderId) === 'PENDING_PAYMENT');
  check('payment held as MISMATCH', psql(`SELECT status FROM trade_payment WHERE out_trade_no='${noC}'`) === 'MISMATCH');
  check('notification recorded as rejected', psql(`SELECT outcome FROM trade_channel_notification WHERE out_trade_no='${noC}'`) === 'REJECTED');

  // 6. 取消：发起过支付但没付
  const d = await newOrder(customer);
  await launch(customer, d.orderId);
  const noD = outTradeNo(d.orderId);
  r = await call('POST', `/api/v1/trade/orders/${d.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"0"' } });
  check('cancel closes an unpaid order with a payment', r.status === 200 && r.json.status === 'CLOSED', r.json);
  check('wechat order closed too', (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === noD)?.state === 'CLOSED');
  check('payment closed', psql(`SELECT status FROM trade_payment WHERE out_trade_no='${noD}'`) === 'CLOSED');

  // 7. 取消：其实已经付了（回调还没到）
  const e = await newOrder(customer);
  await launch(customer, e.orderId);
  await fake('POST', `/_control/pay/${outTradeNo(e.orderId)}?notify=0`);
  r = await call('POST', `/api/v1/trade/orders/${e.orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"0"' } });
  check('cancel of a secretly paid order is refused', r.status === 409 && r.json.code === 'TRADE_ORDER_STATE_INVALID', r.json);
  check('and the order is paid', orderStatus(e.orderId) === 'PAID');

  // 8. 过期：没发起支付的直接拒绝；发起过的由定时任务查单后关
  const f = await newOrder(customer);
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id='${f.orderId}'`);
  r = await launch(customer, f.orderId);
  check('expired order cannot be paid', r.status === 409 && ['TRADE_ORDER_EXPIRED', 'TRADE_ORDER_STATE_INVALID'].includes(r.json.code), r.json);

  const g = await newOrder(customer);
  await launch(customer, g.orderId);
  const noG = outTradeNo(g.orderId);
  const h = await newOrder(customer);
  await launch(customer, h.orderId);
  const noH = outTradeNo(h.orderId);
  await fake('POST', `/_control/pay/${noH}?notify=0`);
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id IN ('${g.orderId}', '${h.orderId}')`);
  const settled = await waitFor(() => orderStatus(g.orderId) === 'CLOSED' && orderStatus(h.orderId) === 'PAID', 150);
  check('timeout job closes the unpaid one after closing it at wechat', settled && (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === noG)?.state === 'CLOSED', [orderStatus(g.orderId), orderStatus(h.orderId)]);
  check('timeout job finds the paid one instead of closing it', orderStatus(h.orderId) === 'PAID' && transitions(h.orderId) === 'SUBMIT,PAYMENT_QUERIED', transitions(h.orderId));
  check('timeout recorded', transitions(g.orderId) === 'SUBMIT,TIMEOUT', transitions(g.orderId));

  // 9. 轮询：付了、回调丢了、App 也没回来
  const i = await newOrder(customer);
  await launch(customer, i.orderId);
  await fake('POST', `/_control/pay/${outTradeNo(i.orderId)}?notify=0`);
  psql(`UPDATE trade_payment SET created_at = now() - interval '2 minutes' WHERE order_id='${i.orderId}'`);
  const polled = await waitFor(() => orderStatus(i.orderId) === 'PAID', 150);
  check('poll job finds a payment whose notification never came', polled && transitions(i.orderId) === 'SUBMIT,PAYMENT_QUERIED', transitions(i.orderId));

}
const done = finish(cleanup);
main().then(() => done(), done);
