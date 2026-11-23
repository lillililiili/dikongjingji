'use strict';

// S6 演练里“运营端上架资源”的几步，经真实运营接口（contracts/openapi-ops-*.yaml）完成，场景 1、4、5 共用。
// 每个函数建的对象都 track() 进清理集合；出错直接抛异常（脚本的 done() 会记一步失败并清理）。

const crypto = require('node:crypto');
const { call, login, track, upload, idem, PNG, PDF, fakeCreditCode, randomDigits } = require('./lib.cjs');

function must(r, what, statuses = [200, 201]) {
  if (!statuses.includes(r.status)) throw new Error(`${what} 失败：${r.status} ${JSON.stringify(r.json).slice(0, 500)}`);
  return r.json;
}

/** 运营端新建经营机构（PROVIDER）。 */
async function createOrg(op, { name, cityCode }) {
  const key = idem();
  const body = { type: 'PROVIDER', name, cityCode };
  const r = await call('POST', '/api/v1/ops/organizations', { token: op, headers: key, body });
  const detail = must(r, '新建机构', [201]);
  track(detail.organization.organizationId);
  const replay = await call('POST', '/api/v1/ops/organizations', { token: op, headers: key, body });
  return { orgId: detail.organization.organizationId, detail, created: r, replay };
}

/** 录资质文字（保存后待审核）。 */
async function submitQualification(op, orgId, version = null) {
  const r = await call('PUT', `/api/v1/ops/organizations/${orgId}/qualification`, { token: op, body: {
    subjectName: 'S6 演练经营主体（示例，不是真实企业）', licenseNumber: fakeCreditCode(),
    businessScope: '无人机及机器人销售、租赁、技术服务（演练）', qualificationNote: '演练用，线下未核对原件', version } });
  return must(r, '录资质');
}

/** 上传一份资质附件，再审核通过。 */
async function approveQualification(op, orgId) {
  const attached = await upload(`/api/v1/ops/organizations/${orgId}/attachments`, op, PDF, 'application/pdf', 'license.pdf');
  must(attached, '上传资质附件', [201]);
  const detail = (await call('GET', `/api/v1/ops/organizations/${orgId}`, { token: op })).json;
  const r = await call('POST', `/api/v1/ops/organizations/${orgId}/qualification/review`, { token: op, body: { decision: 'APPROVE', version: detail.qualification.version } });
  return { attached, review: r, detail: must(r, '审核资质') };
}

/**
 * 运营端“机构与资质”开通服务商资料（S6-01，openapi-ops-organization.yaml 1.1.0；资质要先审核通过）。幂等：返回第一次和重复调用的结果。
 * 服务商资料行挂在机构上，清理时跟着机构的 ID 一起删。
 */
async function openProviderProfile(op, orgId) {
  const first = await call('POST', `/api/v1/ops/organizations/${orgId}/provider-profile`, { token: op });
  const again = await call('POST', `/api/v1/ops/organizations/${orgId}/provider-profile`, { token: op });
  return { first, again, detail: must(first, '开通服务商资料') };
}

/** 登记收款账户（开发环境的占位号段 1900000900–1900000999，演练专用）。 */
async function registerMerchantAccount(op, orgId) {
  const subMchid = `19000009${randomDigits(2)}`;
  const r = await call('PUT', `/api/v1/ops/merchant-accounts/${orgId}`, { token: op, body: { subMchid, active: true } });
  must(r, '登记收款账户');
  return { subMchid, response: r };
}

/** 给机构建操作员账号；返回一次性的初始口令（只留在内存里）并登录。 */
async function createOperator(op, orgId, prefix, roles = ['PROVIDER_OPERATOR']) {
  const loginName = `${prefix}-${crypto.randomBytes(3).toString('hex')}`;
  const r = await call('POST', `/api/v1/ops/organizations/${orgId}/accounts`, { token: op, body: { loginName, displayName: `${prefix} 操作员`, roles } });
  const created = must(r, '建机构账号', [201]);
  const accountId = created.accountId || created.account?.accountId;
  track(accountId);
  const password = created.initialPassword || created.password;
  const token = await login(loginName, password);
  return { loginName, accountId, token, response: r };
}

async function mediaUrl(op, route = '/api/v1/ops/catalog/media') {
  const r = await upload(route, op, PNG, 'image/png', 's6-cover.png');
  const media = must(r, '上传素材', [200, 201]);
  track(media.mediaId, String(media.url || '').split('/').pop().replace(/\.[a-z]+$/, ''));
  return media;
}

/** 新建标价购买商品：规格、本城供给（启用）、补介绍和封面、上架。 */
async function createProduct(op, { orgId, cityCode, name, priceMinor, coverUrl, stock = 5, publish = true }) {
  let r = await call('POST', '/api/v1/ops/catalog/products', { token: op, headers: idem(), body: { category: 'DRONE', name, saleMode: 'BUY' } });
  let detail = must(r, '新建商品', [201]);
  const productId = track(detail.product.productId);
  detail = must(await call('POST', `/api/v1/ops/catalog/products/${productId}/skus`, { token: op, body: { name: '标准版（演练）' } }), '新建规格');
  const skuId = track(detail.skus[0].skuId);
  detail = must(await call('POST', `/api/v1/ops/catalog/products/${productId}/offers`, { token: op, headers: idem(), body: {
    skuId, merchantId: orgId, merchantKind: 'LOCAL_STORE', cityCode, priceAmountMinor: priceMinor, priceUnit: 'ITEM',
    stockQuantity: stock, expressAvailable: true, shippingFeeMinor: 0, pickupPoint: null,
    deliveryNote: '演练供给 · 不发真实货物', shippingNote: '演练 · 包邮', fulfilledFrom: 'S6 演练仓' } }), '新建供给');
  const offer = detail.offers[0];
  const offerId = track(offer.offerId);
  detail = must(await call('POST', `/api/v1/ops/catalog/products/${productId}/offers/${offerId}/status`, { token: op, body: { action: 'ACTIVATE', version: offer.version } }), '启用供给');
  detail = must(await call('PUT', `/api/v1/ops/catalog/products/${productId}`, { token: op, body: {
    category: 'DRONE', name, summary: 'S6 验收演练用的商品，不是真实在售商品', coverUrl, experienceBadge: null, sortOrder: 999,
    sellingPoints: ['演练'], specs: [{ label: '用途', value: '验收演练' }], packItems: [{ name: '演练机', quantity: 1 }],
    gallery: [], introBlocks: [{ type: 'TEXT', text: '验收演练商品，演练结束即删除。', imageUrl: null }], version: detail.product.version } }), '补商品资料');
  if (publish) {
    detail = must(await call('POST', `/api/v1/ops/catalog/products/${productId}/status`, { token: op, body: { action: 'PUBLISH', version: detail.product.version } }), '上架商品');
  }
  return { productId, skuId, offerId, detail };
}

/** 专业服务（航拍）：照开发种子里一条服务的结构建一条，挂在演练机构下。 */
async function createProService(op, { orgId, cityCode, title, publish = true }) {
  const template = must(await call('GET', '/api/v1/ops/pro-services/f2100000-0000-4000-8000-000000000001', { token: op }), '读服务模板');
  const body = {
    providerId: orgId, type: template.type, categoryCode: template.categoryCode, title, summary: 'S6 验收演练 · 园区航拍',
    priceMode: 'PRICE_FROM', priceAmountMinor: 99900, priceUnit: '/次起', priceNote: '演练报价', coverUrl: null, sortOrder: 999,
    cityCodes: [cityCode], tags: template.tags, facets: template.facets, capabilities: template.capabilities,
    priceRows: template.priceRows, scopes: template.scopes, outputs: template.outputs, serviceModes: template.serviceModes,
    terms: template.terms, steps: template.steps, guarantees: template.guarantees, warrantyText: null,
  };
  let r = await call('POST', '/api/v1/ops/pro-services', { token: op, body });
  let svc = must(r, '新建专业服务', [201]);
  const serviceId = track(svc.serviceId);
  if (publish) svc = must(await call('POST', `/api/v1/ops/pro-services/${serviceId}/publish`, { token: op, body: { version: svc.version } }), '上架专业服务');
  return { serviceId, svc };
}

/**
 * 收费活动（按人）和场次。sessions：[{ startsAt, endsAt, checkInOpensAt, enrollmentClosesAt, capacity }]。
 */
async function createActivity(op, { orgId, cityCode, title, priceMinor, coverUrl, sessions, publish = true }) {
  let r = await call('POST', '/api/v1/ops/activities', { token: op, body: {
    type: 'STUDY_TOUR', title, subtitle: 'S6 验收演练', coverUrl, galleryUrls: [], organizerId: orgId, coOrganizerId: null, cityCode,
    venueName: 'S6 演练场地', venueAddress: '演练路 1 号（示例地址）', scheduleNote: null,
    priceMode: priceMinor ? 'PER_PERSON' : 'FREE', priceAmountMinor: priceMinor || null, minAge: 6, maxAge: 16,
    eligibilityNote: '6–16 岁（演练）', seatNote: '每人 1 个名额', refundNote: '开始前可取消，按人原路退回（演练）',
    enrollmentNotice: '这是验收演练用的活动，不真实举办。' } });
  let act = must(r, '新建活动', [201]);
  const activityId = track(act.activityId);
  const sessionIds = [];
  for (const s of sessions) {
    act = must(await call('POST', `/api/v1/ops/activities/${activityId}/sessions`, { token: op, body: { label: s.label || '演练场', ...s } }), '新建场次');
    const added = act.sessions.find((x) => !sessionIds.includes(x.sessionId));
    sessionIds.push(track(added.sessionId));
  }
  if (publish) act = must(await call('POST', `/api/v1/ops/activities/${activityId}/publish`, { token: op, body: { version: act.version } }), '上线活动');
  return { activityId, sessionIds, act };
}

/** 场馆、一个项目、按周模板生成场次（dates 是北京时间日期，startTimes 如 ['10:00']）、上线。 */
async function createVenue(op, { orgId, cityCode, name, coverUrl, priceMinor, capacity = 6, fromDate, toDate, startTimes, publish = true }) {
  let r = await call('POST', '/api/v1/ops/venues', { token: op, headers: idem(), body: {
    operatorId: orgId, name, cityCode, coverUrl, address: '演练路 2 号（示例地址）', phone: null, opensAt: '00:00', closesAt: '23:59',
    closedNote: null, projectSummary: 'S6 演练项目', checkinOpensMinutes: 30, lateEntryMinutes: null, sortOrder: 999,
    infoItems: [{ label: '到馆须知', content: '验收演练场馆，不真实营业' }] } });
  let venue = must(r, '新建场馆', [201]);
  const venueId = track(venue.venueId);
  venue = must(await call('POST', `/api/v1/ops/venues/${venueId}/projects`, { token: op, headers: idem(), body: {
    name: 'S6 演练项目', durationMinutes: 30, priceAmountMinor: priceMinor, minAge: 6, capacity, limitNote: null, sortOrder: 10 } }), '新建项目');
  const projectId = track(venue.projects[0].projectId);
  const gen = await call('POST', `/api/v1/ops/venues/${venueId}/projects/${projectId}/sessions/generate`, { token: op, body: {
    fromDate, toDate, weekdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'], startTimes, capacity } });
  must(gen, '生成场次');
  venue = must(await call('GET', `/api/v1/ops/venues/${venueId}`, { token: op }), '读场馆');
  if (publish) venue = must(await call('POST', `/api/v1/ops/venues/${venueId}/publish`, { token: op, body: { version: venue.version, note: 'S6 演练' } }), '上线场馆');
  return { venueId, projectId, venue, generated: gen.json };
}

module.exports = { must, createOrg, submitQualification, approveQualification, openProviderProfile, registerMerchantAccount, createOperator, mediaUrl,
  createProduct, createProService, createActivity, createVenue };
