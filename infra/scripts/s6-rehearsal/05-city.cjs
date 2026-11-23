#!/usr/bin/env node
'use strict';

// S6 场景 5 城市复制（建设方案 §11.1“以配置开通第二城市，加载指定资源；跨城市和跨商家越权用例全部拦截”；
// docs/delivery/城市开通模板.md §2 的 C1–C11）：
//   运营端把淄博（370300，开发城市目录里未开通）改为筹备中 → 入驻一家淄博商家（资质、收款账户、操作员）→ 上架一件只在淄博卖的商品、
//   一项只在淄博上架的专业服务、一场淄博的免费活动 → 改为已开通；
//   两个城市的列表互不串数据；深链打开别城的资源不能下单；
//   东营商家不能操作淄博商家的订单，淄博商家不能操作东营商家的订单、核销别家的凭证、给没邀请它的需求和询价报价；
//   别的客户读不到 dev-customer 的订单、报名、地址、会话；
//   运营关城后 App 城市面板不再显示。
// 跑完：删掉本脚本建的全部数据（机构、账号、商品、订单、报名、第二个客户……）并查残留；淄博的开通状态按原值还原。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/05-city.cjs

const crypto = require('node:crypto');
const { call, fake, psql, login, key, offerId, step, known, note, track, purgeAndReport, loginOperator, done, sqlText } = require('./lib.cjs');
const fx = require('./fixtures.cjs');

const CITY_A = '370500'; // 东营，已开通
const CITY_B = '370300'; // 淄博，开发城市目录里未开通
const TAG = `S6城市${Date.now() % 100000}`;
const KIT = offerId('101', '1'); // 编程机器人探索套装，示例机器人商家（dev-merchant），只在东营卖，129900
const KIT_PRODUCT = 'c2000000-0000-4000-8000-000000000101';
const DONGYING_ONLY_SERVICE = 'f2100000-0000-4000-8000-000000000002'; // 能源设施航拍，只在东营上架
const INQUIRY_PRODUCT = 'c2000000-0000-4000-8000-000000000203'; // 行业测绘无人机（询价商品）
const YUNYI = '22222222-2222-2222-2222-222222222222';
const has = (list, field, id) => Array.isArray(list) && list.some((x) => x[field] === id);
const items = (r) => r.json?.items ?? [];
const claims = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
const iso = (ms) => new Date(ms).toISOString();

let original = null; // 淄博原来的开通状态，结束时还原

async function cityLists() {
  const r = await call('GET', '/api/v1/platform/cities');
  const codes = (list) => (list || []).map((c) => c.code);
  return { open: codes(r.json?.openCities), preparing: codes(r.json?.preparingCities) };
}

async function setCity(op, status, note) {
  const page = await call('GET', `/api/v1/ops/cities?keyword=${encodeURIComponent('淄博')}&size=5`, { token: op });
  const city = page.json.items.find((c) => c.cityCode === CITY_B);
  const r = await call('PUT', `/api/v1/ops/cities/${CITY_B}/service-status`, { token: op, body: { serviceStatus: status, note, version: city.version } });
  return r;
}

async function paidOrder(customer, { offer, cityCode, payable, addressId }) {
  const r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode, offerId: offer, quantity: 1, deliveryMode: 'EXPRESS', addressId, expectedPayableMinor: payable, agreementAccepted: true } });
  if (r.status !== 201) throw new Error(`下单失败 ${r.status} ${JSON.stringify(r.json)}`);
  track(r.json.orderId);
  await call('POST', `/api/v1/trade/orders/${r.json.orderId}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  const no = psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${r.json.orderId}'`);
  await fake('POST', `/_control/pay/${no}?notify=1`);
  return (await call('GET', `/api/v1/trade/orders/${r.json.orderId}`, { token: customer })).json;
}

async function phoneCustomer() {
  const phone = `199${String(crypto.randomInt(0, 1e8)).padStart(8, '0')}`;
  track(phone);
  const sent = await call('POST', '/api/v1/auth/sms-codes', { body: { phone } });
  if (sent.status !== 202) throw new Error(`发码失败 ${sent.status}`);
  const content = psql(`SELECT content FROM identity_dev_sms_outbox WHERE phone='${phone}' ORDER BY sent_at DESC LIMIT 1`);
  const code = (/验证码 (\d{6})/.exec(content) || [])[1];
  const r = await call('POST', '/api/v1/auth/phone-login', { body: { phone, code, device: { deviceKey: crypto.randomUUID(), deviceName: 'S6 演练手机', platform: 'ANDROID', cityCode: CITY_B } } });
  if (r.status !== 200) throw new Error(`验证码登录失败 ${r.status}`);
  const userId = track(claims(r.json.accessToken).subjectId);
  // 验证码注册时同时建了这个人的“个人客户”机构：用户行指向它（向上的引用），闭包清理顺着子行找不到，要单独记上。
  track(psql(`SELECT default_organization_id FROM identity_user WHERE id='${userId}'`));
  return r.json.accessToken;
}

async function main() {
  const op = await loginOperator();
  const customer = await login('dev-customer');
  const merchantA = await login('dev-merchant');
  const organizerA = await login('dev-organizer');

  // ---- 记下淄博原来的状态 ----
  const row = psql(`SELECT service_status || '|' || version || '|' || coalesce(service_status_changed_at::text, '') FROM platform_city WHERE city_code='${CITY_B}'`).split('|');
  original = { status: row[0], version: Number(row[1]), changedAt: row[2] || null };
  note(`淄博原状态 ${original.status}，version ${original.version}`);
  let lists = await cityLists();
  step('演练前淄博不在城市面板（已开通 / 筹备中）', { req: 'GET /api/v1/platform/cities', expect: '两组都不含 370300' }, !lists.open.includes(CITY_B) && !lists.preparing.includes(CITY_B), lists);

  // ---- C4：改为筹备中 ----
  let r = await setCity(op, 'PREPARING', 'S6 演练：第二城市测试空间，录入资源');
  step('运营端把淄博改为筹备中', { req: `PUT /api/v1/ops/cities/${CITY_B}/service-status PREPARING`, expect: '200' }, r.status === 200 && r.json.serviceStatus === 'PREPARING', r.json);
  lists = await cityLists();
  step('App 城市面板把淄博列在“筹备中”', { req: 'GET /api/v1/platform/cities', expect: 'preparingCities 含 370300' }, lists.preparing.includes(CITY_B) && !lists.open.includes(CITY_B), lists);

  // ---- 淄博商家入驻、上架 ----
  const org = await fx.createOrg(op, { name: `${TAG}淄博商家`, cityCode: CITY_B });
  const orgB = org.orgId;
  await fx.submitQualification(op, orgB);
  const approved = await fx.approveQualification(op, orgB);
  const accountB = await fx.registerMerchantAccount(op, orgB);
  const operatorB = await fx.createOperator(op, orgB, 's6b');
  psql(`INSERT INTO inspection_provider_profile (provider_id, logo_asset_code, certification_label, team_description, status)
        VALUES ('${orgB}', 'PROVIDER_INITIAL', '企业认证', 'S6 演练淄博团队', 'ACTIVE')`); // 问题 S6-01 的绕过，见 01-resources.cjs
  step('淄博商家入驻：资质审核通过、收款账户、操作员账号', { req: 'ops organizations / qualification / merchant-accounts / accounts', expect: '都成功' },
    approved.detail.qualification.status === 'APPROVED' && accountB.response.status === 200 && operatorB.token, { org: orgB, subMchid: accountB.subMchid });
  const cover = await fx.mediaUrl(op);
  const opsCover = await fx.mediaUrl(op, '/api/v1/ops/media'); // 活动封面只收 https 的运营素材地址
  const productB = await fx.createProduct(op, { orgId: orgB, cityCode: CITY_B, name: `${TAG}淄博无人机`, priceMinor: 158000, coverUrl: cover.url });
  const serviceB = await fx.createProService(op, { orgId: orgB, cityCode: CITY_B, title: `${TAG}淄博航拍` });
  const start = Date.now() + 2 * 86_400_000;
  const activityB = await fx.createActivity(op, { orgId: orgB, cityCode: CITY_B, title: `${TAG}淄博开放日`, priceMinor: 0, coverUrl: opsCover.url,
    sessions: [{ startsAt: iso(start), endsAt: iso(start + 3600_000), checkInOpensAt: iso(start - 3600_000), enrollmentClosesAt: iso(start - 3600_000), capacity: 20 }] });
  step('淄博资源上架：商品、专业服务、免费活动', { req: 'ops catalog / pro-services / activities', expect: '均已上架' },
    productB.detail.product.status === 'PUBLISHED' && serviceB.svc.status === 'PUBLISHED' && activityB.act.status === 'PUBLISHED', [productB.detail.product.status, serviceB.svc.status, activityB.act.status]);

  r = await setCity(op, 'OPEN', 'S6 演练：验收用例前开通');
  lists = await cityLists();
  step('运营端把淄博改为已开通，App 城市面板列在“已开通”', { req: 'PUT …/service-status OPEN；GET /api/v1/platform/cities', expect: 'openCities 含 370300' }, r.status === 200 && lists.open.includes(CITY_B), lists);
  r = await call('GET', `/api/v1/ops/cities/${CITY_B}/service-status-changes`, { token: op });
  const changes = (r.json || []).filter((c) => c.note?.startsWith('S6 演练'));
  changes.forEach((c) => track(c.changeId));
  step('城市改动记录多了两条', { req: `GET /api/v1/ops/cities/${CITY_B}/service-status-changes`, expect: '≥2 条 S6 演练记录' }, changes.length >= 2, changes.map((c) => `${c.fromStatus}→${c.toStatus}`));

  // ---- C1：列表互不串数据 ----
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY_B}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('C1 淄博商城能看到淄博商品', { req: `GET /api/v1/mall/products?cityCode=${CITY_B}`, expect: '含' }, has(items(r), 'productId', productB.productId), items(r).map((x) => x.name));
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY_A}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('C1 东营商城看不到淄博本地商品', { req: `GET /api/v1/mall/products?cityCode=${CITY_A}`, expect: '不含' }, r.status === 200 && !has(items(r), 'productId', productB.productId), items(r).map((x) => x.name));
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY_B}&keyword=${encodeURIComponent('编程机器人探索套装')}&size=20`);
  step('C1 淄博商城看不到只在东营卖的商品', { req: `GET /api/v1/mall/products?cityCode=${CITY_B}&keyword=编程机器人探索套装`, expect: '不含' }, r.status === 200 && !has(items(r), 'productId', KIT_PRODUCT), items(r).map((x) => x.name));
  r = await call('GET', `/api/v1/pro-services?type=AERIAL&cityCode=${CITY_B}&size=50`);
  step('C1 淄博专业服务有淄博的、没有只在东营上架的', { req: `GET /api/v1/pro-services?cityCode=${CITY_B}`, expect: '含淄博服务、不含东营服务' },
    has(items(r), 'serviceId', serviceB.serviceId) && !has(items(r), 'serviceId', DONGYING_ONLY_SERVICE), items(r).map((x) => x.title));
  r = await call('GET', `/api/v1/pro-services?type=AERIAL&cityCode=${CITY_A}&size=50`);
  step('C1 东营专业服务没有淄博的', { req: `GET /api/v1/pro-services?cityCode=${CITY_A}`, expect: '不含淄博服务' }, r.status === 200 && !has(items(r), 'serviceId', serviceB.serviceId), items(r).length);
  r = await call('GET', `/api/v1/activities?cityCode=${CITY_A}&size=50`);
  const rB = await call('GET', `/api/v1/activities?cityCode=${CITY_B}&size=50`);
  step('C1 活动只在自己的城市出现', { req: 'GET /api/v1/activities 两城', expect: '淄博含、东营不含；淄博不含东营活动' },
    has(items(rB), 'activityId', activityB.activityId) && !has(items(r), 'activityId', activityB.activityId) && items(rB).every((a) => a.activityId === activityB.activityId || a.cityCode === CITY_B),
    { zibo: items(rB).map((a) => a.title), dongyingHasZibo: has(items(r), 'activityId', activityB.activityId) });
  r = await call('GET', `/api/v1/venues?cityCode=${CITY_B}&size=50`);
  step('C1 淄博没有东营的场馆', { req: `GET /api/v1/venues?cityCode=${CITY_B}`, expect: '空' }, r.status === 200 && items(r).length === 0, items(r).map((v) => v.name));

  // ---- C2：深链打开别城的资源 ----
  r = await call('GET', `/api/v1/mall/products/${KIT_PRODUCT}?cityCode=${CITY_B}`);
  step('C2 淄博打开东营本地商品：没有可买的供给', { req: `GET /api/v1/mall/products/{东营商品}?cityCode=${CITY_B}`, expect: '404 或 offers 为空' }, r.status === 404 || (r.json?.offers || []).length === 0, [r.status, (r.json?.offers || []).length]);
  r = await call('GET', `/api/v1/pro-services/${DONGYING_ONLY_SERVICE}?cityCode=${CITY_B}`);
  step('C2 淄博打开只在东营上架的服务：404', { req: `GET /api/v1/pro-services/{东营服务}?cityCode=${CITY_B}`, expect: '404' }, r.status === 404, r.status);

  const addrB = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: CITY_B, detail: '张店区 演练路 1 号', isDefault: false } });
  const addrA = await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '演练收货人', phone: '13800002026', cityCode: CITY_A, detail: '东营区 演练路 2 号', isDefault: false } });
  track(addrB.json?.addressId, addrA.json?.addressId);
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: CITY_A, offerId: productB.offerId, quantity: 1, deliveryMode: 'EXPRESS', addressId: addrA.json.addressId, expectedPayableMinor: 158000, agreementAccepted: true } });
  if (r.json?.orderId) track(r.json.orderId);
  step('C2 在东营下淄博商品的单被拒', { req: `POST /api/v1/trade/orders cityCode=${CITY_A} offer=淄博供给`, expect: '4xx' }, r.status >= 400 && r.status < 500, [r.status, r.json?.code]);
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: CITY_B, offerId: KIT, quantity: 1, deliveryMode: 'EXPRESS', addressId: addrB.json.addressId, expectedPayableMinor: 129900, agreementAccepted: true } });
  if (r.json?.orderId) track(r.json.orderId);
  step('C2 在淄博下东营商品的单被拒', { req: `POST /api/v1/trade/orders cityCode=${CITY_B} offer=东营供给`, expect: '4xx' }, r.status >= 400 && r.status < 500, [r.status, r.json?.code]);

  // ---- C3：在淄博下单，钱进淄博商家自己的子商户号 ----
  const orderB = await paidOrder(customer, { offer: productB.offerId, cityCode: CITY_B, payable: 158000, addressId: addrB.json.addressId });
  const payB = psql(`SELECT sub_mchid || '|' || status || '|' || amount_minor FROM trade_payment WHERE order_id='${orderB.orderId}'`).split('|');
  step('C3 淄博订单的城市是淄博，收款方是淄博商家自己的子商户号', { req: '下单 + 模拟微信付款回调', expect: `cityCode ${CITY_B}，sub_mchid ${accountB.subMchid}，已付款` },
    orderB.status === 'PAID' && psql(`SELECT city_code FROM trade_order WHERE id='${orderB.orderId}'`) === CITY_B && payB[0] === accountB.subMchid && payB[1] === 'SUCCEEDED', { status: orderB.status, payment: payB });
  const orderA = await paidOrder(customer, { offer: KIT, cityCode: CITY_A, payable: 129900, addressId: addrA.json.addressId });
  step('对照：东营订单进东营商家的子商户号', { req: '下单 + 付款', expect: 'sub_mchid 1900000101' }, psql(`SELECT sub_mchid FROM trade_payment WHERE order_id='${orderA.orderId}'`) === '1900000101', orderA.status);

  // ---- C5：跨商家越权 ----
  const ship = (token, order) => call('POST', `/api/v1/provider/trade/orders/${order.orderId}/shipment`, { token, headers: { 'Idempotency-Key': key(), 'If-Match': `"${order.version}"` }, body: { carrierName: '演练快递', trackingNo: `S6${Date.now()}` } });
  const refund = (token, order) => call('POST', `/api/v1/provider/trade/orders/${order.orderId}/refunds`, { token, headers: { 'Idempotency-Key': key() }, body: { amountMinor: 100, reason: '越权测试' } });
  const denied = (res) => res.status === 404 || res.status === 403;
  let a = await call('GET', `/api/v1/provider/trade/orders/${orderB.orderId}`, { token: merchantA });
  let b = await ship(merchantA, orderB);
  let c = await refund(merchantA, orderB);
  step('C5 东营商家查看、发货、退款淄博商家的订单都被拦下', { req: 'dev-merchant：GET / shipment / refunds 淄博订单', expect: '404 或 403' }, denied(a) && denied(b) && denied(c), [a.status, b.status, c.status]);
  a = await call('GET', `/api/v1/provider/trade/orders/${orderA.orderId}`, { token: operatorB.token });
  b = await ship(operatorB.token, orderA);
  c = await refund(operatorB.token, orderA);
  step('C5 淄博商家查看、发货、退款东营商家的订单都被拦下', { req: '淄博操作员：GET / shipment / refunds 东营订单', expect: '404 或 403' }, denied(a) && denied(b) && denied(c), [a.status, b.status, c.status]);
  r = await call('GET', '/api/v1/provider/trade/orders?page=1&size=50', { token: operatorB.token });
  step('C5 淄博商家的订单列表只有自己的单', { req: 'GET /api/v1/provider/trade/orders（淄博操作员）', expect: '只含淄博订单' }, r.status === 200 && items(r).length >= 1 && items(r).every((o) => o.orderId === orderB.orderId), items(r).map((o) => o.orderId));
  r = await ship(operatorB.token, orderB);
  step('对照：淄博商家能给自己的订单发货', { req: 'POST …/shipment（淄博操作员，自己的单）', expect: '200 SHIPPED' }, r.status === 200 && r.json.status === 'SHIPPED', [r.status, r.json?.status]);

  // 报价：需求只邀请了云翼科技，询价只发给了示例机器人商家
  r = await call('POST', '/api/v1/demands', { token: customer, headers: { 'Idempotency-Key': key() }, body: {
    type: 'AERIAL', cityCode: CITY_A, address: '演练地址', scope: 'S6 演练越权测试', dueDate: new Date(Date.now() + 10 * 86_400_000 + 8 * 3600_000).toISOString().slice(0, 10),
    deliverables: ['PHOTOS'], quoteScope: 'INVITE', invitedProviderIds: [YUNYI], submit: true } });
  const demandId = track(r.json?.demandId);
  const quote = { amountMinor: 100000, method: '无人机航拍', schedule: '一天', durationDays: 1, deliverables: '照片', validUntil: iso(Date.now() + 3 * 86_400_000) };
  a = await call('GET', `/api/v1/provider/demands/${demandId}`, { token: operatorB.token });
  b = await call('PUT', `/api/v1/provider/demands/${demandId}/quote`, { token: operatorB.token, body: quote });
  step('C5 没被邀请的淄博商家看不到、也不能给这份需求报价', { req: 'GET / PUT quote /api/v1/provider/demands/{id}（淄博操作员）', expect: '404 或 403' }, demandId && denied(a) && denied(b), [r.status, a.status, b.status, b.json?.code]);
  r = await call('POST', '/api/v1/mall/inquiries', { token: customer, headers: { 'Idempotency-Key': key() }, body: { productId: INQUIRY_PRODUCT, requirement: 'S6 演练越权测试', quantity: 1, cityCode: CITY_A, expectedDate: null, offerIds: [offerId('203', '1')] } });
  const inquiryId = track(r.json?.inquiryId);
  a = await call('GET', `/api/v1/provider/mall/inquiries/${inquiryId}`, { token: operatorB.token });
  b = await call('PUT', `/api/v1/provider/mall/inquiries/${inquiryId}/quote`, { token: operatorB.token, body: { unitAmountMinor: 100000, model: '演练', leadDays: 3, includes: null, validUntil: iso(Date.now() + 3 * 86_400_000) } });
  step('C5 没被询价的淄博商家看不到、也不能给这张询价单报价', { req: 'GET / PUT quote /api/v1/provider/mall/inquiries/{id}（淄博操作员）', expect: '404 或 403' }, inquiryId && denied(a) && denied(b), [r.status, a.status, b.status, b.json?.code]);

  // 会话：dev-customer 和示例机器人商家的会话（已有就是原来那个）
  r = await call('POST', '/api/v1/conversations', { token: customer, body: { counterpartKind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001' } });
  const conversationId = r.json?.conversationId;
  if (r.status === 201) track(conversationId);
  a = await call('GET', `/api/v1/provider/conversations/${conversationId}/messages`, { token: operatorB.token });
  b = await call('POST', `/api/v1/provider/conversations/${conversationId}/messages`, { token: operatorB.token, headers: { 'Idempotency-Key': key() }, body: { text: '越权回复' } });
  step('C5 淄博商家读不到、也回复不了别家的客服会话', { req: 'GET / POST /api/v1/provider/conversations/{别家会话}/messages', expect: '404 或 403' }, conversationId && denied(a) && denied(b), [r.status, a.status, b.status]);

  // ---- C6：核销别家机构的凭证 ----
  const kid = await call('POST', '/api/v1/activity-participants', { token: customer, body: { name: 'S6城市演练童', birthMonth: '2015-06', guardianConfirmed: true } });
  const kidId = track(kid.json?.participantId);
  r = await call('POST', '/api/v1/activity-enrollments', { token: customer, headers: { 'Idempotency-Key': key() }, body: { activityId: activityB.activityId, sessionId: activityB.sessionIds[0], participantIds: [kidId], noticeAgreed: true } });
  const enrollmentId = track(r.json?.enrollmentId);
  const ticket = r.json?.tickets?.[0];
  step('dev-customer 在淄博活动报名，拿到凭证', { req: 'POST /api/v1/activity-enrollments（免费）', expect: '201，凭证 ACT-…' }, r.status === 201 && /^ACT-/.test(ticket?.ticketCode || ''), [r.status, ticket?.status]);
  r = await call('POST', '/api/v1/checkin-workbench/verifications', { token: organizerA, body: { code: ticket.ticketCode, method: 'MANUAL' } });
  const rec = r.json?.details?.record;
  if (rec?.recordId) track(rec.recordId);
  step('C6 东营机构的核销员核销淄博机构的凭证被拦下，记录里不显示持有人、场次和金额', { req: 'POST /api/v1/checkin-workbench/verifications（dev-organizer）', expect: '409 CHECKIN_OTHER_ORGANIZATION，holderName/ticketSession/amountMinor 为空' },
    r.status === 409 && r.json.code === 'CHECKIN_OTHER_ORGANIZATION' && rec?.holderName === null && rec?.ticketSession === null && rec?.amountMinor === null, r.json);
  r = await call('POST', '/api/v1/activity-checkins', { token: organizerA, body: { ticketCode: ticket.ticketCode } });
  step('C6 旧的活动核销接口同样拦下别家机构', { req: 'POST /api/v1/activity-checkins（dev-organizer）', expect: '404 或 403 或 409' }, [403, 404, 409].includes(r.status), [r.status, r.json?.code]);

  // ---- 客户之间：读不到别人的订单、报名、地址、会话 ----
  const other = await phoneCustomer();
  a = await call('GET', `/api/v1/trade/orders/${orderB.orderId}`, { token: other });
  b = await call('GET', `/api/v1/activity-enrollments/${enrollmentId}`, { token: other });
  c = await call('PUT', `/api/v1/account/addresses/${addrB.json.addressId}`, { token: other, body: { recipientName: '越权', phone: '13800000000', cityCode: CITY_B, detail: '越权', isDefault: false } });
  const d = await call('GET', `/api/v1/conversations/${conversationId}/messages`, { token: other });
  const e = await call('GET', '/api/v1/account/addresses', { token: other });
  step('另一个客户读不到 dev-customer 的订单、报名、会话，改不了地址，地址簿里也没有', { req: '新注册客户：GET 订单 / 报名 / 会话，PUT 地址，GET 地址簿', expect: '404/403，地址簿不含' },
    denied(a) && denied(b) && denied(c) && denied(d) && e.status === 200 && !JSON.stringify(e.json).includes(addrB.json.addressId), [a.status, b.status, c.status, d.status, e.status]);
  a = await call('GET', `/api/v1/provider/trade/orders/${orderB.orderId}`, { token: other });
  b = await call('GET', '/api/v1/ops/cities', { token: other });
  step('C7 客户账号调商家接口、运营接口被拒', { req: 'GET /api/v1/provider/trade/orders/{id}、GET /api/v1/ops/cities（客户）', expect: '403' }, a.status === 403 && b.status === 403, [a.status, b.status]);

  // ---- C9：没登记收款账户的商家不能下单 ----
  const orgC = await fx.createOrg(op, { name: `${TAG}未收款商家`, cityCode: CITY_B });
  const productC = await fx.createProduct(op, { orgId: orgC.orgId, cityCode: CITY_B, name: `${TAG}未收款商品`, priceMinor: 50000, coverUrl: cover.url, publish: false }).catch((error) => ({ error }));
  step('C9 未登记收款账户的商家，标价商品的供给不能启用', { req: '新机构不登记收款账户，启用供给', expect: '422 OPS_MERCHANT_NOT_COLLECTING' }, String(productC.error || '').includes('OPS_MERCHANT_NOT_COLLECTING'), String(productC.error || productC.productId).slice(0, 200));

  // ---- 关城 ----
  r = await setCity(op, 'CLOSED', 'S6 演练：结束，关城');
  lists = await cityLists();
  step('运营关城后 App 城市面板不再显示淄博', { req: 'PUT …/service-status CLOSED；GET /api/v1/platform/cities', expect: '两组都不含 370300' }, r.status === 200 && !lists.open.includes(CITY_B) && !lists.preparing.includes(CITY_B), lists);
  r = await call('GET', `/api/v1/ops/cities/${CITY_B}/service-status-changes`, { token: op });
  (r.json || []).filter((x) => x.note?.startsWith('S6 演练')).forEach((x) => track(x.changeId));
}

function restoreCity() {
  if (!original) return;
  psql(`
    BEGIN;
    DELETE FROM platform_city_service_status_change WHERE city_code='${CITY_B}' AND changed_at >= ${sqlText(require('./lib.cjs').SINCE)}::timestamptz;
    UPDATE platform_city SET service_status=${sqlText(original.status)}, version=${original.version},
           service_status_changed_at=${original.changedAt ? `${sqlText(original.changedAt)}::timestamptz` : 'NULL'}
     WHERE city_code='${CITY_B}';
    COMMIT;`);
  const now = psql(`SELECT service_status || '|' || version || '|' || coalesce(service_status_changed_at::text, '') FROM platform_city WHERE city_code='${CITY_B}'`);
  step('淄博开通状态还原成演练前的样子', { req: 'SQL 还原 platform_city 一行、删本次改动记录', expect: `${original.status}|${original.version}|${original.changedAt || ''}` }, now === `${original.status}|${original.version}|${original.changedAt || ''}`, now);
}

const cleanup = () => { restoreCity(); purgeAndReport(); };
main().then(() => done(cleanup)(), (error) => done(cleanup)(error));
