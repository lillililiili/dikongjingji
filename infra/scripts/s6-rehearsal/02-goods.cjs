#!/usr/bin/env node
'use strict';

// S6 场景 2 实物交易：下单 → 模拟微信付款回调 → 商家发货 → 买家确认收货 → 售后（七天无理由退货）→ 商家同意 → 买家寄回 →
// 商家确认收货 → 原路退款（模拟微信退款回调）；每一步核对订单、支付、退款、售后、站内信的状态是否一致。
// 商品：开发种子的编程机器人探索套装（示例机器人商家 dev-merchant，东营，129900，支持七天无理由）。
//
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/02-goods.cjs
// 跑完删掉本脚本建的订单、售后、地址、站内信和回调记录并查残留（--keep 保留）。

const { call, fake, psql, login, key, offerId, waitFor, step, note, track, purgeAndReport, upload, PNG, done } = require('./lib.cjs');

const KIT = offerId('101', '1');
const PRICE = 129900;
const etag = (v) => `"${v}"`;

const one = (sql) => psql(sql);
const state = (orderId) => one(`SELECT o.status || '|' || o.paid_minor || '|' || o.refunded_minor
  || '|' || coalesce((SELECT string_agg(p.status, ',') FROM trade_payment p WHERE p.order_id = o.id), '-')
  || '|' || coalesce((SELECT string_agg(r.status || ':' || r.amount_minor, ',') FROM trade_refund r WHERE r.order_id = o.id), '-')
  FROM trade_order o WHERE o.id = '${orderId}'`);
const notices = (orderId) => one(`SELECT coalesce(string_agg(event_code, ',' ORDER BY occurred_at), '-') FROM notification_message WHERE target_id = '${orderId}'`);

async function main() {
  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');

  const addr = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: '370500', detail: '东营区 演练路 3 号', isDefault: false } });
  track(addr.json?.addressId);

  // 1. 下单
  const submitKey = key();
  const body = { cityCode: '370500', offerId: KIT, quantity: 1, deliveryMode: 'EXPRESS', addressId: addr.json.addressId, expectedPayableMinor: PRICE, agreementAccepted: true };
  let r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': submitKey }, body });
  const orderId = track(r.json?.orderId);
  step('提交订单：待付款，金额 1299.00，商家是示例机器人商家', { req: 'POST /api/v1/trade/orders', expect: '201 PENDING_PAYMENT payable 129900' },
    r.status === 201 && r.json.status === 'PENDING_PAYMENT' && r.json.amounts.payableMinor === PRICE, [r.status, r.json?.status, r.json?.amounts]);
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': submitKey }, body });
  step('同一幂等键重放返回同一张单', { req: 'POST /api/v1/trade/orders（同键）', expect: '同一 orderId' }, r.json?.orderId === orderId, [r.status, r.json?.orderId]);

  // 2. 付款：模拟微信回调
  r = await call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  step('发起微信 App 支付，拿到调起参数', { req: 'POST /api/v1/trade/orders/{id}/payments', expect: '200 带 prepay 签名' }, r.status === 200 && JSON.stringify(r.json).includes('prepay'), r.status);
  const outTradeNo = track(one(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}'`));
  step('付款前订单、支付状态一致', { req: '查库', expect: 'PENDING_PAYMENT|0|0|PREPAID|-' }, state(orderId) === 'PENDING_PAYMENT|0|0|PREPAID|-', state(orderId));
  const paid = await fake('POST', `/_control/pay/${outTradeNo}?notify=1`);
  step('模拟微信付款成功并回调，后端验签入账', { req: `POST /_control/pay/${outTradeNo}?notify=1`, expect: '回调 204，订单 PAID' }, paid.json?.notification?.status === 204, paid.json?.notification);
  step('付款后订单、支付状态一致', { req: '查库', expect: `PAID|${PRICE}|0|SUCCEEDED|-` }, state(orderId) === `PAID|${PRICE}|0|SUCCEEDED|-`, state(orderId));
  r = await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer });
  step('买家看到已付款、渠道交易号', { req: 'GET /api/v1/trade/orders/{id}', expect: 'PAID，channelTransactionId 有值' }, r.json?.status === 'PAID' && r.json.channelTransactionId, [r.json?.status, r.json?.channelTransactionId]);

  // 3. 发货
  r = await call('GET', `/api/v1/provider/trade/orders/${orderId}`, { token: merchant });
  step('商家在工作台看到这张已付款的单', { req: 'GET /api/v1/provider/trade/orders/{id}', expect: '200 PAID' }, r.status === 200 && r.json.status === 'PAID', [r.status, r.json?.status]);
  r = await call('POST', `/api/v1/provider/trade/orders/${orderId}/shipment`, { token: merchant, headers: { 'Idempotency-Key': key(), 'If-Match': etag(r.json.version) }, body: { carrierName: '演练快递', trackingNo: `S6${Date.now()}` } });
  step('商家发货', { req: 'POST /api/v1/provider/trade/orders/{id}/shipment', expect: '200 SHIPPED' }, r.status === 200 && r.json.status === 'SHIPPED', [r.status, r.json?.status]);
  const shippedNotice = await waitFor(() => notices(orderId).includes('TRADE_ORDER_SHIPPED'), 15);
  step('买家收到“已发货”站内信，一条', { req: '查 notification_message', expect: 'TRADE_ORDER_SHIPPED 一条' }, shippedNotice && notices(orderId) === 'TRADE_ORDER_SHIPPED', notices(orderId));

  // 4. 确认收货
  r = await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer });
  r = await call('POST', `/api/v1/trade/orders/${orderId}/receipt`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': r.etag } });
  step('买家确认收货', { req: 'POST /api/v1/trade/orders/{id}/receipt', expect: '200 COMPLETED' }, r.status === 200 && r.json.status === 'COMPLETED', [r.status, r.json?.status]);
  step('确认收货后订单、支付状态一致', { req: '查库', expect: `COMPLETED|${PRICE}|0|SUCCEEDED|-` }, state(orderId) === `COMPLETED|${PRICE}|0|SUCCEEDED|-`, state(orderId));

  // 5. 售后：七天无理由退货退款
  r = await call('GET', `/api/v1/trade/orders/${orderId}/aftersale-eligibility`, { token: customer });
  const noReason = r.json?.reasons?.find((x) => x.reason === 'NO_REASON_7D');
  step('已收货的订单可以申请七天无理由退货', { req: 'GET /api/v1/trade/orders/{id}/aftersale-eligibility', expect: 'RETURN_REFUND 可用，NO_REASON_7D 可用，最多退 129900' },
    r.status === 200 && noReason?.available === true && r.json.maxRefundMinor === PRICE, r.json);
  const evidence = await upload('/api/v1/trade/aftersale-images', customer, PNG, 'image/png', 'evidence.png', { slot: 'PRODUCT' });
  track(evidence.json?.imageId);
  r = await call('POST', `/api/v1/trade/orders/${orderId}/aftersales`, { token: customer, headers: { 'Idempotency-Key': key() },
    body: { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D', description: 'S6 演练：未拆封，不想要了', imageIds: evidence.json?.imageId ? [evidence.json.imageId] : [] } });
  const aftersaleId = track(r.json?.aftersaleId);
  step('提交七天无理由退货申请，等商家处理', { req: 'POST /api/v1/trade/orders/{id}/aftersales', expect: '201 PENDING_REVIEW' }, r.status === 201 && r.json.status === 'PENDING_REVIEW', [r.status, r.json?.status, r.json?.code]);
  let detail = (await call('GET', `/api/v1/provider/trade/aftersales/${aftersaleId}`, { token: merchant })).json;
  r = await call('POST', `/api/v1/provider/trade/aftersales/${aftersaleId}/approval`, { token: merchant, headers: { 'If-Match': etag(detail.version) }, body: {} });
  step('商家同意，给出寄回地址', { req: 'POST /api/v1/provider/trade/aftersales/{id}/approval', expect: '200 AWAITING_RETURN' }, r.status === 200 && r.json.status === 'AWAITING_RETURN' && r.json.returnAddress, [r.status, r.json?.status]);
  detail = (await call('GET', `/api/v1/trade/aftersales/${aftersaleId}`, { token: customer })).json;
  r = await call('POST', `/api/v1/trade/aftersales/${aftersaleId}/return-shipment`, { token: customer, headers: { 'If-Match': etag(detail.version) }, body: { carrierName: '演练快递', trackingNo: `S6R${Date.now()}` } });
  step('买家填寄回运单', { req: 'POST /api/v1/trade/aftersales/{id}/return-shipment', expect: '200 RETURN_SHIPPED' }, r.status === 200 && r.json.status === 'RETURN_SHIPPED', [r.status, r.json?.status]);
  detail = (await call('GET', `/api/v1/provider/trade/aftersales/${aftersaleId}`, { token: merchant })).json;
  r = await call('POST', `/api/v1/provider/trade/aftersales/${aftersaleId}/receipt`, { token: merchant, headers: { 'If-Match': etag(detail.version) }, body: { refundMinor: PRICE } });
  step('商家确认收到退货，全额退款开始', { req: 'POST /api/v1/provider/trade/aftersales/{id}/receipt refundMinor=129900', expect: '200 REFUNDING' }, r.status === 200 && r.json.status === 'REFUNDING', [r.status, r.json?.status]);
  const outRefundNo = track(one(`SELECT r.out_refund_no FROM trade_refund r JOIN trade_aftersale a ON a.refund_id = r.id WHERE a.id='${aftersaleId}'`));
  step('退款中：订单、退款状态一致（退款已向微信申请，未到账）', { req: '查库', expect: `…|${PRICE}|0|SUCCEEDED|PROCESSING:${PRICE}` }, state(orderId).endsWith(`|${PRICE}|0|SUCCEEDED|PROCESSING:${PRICE}`), state(orderId));

  // 6. 原路退款到账（模拟微信退款回调）
  const refunded = await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  step('模拟微信退款成功并回调', { req: `POST /_control/refund/${outRefundNo}?status=SUCCESS&notify=1`, expect: '回调 204' }, refunded.json?.notification?.status === 204, refunded.json?.notification);
  const finished = await waitFor(() => one(`SELECT status FROM trade_aftersale WHERE id='${aftersaleId}'`) === 'REFUNDED', 20);
  step('售后单变为已退款', { req: '查库 trade_aftersale', expect: 'REFUNDED' }, finished, one(`SELECT status FROM trade_aftersale WHERE id='${aftersaleId}'`));
  step('退款后订单、支付、退款状态一致', { req: '查库', expect: `REFUNDED|${PRICE}|${PRICE}|SUCCEEDED|SUCCEEDED:${PRICE}` }, state(orderId) === `REFUNDED|${PRICE}|${PRICE}|SUCCEEDED|SUCCEEDED:${PRICE}`, state(orderId));
  const refundedNotice = await waitFor(() => notices(orderId).includes('TRADE_REFUND_SUCCEEDED'), 15);
  step('买家收到“退款已原路退回”站内信，发货和退款各一条', { req: '查 notification_message', expect: 'TRADE_ORDER_SHIPPED,TRADE_REFUND_SUCCEEDED' },
    refundedNotice && notices(orderId) === 'TRADE_ORDER_SHIPPED,TRADE_REFUND_SUCCEEDED', notices(orderId));
  r = await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer });
  step('买家订单详情：已退款、退款金额、渠道交易号一致', { req: 'GET /api/v1/trade/orders/{id}', expect: 'REFUNDED，refundedMinor 129900' },
    r.json?.status === 'REFUNDED' && r.json.amounts.refundedMinor === PRICE && r.json.refunds?.length === 1, [r.json?.status, r.json?.amounts, r.json?.refunds?.length]);
  r = await call('GET', `/api/v1/trade/aftersales/${aftersaleId}`, { token: customer });
  step('售后时间线完整', { req: 'GET /api/v1/trade/aftersales/{id}', expect: 'SUBMIT,AGREE,SHIP_RETURN,CONFIRM_RECEIPT,REFUND_SUCCEEDED' },
    r.json?.timeline?.map((e) => e.action).join(',') === 'SUBMIT,AGREE,SHIP_RETURN,CONFIRM_RECEIPT,REFUND_SUCCEEDED', r.json?.timeline?.map((e) => e.action));
  const transitions = one(`SELECT string_agg(coalesce(from_status, '-') || '>' || to_status, ',' ORDER BY occurred_at, created_at) FROM trade_state_transition WHERE order_id='${orderId}'`);
  step('交易状态流转留痕完整', { req: '查 trade_state_transition', expect: '待付款→已付款→已发货→已完成→退款中→已退款' }, transitions.includes('PENDING_PAYMENT>PAID') && transitions.includes('PAID>SHIPPED') && transitions.endsWith('>REFUNDED'), transitions);
  note(`订单 ${orderId}，商户订单号 ${outTradeNo}，退款单号 ${outRefundNo}`);
}

main().then(() => done(() => purgeAndReport())(), (error) => done(() => purgeAndReport())(error));
