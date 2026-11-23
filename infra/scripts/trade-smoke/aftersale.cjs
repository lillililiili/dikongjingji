#!/usr/bin/env node
'use strict';

// S4-2 商品售后、S4-4 订单交易字段、S5-2 商家主页与资料审核（docs/redesign/09-商品售后与商家主页.md）。
// 订单的交易主体快照和渠道交易号；仅退款（未发货）即退；退货退款：凭证上传、商家同意（默认寄回地址）、填和改运单、
// 确认收货少退要写说明、退款回调推进；拒绝后重新申请；超时自动同意、逾期关闭、寄回后超时自动退款；
// 商家主页和“在售”；工作台提交资料、运营端审核；运营端标“不支持七天无理由”；收费报名订单的参与人姓名。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 运营端的几步要 dev-operator：后端启动时设了 DEV_OPERATOR_PASSWORD，这里用 OPERATOR_PASSWORD 传同一个值；不传就跳过这几步。
// 超时的几步把 due_at 改到过去，等后端每分钟一次的售后定时任务（最多等 150 秒）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉 dev-customer 的订单和售后、脚本建的报名和参与人、资料提交（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> \
//     [OPERATOR_PASSWORD=…] node infra/scripts/trade-smoke/aftersale.cjs

const { BASE, BUYER, offerId, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

const MERCHANT = 'c1000000-0000-4000-8000-000000000001'; // 示例机器人商家（dev-merchant）
const KIT = offerId('101', '1'); // 编程机器人探索套装，快递、自提都行，129900
const ARM = offerId('102', '1'); // 桌面六轴机械臂（种子标了不支持七天无理由）
const KIT_PRODUCT = 'c2000000-0000-4000-8000-000000000101';
const ARM_PRODUCT = 'c2000000-0000-4000-8000-000000000102';
const PRICE = 129900;
const ACTIVITY = 'a2000000-0000-4000-8000-000000000002';
const SESSION = 'a3000000-0000-4000-8000-000000000201';
const UNIT = 16800;
const CHILD_NAMES = ['售后冒烟甲', '售后冒烟乙'];
// 最小的 1×1 PNG。
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

const enrollments = new Set();
let customer;
let merchant;
let addressId;

const orderStatus = (id) => psql(`SELECT status FROM trade_order WHERE id='${id}'`);
const aftersaleStatus = (id) => psql(`SELECT status FROM trade_aftersale WHERE id='${id}'`);
const refundOf = (aftersaleId) => psql(`SELECT r.out_refund_no || '|' || r.initiator || '|' || r.amount_minor FROM trade_refund r JOIN trade_aftersale a ON a.refund_id = r.id WHERE a.id='${aftersaleId}'`).split('|');
const settleRefund = (no) => fake('POST', `/_control/refund/${no}?status=SUCCESS&notify=1`);
const etag = (version) => `"${version}"`;

function cleanup() {
  const orders = `SELECT id FROM trade_order WHERE buyer_id='${BUYER}'`;
  psql(`
    BEGIN;
    DELETE FROM trade_aftersale_event WHERE aftersale_id IN (SELECT id FROM trade_aftersale WHERE buyer_id='${BUYER}');
    DELETE FROM trade_aftersale_image WHERE uploaded_by='${BUYER}';
    DELETE FROM trade_aftersale WHERE buyer_id='${BUYER}';
    COMMIT;
  `);
  // 这次建的报名，加上以前（比如 --keep）用同样两个参与人建的。
  const names = CHILD_NAMES.map((n) => `'${n}'`).join(',');
  psql(`SELECT DISTINCT t.enrollment_id FROM activity_enrollment_ticket t JOIN activity_participant p ON p.id = t.participant_id
        WHERE p.owner_id = '${BUYER}' AND p.name IN (${names})`).split('\n').filter(Boolean).forEach((id) => enrollments.add(id));
  const ids = [...enrollments].map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      UPDATE activity_session s SET enrolled_count = s.enrolled_count - t.n
        FROM (SELECT session_id, count(*) AS n FROM activity_enrollment_ticket
              WHERE enrollment_id IN (${ids}) AND status <> 'CANCELLED' GROUP BY session_id) t
       WHERE s.id = t.session_id;
      DELETE FROM activity_enrollment_event WHERE enrollment_id IN (${ids});
      DELETE FROM activity_enrollment_ticket WHERE enrollment_id IN (${ids});
      DELETE FROM activity_enrollment WHERE id IN (${ids});
      COMMIT;
    `);
  }
  psql(`DELETE FROM activity_participant WHERE owner_id='${BUYER}' AND name IN (${CHILD_NAMES.map((n) => `'${n}'`).join(',')})`);
  cleanupBuyer();
  // 运营端的几步改过的东西还原成开发种子的样子。
  psql(`
    BEGIN;
    DELETE FROM mall_merchant_profile_submission WHERE organization_id='${MERCHANT}' AND id <> 'c6000000-0000-4000-8000-000000000001';
    UPDATE mall_merchant_profile p SET business_entity = s.business_entity, intro = s.intro,
           sale_scope_note = s.sale_scope_note, authorization_until = s.authorization_until, category_note = s.category_note,
           invoice_issuer = s.invoice_issuer, aftersale_party = s.aftersale_party, submission_id = s.id
      FROM mall_merchant_profile_submission s WHERE s.id = 'c6000000-0000-4000-8000-000000000001' AND p.organization_id = s.organization_id;
    UPDATE mall_offer SET invoice_issuer = NULL WHERE id = '${KIT}';
    UPDATE mall_product SET no_reason_return = true WHERE id = '${KIT_PRODUCT}';
    UPDATE mall_product SET no_reason_return = false WHERE id = '${ARM_PRODUCT}';
    COMMIT;
  `);
  void orders;
}

async function paidOrder(offer, mode = 'EXPRESS', price = PRICE) {
  const body = { cityCode: '370500', offerId: offer, quantity: 1, deliveryMode: mode, expectedPayableMinor: price, agreementAccepted: true, ...(mode === 'EXPRESS' ? { addressId } : {}) };
  const submitted = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body });
  if (submitted.status !== 201) throw new Error('submit ' + JSON.stringify(submitted.json));
  const id = submitted.json.orderId;
  const launched = await call('POST', `/api/v1/trade/orders/${id}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  if (launched.status !== 200) throw new Error('launch ' + JSON.stringify(launched.json));
  const no = psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${id}'`);
  const paid = await fake('POST', `/_control/pay/${no}?notify=1`);
  if (paid.json?.notification?.status !== 204 || orderStatus(id) !== 'PAID') throw new Error('pay ' + JSON.stringify(paid.json));
  return (await call('GET', `/api/v1/trade/orders/${id}`, { token: customer })).json;
}

async function ship(order) {
  const r = await call('POST', `/api/v1/provider/trade/orders/${order.orderId}/shipment`, {
    token: merchant, headers: { 'Idempotency-Key': key(), 'If-Match': etag(order.version) },
    body: { carrierName: '示例快递', trackingNo: 'SF' + Date.now() } });
  if (r.status !== 200) throw new Error('ship ' + JSON.stringify(r.json));
  return r.json;
}

const apply = (orderId, body, idempotencyKey = key()) => call('POST', `/api/v1/trade/orders/${orderId}/aftersales`, { token: customer, headers: { 'Idempotency-Key': idempotencyKey }, body });
const buyerRead = (id) => call('GET', `/api/v1/trade/aftersales/${id}`, { token: customer });
const merchantRead = (id) => call('GET', `/api/v1/provider/trade/aftersales/${id}`, { token: merchant });
const merchantDo = (id, action, version, body = {}) => call('POST', `/api/v1/provider/trade/aftersales/${id}/${action}`, { token: merchant, headers: { 'If-Match': etag(version) }, body });

async function upload(slot) {
  const form = new FormData();
  form.append('slot', slot);
  form.append('file', new Blob([PNG], { type: 'image/png' }), 'evidence.png');
  const response = await fetch(`${BASE}/api/v1/trade/aftersale-images`, { method: 'POST', headers: { Authorization: `Bearer ${customer}` }, body: form });
  return { status: response.status, json: await response.json().catch(() => null) };
}

/** 把售后单的处理时限挪到过去，等定时任务推进到 status。 */
async function expire(id, status) {
  psql(`UPDATE trade_aftersale SET due_at = now() - interval '1 minute' WHERE id='${id}'`);
  return waitFor(() => aftersaleStatus(id) === status, 150);
}

async function main() {
  cleanup();
  customer = await login('dev-customer');
  merchant = await login('dev-merchant');
  const provider = await login('dev-provider');
  addressId = (await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '东营区 示例路 88 号', isDefault: true } })).json.addressId;

  // ---- 订单的交易字段（S4-4） ----
  const a = await paidOrder(KIT);
  check('order carries the trade parties snapshot', a.parties?.fulfilledFrom === '示例机器人商家 东营仓' && a.parties.invoiceIssuer === '示例机器人商家' && a.parties.aftersaleParty === '示例机器人商家 · 厂家保修', a.parties);
  check('paid order shows the channel transaction id', typeof a.channelTransactionId === 'string' && a.channelTransactionId.length > 0, a.channelTransactionId);
  check('a paid mall order can take an aftersale', a.aftersale?.applicable === true && a.aftersale.latestAftersaleId === null, a.aftersale);
  check('mall orders have no participant names', a.participantNames === null);

  check('aftersales need login', (await call('GET', '/api/v1/trade/aftersales')).status === 401);
  check('merchant cannot use buyer aftersale routes', (await call('GET', '/api/v1/trade/aftersales', { token: merchant })).status === 403);
  check('customer cannot use merchant aftersale routes', (await call('GET', '/api/v1/provider/trade/aftersales', { token: customer })).status === 403);

  // ---- 仅退款：未发货，申请即全额原路退款 ----
  let r = await call('GET', `/api/v1/trade/orders/${a.orderId}/aftersale-eligibility`, { token: customer });
  const kind = (k) => r.json.kinds.find((x) => x.kind === k);
  check('unshipped order offers refund only', r.status === 200 && kind('REFUND_ONLY').available && kind('RETURN_REFUND').unavailableReason === 'NOT_SHIPPED' && r.json.maxRefundMinor === PRICE, r.json);
  const b = await paidOrder(KIT, 'PICKUP');
  const refundOnlyKey = key();
  r = await apply(b.orderId, { kind: 'REFUND_ONLY', reason: 'NOT_WANTED' }, refundOnlyKey);
  const onlyId = r.json?.aftersaleId;
  check('refund only goes straight to refunding', r.status === 201 && r.json.status === 'REFUNDING' && r.json.refund?.amountMinor === PRICE && /^AS\d{16}$/.test(r.json.aftersaleNo), r.json);
  r = await apply(b.orderId, { kind: 'REFUND_ONLY', reason: 'NOT_WANTED' }, refundOnlyKey);
  check('replaying the key returns the same aftersale', r.status === 200 && r.json.aftersaleId === onlyId);
  r = await apply(b.orderId, { kind: 'REFUND_ONLY', reason: 'NOT_WANTED', description: '换了' }, refundOnlyKey);
  check('the same key with other content is refused', r.status === 409 && r.json.code === 'TRADE_IDEMPOTENCY_KEY_REUSED', r.json);
  check('refund only uses the buyer-cancel rule', refundOf(onlyId)[1] === 'BUYER_CANCEL' && orderStatus(b.orderId) === 'REFUNDING');
  await settleRefund(refundOf(onlyId)[0]);
  check('refund success finishes the aftersale and the order', await waitFor(() => aftersaleStatus(onlyId) === 'REFUNDED', 15) && orderStatus(b.orderId) === 'REFUNDED');
  r = await buyerRead(onlyId);
  check('refunded aftersale shows the refund and the timeline', r.json.refund?.status === 'SUCCEEDED' && r.json.refundedAt && r.json.timeline.map((e) => e.action).join(',') === 'SUBMIT,REFUND_SUCCEEDED', r.json);

  // ---- 退货退款：凭证、同意、寄回、少退 ----
  const shipped = await ship(a);
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}/aftersale-eligibility`, { token: customer });
  check('shipped order offers returns only', kind('REFUND_ONLY').unavailableReason === 'ALREADY_SHIPPED' && r.json.reasons.find((x) => x.reason === 'NO_REASON_7D').available, r.json);
  r = await apply(a.orderId, { kind: 'RETURN_REFUND', reason: 'NOT_WANTED' });
  check('a reason of the other kind is a bad request', r.status === 400, r.json);
  const product = await upload('PRODUCT');
  const pkg = await upload('PACKAGE');
  check('an evidence picture uploads', product.status === 201 && product.json.contentType === 'image/png' && product.json.sizeBytes === PNG.length, product.json);
  const svg = new FormData();
  svg.append('slot', 'OTHER');
  svg.append('file', new Blob(['<svg/>'], { type: 'image/svg+xml' }), 'x.svg');
  const svgUpload = await fetch(`${BASE}/api/v1/trade/aftersale-images`, { method: 'POST', headers: { Authorization: `Bearer ${customer}` }, body: svg });
  check('a non-picture is refused', svgUpload.status === 422);
  r = await call('GET', `/api/v1/trade/aftersale-images/${product.json.imageId}`, { token: customer });
  check('the buyer reads their own picture back', r.status === 200 && r.type === 'image/png');
  r = await apply(a.orderId, { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D', requestedMinor: PRICE + 1 });
  check('asking more than paid is refused', r.status === 409 && r.json.code === 'TRADE_AFTERSALE_AMOUNT_EXCEEDS', r.json);
  r = await apply(a.orderId, { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D', description: '未拆封，不想要了', imageIds: [product.json.imageId, pkg.json.imageId] });
  const returnId = r.json?.aftersaleId;
  check('a return waits for the merchant for 48 hours', r.status === 201 && r.json.status === 'PENDING_REVIEW' && r.json.images.length === 2 && r.json.actions.canRevoke && Math.abs(new Date(r.json.dueAt) - Date.now() - 48 * 3600_000) < 120_000, r.json);
  r = await apply(a.orderId, { kind: 'RETURN_REFUND', reason: 'DAMAGED' });
  check('one open aftersale per order', r.status === 409 && r.json.code === 'TRADE_AFTERSALE_ALREADY_OPEN', r.json);
  r = await apply(a.orderId, { kind: 'RETURN_REFUND', reason: 'DAMAGED', imageIds: [product.json.imageId] });
  check('a picture already used cannot be reused', [409, 422].includes(r.status), r.json);
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
  check('the order points at its aftersale', r.json.aftersale.latestAftersaleId === returnId && r.json.aftersale.latestStatus === 'PENDING_REVIEW' && r.json.aftersale.applicable === false, r.json.aftersale);

  check('another organization cannot see it', (await call('GET', `/api/v1/provider/trade/aftersales/${returnId}`, { token: provider })).status === 404);
  r = await call('GET', '/api/v1/provider/trade/aftersales?status=PENDING_REVIEW', { token: merchant });
  check('the merchant lists it as pending', r.status === 200 && r.json.items.some((x) => x.aftersaleId === returnId) && r.json.counts.doing >= 1, r.json);
  r = await call('GET', `/api/v1/provider/trade/aftersales/${returnId}/images/${pkg.json.imageId}`, { token: merchant });
  check('the merchant reads the evidence', r.status === 200 && r.type === 'image/png');
  let detail = (await merchantRead(returnId)).json;
  check('merchant actions on a pending return', detail.actions.canReview && !detail.actions.canRevoke && !detail.actions.canConfirmReceipt, detail.actions);
  check('a stale version is refused', (await merchantDo(returnId, 'approval', detail.version + 5)).status === 412);
  r = await merchantDo(returnId, 'approval', detail.version, {});
  check('approval uses the default return address', r.status === 200 && r.json.status === 'AWAITING_RETURN' && r.json.returnAddress?.address.includes('示例路 120 号'), r.json);
  detail = (await buyerRead(returnId)).json;
  check('buyer can ship back or revoke', detail.actions.canSubmitReturn && detail.actions.canRevoke && detail.returnAddress?.contactName === '示例机器人商家 售后组', detail.actions);
  r = await call('POST', `/api/v1/trade/aftersales/${returnId}/return-shipment`, { token: customer, headers: { 'If-Match': etag(detail.version) }, body: { carrierName: '示例快递', trackingNo: '2026110600012' } });
  check('submitting the waybill waits for the merchant', r.status === 200 && r.json.status === 'RETURN_SHIPPED' && r.json.returnWaybill?.trackingNo === '2026110600012' && r.json.actions.canEditReturn, r.json);
  r = await call('POST', `/api/v1/trade/aftersales/${returnId}/return-shipment`, { token: customer, headers: { 'If-Match': etag(r.json.version) }, body: { carrierName: '其他承运商', trackingNo: 'YT20261106' } });
  check('the waybill can be corrected before receipt', r.status === 200 && r.json.status === 'RETURN_SHIPPED' && r.json.returnWaybill.carrierName === '其他承运商', r.json);
  check('the buyer cannot revoke once shipped back', (await call('POST', `/api/v1/trade/aftersales/${returnId}/revoke`, { token: customer, headers: { 'If-Match': etag(r.json.version) } })).status === 409);
  detail = (await merchantRead(returnId)).json;
  r = await merchantDo(returnId, 'receipt', detail.version, { refundMinor: 100000 });
  check('refunding less needs a note', r.status === 400, r.json);
  r = await merchantDo(returnId, 'receipt', detail.version, { refundMinor: PRICE + 1, note: '多退' });
  check('refunding more than asked is refused', r.status === 409 && r.json.code === 'TRADE_AFTERSALE_AMOUNT_EXCEEDS', r.json);
  r = await merchantDo(returnId, 'receipt', detail.version, { refundMinor: 100000, note: '少了充电线' });
  check('receipt starts the refund', r.status === 200 && r.json.status === 'REFUNDING' && r.json.refundMinor === 100000 && r.json.merchantNote === '少了充电线', r.json);
  const [returnRefundNo, returnInitiator, returnAmount] = refundOf(returnId);
  check('the refund is a merchant refund of the agreed amount', returnInitiator === 'MERCHANT' && returnAmount === '100000');
  check('a partial refund keeps the order shipped', orderStatus(a.orderId) === 'SHIPPED');
  await settleRefund(returnRefundNo);
  check('the refund callback finishes the return', await waitFor(() => aftersaleStatus(returnId) === 'REFUNDED', 15));
  detail = (await buyerRead(returnId)).json;
  check('the refunded return shows its timeline and the transaction id',
    detail.timeline.map((e) => e.action).join(',') === 'SUBMIT,AGREE,SHIP_RETURN,EDIT_RETURN,CONFIRM_RECEIPT,REFUND_SUCCEEDED'
      && detail.refund.channelTransactionId === a.channelTransactionId, detail.timeline);
  r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
  check('the order shows the refund', r.json.amounts.refundedMinor === 100000 && r.json.refunds.some((x) => x.reason.startsWith('售后退货退款')), r.json.amounts);

  // ---- 拒绝后重新申请；七天无理由不支持的商品 ----
  const c = await ship(await paidOrder(ARM, 'EXPRESS', 980000));
  r = await call('GET', `/api/v1/trade/orders/${c.orderId}/aftersale-eligibility`, { token: customer });
  check('a product marked without 7-day returns blocks that reason only', r.json.reasons.find((x) => x.reason === 'NO_REASON_7D').unavailableReason === 'NO_REASON_NOT_SUPPORTED' && r.json.reasons.find((x) => x.reason === 'DAMAGED').available, r.json.reasons);
  r = await apply(c.orderId, { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D' });
  check('applying the blocked reason says why', r.status === 409 && r.json.code === 'TRADE_AFTERSALE_NOT_ELIGIBLE' && r.json.details?.reason === 'NO_REASON_NOT_SUPPORTED', r.json);
  r = await apply(c.orderId, { kind: 'RETURN_REFUND', reason: 'DAMAGED', description: '外壳裂了' });
  const rejectedId = r.json.aftersaleId;
  r = await merchantDo(rejectedId, 'rejection', r.json.version, {});
  check('rejecting needs a reason', r.status === 400);
  r = await merchantDo(rejectedId, 'rejection', (await merchantRead(rejectedId)).json.version, { reason: '凭证不足，无法确认商品损坏', note: '请补充商品和外包装照片后重新申请' });
  check('the merchant rejects with a reason', r.status === 200 && r.json.status === 'REJECTED' && r.json.closedAt, r.json);
  detail = (await buyerRead(rejectedId)).json;
  check('the buyer sees why and may apply again', detail.rejectReason === '凭证不足，无法确认商品损坏' && detail.actions.canReapply, detail);
  r = await call('GET', '/api/v1/trade/aftersales?tab=CLOSED', { token: customer });
  check('the closed tab lists it', r.json.items.some((x) => x.aftersaleId === rejectedId) && r.json.counts.closed >= 1 && r.json.counts.done >= 2, r.json.counts);

  // ---- 超时：48 小时自动同意、7 天不寄回关闭、寄回后 10 天自动退款 ----
  r = await apply(c.orderId, { kind: 'RETURN_REFUND', reason: 'DAMAGED', description: '补了照片' });
  const autoId = r.json.aftersaleId;
  check('a rejected order can apply again', r.status === 201 && r.json.status === 'PENDING_REVIEW', r.json);
  check('an unanswered application is agreed automatically', await expire(autoId, 'AWAITING_RETURN'));
  detail = (await buyerRead(autoId)).json;
  check('the automatic agreement uses the default address', detail.returnAddress?.address.includes('示例路 120 号') && detail.timeline.some((e) => e.action === 'AUTO_AGREE'), detail);
  check('an unreturned agreement closes', await expire(autoId, 'CLOSED'));
  r = await apply(c.orderId, { kind: 'RETURN_REFUND', reason: 'DAMAGED' });
  const thirdId = r.json.aftersaleId;
  check('three aftersales in all, then no more', r.status === 201, r.json);
  await merchantDo(thirdId, 'approval', (await merchantRead(thirdId)).json.version, { contactName: '售后组', phone: '0546-1111111', address: '东营区 示例路 66 号', saveAsDefault: false });
  detail = (await buyerRead(thirdId)).json;
  await call('POST', `/api/v1/trade/aftersales/${thirdId}/return-shipment`, { token: customer, headers: { 'If-Match': etag(detail.version) }, body: { carrierName: '示例快递', trackingNo: 'AUTO20261106' } });
  check('a returned parcel the merchant ignores is refunded automatically', await expire(thirdId, 'REFUNDING'));
  const [autoRefundNo, autoInitiator, autoAmount] = refundOf(thirdId);
  check('the automatic refund has no operator and the asked amount', autoInitiator === 'SOURCE' && autoAmount === '980000');
  await settleRefund(autoRefundNo);
  check('and finishes on the callback', await waitFor(() => aftersaleStatus(thirdId) === 'REFUNDED', 15) && orderStatus(c.orderId) === 'REFUNDED');
  r = await call('GET', `/api/v1/trade/orders/${c.orderId}/aftersale-eligibility`, { token: customer });
  check('a fully refunded order takes no more aftersales', r.json.reasons.every((x) => !x.available), r.json.reasons);
  r = await call('GET', '/api/v1/provider/trade/return-address', { token: merchant });
  check('a one-off address did not replace the default', r.status === 200 && r.json.address.includes('示例路 120 号'), r.json);

  // ---- 商家主页（S5-2） ----
  r = await call('GET', `/api/v1/mall/merchants/${MERCHANT}?cityCode=370500`);
  check('the merchant page is public with the approved profile', r.status === 200 && r.json.name === '示例机器人商家' && r.json.kind === 'FACTORY_DIRECT' && r.json.profile?.businessEntity.startsWith('示例机器人科技') && r.json.profile.authorizationUntil === '2027-10-31' && r.json.productCount >= 2, r.json);
  const page = r.json;
  r = await call('GET', `/api/v1/mall/products?cityCode=370500&merchantId=${MERCHANT}&size=50`);
  check('the 在售 tab lists only this merchant’s products', r.status === 200 && r.json.total === page.productCount && r.json.items.every((x) => x.sellerCount === 1), r.json);
  check('an unknown merchant is not found', (await call('GET', '/api/v1/mall/merchants/00000000-0000-4000-8000-000000000000?cityCode=370500')).status === 404);
  check('the merchant page needs a city', (await call('GET', `/api/v1/mall/merchants/${MERCHANT}`)).status === 400);
  r = await call('GET', `/api/v1/mall/products/${KIT_PRODUCT}?cityCode=370500`);
  const maker = r.json.offers.find((o) => o.merchant.organizationId === MERCHANT);
  const dealer = r.json.offers.find((o) => o.merchant.organizationId === 'c1000000-0000-4000-8000-000000000002');
  check('the product page carries invoice issuer and aftersale party', maker.invoiceIssuer === '示例机器人商家' && maker.aftersaleParty === '示例机器人商家 · 厂家保修' && dealer.aftersaleParty === '示例机器人商家 · 厂家保修' && dealer.invoiceIssuer === '示例教育装备代理' && r.json.noReasonReturn === true, r.json.offers);
  check('the arm says no 7-day returns', (await call('GET', `/api/v1/mall/products/${ARM_PRODUCT}?cityCode=370500`)).json.noReasonReturn === false);

  // ---- 工作台商家资料 ----
  r = await call('GET', '/api/v1/provider/mall/merchant-profile', { token: merchant });
  check('the workbench shows the published profile', r.status === 200 && r.json.published?.businessEntity.startsWith('示例机器人科技') && r.json.latest?.status === 'APPROVED', r.json);
  const draft = { businessEntity: '示例机器人科技有限公司（冒烟）', intro: '冒烟测试的简介', saleScopeNote: '全国发货', authorizationUntil: '2028-01-31', categoryNote: '教育机器人', invoiceIssuer: '示例机器人商家', aftersaleParty: '示例机器人商家 · 厂家保修' };
  r = await call('PUT', '/api/v1/provider/mall/merchant-profile', { token: merchant, body: { ...draft, businessEntity: ' ' } });
  check('the business entity is required', r.status === 400);
  r = await call('PUT', '/api/v1/provider/mall/merchant-profile', { token: merchant, body: draft });
  const first = r.json.latest;
  check('submitting waits for review and keeps the published one', r.status === 200 && first.status === 'SUBMITTED' && r.json.published.businessEntity.startsWith('示例机器人科技有限公司（示例）'), r.json);
  r = await call('PUT', '/api/v1/provider/mall/merchant-profile', { token: merchant, body: { ...draft, intro: '第二版' } });
  const second = r.json.latest;
  check('a new submission supersedes the pending one', psql(`SELECT status FROM mall_merchant_profile_submission WHERE id='${first.submissionId}'`) === 'SUPERSEDED' && second.status === 'SUBMITTED');
  check('a customer cannot read the workbench profile', (await call('GET', '/api/v1/provider/mall/merchant-profile', { token: customer })).status === 403);

  // ---- 运营端：审核、交易条款（要 dev-operator） ----
  if (process.env.OPERATOR_PASSWORD) {
    const operator = await login('dev-operator', process.env.OPERATOR_PASSWORD);
    check('ops review needs the platform operator', (await call('GET', '/api/v1/ops/mall/merchant-profiles', { token: merchant })).status === 403);
    r = await call('GET', '/api/v1/ops/mall/merchant-profiles?status=SUBMITTED', { token: operator });
    check('pending submissions are listed', r.status === 200 && r.json.items.some((x) => x.submissionId === second.submissionId), r.json);
    r = await call('POST', `/api/v1/ops/mall/merchant-profiles/${first.submissionId}/approval`, { token: operator, body: { version: first.version } });
    check('a superseded version cannot be approved', r.status === 409 && r.json.code === 'OPS_MERCHANT_PROFILE_REVIEW_CLOSED', r.json);
    r = await call('POST', `/api/v1/ops/mall/merchant-profiles/${second.submissionId}/rejection`, { token: operator, body: { version: second.version } });
    check('rejecting needs a reason', r.status === 400);
    r = await call('POST', `/api/v1/ops/mall/merchant-profiles/${second.submissionId}/approval`, { token: operator, body: { version: second.version + 1 } });
    check('a stale version is refused', r.status === 409 && r.json.code === 'OPS_VERSION_CONFLICT', r.json);
    r = await call('POST', `/api/v1/ops/mall/merchant-profiles/${second.submissionId}/approval`, { token: operator, body: { version: second.version } });
    check('approval publishes the submission', r.status === 200 && r.json.submission.status === 'APPROVED' && r.json.published.intro === '第二版' && r.json.history.length >= 3, r.json);
    check('the merchant page shows it', (await call('GET', `/api/v1/mall/merchants/${MERCHANT}?cityCode=370500`)).json.profile.intro === '第二版');

    r = await call('GET', `/api/v1/ops/catalog/products/${KIT_PRODUCT}/trade-terms`, { token: operator });
    const dealerTerms = r.json.offers.find((o) => o.merchantId === 'c1000000-0000-4000-8000-000000000002');
    check('ops sees offer terms and what takes effect', r.status === 200 && r.json.noReasonReturn === true && dealerTerms.aftersaleParty === '示例机器人商家 · 厂家保修' && dealerTerms.invoiceIssuer === null && dealerTerms.effectiveInvoiceIssuer === '示例教育装备代理', r.json);
    r = await call('PUT', `/api/v1/ops/catalog/products/${KIT_PRODUCT}/trade-terms`, { token: operator, body: { noReasonReturn: false } });
    check('ops marks the kit without 7-day returns', r.status === 200 && r.json.noReasonReturn === false);
    check('the product page follows', (await call('GET', `/api/v1/mall/products/${KIT_PRODUCT}?cityCode=370500`)).json.noReasonReturn === false);
    r = await call('PUT', `/api/v1/ops/catalog/products/${KIT_PRODUCT}/offers/${KIT}/trade-terms`, { token: operator, body: { invoiceIssuer: '示例机器人科技有限公司', aftersaleParty: '' } });
    check('ops sets an offer invoice issuer', r.status === 200 && r.json.offers.find((o) => o.offerId === KIT).effectiveInvoiceIssuer === '示例机器人科技有限公司', r.json);
    const d = await paidOrder(KIT);
    check('new orders snapshot the new terms', d.parties.invoiceIssuer === '示例机器人科技有限公司', d.parties);
    r = await call('GET', `/api/v1/trade/orders/${a.orderId}`, { token: customer });
    check('old orders keep their snapshot', r.json.parties.invoiceIssuer === '示例机器人商家');
    await call('PUT', `/api/v1/ops/catalog/products/${KIT_PRODUCT}/offers/${KIT}/trade-terms`, { token: operator, body: {} });
    r = await call('GET', `/api/v1/ops/catalog/products/${KIT_PRODUCT}`, { token: operator });
    check('the change is in the product operation log', JSON.stringify(r.json).includes('不支持七天无理由'), r.status);
  } else {
    console.log('skip ops steps: OPERATOR_PASSWORD is not set');
  }

  // ---- 收费报名订单的参与人姓名 ----
  for (const name of CHILD_NAMES) {
    await call('POST', '/api/v1/activity-participants', { token: customer, body: { name, birthMonth: '2016-05', guardianConfirmed: true } });
  }
  const people = (await call('GET', '/api/v1/activity-participants', { token: customer })).json;
  const list = people?.items ?? people;
  const ids = CHILD_NAMES.map((name) => list.find((p) => p.name === name)?.participantId);
  r = await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: ACTIVITY, sessionId: SESSION, participantIds: ids, noticeAgreed: true, expectedAmountMinor: UNIT * 2 } });
  if (r.json?.enrollmentId) enrollments.add(r.json.enrollmentId);
  const enrolOrder = r.json?.payment?.tradeOrderId;
  check('a paid enrolment opens a trade order', r.status === 201 && enrolOrder, r.json);
  r = await call('GET', `/api/v1/trade/orders/${enrolOrder}`, { token: customer });
  // 报名接口按自己的顺序整理参与人（重放判断要用），所以只比姓名集合。
  const shownNames = (r.json?.participantNames ?? '').split('、').sort().join('、');
  check('the enrolment order shows the participants', shownNames === [...CHILD_NAMES].sort().join('、') && r.json.parties.fulfilledFrom === null && r.json.aftersale.applicable === false,
    { participantNames: r.json?.participantNames, parties: r.json?.parties, aftersale: r.json?.aftersale });
}

main().then(() => finish(cleanup)(), finish(cleanup));
