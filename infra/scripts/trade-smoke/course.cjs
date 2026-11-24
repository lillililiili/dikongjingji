#!/usr/bin/env node
'use strict';

// S5-5 课程：列表（线上课全国、线下班按活动和城市、子类目、关键词、价格排序）、详情、试听；购买（金额核对、幂等、
// 已有待付款直接返回、交易订单来源 COURSE、不进商品订单）、付款开通、关闭之后才到账的恢复、学习（播放地址、开始学、播完）、
// 7 天内没开始学的全额退款（含超过 7 天、已开始学的拒绝）、商品附带课程随商城订单完成开通、我的课程、运营端建课到上下架。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；运营端那段要后端启动时设了 DEV_OPERATOR_PASSWORD，脚本也要同一个值（不设就跳过那段）。
// 只对本机运行，跑完清掉 dev-customer 的开通记录、订单和脚本自己建的课程（--keep 保留数据）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> FAKE_WECHAT_URL=http://127.0.0.1:<端口> \
//   DEV_OPERATOR_PASSWORD=<口令> node infra/scripts/trade-smoke/course.cjs

const { offerId, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

const BUYER = '11111111-1111-1111-1111-111111111111';
// 开发种子 R__dev_course.sql。
const FLY = 'd7c00000-0000-4000-8000-000000000001';
const ROBOT = 'd7c00000-0000-4000-8000-000000000002';
const CAMP = 'd7c00000-0000-4000-8000-000000000003';
const CERT = 'd7c00000-0000-4000-8000-000000000004';
const HIDDEN_OFFLINE = 'd7c00000-0000-4000-8000-000000000005';
const DRAFT = 'd7c00000-0000-4000-8000-000000000006';
const FLY_PRICE = 19900;
const TRIAL_LESSON = 'd7c20000-0000-4000-8000-000000000101';
const LESSON_3 = 'd7c20000-0000-4000-8000-000000000103';
const EXPERIENCE_TRIAL = 'b1000000-0000-4000-8000-000000000005';
const ROBOT_KIT_OFFER = offerId('101', '1');

const createdCourses = new Set();
const keep = process.argv.includes('--keep');

const enroll = (token, courseId, expectedAmountMinor, idempotencyKey = key()) =>
  call('POST', '/api/v1/course-enrollments', { token, headers: { 'Idempotency-Key': idempotencyKey }, body: { courseId, expectedAmountMinor } });
const launch = (token, orderId) =>
  call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const enrollmentStatus = (id) => psql(`SELECT status FROM course_enrollment WHERE id='${id}'`);
const read = (token, id) => call('GET', `/api/v1/course-enrollments/${id}`, { token });
const playback = (token, id, lesson) => call('POST', `/api/v1/course-enrollments/${id}/lessons/${lesson}/playback`, { token });
const complete = (token, id, lesson) => call('POST', `/api/v1/course-enrollments/${id}/lessons/${lesson}/completion`, { token });
const refund = (token, id) => call('POST', `/api/v1/course-enrollments/${id}/refund`, { token });

/** 下单并用模拟微信付款（带回调），等到开通。 */
async function paidEnrollment(customer) {
  const r = await enroll(customer, FLY, FLY_PRICE);
  const enrollment = r.json;
  const orderId = enrollment.payment.tradeOrderId;
  await launch(customer, orderId);
  await fake('POST', `/_control/pay/${outTradeNo(orderId)}?notify=1`);
  await waitFor(() => enrollmentStatus(enrollment.enrollmentId) === 'ACTIVE', 15);
  return { enrollmentId: enrollment.enrollmentId, orderId };
}

function cleanup() {
  if (keep) return;
  psql(`
    BEGIN;
    DELETE FROM course_lesson_progress WHERE enrollment_id IN (SELECT id FROM course_enrollment WHERE account_id = '${BUYER}');
    DELETE FROM course_enrollment_event WHERE enrollment_id IN (SELECT id FROM course_enrollment WHERE account_id = '${BUYER}');
    DELETE FROM course_enrollment WHERE account_id = '${BUYER}';
    COMMIT;
  `);
  const ids = [...createdCourses].map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      DELETE FROM course_ops_change WHERE course_id IN (${ids});
      DELETE FROM course_bundle WHERE course_id IN (${ids});
      DELETE FROM course_lesson WHERE course_id IN (${ids});
      DELETE FROM course_chapter WHERE course_id IN (${ids});
      DELETE FROM course WHERE id IN (${ids});
      COMMIT;
    `);
  }
  cleanupBuyer();
}

async function catalog() {
  let r = await call('GET', '/api/v1/courses?cityCode=370500&page=1&size=20');
  const ids = (r.json?.items ?? []).map((c) => c.courseId);
  const card = (id) => r.json.items.find((c) => c.courseId === id);
  check('list shows the online course with its price and lessons', card(FLY)?.mode === 'ONLINE' && card(FLY).price.amountMinor === FLY_PRICE && card(FLY).lessonCount === 12 && card(FLY).activityId === null, card(FLY));
  check('offline classes take title, price and city from their activities', card(CAMP)?.mode === 'OFFLINE' && card(CAMP).title === '机器人研学体验' && card(CAMP).price.mode === 'FIXED' && card(CAMP).price.amountMinor === 16800 && card(CAMP).activityId === 'a2000000-0000-4000-8000-000000000002' && card(CAMP).cityCode === '370500' && card(CAMP).scheduleLabel === '按场次', card(CAMP));
  check('an inquiry activity shows as price on request', card(CERT)?.price.mode === 'INQUIRY' && card(CERT).price.amountMinor === null, card(CERT));
  check('bundle-only, draft and offline-without-activity courses are not listed', !ids.includes(ROBOT) && !ids.includes(DRAFT) && !ids.includes(HIDDEN_OFFLINE), ids);
  r = await call('GET', '/api/v1/courses?cityCode=110100');
  check('another city sees only the online course', (r.json?.items ?? []).map((c) => c.courseId).join() === FLY, r.json);
  r = await call('GET', '/api/v1/courses?cityCode=370500&topic=CERTIFICATE');
  check('topic filter', (r.json?.items ?? []).map((c) => c.courseId).join() === CERT, r.json);
  r = await call('GET', `/api/v1/courses?cityCode=370500&keyword=${encodeURIComponent('航线')}`);
  check('keyword matches the subtitle', (r.json?.items ?? []).map((c) => c.courseId).join() === FLY, r.json);
  r = await call('GET', '/api/v1/courses?cityCode=370500&sort=PRICE_DESC');
  check('price sort puts unpriced cards last', (r.json?.items ?? []).map((c) => c.courseId).join() === [FLY, CAMP, CERT].join(), r.json?.items?.map((c) => c.courseId));
  check('bad topic is 400', (await call('GET', '/api/v1/courses?cityCode=370500&topic=COOKING')).status === 400);

  r = await call('GET', `/api/v1/courses/${FLY}`);
  check('detail has chapters, trial lessons and online payment', r.status === 200 && r.json.chapters.length === 4 && r.json.lessonCount === 12 && r.json.trialLessonCount === 2 && r.json.purchasable === true && r.json.validityDays === 365 && r.json.organization.name === '示例培训机构' && r.json.experienceItemId === EXPERIENCE_TRIAL, r.json);
  check('lessons are numbered across chapters', r.json?.chapters?.[1]?.lessons?.[0]?.no === 4);
  r = await call('GET', `/api/v1/courses/${ROBOT}`);
  check('a bundle-only course has no price and cannot be bought', r.status === 200 && r.json.price === null && r.json.purchasable === false, r.json);
  check('offline classes and drafts have no course detail', (await call('GET', `/api/v1/courses/${CAMP}`)).status === 404 && (await call('GET', `/api/v1/courses/${DRAFT}`)).status === 404);

  r = await call('GET', `/api/v1/course-trials?experienceItemId=${EXPERIENCE_TRIAL}`);
  check('trial by the experience entry gives the two trial lessons', r.status === 200 && r.json.courseId === FLY && r.json.lessons.length === 2 && r.json.lessons[0].lessonId === TRIAL_LESSON && r.json.lessons[0].playUrl === null, r.json);
  check('trial needs exactly one of the two ids', (await call('GET', '/api/v1/course-trials')).status === 400);
}

async function purchaseAndRestore(customer) {
  check('buying needs a login', (await enroll(null, FLY, FLY_PRICE)).status === 401);
  let r = await call('GET', `/api/v1/course-enrollments?bucket=ALL`, { token: customer });
  check('my courses offer the trial before buying', r.json?.items?.length === 0 && r.json.trials.some((t) => t.courseId === FLY && t.trialLessonCount === 2), r.json);
  r = await enroll(customer, FLY, FLY_PRICE + 100);
  check('a stale price is refused', r.status === 409 && r.json.code === 'COURSE_AMOUNT_CHANGED', r.json);
  r = await enroll(customer, ROBOT, 100);
  check('a bundle-only course cannot be bought', r.status === 409 && r.json.code === 'COURSE_NOT_PURCHASABLE', r.json);
  const k = key();
  r = await enroll(customer, FLY, FLY_PRICE, k);
  const first = r.json;
  check('purchase creates a pending enrollment and a trade order', r.status === 201 && first.status === 'PENDING_PAYMENT' && first.source === 'PURCHASE' && first.payment.amountMinor === FLY_PRICE && first.refundability.refundable === false, first);
  r = await enroll(customer, FLY, FLY_PRICE, k);
  check('replaying the key returns the same enrollment', r.status === 200 && r.json.enrollmentId === first.enrollmentId);
  r = await enroll(customer, FLY, FLY_PRICE);
  check('a second purchase returns the pending one', r.status === 200 && r.json.enrollmentId === first.enrollmentId);
  r = await call('GET', `/api/v1/courses/${FLY}/me`, { token: customer });
  check('the detail page sees the pending payment', r.json?.status === 'PENDING_PAYMENT' && r.json.tradeOrderId === first.payment.tradeOrderId, r.json);
  const orderId = first.payment.tradeOrderId;
  r = await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer });
  check('trade order is a course order', r.json?.source === 'COURSE' && r.json.sourceRef === first.enrollmentId && r.json.orderNo.startsWith('CO') && r.json.delivery === null && r.json.lines[0].skuName === '单人账号' && r.json.merchant.name === '示例培训机构', r.json);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods orders leave course orders out', !(r.json.items ?? []).some((o) => o.orderId === orderId));
  check('a pending enrollment cannot play', (await playback(customer, first.enrollmentId, TRIAL_LESSON)).json?.code === 'COURSE_NOT_LEARNABLE');

  // 先试“取消未付款”：交易模块先查单再关单，开通记录随之关闭。
  const cancelled = (await enroll(customer, FLY, FLY_PRICE)).json;
  check('the pending one comes back while it is open', cancelled.enrollmentId === first.enrollmentId);
  await launch(customer, orderId);
  const order = (await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer })).json;
  r = await call('POST', `/api/v1/trade/orders/${orderId}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': `"${order.version}"` } });
  check('cancelling the unpaid order closes the enrollment', r.status === 200 && enrollmentStatus(first.enrollmentId) === 'CLOSED', r.json);

  // 再买一张，模拟“超时关单但渠道那边还开着”（只改库，和 venue.cjs 一样），付款之后才到账：没有别的开通时恢复。
  const late = (await enroll(customer, FLY, FLY_PRICE)).json;
  check('after closing a new purchase gets a new enrollment', late.enrollmentId !== first.enrollmentId && late.status === 'PENDING_PAYMENT', late);
  const lateOrder = late.payment.tradeOrderId;
  await launch(customer, lateOrder);
  psql(`
    BEGIN;
    UPDATE trade_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${lateOrder}';
    UPDATE trade_payment SET status = 'CLOSED', updated_at = now() WHERE order_id = '${lateOrder}' AND status IN ('CREATED', 'PREPAID');
    INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, occurred_at)
      VALUES ('${lateOrder}', 'PENDING_PAYMENT', 'CLOSED', 'TIMEOUT', now());
    UPDATE course_enrollment SET status = 'CLOSED', version = version + 1 WHERE id = '${late.enrollmentId}';
    COMMIT;
  `);
  await fake('POST', `/_control/pay/${outTradeNo(lateOrder)}?notify=1`);
  await waitFor(() => enrollmentStatus(late.enrollmentId) === 'ACTIVE', 15);
  first.enrollmentId = late.enrollmentId;
  r = await read(customer, first.enrollmentId);
  check('a payment after closing restores the course', r.json?.status === 'ACTIVE' && r.json.activatedAt && r.json.expiresAt && r.json.refundability.refundable === true, r.json);
  const days = (new Date(r.json.expiresAt) - new Date(r.json.activatedAt)) / 86_400_000;
  check('the course is open for 365 days', Math.round(days) === 365, days);
  r = await enroll(customer, FLY, FLY_PRICE);
  check('an open course cannot be bought again', r.status === 409 && r.json.code === 'COURSE_ALREADY_OWNED', r.json);

  // 没开始学：全额退款，课程关闭；交易订单整单退款。
  r = await refund(customer, first.enrollmentId);
  check('refund within 7 days before learning', r.status === 200 && r.json.status === 'REFUNDED' && r.json.refund.amountMinor === FLY_PRICE, r.json);
  check('refund replay changes nothing', (await refund(customer, first.enrollmentId)).json?.status === 'REFUNDED');
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${lateOrder}' ORDER BY created_at DESC LIMIT 1`);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  await waitFor(() => tradeStatus(lateOrder) === 'REFUNDED', 15);
  r = await read(customer, first.enrollmentId);
  check('the refund reaches the buyer', r.json?.refund?.status === 'SUCCEEDED' && tradeStatus(lateOrder) === 'REFUNDED', [r.json?.refund, tradeStatus(lateOrder)]);
  check('a refunded course cannot play', (await playback(customer, first.enrollmentId, TRIAL_LESSON)).json?.code === 'COURSE_NOT_LEARNABLE');
}

async function learning(customer) {
  const { enrollmentId, orderId } = await paidEnrollment(customer);
  check('buying again after a refund opens the course', enrollmentStatus(enrollmentId) === 'ACTIVE' && tradeStatus(orderId) === 'PAID');
  // 付款超过 7 天：不能在线退。
  psql(`UPDATE course_enrollment SET paid_at = paid_at - interval '8 days' WHERE id='${enrollmentId}'`);
  let r = await refund(customer, enrollmentId);
  check('past 7 days the refund is refused', r.status === 409 && r.json.code === 'COURSE_REFUND_NOT_ALLOWED' && r.json.details?.reason === 'WINDOW_PASSED', r.json);
  psql(`UPDATE course_enrollment SET paid_at = paid_at + interval '8 days' WHERE id='${enrollmentId}'`);

  r = await complete(customer, enrollmentId, TRIAL_LESSON);
  check('a lesson cannot be finished before it was played', r.json?.code === 'COURSE_LESSON_NOT_STARTED', r.json);
  r = await playback(customer, enrollmentId, TRIAL_LESSON);
  check('a trial lesson plays and keeps the refund', r.status === 200 && r.json.playUrl === null && r.json.refundability.refundable === true, r.json);
  r = await complete(customer, enrollmentId, TRIAL_LESSON);
  check('finishing a lesson counts it once', r.json?.completedCount === 1 && r.json.resume.no === 2, r.json);
  check('finishing it again changes nothing', (await complete(customer, enrollmentId, TRIAL_LESSON)).json?.completedCount === 1);
  r = await playback(customer, enrollmentId, LESSON_3);
  check('starting a paid lesson ends the online refund', r.status === 200 && r.json.refundability.refundable === false && r.json.refundability.blockedReason === 'STARTED', r.json);
  r = await refund(customer, enrollmentId);
  check('after starting the refund is refused', r.json?.code === 'COURSE_REFUND_NOT_ALLOWED' && r.json.details?.reason === 'STARTED', r.json);
  r = await read(customer, enrollmentId);
  const states = r.json.chapters.flatMap((c) => c.lessons).slice(0, 4).map((l) => l.state).join();
  check('lesson states follow what was played', states === 'COMPLETED,NOT_STARTED,STARTED,NOT_STARTED' && r.json.resume.no === 3, [states, r.json.resume]);
  check('another account cannot see the enrollment', (await read(await login('dev-provider'), enrollmentId)).status === 403);
  return enrollmentId;
}

async function bundle(customer, merchant) {
  // 买“编程机器人探索套装”（附带编程机器人入门课），到店自提、付款，商家核销取货码后订单完成，课程开通。
  const submitted = await call('POST', '/api/v1/trade/orders', {
    token: customer,
    headers: { 'Idempotency-Key': key() },
    body: { cityCode: '370500', offerId: ROBOT_KIT_OFFER, quantity: 1, deliveryMode: 'PICKUP', agreementAccepted: true, expectedPayableMinor: Number(psql(`SELECT price_amount_minor FROM mall_offer WHERE id='${ROBOT_KIT_OFFER}'`)) },
  });
  check('robot kit order submitted', submitted.status === 201, submitted.json);
  const orderId = submitted.json.orderId;
  await launch(customer, orderId);
  await fake('POST', `/_control/pay/${outTradeNo(orderId)}?notify=1`);
  await waitFor(() => tradeStatus(orderId) === 'PAID', 15);
  let order = (await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer })).json;
  check('nothing is opened before the order completes', psql(`SELECT count(*) FROM course_enrollment WHERE account_id='${BUYER}' AND course_id='${ROBOT}'`) === '0');
  const r = await call('POST', `/api/v1/provider/trade/orders/${orderId}/pickup`, {
    token: merchant, headers: { 'Idempotency-Key': key(), 'If-Match': `"${order.version}"` }, body: { pickupCode: order.delivery.pickupCode },
  });
  check('pickup completes the robot kit order', r.json?.status === 'COMPLETED', r.json);
  const row = psql(`SELECT status || '|' || source || '|' || bundle_order_no || '|' || bundle_sku_name FROM course_enrollment WHERE account_id='${BUYER}' AND course_id='${ROBOT}'`);
  check('the bundled course opens with the order as its source', row.startsWith('ACTIVE|BUNDLE|' + order.orderNo + '|'), row);
  const mine = await call('GET', '/api/v1/course-enrollments?bucket=ALL', { token: customer });
  const card = mine.json?.items?.find((item) => item.courseId === ROBOT);
  check('my courses show the bundled course with its source', card?.source === 'BUNDLE' && card.bundle.orderId === orderId && card.lessonCount === 16 && card.completedCount === 0, card);
  const detail = await read(customer, card.enrollmentId);
  check('a bundled course has no refund of its own', detail.json?.refundability.blockedReason === 'BUNDLED' && detail.json.payment === null, detail.json?.refundability);
  check('trial cards leave out courses already open', !(mine.json.trials ?? []).some((t) => t.courseId === FLY), mine.json.trials);
  const learning = await call('GET', '/api/v1/course-enrollments?bucket=LEARNING', { token: customer });
  check('learning lists both open courses', learning.json?.total === 2, learning.json?.total);
  check('finished lists none', (await call('GET', '/api/v1/course-enrollments?bucket=FINISHED', { token: customer })).json?.total === 0);
  order = (await call('GET', `/api/v1/trade/orders/${orderId}`, { token: customer })).json;
}

async function ops() {
  const password = process.env.DEV_OPERATOR_PASSWORD;
  if (!password) {
    console.log('skip ops part (DEV_OPERATOR_PASSWORD not set)');
    return;
  }
  const operator = await login('dev-operator', password);
  let r = await call('GET', '/api/v1/ops/courses?mode=OFFLINE', { token: operator });
  check('ops list filters by mode', r.status === 200 && r.json.items.every((c) => c.mode === 'OFFLINE') && r.json.total >= 3, r.json);
  r = await call('POST', '/api/v1/ops/courses', { token: operator, body: { mode: 'ONLINE', topic: 'ROBOT', title: '冒烟课程', organizationId: 'a1000000-0000-4000-8000-000000000003' } });
  check('ops creates a draft', r.status === 201 && r.json.status === 'DRAFT' && r.json.version === 0, r.json);
  const id = r.json.courseId;
  createdCourses.add(id);
  const draft = {
    version: 0, topic: 'ROBOT', title: '冒烟课程', subtitle: '冒烟', organizationId: 'a1000000-0000-4000-8000-000000000003',
    coverUrl: null, galleryUrls: [], priceAmountMinor: 9900, validityDays: 30, ageNote: '8 岁以上', instructorName: null, instructorTitle: null,
    includes: [{ name: '录播课程', quantity: 2 }], extraSpecs: [], intro: [], experienceItemId: null, bundledProductIds: [], activityId: null, scheduleLabel: null, sortOrder: 5,
  };
  r = await call('PUT', `/api/v1/ops/courses/${id}`, { token: operator, body: draft });
  check('ops saves the course', r.status === 200 && r.json.version === 1 && r.json.priceAmountMinor === 9900, r.json);
  r = await call('PUT', `/api/v1/ops/courses/${id}`, { token: operator, body: draft });
  check('a stale version is refused', r.json?.code === 'OPS_VERSION_CONFLICT', r.json);
  r = await call('PUT', `/api/v1/ops/courses/${id}`, { token: operator, body: { ...draft, version: 1, experienceItemId: EXPERIENCE_TRIAL } });
  check('an experience entry already used is refused', r.json?.code === 'OPS_COURSE_EXPERIENCE_TAKEN', r.json);
  r = await call('PUT', `/api/v1/ops/courses/${id}/outline`, { token: operator, body: { version: 1, chapters: [{ title: '第 1 章', lessons: [{ title: '第一课', durationSeconds: 600, trial: true }, { title: '第二课', durationSeconds: 700, trial: false }] }] } });
  check('ops saves the outline', r.status === 200 && r.json.lessonCount === 2 && r.json.chapters[0].lessons[1].no === 2, r.json);
  const outline = r.json;
  r = await call('POST', `/api/v1/ops/courses/${id}/list`, { token: operator, body: { version: outline.version } });
  check('listing without a cover and videos is refused with what is missing', r.status === 422 && r.json.code === 'OPS_PUBLISH_INCOMPLETE' && r.json.details.missing.join() === 'coverUrl,videos', r.json);
  r = await call('PUT', `/api/v1/ops/courses/${id}`, { token: operator, body: { ...draft, version: outline.version, coverUrl: 'https://cdn.example.com/course.jpg' } });
  const withCover = r.json;
  const lessons = withCover.chapters[0].lessons;
  r = await call('PUT', `/api/v1/ops/courses/${id}/outline`, { token: operator, body: { version: withCover.version, chapters: [{ chapterId: withCover.chapters[0].chapterId, title: '第 1 章 入门', lessons: lessons.map((l) => ({ lessonId: l.lessonId, title: l.title, durationSeconds: l.durationSeconds, trial: l.trial, videoUrl: `https://cdn.example.com/${l.no}.mp4` })) }] } });
  check('editing the outline keeps the lesson ids', r.status === 200 && r.json.chapters[0].lessons.map((l) => l.lessonId).join() === lessons.map((l) => l.lessonId).join() && r.json.listMissing.length === 0, r.json);
  r = await call('POST', `/api/v1/ops/courses/${id}/list`, { token: operator, body: { version: r.json.version } });
  check('ops lists the course', r.status === 200 && r.json.status === 'LISTED' && r.json.firstListedAt, r.json);
  const listedVersion = r.json.version;
  const resource = '/api/v1/ops/courses/' + id;
  const rejectedEdit = await call('PUT', resource, { token: operator, body: { ...draft, version: listedVersion } });
  check('a listed course keeps its cover', rejectedEdit.status === 422 && rejectedEdit.json?.details?.missing?.includes('coverUrl'), rejectedEdit.json);
  const rejectedOutline = await call('PUT', resource + '/outline', { token: operator, body: { version: listedVersion, chapters: [] } });
  check('a listed course keeps its lessons', rejectedOutline.status === 422 && rejectedOutline.json?.details?.missing?.includes('lessons'), rejectedOutline.json);
  const restored = await call('GET', resource, { token: operator });
  check('rejected edits roll back version, cover, and outline', restored.json.version === listedVersion
    && restored.json.coverUrl === withCover.coverUrl && restored.json.chapters[0].lessons.length === 2, restored.json);
  r = await call('PUT', resource, { token: operator, body: { ...draft, version: listedVersion, coverUrl: withCover.coverUrl, title: '更新后的冒烟课程' } });
  check('a complete listed edit succeeds', r.status === 200 && r.json.version === listedVersion + 1, r.json);
  const listed = await call('GET', '/api/v1/courses?cityCode=370500');
  check('the listed course shows in the app', listed.json.items.some((c) => c.courseId === id));
  const trial = await call('GET', `/api/v1/course-trials?courseId=${id}`);
  check('its trial lesson plays the https video', trial.json?.lessons?.[0]?.playUrl === 'https://cdn.example.com/1.mp4', trial.json);
  r = await call('POST', `/api/v1/ops/courses/${id}/unlist`, { token: operator, body: { version: r.json.version } });
  check('ops unlists the course', r.json?.status === 'UNLISTED' && !(await call('GET', '/api/v1/courses?cityCode=370500')).json.items.some((c) => c.courseId === id), r.json);
  r = await call('GET', `/api/v1/ops/courses/${id}/changes`, { token: operator });
  check('every change is recorded', r.json?.map((c) => c.action).join() === 'UNLIST,UPDATE,LIST,OUTLINE,UPDATE,OUTLINE,UPDATE,CREATE', r.json?.map((c) => c.action));
  check('a customer cannot use ops', (await call('GET', '/api/v1/ops/courses', { token: await login('dev-customer') })).status === 403);
}

async function main() {
  cleanup();
  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  await catalog();
  await purchaseAndRestore(customer);
  await learning(customer);
  await bundle(customer, merchant);
  await ops();
}

main().then(finish(cleanup), finish(cleanup));
