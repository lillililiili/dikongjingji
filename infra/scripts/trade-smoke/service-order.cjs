#!/usr/bin/env node
'use strict';

// S4-3 通用服务订单 + S4-4 需求和专业服务补字段（docs/redesign/03-App实现任务计划.md §28）：
// 航拍需求 → 服务商报价（明细行、对比项）→ 客户选定生成服务订单 → 服务商出方案、改方案 → 客户确认 → 经交易模块一次付清
// （来源 PRO_SERVICE、PS 开头、钱进服务商子商户号）→ 开工 → 上传交付文件并交付 → 客户下载（短时令牌）→ 退回整改 → 再交付 →
// 验收；开工前取消（未付款关单、已付款整笔退款）；开工后协商终止（部分退款）；关了之后才到账的付款整笔退回；
// 需求计数、设备维护质保期、服务商主页资料（已通过的示例 + 工作台提交 + 运营审核，有 DEV_OPERATOR_PASSWORD 时）。
// 真实数据库 + 本机模拟微信（后端带着它写出的 backend.env 启动）。跑完清掉脚本自己建的需求、服务订单和 dev-customer 的交易订单
// （--keep 保留数据）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/service-order.cjs

const { BASE, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

const YUNYI = '22222222-2222-2222-2222-222222222222';
const EAGLE = 'f2000000-0000-4000-8000-000000000001';
const MAINTENANCE_SERVICE = 'f2100000-0000-4000-8000-000000000009';
const AMOUNT = 300_000;
const PDF = Buffer.from('%PDF-1.7\n冒烟交付报告\n', 'utf8');

const demands = new Set();
const beijingDate = (days) => new Date(Date.now() + days * 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
const orderOf = (token, id) => call('GET', `/api/v1/service-orders/${id}`, { token });
const providerOrder = (token, id) => call('GET', `/api/v1/provider/service-orders/${id}`, { token });

async function upload(token, orderId, name, bytes) {
  const form = new FormData();
  form.append('file', new Blob([bytes]), name);
  const response = await fetch(`${BASE}/api/v1/provider/service-orders/${orderId}/files`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form,
  });
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: response.status, json };
}

const quoteBody = (overrides = {}) => ({
  amountMinor: AMOUNT,
  method: '无人机航拍',
  schedule: '半天 · 11/28 进场',
  durationDays: 3,
  deliverables: '成片 1 条、照片 50 张',
  validUntil: new Date(Date.now() + 5 * 86_400_000).toISOString(),
  lines: [
    { name: '航拍作业', quantity: 1, unit: '次', unitAmountMinor: 200_000, amountMinor: 200_000 },
    { name: '后期剪辑', amountMinor: 100_000 },
  ],
  team: '东营本地',
  nightWork: '不含',
  reportHours: 72,
  archiveDays: 30,
  firstVisitDate: beijingDate(5),
  ...overrides,
});

/** 发一份航拍需求（只邀请云翼科技）、云翼报价、客户选定，返回服务订单 ID。 */
async function chosenOrder(customer, provider) {
  const r = await call('POST', '/api/v1/demands', {
    token: customer,
    headers: { 'Idempotency-Key': key() },
    body: {
      type: 'AERIAL', cityCode: '370500', address: '黄河口景区', scope: '景区宣传航拍', dueDate: beijingDate(10),
      deliverables: ['PHOTOS'], quoteScope: 'INVITE', invitedProviderIds: [YUNYI], submit: true,
    },
  });
  demands.add(r.json?.demandId);
  const demandId = r.json.demandId;
  await call('PUT', `/api/v1/provider/demands/${demandId}/quote`, { token: provider, body: quoteBody() });
  const quotes = await call('GET', `/api/v1/demands/${demandId}/quotes`, { token: customer });
  const quote = quotes.json.items[0];
  const chosen = await call('POST', `/api/v1/demands/${demandId}/choice`, {
    token: customer, headers: { 'If-Match': `"${quotes.json.demandVersion}"` }, body: { quoteId: quote.quoteId, quoteVersion: quote.version },
  });
  return { demandId, quotes, chosen };
}

async function planned(customer, provider, orderId) {
  const seen = await providerOrder(provider, orderId);
  await call('PUT', `/api/v1/provider/service-orders/${orderId}/plan`, {
    token: provider, headers: { 'If-Match': seen.etag }, body: { schedule: '11/28 上门，约半天', terms: ['成片 1 条', '照片 50 张'] },
  });
  const read = await orderOf(customer, orderId);
  return call('POST', `/api/v1/service-orders/${orderId}/plan-confirmation`, {
    token: customer, headers: { 'If-Match': read.etag }, body: { planVersion: read.json.plan.version, agreed: true },
  });
}

async function paid(customer, provider, orderId) {
  await planned(customer, provider, orderId);
  const start = (await call('POST', `/api/v1/service-orders/${orderId}/payments`, { token: customer })).json;
  await launch(customer, start.tradeOrderId);
  await fake('POST', `/_control/pay/${outTradeNo(start.tradeOrderId)}?notify=1`);
  return start;
}

function cleanup() {
  const ids = [...demands].filter(Boolean).map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      DELETE FROM service_order WHERE demand_id IN (${ids});
      DELETE FROM demand_event WHERE demand_id IN (${ids});
      DELETE FROM demand_quote_line WHERE quote_id IN (SELECT id FROM demand_quote WHERE demand_id IN (${ids}));
      DELETE FROM demand_quote WHERE demand_id IN (${ids});
      DELETE FROM demand WHERE id IN (${ids});
      COMMIT;
    `);
  }
  psql(`DELETE FROM pro_provider_profile WHERE provider_id = '${YUNYI}'`);
  psql(`DELETE FROM notification_message WHERE recipient_id = '11111111-1111-1111-1111-111111111111' AND source_key LIKE 'demand-%'`);
  cleanupBuyer();
}

async function main() {
  const customer = await login('dev-customer');
  const provider = await login('dev-provider');

  // 1. 报价明细和对比项（需求 1.3.0）
  const before = (await call('GET', '/api/v1/demand-counts', { token: customer })).json;
  check('demand counts answer every status', before && Number.isInteger(before.total) && Number.isInteger(before.draft), before);
  let r = await call('POST', '/api/v1/demands', {
    token: customer, headers: { 'Idempotency-Key': key() },
    body: { type: 'AERIAL', cityCode: '370500', address: '黄河口景区', scope: '景区宣传航拍', dueDate: beijingDate(10), deliverables: ['PHOTOS'], quoteScope: 'INVITE', invitedProviderIds: [YUNYI], submit: true },
  });
  demands.add(r.json?.demandId);
  const demandId = r.json?.demandId;
  check('an aerial demand is open', r.status === 201 && r.json.status === 'OPEN', r.json);
  r = await call('PUT', `/api/v1/provider/demands/${demandId}/quote`, { token: provider, body: quoteBody({ lines: [{ name: '航拍作业', amountMinor: 100_000 }] }) });
  check('lines must add up to the total', r.status === 400, r.json);
  r = await call('PUT', `/api/v1/provider/demands/${demandId}/quote`, { token: provider, body: quoteBody({ lines: [{ name: '航拍作业', quantity: 2, unit: '次', unitAmountMinor: 100_000, amountMinor: 300_000 }] }) });
  check('quantity × unit price must equal the line amount', r.status === 400, r.json);
  r = await call('PUT', `/api/v1/provider/demands/${demandId}/quote`, { token: provider, body: quoteBody() });
  check('quote with lines and comparison fields', r.status === 200 && r.json.myQuote.lines.length === 2 && r.json.myQuote.team === '东营本地' && r.json.myQuote.reportHours === 72, r.json.myQuote);
  const quotes = await call('GET', `/api/v1/demands/${demandId}/quotes`, { token: customer });
  const quote = quotes.json.items[0];
  check('customer sees lines and comparison fields', quote.lines[0].unit === '次' && quote.lines[1].quantity === null && quote.archiveDays === 30 && quote.firstVisitDate === beijingDate(5) && quote.nightWork === '不含', quote);
  const after = (await call('GET', '/api/v1/demand-counts', { token: customer })).json;
  check('counts include the new quoted demand', after.total === before.total + 1 && after.quoted === before.quoted + 1, after);

  // 2. 选定即生成服务订单（不建交易订单）
  const tradeOrdersBefore = psql(`SELECT count(*) FROM trade_order WHERE buyer_id = '11111111-1111-1111-1111-111111111111'`);
  r = await call('POST', `/api/v1/demands/${demandId}/choice`, { token: customer, headers: { 'If-Match': `"${quotes.json.demandVersion}"` }, body: { quoteId: quote.quoteId, quoteVersion: quote.version } });
  const orderId = r.json?.serviceOrderId;
  check('choosing an aerial quote opens a service order', r.status === 200 && r.json.status === 'CHOSEN' && orderId, r.json);
  r = await call('POST', `/api/v1/demands/${demandId}/choice`, { token: customer, headers: { 'If-Match': `"${quotes.json.demandVersion}"` }, body: { quoteId: quote.quoteId, quoteVersion: quote.version } });
  check('choosing again keeps the same order', r.json.serviceOrderId === orderId, r.json);
  r = await orderOf(customer, orderId);
  let order = r.json;
  check('the order waits for a plan with the quote snapshot', order.status === 'AWAITING_PLAN' && /^SV\d{14}$/.test(order.orderNo) && order.amountMinor === AMOUNT && order.lines.length === 2 && order.quote.team === '东营本地' && order.payment.collectable && r.etag === '"0"', order);
  check('the customer is the account\'s organization with a contact name', order.customer.kind === 'ORGANIZATION' && order.customer.organizationName && order.customer.contactName, order.customer);
  r = await call('GET', '/api/v1/service-orders?status=AWAITING_PLAN', { token: customer });
  check('my service orders list it', r.json.items.some((item) => item.orderId === orderId), r.json);
  check('a provider route needs a provider', (await call('GET', '/api/v1/provider/service-orders', { token: customer })).status === 403);
  check('a customer route needs a customer', (await call('GET', '/api/v1/service-orders', { token: provider })).status === 403);
  check('choosing creates no trade order', psql(`SELECT count(*) FROM trade_order WHERE buyer_id = '11111111-1111-1111-1111-111111111111'`) === tradeOrdersBefore);

  // 3. 出方案、改方案、确认
  let seen = await providerOrder(provider, orderId);
  check('the provider sees the order and may issue a plan', seen.status === 200 && seen.json.actions.issuePlan && !seen.json.actions.confirmPlan, seen.json?.actions);
  r = await call('PUT', `/api/v1/provider/service-orders/${orderId}/plan`, { token: provider, headers: { 'If-Match': '"9"' }, body: { schedule: '11/28 上门', terms: ['成片 1 条'] } });
  check('a stale order version is 412', r.status === 412 && r.json.code === 'SERVICE_ORDER_VERSION_CONFLICT', r.json);
  r = await call('PUT', `/api/v1/provider/service-orders/${orderId}/plan`, { token: provider, headers: { 'If-Match': seen.etag }, body: { schedule: '11/28 上门', terms: [] } });
  check('a plan needs at least one delivery term', r.status === 400, r.json);
  r = await call('PUT', `/api/v1/provider/service-orders/${orderId}/plan`, { token: provider, headers: { 'If-Match': seen.etag }, body: { schedule: '11/28 上门，约半天', terms: ['成片 1 条'] } });
  check('plan v1 at the quoted amount', r.status === 200 && r.json.status === 'PLAN_PENDING' && r.json.plan.version === 1 && r.json.plan.amountMinor === AMOUNT, r.json.plan);
  r = await call('PUT', `/api/v1/provider/service-orders/${orderId}/plan`, { token: provider, headers: { 'If-Match': r.etag }, body: { schedule: '11/29 上门，约半天', terms: ['成片 1 条', '照片 50 张'], note: '含调色' } });
  check('revised plan is v2', r.json.plan.version === 2 && r.json.plan.terms.length === 2, r.json.plan);
  let read = await orderOf(customer, orderId);
  const confirm = (headers, body) => call('POST', `/api/v1/service-orders/${orderId}/plan-confirmation`, { token: customer, headers, body });
  r = await confirm({ 'If-Match': read.etag }, { planVersion: 1, agreed: true });
  check('confirming an old plan is refused', r.status === 409 && r.json.code === 'SERVICE_ORDER_PLAN_CHANGED', r.json);
  r = await confirm({ 'If-Match': read.etag }, { planVersion: 2, agreed: false });
  check('the plan must be agreed', r.status === 422 && r.json.code === 'SERVICE_ORDER_TERMS_NOT_AGREED', r.json);
  r = await confirm({ 'If-Match': read.etag }, { planVersion: 2, agreed: true });
  check('plan confirmed, waiting for payment', r.status === 200 && r.json.status === 'AWAITING_PAYMENT' && r.json.plan.confirmedBy && r.json.actions.pay, r.json);

  // 4. 付款：交易订单 PRO_SERVICE、PS 开头、15 分钟、服务商子商户号
  r = await call('POST', `/api/v1/service-orders/${orderId}/payments`, { token: customer });
  const start = r.json;
  check('payment starts a trade order', r.status === 200 && start.tradeOrderId && start.amountMinor === AMOUNT, start);
  r = await call('POST', `/api/v1/service-orders/${orderId}/payments`, { token: customer });
  check('starting again reuses the open trade order', r.json.tradeOrderId === start.tradeOrderId, r.json);
  r = await call('GET', `/api/v1/trade/orders/${start.tradeOrderId}`, { token: customer });
  const minutes = (new Date(r.json.payBefore) - Date.now()) / 60000;
  check('trade order is a PRO_SERVICE order within 15 minutes', r.json.source === 'PRO_SERVICE' && /^PS/.test(r.json.orderNo) && r.json.amounts.payableMinor === AMOUNT && r.json.lines[0].productId === orderId && r.json.lines[0].skuName === '方案 v2' && minutes > 13 && minutes <= 15, r.json);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods-order list leaves service orders out', !(r.json.items ?? []).some((o) => o.orderId === start.tradeOrderId));
  await launch(customer, start.tradeOrderId);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === outTradeNo(start.tradeOrderId));
  check('wechat order goes to the provider sub merchant', fakeOrder?.sub_mchid === '1900000301' && fakeOrder.total === AMOUNT, fakeOrder);
  await fake('POST', `/_control/pay/${outTradeNo(start.tradeOrderId)}?notify=1`);
  read = await orderOf(customer, orderId);
  check('paid: waiting to start', read.json.status === 'AWAITING_START' && read.json.payment.paidAt && read.json.payment.paidTradeOrderId === start.tradeOrderId && read.json.actions.cancel, read.json);

  // 5. 开工、上传、交付、下载、退回整改、再交付、验收
  seen = await providerOrder(provider, orderId);
  r = await call('POST', `/api/v1/provider/service-orders/${orderId}/start`, { token: provider, headers: { 'If-Match': seen.etag } });
  check('started: in service, the customer can no longer cancel', r.json.status === 'IN_SERVICE' && r.json.startedAt, r.json);
  r = await call('POST', `/api/v1/service-orders/${orderId}/cancel`, { token: customer, headers: { 'If-Match': `"${r.json.version}"` }, body: {} });
  check('no online cancel after the start', r.status === 409 && r.json.code === 'SERVICE_ORDER_STATE_INVALID', r.json);
  r = await upload(provider, orderId, '../成片说明.exe', Buffer.from('MZ not a pdf'));
  check('unknown file types are refused', r.status === 422 && r.json.code === 'SERVICE_ORDER_FILE_INVALID', r.json);
  r = await upload(provider, orderId, '交付报告.pdf', PDF);
  const file = r.json;
  check('a PDF is uploaded and staged', r.status === 200 && file.contentType === 'application/pdf' && file.sizeBytes === PDF.length, file);
  r = await upload(provider, orderId, '多余.pdf', PDF);
  const extra = r.json;
  r = await call('DELETE', `/api/v1/provider/service-orders/${orderId}/files/${extra.fileId}`, { token: provider });
  check('a staged file can be removed', r.status === 204, r.json);
  seen = await providerOrder(provider, orderId);
  check('the provider sees the staged file; the customer does not', seen.json.stagedFiles.length === 1 && (await orderOf(customer, orderId)).json.stagedFiles.length === 0, seen.json.stagedFiles);
  r = await call('POST', `/api/v1/service-orders/${orderId}/files/${file.fileId}/download-intents`, { token: customer });
  check('an undelivered file cannot be downloaded', r.status === 404, r.json);
  r = await call('POST', `/api/v1/provider/service-orders/${orderId}/delivery`, { token: provider, headers: { 'If-Match': seen.etag }, body: { note: '成片已交付，照片 50 张', fileIds: [file.fileId] } });
  check('delivered: round 1 waits for acceptance', r.json.status === 'DELIVERED' && r.json.deliveries[0].round === 1 && r.json.deliveries[0].files.length === 1, r.json);
  r = await call('POST', `/api/v1/service-orders/${orderId}/files/${file.fileId}/download-intents`, { token: customer });
  const intent = r.json;
  check('a short-lived download link', r.status === 200 && intent.url.startsWith('/api/v1/service-order-files/') && (new Date(intent.expiresAt) - Date.now()) / 60000 <= 10, intent);
  const downloaded = await fetch(BASE + intent.url);
  const body = Buffer.from(await downloaded.arrayBuffer());
  check('the link downloads the file without a login', downloaded.status === 200 && body.equals(PDF) && /attachment/.test(downloaded.headers.get('content-disposition') || ''), downloaded.status);
  check('an unknown token is not found', (await fetch(`${BASE}/api/v1/service-order-files/${key()}`)).status === 404);
  r = await call('GET', `/api/v1/provider/service-orders/${orderId}/files/${file.fileId}/content`, { token: provider });
  check('the provider downloads with a login', r.status === 200);
  read = await orderOf(customer, orderId);
  r = await call('POST', `/api/v1/service-orders/${orderId}/acceptance`, { token: customer, headers: { 'If-Match': read.etag }, body: { round: 2 } });
  check('accepting a round that is not the latest is refused', r.status === 409 && r.json.code === 'SERVICE_ORDER_DELIVERY_CHANGED', r.json);
  r = await call('POST', `/api/v1/service-orders/${orderId}/rework`, { token: customer, headers: { 'If-Match': read.etag }, body: { round: 1, reason: '画面有遮挡，请重新剪辑' } });
  check('returned for rework', r.json.status === 'REWORK' && r.json.deliveries[0].status === 'RETURNED' && r.json.deliveries[0].reviewNote, r.json);
  seen = await providerOrder(provider, orderId);
  r = await call('POST', `/api/v1/provider/service-orders/${orderId}/delivery`, { token: provider, headers: { 'If-Match': seen.etag }, body: { note: '已重新剪辑', fileIds: [] } });
  check('delivered again: round 2', r.json.status === 'DELIVERED' && r.json.deliveries.length === 2, r.json);
  read = await orderOf(customer, orderId);
  r = await call('POST', `/api/v1/service-orders/${orderId}/acceptance`, { token: customer, headers: { 'If-Match': read.etag }, body: { round: 2 } });
  check('accepted: completed', r.json.status === 'COMPLETED' && r.json.completedAt && r.json.deliveries[1].status === 'ACCEPTED', r.json);
  r = await call('POST', `/api/v1/service-orders/${orderId}/acceptance`, { token: customer, headers: { 'If-Match': read.etag }, body: { round: 2 } });
  check('accepting again changes nothing', r.status === 200 && r.json.status === 'COMPLETED', r.json);
  const events = r.json.events.map((event) => event.type).join(',');
  check('the order keeps its record', events === 'CREATED,PLAN_ISSUED,PLAN_REVISED,PLAN_CONFIRMED,PAID,STARTED,DELIVERED,RETURNED,DELIVERED,ACCEPTED', events);

  // 6. 开工前取消：未付款时关掉正在付的交易订单；关了之后才到账的整笔退回
  const second = await chosenOrder(customer, provider);
  const secondId = second.chosen.json.serviceOrderId;
  await planned(customer, provider, secondId);
  const pending = (await call('POST', `/api/v1/service-orders/${secondId}/payments`, { token: customer })).json;
  await launch(customer, pending.tradeOrderId);
  read = await orderOf(customer, secondId);
  r = await call('POST', `/api/v1/service-orders/${secondId}/cancel`, { token: customer, headers: { 'If-Match': read.etag }, body: { reason: '计划有变' } });
  check('cancelled before payment; the open trade order is closed', r.json.status === 'CANCELLED' && r.json.closedBy === 'CUSTOMER' && r.json.payment.refunds.length === 0 && psqlStatus(pending.tradeOrderId) === 'CLOSED', r.json);

  // 7. 开工前取消：已付款整笔原路退款（服务商取消）
  const third = await chosenOrder(customer, provider);
  const thirdId = third.chosen.json.serviceOrderId;
  const thirdPay = await paid(customer, provider, thirdId);
  seen = await providerOrder(provider, thirdId);
  r = await call('POST', `/api/v1/provider/service-orders/${thirdId}/cancel`, { token: provider, headers: { 'If-Match': seen.etag }, body: { reason: '设备故障，无法按期服务' } });
  check('provider cancels a paid order: full refund', r.json.status === 'CANCELLED' && r.json.closedBy === 'PROVIDER' && r.json.payment.refunds[0]?.kind === 'CANCEL' && r.json.payment.refunds[0].amountMinor === AMOUNT, r.json);
  check('the refund goes to the channel', await waitFor(() => ['PROCESSING', 'SUCCEEDED'].includes(psql(`SELECT status FROM trade_refund WHERE order_id='${thirdPay.tradeOrderId}'`)), 30));

  // 8. 开工后协商终止，部分退款
  const fourth = await chosenOrder(customer, provider);
  const fourthId = fourth.chosen.json.serviceOrderId;
  await paid(customer, provider, fourthId);
  seen = await providerOrder(provider, fourthId);
  r = await call('POST', `/api/v1/provider/service-orders/${fourthId}/start`, { token: provider, headers: { 'If-Match': seen.etag } });
  r = await call('POST', `/api/v1/provider/service-orders/${fourthId}/termination`, { token: provider, headers: { 'If-Match': r.etag }, body: { refundAmountMinor: AMOUNT + 1, reason: '协商一致' } });
  check('cannot refund more than was paid', r.status === 409 && r.json.code === 'SERVICE_ORDER_REFUND_NOT_ALLOWED', r.json);
  seen = await providerOrder(provider, fourthId);
  r = await call('POST', `/api/v1/provider/service-orders/${fourthId}/termination`, { token: provider, headers: { 'If-Match': seen.etag }, body: { refundAmountMinor: 100_000, reason: '协商一致，退一部分' } });
  check('terminated with a partial refund', r.json.status === 'TERMINATED' && r.json.payment.refundedMinor === 100_000 && r.json.payment.refunds[0].kind === 'TERMINATE', r.json);

  // 9. 专业服务补字段：质保期、服务商主页资料
  r = await call('GET', `/api/v1/pro-services/${MAINTENANCE_SERVICE}?cityCode=370500`);
  check('maintenance detail has a warranty', r.status === 200 && /质保/.test(r.json.warrantyText || ''), r.json?.warrantyText);
  r = await call('GET', `/api/v1/providers/${EAGLE}?cityCode=370500`);
  check('the provider page shows the approved profile', r.json.profile?.legalEntity === '示例鹰眼智能科技有限公司' && r.json.profile.deliverables.length === 4 && r.json.profile.authorizationUntil === '2027-10-31', r.json.profile);
  r = await call('GET', '/api/v1/provider/profile', { token: provider });
  check('the workbench profile starts empty', r.status === 200 && r.json.status === 'NONE' && r.json.version === 0, r.json);
  r = await call('PUT', '/api/v1/provider/profile', { token: provider, body: { legalEntity: '示例云翼科技有限公司', serviceModes: '无人机巡检 · 航拍', authorizationUntil: beijingDate(300), deliverables: ['巡检报告', '影像存档'], localTeamLabel: '东营本地团队', version: 0 } });
  check('the provider submits a profile for review', r.status === 200 && r.json.status === 'PENDING' && r.json.published === null, r.json);
  r = await call('GET', `/api/v1/providers/${YUNYI}?cityCode=370500`);
  check('a pending profile is not shown yet', r.json.profile === null, r.json.profile);
  if (process.env.DEV_OPERATOR_PASSWORD) {
    const operator = await login('dev-operator', process.env.DEV_OPERATOR_PASSWORD);
    const detail = (await call('GET', `/api/v1/ops/provider-profiles/${YUNYI}`, { token: operator })).json;
    r = await call('POST', `/api/v1/ops/provider-profiles/${YUNYI}/review`, { token: operator, body: { decision: 'APPROVE', version: detail.version } });
    check('the operator approves it', r.status === 200 && r.json.status === 'APPROVED' && r.json.changes.length === 2, r.json);
    r = await call('GET', `/api/v1/providers/${YUNYI}?cityCode=370500`);
    check('the approved profile is on the provider page', r.json.profile?.legalEntity === '示例云翼科技有限公司', r.json.profile);
  } else {
    console.log('skip  operator review (DEV_OPERATOR_PASSWORD not set)');
  }
}

function psqlStatus(tradeOrderId) {
  return psql(`SELECT status FROM trade_order WHERE id='${tradeOrderId}'`);
}

const done = finish(cleanup);
main().then(() => done(), done);
