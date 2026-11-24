#!/usr/bin/env node
'use strict';

// S6 场景 1 资源与授权：运营端经 ops 接口开机构、录资质并审核、登记收款账户、建机构账号、上传素材、上架商品 / 专业服务 /
// 活动 / 场馆；资质审核前上架一律被拒、App 看不到，审核通过后上架可见，撤回（改资料回到待审核）和驳回后不可见，再审核通过自动恢复
// （身份迁移 V013）；草稿和下架的看不到；资质审核通过后开通服务商资料（S6-01）、机构在工作台提交主页资料并由平台审核；
// 客户、平台客服不能用运营接口。
// 无授权素材不进公开构建由 apps/mobile 的 verify:public-production-preview 检查（见测试报告，不在这个脚本里跑）。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/01-resources.cjs
// 跑完删掉本脚本建的全部数据并查残留（--keep 保留）。

const { call, login, step, known, note, track, purgeAndReport, loginOperator, upload, PNG, beijingDate, done } = require('./lib.cjs');
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

  // ---- 资质审核前：草稿能准备，上架（上线）一律被拒，App 看不到 ----
  const draft = await fx.createProduct(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}草稿机`, priceMinor: 99900, coverUrl: cover.url, publish: false });
  const product = await fx.createProduct(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}航拍机`, priceMinor: 199900, coverUrl: cover.url, publish: false });
  const svc = await fx.createProService(op, { orgId: org.orgId, cityCode: CITY, title: `${TAG}园区航拍`, publish: false });
  const start = Date.now() + 3 * 86_400_000;
  const iso = (ms) => new Date(ms).toISOString();
  const activity = await fx.createActivity(op, { orgId: org.orgId, cityCode: CITY, title: `${TAG}研学`, priceMinor: 9900, coverUrl: opsMediaUrl, publish: false,
    sessions: [{ startsAt: iso(start), endsAt: iso(start + 2 * 3600_000), checkInOpensAt: iso(start - 3600_000), enrollmentClosesAt: iso(start - 86_400_000), capacity: 10 }] });
  const venue = await fx.createVenue(op, { orgId: org.orgId, cityCode: CITY, name: `${TAG}体验馆`, coverUrl: opsMediaUrl, priceMinor: 2900, publish: false,
    fromDate: beijingDate(1), toDate: beijingDate(2), startTimes: ['10:00', '15:00'] });
  const publishing = {
    product: () => call('POST', `/api/v1/ops/catalog/products/${product.productId}/status`, { token: op, body: { action: 'PUBLISH', version: product.detail.product.version } }),
    service: () => call('POST', `/api/v1/ops/pro-services/${svc.serviceId}/publish`, { token: op, body: { version: svc.svc.version } }),
    activity: () => call('POST', `/api/v1/ops/activities/${activity.activityId}/publish`, { token: op, body: { version: activity.act.version } }),
    venue: () => call('POST', `/api/v1/ops/venues/${venue.venueId}/publish`, { token: op, body: { version: venue.venue.version, note: 'S6 演练' } }),
  };
  /** App 公开接口现在能看到哪几样：列表和详情都看得到才算可见，都看不到才算不可见，不一致返回 MIXED。 */
  async function visible() {
    const q = `cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`;
    const [pl, pd, sl, sd, al, ad, vl, vd] = await Promise.all([
      call('GET', `/api/v1/mall/products?${q}`), call('GET', `/api/v1/mall/products/${product.productId}?cityCode=${CITY}`),
      call('GET', `/api/v1/pro-services?type=AERIAL&${q}`), call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`),
      call('GET', `/api/v1/activities?cityCode=${CITY}&size=50`), call('GET', `/api/v1/activities/${activity.activityId}?cityCode=${CITY}`),
      call('GET', `/api/v1/venues?cityCode=${CITY}&size=50`), call('GET', `/api/v1/venues/${venue.venueId}?cityCode=${CITY}`)]);
    const both = (list, field, id, detail) => {
      const listed = list.status === 200 && has(items(list), field, id);
      if (listed && detail.status === 200) return 'VISIBLE';
      return !listed && list.status === 200 && detail.status === 404 ? 'HIDDEN' : `MIXED(${list.status},${listed},${detail.status})`;
    };
    return { product: both(pl, 'productId', product.productId, pd), service: both(sl, 'serviceId', svc.serviceId, sd),
      activity: both(al, 'activityId', activity.activityId, ad), venue: both(vl, 'venueId', venue.venueId, vd) };
  }
  const all = (state, value) => Object.values(state).every((x) => x === value);
  const EXPECTED = { product: 'OPS_MERCHANT_NOT_QUALIFIED', service: 'OPS_PROVIDER_NOT_QUALIFIED',
    activity: 'OPS_ACTIVITY_ORGANIZER_NOT_QUALIFIED', venue: 'OPS_VENUE_OPERATOR_NOT_QUALIFIED' };
  const refused = {};
  for (const [kind, publish] of Object.entries(publishing)) {
    const answer = await publish();
    refused[kind] = [answer.status, answer.json?.code];
  }
  step('资质审核前：商品、专业服务、活动、场馆上架（上线）都被拒', { req: '平台运营代上架未审核机构的四样', expect: '409 + …_NOT_QUALIFIED' },
    Object.entries(refused).every(([kind, [status, code]]) => status === 409 && code === EXPECTED[kind]), refused);
  let seen = await visible();
  step('资质审核前：App 列表和详情都看不到', { req: 'GET 商城 / 专业服务 / 活动 / 场馆 的列表和详情', expect: '都 HIDDEN' }, all(seen, 'HIDDEN'), seen);
  r = await call('POST', `/api/v1/ops/organizations/${org.orgId}/provider-profile`, { token: op });
  step('资质审核通过前不能开通服务商资料', { req: 'POST /api/v1/ops/organizations/{org}/provider-profile（资质待审核）', expect: '409 OPS_QUALIFICATION_NOT_APPROVED' },
    r.status === 409 && r.json?.code === 'OPS_QUALIFICATION_NOT_APPROVED', [r.status, r.json?.code]);

  // ---- 审核通过：能上架，App 可见 ----
  const approved = await fx.approveQualification(op, org.orgId);
  step('上传资质附件后审核通过', { req: 'POST …/attachments + …/qualification/review APPROVE', expect: '附件 201，审核后 APPROVED、有审核时间' },
    approved.attached.status === 201 && approved.detail.qualification.status === 'APPROVED' && approved.detail.organization.qualificationReviewedAt, approved.detail.qualification);
  step('审核通过后机构详情显示“服务商资料未开通”', { req: 'GET /api/v1/ops/organizations/{org}', expect: 'providerProfileStatus=NOT_OPENED' },
    approved.detail.providerProfileStatus === 'NOT_OPENED', approved.detail.providerProfileStatus);
  const listed = {};
  for (const [kind, publish] of Object.entries(publishing)) listed[kind] = fx.must(await publish(), `上架 ${kind}`);
  step('审核通过后四样都能上架（上线）', { req: '同样的四个上架请求', expect: '商品、服务、活动、场馆都 PUBLISHED' },
    listed.product.product.status === 'PUBLISHED' && listed.service.status === 'PUBLISHED' && listed.activity.status === 'PUBLISHED' && listed.venue.status === 'PUBLISHED',
    [listed.product.product.status, listed.service.status, listed.activity.status, listed.venue.status]);
  seen = await visible();
  step('审核通过并上架后：App 列表和详情都看得到', { req: 'GET 四样的列表和详情', expect: '都 VISIBLE' }, all(seen, 'VISIBLE'), seen);
  r = await call('GET', `/api/v1/mall/products?cityCode=${CITY}&keyword=${encodeURIComponent(TAG)}&size=20`);
  step('App 商城列表看不到草稿商品', { req: `GET /api/v1/mall/products?cityCode=${CITY}&keyword=${TAG}`, expect: '不含草稿' }, !has(items(r), 'productId', draft.productId), items(r).map((x) => x.name));
  r = await call('GET', `/api/v1/mall/products/${draft.productId}?cityCode=${CITY}`);
  step('草稿商品详情打不开', { req: `GET /api/v1/mall/products/{草稿}`, expect: '404' }, r.status === 404, r.status);
  r = await call('GET', `/api/v1/mall/products/${product.productId}?cityCode=${CITY}`);
  const offer = r.json?.offers?.find((o) => o.offerId === product.offerId);
  step('商品详情带本机构的在售供给，可在线付款', { req: `GET /api/v1/mall/products/{id}?cityCode=${CITY}`, expect: '供给价格 199900、onlinePayment' },
    r.status === 200 && offer?.amountMinor === 199900 && offer.onlinePayment === true && offer.merchant.organizationId === org.orgId, offer);
  r = await call('GET', '/api/v1/activities?cityCode=370300&size=50');
  step('别的城市看不到这个活动', { req: 'GET /api/v1/activities?cityCode=370300', expect: '不含' }, r.status === 200 && !has(items(r), 'activityId', activity.activityId), items(r).length);
  step('场馆按周模板生成 4 场', { req: 'POST /api/v1/ops/venues/{id}/projects/{id}/sessions/generate（2 天 × 2 个时间）', expect: '生成 4 场' }, venue.generated?.createdCount === 4, venue.generated);

  // ---- 服务商资料（S6-01）：资质审核通过后运营端开通，机构在工作台提交主页资料，平台审核 ----
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('没开通服务商资料：服务详情没有服务商行', { req: 'GET /api/v1/pro-services/{id}', expect: 'provider=null' }, r.status === 200 && r.json.provider === null, r.json?.provider);
  const opened = await fx.openProviderProfile(op, org.orgId);
  const openRecords = (opened.again.json?.operations ?? []).filter((o) => o.action === 'PROVIDER_PROFILE_OPEN').length;
  step('运营端开通服务商资料，重复调用幂等', { req: 'POST /api/v1/ops/organizations/{org}/provider-profile ×2', expect: '都是 200、ACTIVE；操作记录只有一条 PROVIDER_PROFILE_OPEN' },
    opened.first.status === 200 && opened.again.status === 200 && opened.detail.providerProfileStatus === 'ACTIVE'
      && opened.again.json?.providerProfileStatus === 'ACTIVE' && openRecords === 1,
    { first: opened.first.status, again: opened.again.status, status: opened.again.json?.providerProfileStatus, openRecords });

  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  let page = await call('GET', `/api/v1/providers/${org.orgId}?cityCode=${CITY}`);
  let workbench = await call('GET', '/api/v1/provider/profile', { token: operator.token });
  known('S6-01', '运营端新入驻的服务商在 App 上有服务商行和服务商主页、能在工作台提交服务商资料',
    { req: 'GET /api/v1/pro-services/{id}、GET /api/v1/providers/{org}、GET /api/v1/provider/profile（机构操作员）', expect: 'provider 不为 null；主页 200；工作台 200' },
    r.json?.provider && page.status === 200 && workbench.status === 200,
    { provider: r.json?.provider ?? null, providerPage: page.status, workbenchProfile: workbench.status });
  step('审核通过后服务详情的服务商显示“资质已审核”', { req: 'GET /api/v1/pro-services/{id}', expect: 'provider.qualificationReviewed=true' }, r.json?.provider?.qualificationReviewed === true, r.json?.provider);
  const invitable = async () => {
    const answer = await call('GET', `/api/v1/demand-providers?cityCode=${CITY}`, { token: customer });
    return answer.status === 200 && JSON.stringify(answer.json ?? '').includes(org.orgId);
  };
  step('需求发布能邀请这家服务商', { req: `GET /api/v1/demand-providers?cityCode=${CITY}`, expect: '含本机构' }, await invitable(), null);

  // 城市开通模板第 6 步：机构在工作台提交主页资料，平台运营审核通过后 App 主页显示。
  const profileDraft = { legalEntity: 'S6 演练经营主体（示例）', serviceModes: '无人机航拍 · 巡检', authorizationUntil: null,
    deliverables: ['航拍成片', '巡检报告'], localTeamLabel: '东营本地团队', version: workbench.json?.version ?? 0 };
  const submitted = await call('PUT', '/api/v1/provider/profile', { token: operator.token, body: profileDraft });
  const review = await call('POST', `/api/v1/ops/provider-profiles/${org.orgId}/review`, { token: op, body: { decision: 'APPROVE', version: submitted.json?.version } });
  page = await call('GET', `/api/v1/providers/${org.orgId}?cityCode=${CITY}`);
  step('机构在工作台提交服务商资料，平台审核通过后 App 主页显示', { req: 'PUT /api/v1/provider/profile → POST /api/v1/ops/provider-profiles/{org}/review APPROVE → GET /api/v1/providers/{org}', expect: 'PENDING → APPROVED，主页 profile.legalEntity 为提交的经营主体' },
    submitted.status === 200 && submitted.json?.status === 'PENDING' && review.status === 200 && review.json?.status === 'APPROVED'
      && page.json?.profile?.legalEntity === profileDraft.legalEntity,
    { submitted: [submitted.status, submitted.json?.status], review: [review.status, review.json?.status], profile: page.json?.profile ?? null });

  // ---- 资质撤回（改资料回到待审核）、驳回：已上架的也不可见；再审核通过自动恢复 ----
  r = await call('POST', '/api/v1/mall/cart/items', { token: customer, body: { offerId: product.offerId, quantity: 1, cityCode: CITY } });
  track(r.json?.cartItemId);
  step('可见时客户能把商品加入购物车', { req: 'POST /api/v1/mall/cart/items', expect: '200/201' }, [200, 201].includes(r.status), [r.status, r.json]);
  const resubmitted = await fx.submitQualification(op, org.orgId, approved.detail.qualification.version);
  seen = await visible();
  const pageAfter = await call('GET', `/api/v1/providers/${org.orgId}?cityCode=${CITY}`);
  const shop = await call('GET', `/api/v1/mall/merchants/${org.orgId}?cityCode=${CITY}`);
  step('改资料回到待审核后：已上架的四样、服务商主页、商家主页在 App 都不可见', { req: 'PUT …/qualification 再 GET 列表、详情和主页', expect: 'SUBMITTED，都 HIDDEN，两个主页 404' },
    resubmitted.qualification.status === 'SUBMITTED' && all(seen, 'HIDDEN') && pageAfter.status === 404 && shop.status === 404, { ...seen, providerPage: pageAfter.status, merchantPage: shop.status });
  const cart = await call('GET', `/api/v1/mall/cart?cityCode=${CITY}`, { token: customer });
  const inCart = (cart.json?.invalidItems ?? []).find((x) => x.offerId === product.offerId);
  const again2 = await call('POST', '/api/v1/mall/cart/items', { token: customer, body: { offerId: product.offerId, quantity: 1, cityCode: CITY } });
  step('待审核时购物车里这一行失效，也不能再加购', { req: 'GET /api/v1/mall/cart；POST …/cart/items', expect: '在 invalidItems 里；加购 4xx' }, inCart && again2.status >= 400 && again2.status < 500, { invalid: inCart?.invalidReason, add: [again2.status, again2.json?.code] });
  step('待审核时需求不能邀请这家服务商', { req: `GET /api/v1/demand-providers?cityCode=${CITY}`, expect: '不含本机构' }, !(await invitable()), null);
  const rejected = fx.must(await call('POST', `/api/v1/ops/organizations/${org.orgId}/qualification/review`, { token: op, body: { decision: 'REJECT', reason: 'S6 演练：资料不清', version: resubmitted.qualification.version } }), '驳回');
  seen = await visible();
  step('审核驳回后仍不可见', { req: 'review REJECT 再 GET 四样', expect: 'REJECTED，都 HIDDEN' }, rejected.qualification.status === 'REJECTED' && all(seen, 'HIDDEN'), seen);
  const fixedUp = await fx.submitQualification(op, org.orgId, rejected.qualification.version);
  const again = fx.must(await call('POST', `/api/v1/ops/organizations/${org.orgId}/qualification/review`, { token: op, body: { decision: 'APPROVE', version: fixedUp.qualification.version } }), '再次审核');
  seen = await visible();
  step('补资料再次审核通过后自动恢复可见，不用重新上架', { req: 'review APPROVE 再 GET 四样', expect: 'APPROVED，都 VISIBLE' }, again.qualification.status === 'APPROVED' && all(seen, 'VISIBLE'), seen);
  const cartBack = await call('GET', `/api/v1/mall/cart?cityCode=${CITY}`, { token: customer });
  step('恢复后购物车里这一行重新有效', { req: 'GET /api/v1/mall/cart', expect: '不在 invalidItems 里' },
    cartBack.status === 200 && !(cartBack.json?.invalidItems ?? []).some((x) => x.offerId === product.offerId) && JSON.stringify(cartBack.json?.groups ?? '').includes(product.offerId), cartBack.status);
  r = await call('GET', `/api/v1/pro-services/${svc.serviceId}?cityCode=${CITY}`);
  step('恢复后服务详情的服务商又显示“资质已审核”', { req: 'GET /api/v1/pro-services/{id}', expect: 'qualificationReviewed=true' }, r.json?.provider?.qualificationReviewed === true, r.json?.provider);
  const published = fx.must(await call('GET', `/api/v1/ops/pro-services/${svc.serviceId}`, { token: op }), '读专业服务');

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
