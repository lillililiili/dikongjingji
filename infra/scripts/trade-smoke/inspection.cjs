#!/usr/bin/env node
'use strict';

// S4-1 巡检付款节点（docs/redesign/08-后续开发计划.md §5 D9、C3、B2）：确认方案 → 首付 30% → 渠道回调 → 重复回调不重复记账
// → 履约、退回整改、重新交付、验收 → 按次服务费 → 改期申请与服务商确认 → 开工后不能在线取消；
// 另外两张单：首付后、开工前取消，首付整笔原路退回；首付前取消不收费，取消后才到账的付款整笔自动退回。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）+ 对象存储，
// 后端要开 INSPECTION_PAYMENT_NODES_ENABLED=true（否则方案没有付款节点）。环境变量见 lib.cjs。
// 只在一次性本地环境运行：脚本会留下真实预约、方案和服务单（和 verify-visit-flow-smoke.cjs 一样），
// 跑完删掉 dev-customer 名下的交易订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/inspection.cjs

const crypto = require('node:crypto');
const { check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子：电力输电线路无人机巡检，服务商“云翼科技”（dev-provider），收款账户 1900000301。
const SERVICE_ID = '33333333-3333-3333-3333-333333333333';
const PROVIDER_ID = '22222222-2222-2222-2222-222222222222';
const SUB_MCHID = '1900000301';
// 24 基 × ¥699 = ¥16,776；首付 30% = ¥5,032.80；两次巡检，每次 ¥5,871.60。
const UNIT = 69_900;
const TOTAL = 24 * UNIT;
const DOWN = 503_280;
const PER_VISIT = (TOTAL - DOWN) / 2;
const ZIP = Buffer.from([0x50, 0x4b, 0x03, 0x04]);
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const ASSIGNMENTS = { assignments: [
  { type: 'TEAM_MEMBER', resourceId: '77777777-7777-4777-8777-777777777701', displayName: '东营巡检班组', roleLabel: '项目负责人', contactMobile: '13812346621' },
  { type: 'DEVICE', resourceId: '77777777-7777-4777-8777-777777777702', displayName: 'M350 RTK + H30T', roleLabel: null },
] };

const isoDate = (date) => date.toISOString().slice(0, 10);
const daysFromNow = (days) => new Date(Date.now() + days * 86_400_000);
const orderRoute = (id) => `/api/v1/inspection/service-orders/${id}`;
const providerVisit = (orderId, visitId) => `/api/v1/provider/inspection/service-orders/${orderId}/visits/${visitId}`;
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
const withIfMatch = (version, extra = {}) => ({ 'If-Match': `"${version}"`, 'Idempotency-Key': key(), ...extra });

async function ok(method, route, options, label) {
  const r = await call(method, route, options);
  if (r.status >= 300) throw new Error(`${label ?? route} → ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}

async function reservation(customer) {
  const day = 8 + Math.floor(Math.random() * 18);
  return ok('POST', '/api/v1/inspection/reservations', {
    token: customer,
    headers: { 'Idempotency-Key': key() },
    body: {
      serviceId: SERVICE_ID,
      cityCode: '370500',
      location: { address: '东营付款节点冒烟线路', longitude: 118.5, latitude: 37.4 },
      lineLengthMeters: 18_600,
      preferredServiceDate: isoDate(daysFromNow(day)),
      preferredTimeSlotCode: 'MORNING',
      inspectionItemCodes: ['POWER_LINE', 'TOWER'],
      attachmentFileIds: [],
      note: 'inspection payment smoke',
      contact: { name: '冒烟', mobile: '13800000000' },
      agreementVersion: '2026-07',
      agreementAccepted: true,
    },
  }, 'create reservation');
}

async function confirmedOrder(customer, provider, visitDates) {
  const booked = await reservation(customer);
  const plan = await ok('POST', `/api/v1/provider/inspection/reservations/${booked.id}/plans`, {
    token: provider,
    headers: withIfMatch(0),
    body: {
      providerId: PROVIDER_ID,
      validUntil: daysFromNow(2).toISOString(),
      visits: visitDates.map((plannedDate) => ({ plannedDate })),
      summary: '付款节点冒烟',
      scopeSummary: '线路约 18.6 公里',
      serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
      deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
      changeNote: null,
      planItems: [{ code: 'POWER_LINE', name: '线路本体巡检', quantity: 24, unit: '基', unitPriceMinor: UNIT }],
      deliverables: [
        { code: 'HD_MEDIA', name: '高清影像', fileType: 'IMAGE_ARCHIVE' },
        { code: 'DEFECT_LIST', name: '缺陷清单', fileType: 'DEFECT_LIST' },
        { code: 'POINT_MARKS', name: '点位标注', fileType: 'POINT_KML' },
        { code: 'REPORT', name: '巡检报告', fileType: 'INSPECTION_REPORT' },
      ],
      currency: 'CNY',
    },
  }, 'publish plan');
  const confirmKey = key();
  const body = { agreementVersion: 'inspection-plan-v2', agreementAccepted: true };
  const order = await ok('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
    token: customer, headers: { 'Idempotency-Key': confirmKey, 'If-Match': '"0"' }, body,
  }, 'confirm plan');
  const replay = await ok('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
    token: customer, headers: { 'Idempotency-Key': confirmKey, 'If-Match': '"0"' }, body,
  }, 'confirm replay');
  check('confirm replay returns the same order', replay.id === order.id);
  return { reservation: booked, plan, order };
}

const payNode = (token, orderId, nodeNo) => call('POST', `${orderRoute(orderId)}/payment-nodes/${nodeNo}/payment`, { token });
const readOrder = async (token, orderId) => ok('GET', orderRoute(orderId), { token }, 'read order');

async function payThroughFake(customer, tradeOrderId, expectedTotal) {
  const r = await launch(customer, tradeOrderId);
  check('payment launches', r.status === 200 && r.json.appCall?.prepayId, r.json);
  const no = outTradeNo(tradeOrderId);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === no);
  check('wechat order goes to the provider sub merchant for the node amount',
    fakeOrder && fakeOrder.sub_mchid === SUB_MCHID && fakeOrder.total === expectedTotal, fakeOrder);
  await fake('POST', `/_control/pay/${no}?notify=1`);
  return no;
}

async function uploadFile(provider, visitId, type, name, contentType, bytes, extra = {}) {
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  const intent = await ok('POST', '/api/v1/inspection/file-objects/upload-intents', {
    token: provider,
    headers: { 'Idempotency-Key': key() },
    body: { purpose: 'DELIVERY_ARTIFACT', ownerId: visitId, deliveryFileType: type, originalName: name, contentType, sizeBytes: bytes.length, sha256 },
  }, `upload intent ${name}`);
  const put = await fetch(intent.uploadUrl, { method: 'PUT', body: bytes, headers: { 'Content-Type': contentType } });
  if (!put.ok) throw new Error(`upload ${name} → ${put.status}`);
  await ok('POST', `/api/v1/inspection/file-objects/${intent.fileId}/complete`, {
    token: provider, headers: { 'Idempotency-Key': key() }, body: { etag: `smoke-${intent.fileId}`, sizeBytes: bytes.length, sha256 },
  }, `complete ${name}`);
  return { fileId: intent.fileId, type, displayName: name, pointCount: extra.pointCount ?? null, itemCount: extra.itemCount ?? null };
}

async function publishDelivery(provider, orderId, visit, round) {
  const marker = Buffer.from(`${visit.id}-${round}-${key()}`);
  const files = [
    await uploadFile(provider, visit.id, 'IMAGE_ARCHIVE', `images-${round}.zip`, 'application/zip', Buffer.concat([ZIP, marker]), { itemCount: 36 }),
    await uploadFile(provider, visit.id, 'DEFECT_LIST', `defects-${round}.xlsx`, XLSX, Buffer.concat([ZIP, marker]), { itemCount: 3 }),
    await uploadFile(provider, visit.id, 'POINT_KML', `points-${round}.kml`, 'application/vnd.google-earth.kml+xml', Buffer.concat([Buffer.from('<?xml version="1.0"?><kml/>'), marker]), { pointCount: 24 }),
    await uploadFile(provider, visit.id, 'INSPECTION_REPORT', `report-${round}.pdf`, 'application/pdf', Buffer.concat([Buffer.from('%PDF-1.4\n'), marker])),
  ];
  return call('POST', `${providerVisit(orderId, visit.id)}/delivery`, {
    token: provider,
    headers: withIfMatch(visit.version),
    body: {
      inspectedLengthMeters: 18_600,
      metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 420000, unit: '㎡' }],
      issueCount: round,
      issueGroupBy: 'CATEGORY',
      issueSummary: [{ groupCode: 'CRACK', groupName: '墙体裂缝', count: round }],
      files,
    },
  });
}

async function progress(provider, orderId, visit, eventCode) {
  return ok('POST', `${providerVisit(orderId, visit.id)}/progress-events`, {
    token: provider,
    headers: withIfMatch(visit.version),
    body: { eventCode, occurredAt: new Date(Date.now() + 1_000).toISOString(), message: eventCode },
  }, eventCode);
}

async function main() {
  const customer = await login('dev-customer');
  const provider = await login('dev-provider');
  const start = 8 + Math.floor(Math.random() * 10);
  const dates = [isoDate(daysFromNow(start)), isoDate(daysFromNow(start + 14))];

  // 1. 确认方案：服务单待付首付，节点金额按 D9 拆分
  const { plan, order: confirmed } = await confirmedOrder(customer, provider, dates);
  const orderId = confirmed.id;
  if (plan.paymentNodes.length === 0) throw new Error('方案没有付款节点：后端要开 INSPECTION_PAYMENT_NODES_ENABLED=true');
  check('nodes are 30% down then one per visit, adding up to the total',
    plan.paymentNodes.map((n) => n.amountMinor).join(',') === `${DOWN},${PER_VISIT},${PER_VISIT}` && plan.totalAmountMinor === TOTAL,
    plan.paymentNodes);
  check('confirmed order awaits its down payment', confirmed.status === 'AWAITING_PAYMENT' && confirmed.paymentMode === 'NODES'
    && confirmed.paymentNodes[0].payable === true && confirmed.cancellable === true, confirmed);
  let r = await call('PUT', `/api/v1/provider/inspection/service-orders/${orderId}/arrangement`, {
    token: provider, headers: withIfMatch(confirmed.version), body: ASSIGNMENTS,
  });
  check('provider cannot arrange before the down payment', r.status === 409 && r.json.code === 'INS_ORDER_STATE_INVALID', r.json);
  r = await payNode(customer, orderId, 2);
  check('a visit fee is not due before its visit is accepted', r.status === 409 && r.json.code === 'INS_PAYMENT_NOT_DUE', r.json);

  // 2. 首付：一张交易订单，重放返回同一张
  r = await payNode(customer, orderId, 1);
  check('down payment opens a trade order', r.status === 200 && r.json.tradeOrderId && r.json.paid === false, r.json);
  const downOrder = r.json.tradeOrderId;
  r = await payNode(customer, orderId, 1);
  check('asking again returns the same trade order', r.json.tradeOrderId === downOrder, r.json);
  r = await call('GET', `/api/v1/trade/orders/${downOrder}`, { token: customer });
  const attemptId = r.json.sourceRef;
  check('trade order is an inspection order paying the down payment', r.status === 200 && r.json.source === 'INSPECTION'
    && r.json.merchant.organizationId === PROVIDER_ID && !r.json.delivery && r.json.amounts.payableMinor === DOWN
    && r.json.lines[0].productId === orderId && r.json.lines[0].skuName === '首付 30%' && /· 方案 v1$/.test(r.json.lines[0].productName)
    && /^IO/.test(r.json.orderNo), r.json);
  check('one trade order for the attempt', psql(`SELECT count(*) FROM trade_order WHERE source='INSPECTION' AND source_ref='${attemptId}'`) === '1');
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods-order list leaves inspection orders out', r.status === 200 && !(r.json.items ?? []).some((o) => o.orderId === downOrder));
  const downNo = await payThroughFake(customer, downOrder, DOWN);
  let order = await readOrder(customer, orderId);
  check('the callback pays the node and the order is arranging', order.status === 'ARRANGING' && order.paymentNodes[0].paidAt
    && order.paymentNodes[0].tradeOrderId === downOrder && order.paymentRecords[0]?.status === 'PAID', order);
  await fake('POST', `/_control/renotify/${downNo}`);
  await fake('POST', `/_control/renotify/${downNo}`);
  order = await readOrder(customer, orderId);
  check('repeated notifications change nothing', order.status === 'ARRANGING' && order.paymentRecords.length === 1
    && psql(`SELECT count(*) FROM inspection_state_transition WHERE aggregate_id='${orderId}' AND from_status='AWAITING_PAYMENT'`) === '1', order.paymentRecords);
  r = await payNode(customer, orderId, 1);
  check('a paid node answers paid with its trade order', r.json.paid === true && r.json.tradeOrderId === downOrder, r.json);

  // 3. 履约：安排、排期、开工、完成、交付
  order = await ok('PUT', `/api/v1/provider/inspection/service-orders/${orderId}/arrangement`, {
    token: provider,
    headers: withIfMatch(order.version),
    body: ASSIGNMENTS,
  }, 'arrange');
  let [first, second] = order.visits;
  first = await ok('PUT', `${providerVisit(orderId, first.id)}/schedule`, { token: provider, headers: withIfMatch(first.version), body: { plannedStartAt: `${first.plannedDate}T01:00:00Z` } }, 'schedule');

  // 改期（C3）：第 2 次还早，可以申请；服务商确认后换日期、回到待排期
  order = await readOrder(customer, orderId);
  second = order.visits[1];
  check('a visit two weeks out can be rescheduled', second.reschedulable === true, second);
  const newDate = isoDate(new Date(new Date(`${second.plannedDate}T00:00:00Z`).getTime() + 2 * 86_400_000));
  r = await call('POST', `${orderRoute(orderId)}/visits/${second.id}/reschedule-requests`, {
    token: customer, headers: withIfMatch(second.version), body: { requestedDate: newDate, reason: '园区那天封闭' },
  });
  check('reschedule request created', r.status === 201 && r.json.status === 'REQUESTED' && r.json.fromDate === second.plannedDate, r.json);
  const requestId = r.json.id;
  r = await call('POST', `${orderRoute(orderId)}/visits/${second.id}/reschedule-requests`, {
    token: customer, headers: withIfMatch(second.version), body: { requestedDate: newDate, reason: '园区那天封闭' },
  });
  check('the same request again returns it', r.status === 201 && r.json.id === requestId, r.json);
  r = await call('POST', `${providerVisit(orderId, second.id)}/reschedule-requests/${requestId}/decision`, {
    token: provider, body: { decision: 'APPROVE', note: '可以' },
  });
  check('provider approves', r.status === 200 && r.json.status === 'APPROVED', r.json);
  order = await readOrder(customer, orderId);
  check('the visit moved and remembers its original date', order.visits[1].plannedDate === newDate
    && order.visits[1].originalPlannedDate === second.plannedDate && order.visits[1].status === 'PENDING_SCHEDULE', order.visits[1]);
  first = order.visits[0];

  let visit = await progress(provider, orderId, first, 'SERVICE_STARTED');
  order = await readOrder(customer, orderId);
  check('once a visit starts the order cannot be cancelled online', order.cancellable === false);
  r = await call('POST', `${orderRoute(orderId)}/cancel`, { token: customer, headers: withIfMatch(order.version), body: {} });
  check('cancelling after the start is refused', r.status === 409 && r.json.code === 'INS_ORDER_NOT_CANCELLABLE', r.json);
  visit = await progress(provider, orderId, visit, 'SERVICE_COMPLETED');
  r = await publishDelivery(provider, orderId, visit, 1);
  check('first delivery published', r.status === 201 && r.json.status === 'PUBLISHED', r.json);

  // 4. 退回整改（B2）：服务商重新交付后再验收
  order = await readOrder(customer, orderId);
  visit = order.visits[0];
  r = await call('POST', `${orderRoute(orderId)}/visits/${visit.id}/rework`, {
    token: customer, headers: withIfMatch(visit.version), body: { reason: '第 3 段线路没有拍到' },
  });
  check('delivery returned for rework', r.status === 201 && r.json.deliveryRevision === 1 && r.json.resolvedAt === null, r.json);
  order = await readOrder(customer, orderId);
  visit = order.visits[0];
  check('visit is in rework and the order back in progress', visit.status === 'REWORK' && order.status === 'IN_PROGRESS'
    && visit.rework?.reason === '第 3 段线路没有拍到', { status: order.status, visit: visit.status });
  r = await call('POST', `${orderRoute(orderId)}/visits/${visit.id}/acceptance`, {
    token: customer, headers: withIfMatch(visit.version), body: { confirmed: true },
  });
  check('a returned delivery cannot be accepted', r.status === 409, r.json);
  r = await publishDelivery(provider, orderId, visit, 2);
  check('provider delivers again', r.status === 201 && r.json.status === 'PUBLISHED' && r.json.issueCount === 2, r.json);
  check('the delivery has only the new files', psql(`SELECT count(*) FROM inspection_delivery_file WHERE delivery_id='${r.json.id}'`) === '4'
    && psql(`SELECT revision FROM inspection_delivery WHERE id='${r.json.id}'`) === '2');
  order = await readOrder(customer, orderId);
  visit = order.visits[0];
  check('rework resolved', visit.status === 'DELIVERED' && visit.rework?.resolvedAt, visit.rework);
  r = await call('POST', `${orderRoute(orderId)}/visits/${visit.id}/acceptance`, {
    token: customer, headers: withIfMatch(visit.version, { 'Idempotency-Key': key() }), body: { confirmed: true },
  });
  check('first visit accepted', r.status === 200 && r.json.visitStatus === 'ACCEPTED', r.json);

  // 5. 按次服务费：验收后可付，付了不影响后续
  order = await readOrder(customer, orderId);
  check('the first visit fee is now due', order.paymentNodes[1].payable === true && order.paymentNodes[2].payable === false, order.paymentNodes);
  r = await call('GET', '/api/v1/inspection/reservations?page=1&size=50&stage=ALL', { token: customer });
  const row = r.json.items.find((item) => item.serviceOrderId === orderId);
  check('the order list says which node to pay', row?.paymentDueNodeNo === 2 && row.paymentDueAmountMinor === PER_VISIT && row.paymentDueVisitNo === 1 && row.stage === 'IN_PROGRESS', row);
  r = await payNode(customer, orderId, 2);
  const visitOrder = r.json.tradeOrderId;
  r = await call('GET', `/api/v1/trade/orders/${visitOrder}`, { token: customer });
  check('visit fee trade order', r.json.amounts.payableMinor === PER_VISIT && r.json.lines[0].skuName === '第 1/2 次服务费', r.json.lines);
  await payThroughFake(customer, visitOrder, PER_VISIT);
  order = await readOrder(customer, orderId);
  check('the visit fee is paid and the order keeps going', order.paymentNodes[1].paidAt && order.status === 'IN_PROGRESS' && order.paymentNodes[2].paidAt === null, order.paymentNodes);

  // 6. 首付后、开工前取消：首付整笔原路退回
  const two = await confirmedOrder(customer, provider, [isoDate(daysFromNow(start + 3))]);
  r = await payNode(customer, two.order.id, 1);
  const twoDown = r.json.tradeOrderId;
  check('single-visit down payment is 30%', psql(`SELECT payable_minor FROM trade_order WHERE id='${twoDown}'`) === String(DOWN));
  await payThroughFake(customer, twoDown, DOWN);
  let twoOrder = await readOrder(customer, two.order.id);
  check('second order arranging after its down payment', twoOrder.status === 'ARRANGING' && twoOrder.cancellable === true, twoOrder.status);
  r = await call('POST', `${orderRoute(two.order.id)}/cancel`, { token: customer, headers: withIfMatch(twoOrder.version), body: { reason: '项目延期' } });
  check('cancelled before the first visit', r.status === 200 && r.json.status === 'CANCELLED' && r.json.cancellation?.reason === '项目延期', r.json?.status);
  check('the down payment refund is requested in full by the source', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${twoDown}'`) === `SOURCE:${DOWN}`);
  r = await call('POST', `${orderRoute(two.order.id)}/cancel`, { token: customer, headers: withIfMatch(twoOrder.version), body: {} });
  check('cancelling again changes nothing', r.status === 200 && psql(`SELECT count(*) FROM trade_refund WHERE order_id='${twoDown}'`) === '1', r.json?.status);
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${twoDown}'`);
  await waitFor(() => psql(`SELECT status FROM trade_refund WHERE order_id='${twoDown}'`) === 'PROCESSING', 30);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  twoOrder = await readOrder(customer, two.order.id);
  check('the refund shows as succeeded on the node', twoOrder.paymentNodes[0].refund?.status === 'SUCCEEDED' && twoOrder.paymentNodes[0].refund.amountMinor === DOWN, twoOrder.paymentNodes[0]);
  check('the trade order is refunded', tradeStatus(twoDown) === 'REFUNDED', tradeStatus(twoDown));
  r = await payNode(customer, two.order.id, 2);
  check('a cancelled order takes no more money', r.status === 409 && r.json.code === 'INS_PAYMENT_NOT_DUE', r.json);

  // 7. 首付前取消：不收费；取消后才到账的付款整笔自动退回
  const three = await confirmedOrder(customer, provider, [isoDate(daysFromNow(start + 4))]);
  r = await payNode(customer, three.order.id, 1);
  const threeDown = r.json.tradeOrderId;
  r = await launch(customer, threeDown);
  const threeNo = outTradeNo(threeDown);
  r = await call('POST', `${orderRoute(three.order.id)}/cancel`, { token: customer, headers: withIfMatch(three.order.version), body: {} });
  check('cancelled before paying, nothing charged', r.status === 200 && r.json.status === 'CANCELLED' && tradeStatus(threeDown) === 'CLOSED'
    && psql(`SELECT count(*) FROM trade_refund WHERE order_id='${threeDown}'`) === '0', [r.json?.status, tradeStatus(threeDown)]);
  r = await fake('POST', `/_control/pay/${threeNo}?notify=1`);
  check('wechat refuses to take money for the closed order', tradeStatus(threeDown) === 'CLOSED', [r.status, tradeStatus(threeDown)]);

  // 8. 取消之后才到账（本地已经关了、微信那边其实付了）：整笔自动原路退回，服务单仍是已取消、节点未付
  const four = await confirmedOrder(customer, provider, [isoDate(daysFromNow(start + 5))]);
  r = await payNode(customer, four.order.id, 1);
  const fourDown = r.json.tradeOrderId;
  await launch(customer, fourDown);
  const fourNo = outTradeNo(fourDown);
  closeLocally(fourDown);
  r = await call('POST', `${orderRoute(four.order.id)}/cancel`, { token: customer, headers: withIfMatch(four.order.version), body: {} });
  check('the order is cancelled while wechat still holds the payment', r.status === 200 && r.json.status === 'CANCELLED', r.json?.status);
  await fake('POST', `/_control/pay/${fourNo}?notify=1`);
  const refunded = await waitFor(() => psql(`SELECT count(*) FROM trade_refund WHERE order_id='${fourDown}'`) === '1', 15);
  check('a payment arriving after the cancellation is refunded in full', refunded
    && psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${fourDown}'`) === `SOURCE:${DOWN}`
    && tradeStatus(fourDown) === 'REFUNDING', tradeStatus(fourDown));
  const fourOrder = await readOrder(customer, four.order.id);
  check('the cancelled order stays cancelled and unpaid', fourOrder.status === 'CANCELLED' && fourOrder.paymentNodes[0].paidAt === null, fourOrder.status);
}

/** 模拟“本地已经超时关闭、微信那边其实付了”：交易订单和付款尝试按关闭时的样子改。 */
function closeLocally(tradeOrderId) {
  psql(`
    BEGIN;
    UPDATE trade_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${tradeOrderId}';
    UPDATE trade_payment SET status = 'CLOSED', updated_at = now() WHERE order_id = '${tradeOrderId}' AND status IN ('CREATED', 'PREPAID');
    INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, occurred_at)
      VALUES ('${tradeOrderId}', 'PENDING_PAYMENT', 'CLOSED', 'TIMEOUT', now());
    UPDATE inspection_payment_attempt SET status = 'CLOSED', closed_at = now(), updated_at = now()
      WHERE trade_order_id = '${tradeOrderId}' AND status = 'OPEN';
    COMMIT;
  `);
}

const done = finish(cleanupBuyer);
main().then(() => done(), done);
