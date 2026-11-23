#!/usr/bin/env node
'use strict';

// S6 场景 1 资源与授权：运营端经 ops 接口开机构、录资质并审核、登记收款账户、建机构账号、上传素材、上架商品 / 专业服务 /
// 活动 / 场馆；App 公开接口能看到；草稿和下架的看不到；资质审核前后服务详情里服务商的“资质已审核”跟着变；客户、平台客服不能用运营接口。
// 无授权素材不进公开构建由 apps/mobile 的 verify:public-production-preview 检查（见测试报告，不在这个脚本里跑）。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/01-resources.cjs
// 跑完删掉本脚本建的全部数据并查残留（--keep 保留）。

const { call, login, psql, step, known, note, track, purgeAndReport, loginOperator, upload, PNG, beijingDate, done } = require('./lib.cjs');
const fx = require('./fixtures.cjs');

const CITY = '370500';
const TAG = `S6演练${Date.now() % 100000}`;
const has = (list, field, id) => Array.isArray(list) && list.some((x) => x[field] === id);
const items = (r) => r.json?.items ?? [];

async function main() {
  const op = await loginOperator();
  const customer = await login('dev-customer');
  const support = await login('dev-support');

  // ---- 权限：客户和平台客服不能用运营接口 ----
  let r = await call('GET', '/api/v1/ops/organizations', { token: customer });
  step('客户账号调运营接口被拒', { req: 'GET /api/v1/ops/organizations（dev-customer）', expect: '403' }, r.status === 403, r.status);
  r = await call('GET', '/api/v1/ops/organizations', { token: support });
  step('平台客服调平台运营接口被拒', { req: 'GET /api/v1/ops/organizations（dev-support）', expect: '403' }, r.status === 403, r.status);
  r = await call('GET', '/api/v1/ops/feedback', { token: op });
  const supportFeedback = await call('GET', '/api/v1/ops/feedback', { token: support });
  step('平台运营不能用“举报与反馈”（只给平台客服），平台客服可以', { req: 'GET /api/v1/ops/feedback（dev-operator / dev-support）', expect: '403 / 200' }, r.status === 403 && supportFeedback.status === 200, [r.status, supportFeedback.status]);
  r = await call('GET', '/api/v1/ops/organizations');
  step('未登录调运营接口被拒', { req: 'GET /api/v1/ops/organizations（无令牌）', expect: '401' }, r.status === 401, r.status);

  // ---- 机构入驻 ----
  const org = await fx.createOrg(op, { name: `${TAG}商家`, cityCode: CITY });
  step('运营端新建经营机构', { req: 'POST /api/v1/ops/organizations', expect: '201，机构在东营，资质未录' },
    org.created.status === 201 && org.detail.organization.cityCode === CITY && org.detail.organization.qualificationReviewedAt === null, org.detail.organization);
  step('同一幂等键重放返回同一家', { req: 'POST /api/v1/ops/organizations（同 Idempotency-Key）', expect: '200，同一 organizationId' },
    org.replay.status === 200 && org.replay.json.organization.organizationId === org.orgId, [org.replay.status, org.replay.json?.organization?.organizationId]);
  r = await call('POST', '/api/v1/ops/organizations', { token: op, headers: { 'Idempotency-Key': crypto.randomUUID() }, body: { type: 'PROVIDER', name: `${TAG}错城`, cityCode: '999999' } });
  step('不认识的城市拒绝建机构', { req: 'POST /api/v1/ops/organizations cityCode=999999', expect: '422 OPS_CITY_UNKNOWN' }, r.status === 422 && r.json.code === 'OPS_CITY_UNKNOWN', r.json);

  const qual = await fx.submitQualification(op, org.orgId);
  step('录入资质资料后待审核', { req: 'PUT …/qualification', expect: 'SUBMITTED' }, qual.qualification?.status === 'SUBMITTED', qual.qualification);
  r = await call('POST', `/api/v1/ops/organizations/${org.orgId}/qualification/review`, { token: op, body: { decision: 'APPROVE', version: qual.qualification.version } });
  step('没有附件不能审核通过', { req: 'POST …/qualification/review APPROVE（无附件）', expect: '409 OPS_QUALIFICATION_INCOMPLETE' }, r.status === 409 && r.json.code === 'OPS_QUALIFICATION_INCOMPLETE', r.json);

  const account = await fx.registerMerchantAccount(op, org.orgId);
  step('登记收款账户（占位子商户号）', { req: 'PUT /api/v1/ops/merchant-accounts/{org}', expect: '200，active' }, account.response.status === 200 && account.response.json.active === true, account.response.json);
  const operator = await fx.createOperator(op, org.orgId, 's6a');
  step('给机构建操作员账号，初始口令只返回一次，能登录', { req: 'POST …/accounts roles=[PROVIDER_OPERATOR]', expect: '201 + 能登录' },
    operator.response.status === 201 && typeof operator.token === 'string', { status: operator.response.status, loginName: operator.loginName, roles: operator.response.json.account?.roles });
  r = await call('GET', '/api/v1/ops/organizations', { token: operator.token });
  step('机构操作员不能用平台运营接口', { req: 'GET /api/v1/ops/organizations（机构操作员）', expect: '403' }, r.status === 403, r.status);

  // ---- 素材上传（本机存储） ----
  const cover = await fx.mediaUrl(op, '/api/v1/ops/catalog/media');
  step('上传商品图片，拿到受控地址', { req: 'POST /api/v1/ops/catalog/media（PNG）', expect: '201，image/png，受控地址' }, cover.contentType === 'image/png' && /^https?:\/\//.test(cover.url), cover);
  const opsMedia = await upload('/api/v1/ops/media', op, PNG, 'image/png', 's6-media.png');
  step('上传运营素材（活动、场馆封面用）', { req: 'POST /api/v1/ops/media（PNG）', expect: '201，带主机名的地址' }, [200, 201].includes(opsMedia.status) && /^https:\/\//.test(opsMedia.json?.url || ''), opsMedia.json);
  const opsMediaUrl = opsMedia.json.url;
  track(opsMedia.json.mediaId, opsMediaUrl.split('/').pop().replace(/\.[a-z]+$/, ''));
  const fileName = opsMediaUrl.split('/').pop();
  r = await call('GET', `/api/v1/ops/media/${fileName}`, { token: op });
  step('素材能经后端读回', { req: `GET /api/v1/ops/media/${fileName}`, expect: '200 image/png' }, r.status === 200 && (r.type || '').startsWith('image/png'), [r.status, r.type]);
  const bad = await upload('/api/v1/ops/catalog/media', op, Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'), 'image/png', 'fake.png');
  step('不是图片的文件按文件头拒绝', { req: 'POST /api/v1/ops/catalog/media（SVG 冒充 PNG）', expect: '4xx' }, bad.status >= 400 && bad.status < 500, [bad.status, bad.json]);

  // ---- 商品：草稿看不到，上架后看得到 ----
  const draft = await fx.createProduct(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}草稿机`, priceMinor: 99900, coverUrl: cover.url, publish: false });
  const product = await fx.createProduct(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}航拍机`, priceMinor: 199900, coverUrl: cover.url });
  step('商品上架', { req: 'POST /api/v1/ops/catalog/products/{id}/status PUBLISH', expect: 'PUBLISHED' }, product.detail.product.status === 'PUBLISHED', product.detail.product.status);
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('App 商城列表能看到上架的商品', { req: `GET /api/v1/mall/products?cityCode=${CITY}&keyword=${TAG}`, expect: '含上架商品' }, has(items(r), 'productId', product.productId), items(r).map((x) => x.name));
  step('App 商城列表看不到草稿商品', { req: '同上', expect: '不含草稿' }, !has(items(r), 'productId', draft.productId), items(r).map((x) => x.name));
  r = await call('GET', `/api/v1/mall/products/${draft.productId}?cityCode=${CITY}`);
  step('草稿商品详情打不开', { req: `GET /api/v1/mall/products/{草稿}`, expect: '404' }, r.status === 404, r.status);
  r = await call('GET', `/api/v1/mall/products/${product.productId}?cityCode=${CITY}`);
  const offer = r.json?.offers?.find((o) => o.offerId === product.offerId);
  step('商品详情带本机构的在售供给，可在线付款', { req: `GET /api/v1/mall/products/{id}?cityCode=${CITY}`, expect: '供给价格 199900、onlinePayment' },
    r.status === 200 && offer?.amountMinor === 199900 && offer.onlinePayment === true && offer.merchant.organizationId === org.orgId, offer);

  // ---- 专业服务：上架前看不到，上架后看得到；服务商主页的“资质已审核” ----
  const svc = await fx.createProService(op, { orgId: org.orgId, cityCode: CITY, title: `${TAG}园区航拍`, publish: false });
  r = await call('GET', `/api/v1/pro-services?type=AERIAL&cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('未上架的专业服务 App 看不到', { req: `GET /api/v1/pro-services?keyword=${TAG}`, expect: '不含' }, r.status === 200 && !has(items(r), 'serviceId', svc.serviceId), items(r).map((x) => x.title));
  const published = fx.must(await call('POST', `/api/v1/ops/pro-services/${svc.serviceId}/publish`, { token: op, body: { version: svc.svc.version } }), '上架专业服务');
  r = await call('GET', `/api/v1/pro-services?type=AERIAL&cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('上架后的专业服务 App 能看到', { req: '同上', expect: '含' }, has(items(r), 'serviceId', svc.serviceId), items(r).map((x) => x.title));
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  const page = await call('GET', `/api/v1/providers/${org.orgId}?cityCode=${CITY}`);
  const workbench = await call('GET', '/api/v1/provider/profile', { token: operator.token });
  known('S6-01', '运营端新入驻的服务商在 App 上有服务商行和服务商主页、能在工作台提交服务商资料',
    { req: 'GET /api/v1/pro-services/{id}、GET /api/v1/providers/{org}、GET /api/v1/provider/profile（机构操作员）', expect: 'provider 不为 null；主页 200；工作台 200' },
    r.json?.provider && page.status === 200 && workbench.status === 200,
    { provider: r.json?.provider ?? null, providerPage: page.status, workbenchProfile: workbench.status });
  // 绕过 S6-01：照导入模板 06-inspection-provider-profiles.csv 的结果补一行服务商资料（运营端现在没有入口、导入只能预检）。
  psql(`INSERT INTO inspection_provider_profile (provider_id, logo_asset_code, certification_label, team_description, status)
        VALUES ('${org.orgId}', 'PROVIDER_INITIAL', '企业认证', 'S6 演练团队', 'ACTIVE')`);
  note('S6-01 绕过：按导入模板 06 表的结果用 SQL 补了 inspection_provider_profile 一行，后面的步骤才能看“资质已审核”');
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('资质未审核：服务详情的服务商不显示“资质已审核”', { req: 'GET /api/v1/pro-services/{id}', expect: 'provider.qualificationReviewed=false' }, r.status === 200 && r.json.provider?.qualificationReviewed === false, [r.status, r.json?.provider]);

  const approved = await fx.approveQualification(op, org.orgId);
  step('上传资质附件后审核通过', { req: 'POST …/attachments + …/qualification/review APPROVE', expect: '附件 201，审核后 APPROVED、有审核时间' },
    approved.attached.status === 201 && approved.detail.qualification.status === 'APPROVED' && approved.detail.organization.qualificationReviewedAt, approved.detail.qualification);
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('审核通过后服务详情的服务商显示“资质已审核”', { req: 'GET /api/v1/pro-services/{id}', expect: 'provider.qualificationReviewed=true' }, r.json?.provider?.qualificationReviewed === true, r.json?.provider);
  const resubmitted = await fx.submitQualification(op, org.orgId, approved.detail.qualification.version);
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('资料改动后回到待审核，“资质已审核”先撤下', { req: 'PUT …/qualification 再 GET /api/v1/pro-services/{id}', expect: 'SUBMITTED，qualificationReviewed=false' },
    resubmitted.qualification.status === 'SUBMITTED' && r.json?.provider?.qualificationReviewed === false, [resubmitted.qualification.status, r.json?.provider]);
  const again = fx.must(await call('POST', `/api/v1/ops/organizations/${org.orgId}/qualification/review`, { token: op, body: { decision: 'APPROVE', version: resubmitted.qualification.version } }), '再次审核');
  step('再次审核通过', { req: 'review APPROVE', expect: 'APPROVED' }, again.qualification.status === 'APPROVED', again.qualification.status);

  // ---- 活动 ----
  const start = Date.now() + 3 * 86_400_000;
  const iso = (ms) => new Date(ms).toISOString();
  const activity = await fx.createActivity(op, { orgId: org.orgId, cityCode: CITY, title: `${TAG}研学`, priceMinor: 9900, coverUrl: opsMediaUrl,
    sessions: [{ startsAt: iso(start), endsAt: iso(start + 2 * 3600_000), checkInOpensAt: iso(start - 3600_000), enrollmentClosesAt: iso(start - 86_400_000), capacity: 10 }] });
  step('活动和场次上线', { req: 'POST /api/v1/ops/activities + sessions + publish', expect: 'PUBLISHED' }, activity.act.status === 'PUBLISHED', [activity.act.status, activity.act.publishMissing]);
  r = await call('GET', `/api/v1/activities?cityCode=${CITY}&size=50`);
  step('App 赛事研学列表能看到上线的活动', { req: `GET /api/v1/activities?cityCode=${CITY}`, expect: '含' }, has(items(r), 'activityId', activity.activityId), items(r).length);
  r = await call('GET', `/api/v1/activities?cityCode=370300&size=50`);
  step('别的城市看不到这个活动', { req: 'GET /api/v1/activities?cityCode=370300', expect: '不含' }, r.status === 200 && !has(items(r), 'activityId', activity.activityId), items(r).length);

  // ---- 场馆 ----
  const venue = await fx.createVenue(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}体验馆`, coverUrl: opsMediaUrl, priceMinor: 2900,
    fromDate: beijingDate(1), toDate: beijingDate(2), startTimes: ['10:00', '15:00'] });
  step('场馆、项目、场次上线', { req: 'POST /api/v1/ops/venues + projects + sessions/generate + publish', expect: 'PUBLISHED，生成 4 场' },
    venue.venue.status === 'PUBLISHED', { status: venue.venue.status, generated: venue.generated });
  r = await call('GET', `/api/v1/venues?cityCode=${CITY}&size=50`);
  step('App 体验频道能看到上线的场馆', { req: `GET /api/v1/venues?cityCode=${CITY}`, expect: '含' }, has(items(r), 'venueId', venue.venueId), items(r).length);

  // ---- 下架后看不到 ----
  let p = fx.must(await call('GET', `/api/v1/ops/catalog/products/${product.productId}`, { token: op }), '读商品');
  fx.must(await call('POST', `/api/v1/ops/catalog/products/${product.productId}/status`, { token: op, body: { action: 'UNPUBLISH', version: p.product.version } }), '下架商品');
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  const detailAfter = await call('GET', `/api/v1/mall/products/${product.productId}?cityCode=${CITY}`);
  step('下架的商品列表和详情都看不到', { req: 'UNPUBLISH 后 GET 列表和详情', expect: '列表不含，详情 404' }, !has(items(r), 'productId', product.productId) && detailAfter.status === 404, [items(r).length, detailAfter.status]);

  fx.must(await call('POST', `/api/v1/ops/pro-services/${svc.serviceId}/offline`, { token: op, body: { version: published.version } }), '下架专业服务');
  r = await call('GET', `/api/v1/pro-services?type=AERIAL&cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  const svcDetail = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('下架的专业服务列表和详情都看不到', { req: 'offline 后 GET 列表和详情', expect: '列表不含，详情 404' }, !has(items(r), 'serviceId', svc.serviceId) && svcDetail.status === 404, [items(r).length, svcDetail.status]);

  const actNow = fx.must(await call('GET', `/api/v1/ops/activities/${activity.activityId}`, { token: op }), '读活动');
  fx.must(await call('POST', `/api/v1/ops/activities/${activity.activityId}/offline`, { token: op, body: { version: actNow.version } }), '下线活动');
  r = await call('GET', `/api/v1/activities?cityCode=${CITY}&size=50`);
  const actDetail = await call('GET', `/api/v1/activities/${activity.activityId}?cityCode=${CITY}`);
  step('下线的活动列表看不到、详情不能报名', { req: 'offline 后 GET 列表和详情', expect: '列表不含；详情 404 或不可报名' },
    !has(items(r), 'activityId', activity.activityId) && (actDetail.status === 404 || actDetail.json?.sessions?.every((s) => s.status !== 'OPEN')), [actDetail.status, actDetail.json?.status]);

  const venueNow = fx.must(await call('GET', `/api/v1/ops/venues/${venue.venueId}`, { token: op }), '读场馆');
  fx.must(await call('POST', `/api/v1/ops/venues/${venue.venueId}/offline`, { token: op, body: { version: venueNow.version, note: 'S6 演练结束' } }), '下线场馆');
  r = await call('GET', `/api/v1/venues?cityCode=${CITY}&size=50`);
  const venueDetail = await call('GET', `/api/v1/venues/${venue.venueId}?cityCode=${CITY}`);
  step('下线的场馆列表和详情都看不到', { req: 'offline 后 GET 列表和详情', expect: '列表不含，详情 404' }, !has(items(r), 'venueId', venue.venueId) && venueDetail.status === 404, [items(r).length, venueDetail.status]);

  // ---- 留痕 ----
  const orgDetail = fx.must(await call('GET', `/api/v1/ops/organizations/${org.orgId}`, { token: op }), '读机构');
  const actions = orgDetail.operations.map((o) => o.action);
  step('机构的每一步都有操作记录', { req: `GET /api/v1/ops/organizations/{org}`, expect: '新建、资质、附件、审核、账号都有记录' }, actions.length >= 6, actions);
  p = fx.must(await call('GET', `/api/v1/ops/catalog/products/${product.productId}`, { token: op }), '读商品');
  const pActions = p.operations.map((o) => o.action);
  step('商品的上下架、启用供给都有操作记录', { req: 'GET /api/v1/ops/catalog/products/{id}', expect: '含 PRODUCT_PUBLISH、PRODUCT_UNPUBLISH、OFFER_ACTIVATE' },
    ['PRODUCT_PUBLISH', 'PRODUCT_UNPUBLISH', 'OFFER_ACTIVATE'].every((a) => pActions.includes(a)), pActions);
  note(`演练机构 ${org.orgId}，商品 ${product.productId}，服务 ${svc.serviceId}，活动 ${activity.activityId}，场馆 ${venue.venueId}`);
}

const crypto = require('node:crypto');
main().then(() => done(() => purgeAndReport())(), (error) => done(() => purgeAndReport())(error));
