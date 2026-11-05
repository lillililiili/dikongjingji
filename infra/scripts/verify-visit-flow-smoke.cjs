#!/usr/bin/env node
'use strict';

// 多次履约服务端冒烟：只走 API，不走页面，覆盖 6 次和 1 次两种服务单。
// 用 Node 写是因为 macOS 开发机没有 pwsh；F05-F07 的 .ps1 脚本是 Windows 上的等价验收。
//
// 只在一次性本地环境运行：脚本会留下真实预约、方案和服务单。
//   BASE_URL              后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//   PG_CONTAINER          可选，Postgres 容器名；设置后写入 F04 种子并核对数据库留痕
//   PG_USER / PG_DB       PG_CONTAINER 对应的账号和库，默认 inspection
//   EXPECT_PAYMENT_NODES  后端开启 INSPECTION_PAYMENT_NODES_ENABLED 时设为 1

const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const PG_CONTAINER = process.env.PG_CONTAINER || '';
const PG_USER = process.env.PG_USER || 'inspection';
const PG_DB = process.env.PG_DB || 'inspection';
const EXPECT_PAYMENT_NODES = process.env.EXPECT_PAYMENT_NODES === '1';

const SERVICE_ID = '33333333-3333-3333-3333-333333333333';
const PROVIDER_ID = '22222222-2222-2222-2222-222222222222';
const CONTACT_MOBILE = '13812346621';
const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const ZIP_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]);

const uuid = () => crypto.randomUUID();
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const isoDate = (date) => date.toISOString().slice(0, 10);
const daysFromNow = (days) => new Date(Date.now() + days * 86_400_000);
// 预约只能选 30 天内的日期，每个时段容量 9，随机避开已约满的时段。
const nextBookableDay = () => 8 + Math.floor(Math.random() * 20);

let checkCount = 0;

function check(condition, message) {
  if (!condition) {
    throw new Error(`检查失败：${message}`);
  }
  checkCount += 1;
}

async function call(method, route, { token, key, ifMatch, body, rawBody } = {}) {
  const headers = { 'X-Request-Id': `visit-smoke-${uuid()}` };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`;
  if (body !== undefined || rawBody !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(BASE_URL + route, {
    method,
    headers,
    body: rawBody ?? (body === undefined ? undefined : JSON.stringify(body)),
  });
  const text = await response.text();
  return {
    status: response.status,
    etag: response.headers.get('etag'),
    json: text ? JSON.parse(text) : null,
  };
}

async function ok(method, route, options) {
  const result = await call(method, route, options);
  if (result.status >= 300) {
    throw new Error(`${method} ${route} 返回 ${result.status}：${JSON.stringify(result.json)}`);
  }
  return result.json;
}

async function login(loginName) {
  const result = await call('POST', '/api/v1/auth/login', {
    body: { loginName, password: 'test-password' },
  });
  if (result.status !== 200) {
    throw new Error(`${loginName} 无法登录。设置 PG_CONTAINER 写入 F04 种子后重试。`);
  }
  return result.json.accessToken;
}

function psql(sql) {
  return execFileSync(
    'docker',
    ['exec', '-i', PG_CONTAINER, 'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', PG_USER, '-d', PG_DB],
    { input: sql, encoding: 'utf8' },
  ).trim();
}

function seedSmokeData() {
  const f04 = fs.readFileSync(path.join(__dirname, 'verify-f04-reservation-smoke.ps1'), 'utf8');
  const seed = /\$seedSql = @'\r?\n([\s\S]*?)\r?\n'@/.exec(f04);
  if (!seed) {
    throw new Error('没有在 verify-f04-reservation-smoke.ps1 里找到种子 SQL。');
  }
  psql(seed[1]);
}

function deliveryFixtures(visitNo) {
  const marker = Buffer.from(`visit-${visitNo}-${uuid()}`);
  return [
    {
      type: 'IMAGE_ARCHIVE',
      name: `visit-${visitNo}-images.zip`,
      contentType: 'application/zip',
      bytes: Buffer.concat([ZIP_MAGIC, marker]),
      itemCount: 36,
    },
    {
      type: 'DEFECT_LIST',
      name: `visit-${visitNo}-defects.xlsx`,
      contentType: XLSX_TYPE,
      bytes: Buffer.concat([ZIP_MAGIC, marker]),
      itemCount: 3,
    },
    {
      type: 'POINT_KML',
      name: `visit-${visitNo}-points.kml`,
      contentType: 'application/vnd.google-earth.kml+xml',
      bytes: Buffer.concat([Buffer.from('<?xml version="1.0"?><kml/>'), marker]),
      pointCount: 24,
    },
    {
      type: 'INSPECTION_REPORT',
      name: `visit-${visitNo}-report.pdf`,
      contentType: 'application/pdf',
      bytes: Buffer.concat([Buffer.from('%PDF-1.4\n'), marker]),
    },
  ];
}

async function uploadDeliveryFile(token, visitId, fixture) {
  const digest = sha256(fixture.bytes);
  const intent = await ok('POST', '/api/v1/inspection/file-objects/upload-intents', {
    token,
    key: uuid(),
    body: {
      purpose: 'DELIVERY_ARTIFACT',
      ownerId: visitId,
      deliveryFileType: fixture.type,
      originalName: fixture.name,
      contentType: fixture.contentType,
      sizeBytes: fixture.bytes.length,
      sha256: digest,
    },
  });
  const uploaded = await fetch(intent.uploadUrl, {
    method: 'PUT',
    body: fixture.bytes,
    headers: { 'Content-Type': fixture.contentType },
  });
  check(uploaded.ok, `${fixture.name} 写入对象存储`);
  const ready = await ok('POST', `/api/v1/inspection/file-objects/${intent.fileId}/complete`, {
    token,
    key: uuid(),
    body: { etag: `visit-smoke-${intent.fileId}`, sizeBytes: fixture.bytes.length, sha256: digest },
  });
  check(ready.status === 'READY', `${fixture.name} 校验后可用`);
  return {
    fileId: intent.fileId,
    type: fixture.type,
    displayName: fixture.name,
    pointCount: fixture.pointCount ?? null,
    itemCount: fixture.itemCount ?? null,
  };
}

async function download(url) {
  const response = await fetch(url);
  check(response.ok, '受控下载地址可用');
  return Buffer.from(await response.arrayBuffer());
}

function zipEntryCount(bytes) {
  let entries = 0;
  for (let offset = 0; offset + 4 <= bytes.length; offset += 1) {
    if (bytes.readUInt32LE(offset) === 0x02014b50) entries += 1;
  }
  return entries;
}

function createActors(tokens) {
  const { customer, other, provider, platform } = tokens;
  const orderRoute = (orderId) => `/api/v1/inspection/service-orders/${orderId}`;
  const customerVisit = (orderId, visitId) => `${orderRoute(orderId)}/visits/${visitId}`;
  const providerVisit = (orderId, visitId) =>
    `/api/v1/provider/inspection/service-orders/${orderId}/visits/${visitId}`;
  const progressBody = (eventCode) => ({
    eventCode,
    occurredAt: new Date(Date.now() + 1_000).toISOString(),
    message: eventCode,
  });

  async function readOrder(orderId) {
    return ok('GET', orderRoute(orderId), { token: customer });
  }

  async function listRow(reservationId) {
    const list = await ok('GET', '/api/v1/inspection/reservations?page=1&size=50&stage=ALL', {
      token: customer,
    });
    const row = list.items.find((item) => item.reservationId === reservationId);
    check(row, `订单列表包含预约 ${reservationId}`);
    return row;
  }

  async function schedule(orderId, visit) {
    const scheduled = await ok('PUT', `${providerVisit(orderId, visit.id)}/schedule`, {
      token: provider,
      key: uuid(),
      ifMatch: visit.version,
      body: { plannedStartAt: `${visit.plannedDate}T01:00:00Z` },
    });
    check(scheduled.status === 'SCHEDULED', `第 ${visit.visitNo} 次已排期`);
    return scheduled;
  }

  async function progress(orderId, visit, eventCode) {
    return ok('POST', `${providerVisit(orderId, visit.id)}/progress-events`, {
      token: provider,
      key: uuid(),
      ifMatch: visit.version,
      body: progressBody(eventCode),
    });
  }

  function publishBody(files) {
    return {
      inspectedLengthMeters: 18_600,
      metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 420000, unit: '㎡' }],
      issueCount: 3,
      issueGroupBy: 'CATEGORY',
      issueSummary: [
        { groupCode: 'CRACK', groupName: '墙体裂缝', count: 2 },
        { groupCode: 'DRAIN', groupName: '排水堵塞', count: 1 },
      ],
      files,
    };
  }

  async function publishDelivery(orderId, visit, fixtures) {
    const files = [];
    for (const fixture of fixtures) {
      files.push(await uploadDeliveryFile(provider, visit.id, fixture));
    }
    const delivery = await ok('POST', `${providerVisit(orderId, visit.id)}/delivery`, {
      token: provider,
      key: uuid(),
      ifMatch: visit.version,
      body: publishBody(files),
    });
    check(delivery.status === 'PUBLISHED', `第 ${visit.visitNo} 次交付已发布`);
    check(delivery.visitId === visit.id, `第 ${visit.visitNo} 次交付挂在巡检次上`);
    return { delivery, files };
  }

  async function accept(orderId, visit, version) {
    return ok('POST', `${customerVisit(orderId, visit.id)}/acceptance`, {
      token: customer,
      key: uuid(),
      ifMatch: version,
      body: { confirmed: true, note: `第 ${visit.visitNo} 次验收` },
    });
  }

  return {
    customer,
    other,
    provider,
    platform,
    orderRoute,
    customerVisit,
    providerVisit,
    progressBody,
    readOrder,
    listRow,
    schedule,
    progress,
    publishBody,
    publishDelivery,
    accept,
  };
}

async function createReservation(token, preferredServiceDate) {
  return ok('POST', '/api/v1/inspection/reservations', {
    token,
    key: uuid(),
    body: {
      serviceId: SERVICE_ID,
      cityCode: '370500',
      location: { address: 'Dongying visit smoke line', longitude: 118.5, latitude: 37.4 },
      lineLengthMeters: 18_600,
      preferredServiceDate,
      preferredTimeSlotCode: 'MORNING',
      inspectionItemCodes: ['POWER_LINE', 'TOWER'],
      attachmentFileIds: [],
      note: 'visit flow smoke',
      contact: { name: 'Zhang', mobile: '13800000000' },
      agreementVersion: '2026-07',
      agreementAccepted: true,
    },
  });
}

async function createConfirmedOrder(actors, visitDates) {
  const { customer, other, provider, platform } = actors;
  const reservation = await createReservation(customer, visitDates[0]);

  const publishBody = (summary, changeNote) => ({
    providerId: PROVIDER_ID,
    validUntil: daysFromNow(2).toISOString(),
    visits: visitDates.map((plannedDate) => ({ plannedDate })),
    summary,
    scopeSummary: '线路约 18.6 公里',
    serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
    deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
    changeNote,
    planItems: [
      { code: 'POWER_LINE', name: '线路本体巡检', quantity: 24, unit: 'tower', unitPriceMinor: 69_900 },
    ],
    deliverables: [
      { code: 'HD_MEDIA', name: '高清影像', fileType: 'IMAGE_ARCHIVE' },
      { code: 'DEFECT_LIST', name: '缺陷清单', fileType: 'DEFECT_LIST' },
      { code: 'POINT_MARKS', name: '点位标注', fileType: 'POINT_KML' },
      { code: 'REPORT', name: '巡检报告', fileType: 'INSPECTION_REPORT' },
    ],
    currency: 'CNY',
  });
  const planRoute = `/api/v1/provider/inspection/reservations/${reservation.id}/plans`;

  const nullVisit = await call('POST', planRoute, {
    token: provider,
    key: uuid(),
    ifMatch: 0,
    body: { ...publishBody('空日期', null), visits: [null] },
  });
  check(nullVisit.status === 400, `方案日期里有 null 时返回 400，实际 ${nullVisit.status}`);

  const publishKey = uuid();
  const firstBody = publishBody('第一版方案', null);
  const first = await ok('POST', planRoute, {
    token: provider, key: publishKey, ifMatch: 0, body: firstBody,
  });
  const replay = await ok('POST', planRoute, {
    token: provider, key: publishKey, ifMatch: 0, body: firstBody,
  });
  check(replay.id === first.id, '方案发布重放返回同一方案');
  const reused = await call('POST', planRoute, {
    token: provider,
    key: publishKey,
    ifMatch: 1,
    body: { ...firstBody, summary: '换了内容的方案' },
  });
  check(reused.status === 409, `幂等键换内容被拒绝，实际 ${reused.status}`);

  const plan = await ok('POST', planRoute, {
    token: provider,
    key: uuid(),
    ifMatch: 1,
    body: publishBody('第二版方案', '调整巡检范围'),
  });
  check(plan.planVersion === 2, '第二版方案版本号为 2');
  check(plan.visits.length === visitDates.length, '方案记录全部巡检次');
  check(plan.serviceDate === visitDates[0], `serviceDate 是首次日期，实际 ${plan.serviceDate}`);
  check(plan.visits.at(-1).plannedDate === visitDates.at(-1), '末次日期按 ISO 日期返回');
  check(plan.deliveryTerms.reportDueHours === 48, '方案返回交付约定');
  check(plan.deliverables[3].fileType === 'INSPECTION_REPORT', '交付物带文件类型');

  if (EXPECT_PAYMENT_NODES) {
    const nodes = plan.paymentNodes;
    const total = nodes.reduce((sum, node) => sum + node.amountMinor, 0);
    check(nodes.length === visitDates.length + 1, '付款节点 = 预付款 + 每次一笔');
    check(nodes[0].nodeType === 'DOWN_PAYMENT', '第一笔是预付款');
    check(total === plan.totalAmountMinor, '付款节点合计等于方案总价');
    check(
      nodes[0].amountMinor === Math.round(plan.totalAmountMinor * 0.3),
      '预付款是总价的 30%，四舍五入到分',
    );
  } else {
    check(plan.paymentNodes.length === 0, '付款节点开关关闭时方案不带节点');
  }

  const versions = await ok('GET', `/api/v1/inspection/reservations/${reservation.id}/plans`, {
    token: customer,
  });
  check(versions.length === 2, '客户能看到两版方案');
  check(versions.some((item) => item.changeNote === '调整巡检范围'), '方案版本带变更说明');
  const hiddenVersions = await call('GET', `/api/v1/inspection/reservations/${reservation.id}/plans`, {
    token: other,
  });
  check(hiddenVersions.status === 404, '其他客户看不到方案版本');

  const confirmBody = { agreementVersion: 'inspection-plan-v2', agreementAccepted: true };
  const confirmKey = uuid();
  if (actors.usedConfirmKey) {
    const reused = await call('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
      token: customer, key: actors.usedConfirmKey, ifMatch: 0, body: confirmBody,
    });
    check(reused.status === 409, `确认幂等键不能跨方案复用，实际 ${reused.status}`);
    check(reused.json.code === 'INS_IDEMPOTENCY_KEY_REUSED', '跨方案复用返回幂等键冲突');
  }
  actors.usedConfirmKey = confirmKey;
  const platformConfirm = await call('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
    token: platform, key: uuid(), ifMatch: 0, body: confirmBody,
  });
  check(platformConfirm.status === 403, '只读平台账号不能确认方案');
  const order = await ok('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
    token: customer, key: confirmKey, ifMatch: 0, body: confirmBody,
  });
  const confirmReplay = await ok('POST', `/api/v1/inspection/plans/${plan.id}/confirm`, {
    token: customer, key: confirmKey, ifMatch: 0, body: confirmBody,
  });
  check(confirmReplay.id === order.id, '方案确认重放返回同一服务单');
  check(order.status === 'ARRANGING', '确认后服务单待安排');
  check(order.visits.length === visitDates.length, '服务单复制全部巡检次');
  check(order.visits.every((visit) => visit.status === 'PENDING_SCHEDULE'), '巡检次待排期');
  check(order.visits.every((visit) => visit.id !== order.id), '新巡检次有自己的 ID');
  check(order.paymentMode === (EXPECT_PAYMENT_NODES ? 'NODES' : 'NONE'), '付款模式跟随开关');
  if (EXPECT_PAYMENT_NODES) {
    check(order.paymentNodes.length === visitDates.length + 1, '服务单复制付款节点');
    check(order.paymentNodes[0].payable === true, '预付款可支付');
    check(order.paymentNodes.slice(1).every((node) => node.payable === false), '未交付的次款不可支付');
  }
  return { reservation, plan, order };
}

async function arrange(actors, order) {
  const { provider, customer } = actors;
  const route = `/api/v1/provider/inspection/service-orders/${order.id}/arrangement`;
  const body = {
    assignments: [
      {
        type: 'TEAM_MEMBER',
        resourceId: '77777777-7777-4777-8777-777777777701',
        displayName: '东营巡检班组',
        roleLabel: '项目负责人',
        contactMobile: CONTACT_MOBILE,
      },
      {
        type: 'DEVICE',
        resourceId: '77777777-7777-4777-8777-777777777702',
        displayName: 'M350 RTK + H30T',
        roleLabel: null,
      },
    ],
  };
  if (actors.usedArrangeKey) {
    const reused = await call('PUT', route, {
      token: provider, key: actors.usedArrangeKey, ifMatch: 0, body,
    });
    check(reused.status === 409, `安排幂等键不能跨服务单复用，实际 ${reused.status}`);
    check(reused.json.code === 'INS_IDEMPOTENCY_KEY_REUSED', '跨服务单复用返回幂等键冲突');
  }
  const key = uuid();
  actors.usedArrangeKey = key;
  const arranged = await ok('PUT', route, { token: provider, key, ifMatch: 0, body });
  const replay = await ok('PUT', route, { token: provider, key, ifMatch: 0, body });
  check(arranged.version === 1 && replay.version === 1, '安排重放不再次递增版本');
  const customerView = await ok('GET', actors.orderRoute(order.id), { token: customer });
  const contact = customerView.assignments.find((item) => item.type === 'TEAM_MEMBER');
  check(contact.contactMobileMasked === '138****6621', '客户只看到脱敏手机号');
  check(!JSON.stringify(customerView).includes(CONTACT_MOBILE), '响应里没有明文手机号');
  return customerView;
}

async function runSixVisitOrder(actors) {
  const { customer, other, provider, platform } = actors;
  const start = nextBookableDay();
  const dates = [0, 14, 28, 42, 56, 70].map((offset) => isoDate(daysFromNow(start + offset)));
  const { reservation, order: confirmed } = await createConfirmedOrder(actors, dates);
  const orderId = confirmed.id;
  let order = await arrange(actors, confirmed);
  const [first, second, third] = order.visits;

  const firstSchedule = await actors.schedule(orderId, first);
  const scheduledSecond = await actors.schedule(orderId, second);

  // 服务商改排期不需要客户确认，开始之后就不能再改。
  const movedDate = isoDate(daysFromNow(start + 2));
  const scheduledFirst = await ok('PUT', `${actors.providerVisit(orderId, first.id)}/schedule`, {
    token: provider,
    key: uuid(),
    ifMatch: firstSchedule.version,
    body: { plannedStartAt: `${movedDate}T03:00:00Z` },
  });
  check(scheduledFirst.status === 'SCHEDULED', '改排期后仍是已排期');
  check(scheduledFirst.plannedStartAt.startsWith(`${movedDate}T03:00`), '改排期写入新时间');
  const rescheduledRow = await actors.listRow(reservation.id);
  check(rescheduledRow.nextVisitDate === movedDate, `列表下次日期跟随改期，实际 ${rescheduledRow.nextVisitDate}`);

  const unscheduledStart = await call('POST', `${actors.providerVisit(orderId, third.id)}/progress-events`, {
    token: provider, key: uuid(), ifMatch: third.version, body: actors.progressBody('SERVICE_STARTED'),
  });
  check(unscheduledStart.status === 409, `未排期不能开始，实际 ${unscheduledStart.status}`);
  check(unscheduledStart.json.code === 'INS_VISIT_STATE_INVALID', '未排期返回巡检次状态错误');

  const outOfOrder = await call('POST', `${actors.providerVisit(orderId, second.id)}/progress-events`, {
    token: provider, key: uuid(), ifMatch: scheduledSecond.version, body: actors.progressBody('SERVICE_STARTED'),
  });
  check(outOfOrder.status === 409, `不能跳过上一次，实际 ${outOfOrder.status}`);
  check(outOfOrder.json.code === 'INS_VISIT_OUT_OF_ORDER', '跳次返回顺序错误');

  const jump = await call('POST', `${actors.providerVisit(orderId, first.id)}/progress-events`, {
    token: provider, key: uuid(), ifMatch: scheduledFirst.version, body: actors.progressBody('SERVICE_COMPLETED'),
  });
  check(jump.status === 409, `未开始不能完成，实际 ${jump.status}`);

  const earlyUpload = await call('POST', '/api/v1/inspection/file-objects/upload-intents', {
    token: provider,
    key: uuid(),
    body: {
      purpose: 'DELIVERY_ARTIFACT',
      ownerId: first.id,
      deliveryFileType: 'INSPECTION_REPORT',
      originalName: 'early.pdf',
      contentType: 'application/pdf',
      sizeBytes: 16,
      sha256: sha256(Buffer.from('early')),
    },
  });
  check(earlyUpload.status === 409, `巡检次未开始不能上传，实际 ${earlyUpload.status}`);

  const platformWrite = await call('POST', `${actors.providerVisit(orderId, first.id)}/progress-events`, {
    token: platform, key: uuid(), ifMatch: scheduledFirst.version, body: actors.progressBody('SERVICE_STARTED'),
  });
  check(platformWrite.status === 403, '只读平台账号不能推进履约');

  const startKey = uuid();
  const startBody = actors.progressBody('SERVICE_STARTED');
  const startRoute = `${actors.providerVisit(orderId, first.id)}/progress-events`;
  let visitOne = await ok('POST', startRoute, {
    token: provider, key: startKey, ifMatch: scheduledFirst.version, body: startBody,
  });
  const startReplay = await ok('POST', startRoute, {
    token: provider, key: startKey, ifMatch: scheduledFirst.version, body: startBody,
  });
  check(startReplay.version === visitOne.version, '开始重放不再次递增版本');
  order = await actors.readOrder(orderId);
  check(order.status === 'IN_PROGRESS', '首次开始后服务单进入服务中');

  visitOne = await actors.progress(orderId, visitOne, 'SERVICE_PROGRESS');
  visitOne = await actors.progress(orderId, visitOne, 'SERVICE_COMPLETED');
  check(visitOne.status === 'IN_PROGRESS', '现场完成后巡检次仍待交付');
  const lateSchedule = await call('PUT', `${actors.providerVisit(orderId, first.id)}/schedule`, {
    token: provider,
    key: uuid(),
    ifMatch: visitOne.version,
    body: { plannedStartAt: `${first.plannedDate}T04:00:00Z` },
  });
  check(lateSchedule.status === 409, `已开始不能改排期，实际 ${lateSchedule.status}`);

  const backdated = await call('POST', `${actors.providerVisit(orderId, second.id)}/progress-events`, {
    token: provider,
    key: uuid(),
    ifMatch: scheduledSecond.version,
    body: { ...actors.progressBody('SERVICE_STARTED'), occurredAt: daysFromNow(-1).toISOString() },
  });
  check(backdated.status === 409, `下一次不能早于上一次完成，实际 ${backdated.status}`);
  check(backdated.json.code === 'INS_VISIT_STATE_INVALID', '时间倒退返回巡检次状态错误');

  // 上一次现场完成即可开始下一次，不等交付和验收。
  const visitTwo = await actors.progress(orderId, scheduledSecond, 'SERVICE_STARTED');
  check(visitTwo.status === 'IN_PROGRESS', '上次现场完成后可以开始下一次');

  const deliveryRoute0 = `${actors.providerVisit(orderId, first.id)}/delivery`;
  const invalidPublish = [
    ['缺 files', JSON.stringify({ ...actors.publishBody([]), files: undefined })],
    ['files 含 null', JSON.stringify(actors.publishBody([null]))],
    ['metrics 含 null', JSON.stringify({ ...actors.publishBody([]), metrics: [null] })],
    ['issueSummary 含 null', JSON.stringify({ ...actors.publishBody([]), issueSummary: [null] })],
    ['指标值超大', JSON.stringify(actors.publishBody([])).replace('420000', '1e200000')],
    ['指标值极大', JSON.stringify(actors.publishBody([])).replace('420000', '1e999999999')],
  ];
  for (const [label, rawBody] of invalidPublish) {
    const rejected = await call('POST', deliveryRoute0, {
      token: provider, key: uuid(), ifMatch: visitOne.version, rawBody,
    });
    check(rejected.status === 422, `${label} 返回 422，实际 ${rejected.status}`);
    check(rejected.json.code === 'INS_DELIVERY_FILES_INVALID', `${label} 返回交付文件错误`);
  }
  const unfinished = await call('POST', `${actors.providerVisit(orderId, second.id)}/delivery`, {
    token: provider,
    key: uuid(),
    ifMatch: (await actors.readOrder(orderId)).visits[1].version,
    body: actors.publishBody([]),
  });
  check(unfinished.status === 409, `现场未完成不能发布交付，实际 ${unfinished.status}`);
  check(unfinished.json.code === 'INS_DELIVERY_STATE_INVALID', '现场未完成返回交付状态错误');

  // 新预约比这张服务单晚，交付之后服务单仍要排到它前面。
  const newerReservation = await createReservation(customer, isoDate(daysFromNow(nextBookableDay())));
  const firstFixtures = deliveryFixtures(1);
  const { delivery: firstDelivery, files: firstFiles } =
    await actors.publishDelivery(orderId, visitOne, firstFixtures);
  const orderedIds = (await ok('GET', '/api/v1/inspection/reservations?page=1&size=50&stage=ALL', {
    token: customer,
  })).items.map((item) => item.reservationId);
  check(
    orderedIds.indexOf(reservation.id) < orderedIds.indexOf(newerReservation.id),
    '巡检次交付后服务单在列表里上浮',
  );

  order = await actors.readOrder(orderId);
  check(order.status === 'IN_PROGRESS', '只交付一次时服务单仍在服务中');
  let row = await actors.listRow(reservation.id);
  check(row.stage === 'AWAITING_ACCEPTANCE', `有待验收的次时阶段为待验收，实际 ${row.stage}`);
  check(row.pendingAcceptanceVisitNo === 1, '列表指向第 1 次待验收');
  check(row.pendingAcceptanceVisitId === first.id, '列表带待验收巡检次 ID');
  check(row.visitCount === 6 && row.acceptedVisitCount === 0, '列表带巡检次进度');

  const deliveryRoute = `${actors.customerVisit(orderId, first.id)}/delivery`;
  const customerDelivery = await ok('GET', deliveryRoute, { token: customer });
  check(customerDelivery.id === firstDelivery.id, '客户读到本次交付');
  check(customerDelivery.issueGroupBy === 'CATEGORY', '问题汇总按类别分组');
  check(customerDelivery.issueSummary[0].groupName === '墙体裂缝', '问题汇总分组名原样返回');
  check(customerDelivery.metrics[0].code === 'COVERED_AREA', '交付带指标');
  check(customerDelivery.files[0].itemCount === 36, '交付文件带数量');
  check((await call('GET', deliveryRoute, { token: other })).status === 404, '其他客户看不到交付');
  const notDelivered = await call('GET', `${actors.customerVisit(orderId, second.id)}/delivery`, {
    token: customer,
  });
  check(notDelivered.status === 404, '未交付的巡检次没有交付');

  const report = firstFiles.find((file) => file.type === 'INSPECTION_REPORT');
  const fileRoute = `${actors.customerVisit(orderId, first.id)}/delivery-files/${report.fileId}/download-intent`;
  const intent = await ok('POST', fileRoute, { token: customer, key: uuid() });
  const reportBytes = await download(intent.downloadUrl);
  check(
    sha256(reportBytes) === sha256(firstFixtures.find((item) => item.type === 'INSPECTION_REPORT').bytes),
    '下载到的是本次上传的报告',
  );
  const wrongVisit = await call(
    'POST',
    `${actors.customerVisit(orderId, second.id)}/delivery-files/${report.fileId}/download-intent`,
    { token: customer, key: uuid() },
  );
  check(wrongVisit.status === 404, '换巡检次不能下载别的交付文件');
  const bundle = await ok(
    'POST',
    `${actors.customerVisit(orderId, first.id)}/delivery-files/download-all-intent`,
    { token: customer, key: uuid() },
  );
  check(zipEntryCount(await download(bundle.downloadUrl)) === 4, '打包下载含 4 个文件');
  await ok('POST', fileRoute, { token: platform, key: uuid() });

  const deliveredVisitOne = (await actors.readOrder(orderId)).visits[0];
  const platformAccept = await call('POST', `${actors.customerVisit(orderId, first.id)}/acceptance`, {
    token: platform, key: uuid(), ifMatch: deliveredVisitOne.version, body: { confirmed: true },
  });
  check(platformAccept.status === 403, '只读平台账号不能验收');
  const staleAccept = await call('POST', `${actors.customerVisit(orderId, first.id)}/acceptance`, {
    token: customer, key: uuid(), ifMatch: deliveredVisitOne.version + 5, body: { confirmed: true },
  });
  check(staleAccept.status === 412 || staleAccept.status === 409, `旧版本验收被拒绝，实际 ${staleAccept.status}`);

  const acceptKey = uuid();
  const acceptBody = { confirmed: true, note: '第 1 次验收' };
  const acceptRoute = `${actors.customerVisit(orderId, first.id)}/acceptance`;
  const accepted = await ok('POST', acceptRoute, {
    token: customer, key: acceptKey, ifMatch: deliveredVisitOne.version, body: acceptBody,
  });
  const acceptReplay = await ok('POST', acceptRoute, {
    token: customer, key: acceptKey, ifMatch: deliveredVisitOne.version, body: acceptBody,
  });
  check(acceptReplay.id === accepted.id, '验收重放返回同一记录');
  check(accepted.visitStatus === 'ACCEPTED', '本次验收通过');
  check(accepted.orderStatus === 'IN_PROGRESS', '只验收一次时服务单仍在服务中');
  if (EXPECT_PAYMENT_NODES) {
    const nodes = (await actors.readOrder(orderId)).paymentNodes;
    check(nodes[1].payable === true, '第 1 次验收后这一次的款可支付');
    check(nodes.slice(2).every((node) => node.payable === false), '未验收的次款不可支付');
  }
  check(typeof accepted.acceptedByName === 'string' && accepted.acceptedByName.length > 0, '验收记录带验收人');

  // 其余 5 次全部交付、先不验收，服务单应在最后一次交付时进入已交付。
  let current = visitTwo;
  for (let visitNo = 2; visitNo <= 6; visitNo += 1) {
    if (visitNo > 2) {
      const pending = (await actors.readOrder(orderId)).visits[visitNo - 1];
      const scheduled = await actors.schedule(orderId, pending);
      current = await actors.progress(orderId, scheduled, 'SERVICE_STARTED');
    }
    current = await actors.progress(orderId, current, 'SERVICE_COMPLETED');
    await actors.publishDelivery(orderId, current, deliveryFixtures(visitNo));
  }
  order = await actors.readOrder(orderId);
  check(order.status === 'DELIVERED', `全部交付后服务单已交付，实际 ${order.status}`);
  check(order.deliveredAt, '服务单记录全部交付时间');
  row = await actors.listRow(reservation.id);
  check(row.stage === 'AWAITING_ACCEPTANCE' && row.pendingAcceptanceVisitNo === 2, '待验收指向最早的未验收次');

  for (let visitNo = 2; visitNo <= 6; visitNo += 1) {
    const visit = (await actors.readOrder(orderId)).visits[visitNo - 1];
    const result = await actors.accept(orderId, visit, visit.version);
    check(
      result.orderStatus === (visitNo === 6 ? 'ACCEPTED' : 'DELIVERED'),
      `第 ${visitNo} 次验收后服务单状态 ${result.orderStatus}`,
    );
  }
  order = await actors.readOrder(orderId);
  check(order.status === 'ACCEPTED' && order.acceptedAt, '全部验收后服务单完成');
  check(order.visits.every((visit) => visit.status === 'ACCEPTED'), '6 次全部已验收');
  row = await actors.listRow(reservation.id);
  check(row.stage === 'COMPLETED', `全部验收后阶段为已完成，实际 ${row.stage}`);
  check(row.acceptedVisitCount === 6 && row.pendingAcceptanceVisitId == null, '列表不再有待验收次');

  return { reservationId: reservation.id, orderId, visits: order.visits.length };
}

async function runSingleVisitOrder(actors) {
  const dates = [isoDate(daysFromNow(nextBookableDay()))];
  const { reservation, order: confirmed } = await createConfirmedOrder(actors, dates);
  const orderId = confirmed.id;
  const order = await arrange(actors, confirmed);
  const scheduled = await actors.schedule(orderId, order.visits[0]);
  let visit = await actors.progress(orderId, scheduled, 'SERVICE_STARTED');
  visit = await actors.progress(orderId, visit, 'SERVICE_COMPLETED');
  await actors.publishDelivery(orderId, visit, deliveryFixtures(1));
  const delivered = await actors.readOrder(orderId);
  check(delivered.status === 'DELIVERED', '单次服务单交付后已交付');
  const result = await actors.accept(orderId, delivered.visits[0], delivered.visits[0].version);
  check(result.orderStatus === 'ACCEPTED', '单次服务单验收后完成');
  const row = await actors.listRow(reservation.id);
  check(row.stage === 'COMPLETED' && row.visitCount === 1, '单次服务单阶段已完成');
  return { reservationId: reservation.id, orderId, visits: 1 };
}

function databaseEvidence(orderId, visits, reschedules) {
  const counts = psql(`
    SELECT
      (SELECT count(*) FROM inspection_service_visit WHERE service_order_id = '${orderId}') || '|' ||
      (SELECT count(*) FROM inspection_delivery WHERE service_order_id = '${orderId}') || '|' ||
      (SELECT count(*) FROM inspection_acceptance WHERE service_order_id = '${orderId}') || '|' ||
      (SELECT count(*) FROM inspection_state_transition t
        JOIN inspection_service_visit v ON v.id = t.aggregate_id
        WHERE t.aggregate_type = 'SERVICE_VISIT' AND v.service_order_id = '${orderId}') || '|' ||
      (SELECT count(*) FROM inspection_domain_event_outbox e
        JOIN inspection_service_visit v ON v.id = e.aggregate_id
        WHERE v.service_order_id = '${orderId}');
  `);
  const [visitRows, deliveries, acceptances, transitions, events] = counts.split('|').map(Number);
  check(visitRows === visits, `数据库巡检次 ${visitRows}`);
  check(deliveries === visits, `数据库交付 ${deliveries}`);
  check(acceptances === visits, `数据库验收 ${acceptances}`);
  // 每次 5 条迁移：创建、排期、开始、交付、验收，每改一次排期多 1 条。
  // 每次至少 3 个事件：排期、开始、完成；发布和验收事件记在交付聚合上。
  check(transitions === visits * 5 + reschedules, `巡检次状态迁移留痕 ${transitions}`);
  check(events >= visits * 3, `巡检次领域事件 ${events}`);
  const mobile = psql(`
    SELECT contact_mobile_masked || '|' || position('${CONTACT_MOBILE}' in encode(contact_mobile_ciphertext, 'escape'))
    FROM inspection_order_assignment
    WHERE service_order_id = '${orderId}' AND contact_mobile_ciphertext IS NOT NULL;
  `);
  check(mobile === '138****6621|0', `手机号只存密文和脱敏值，实际 ${mobile}`);
  return counts;
}

async function main() {
  const baseUrl = new URL(BASE_URL);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(baseUrl.hostname)) {
    throw new Error('冒烟脚本只允许连回环地址。');
  }
  if (PG_CONTAINER) {
    seedSmokeData();
  }
  const actors = createActors({
    customer: await login('smoke-customer'),
    other: await login('smoke-other'),
    provider: await login('smoke-provider'),
    platform: await login('smoke-platform'),
  });

  const six = await runSixVisitOrder(actors);
  const single = await runSingleVisitOrder(actors);
  const evidence = PG_CONTAINER
    ? {
        six: databaseEvidence(six.orderId, 6, 1),
        single: databaseEvidence(single.orderId, 1, 0),
      }
    : '未设置 PG_CONTAINER，跳过数据库核对';

  console.log(JSON.stringify({
    checks: checkCount,
    paymentNodes: EXPECT_PAYMENT_NODES,
    sixVisitOrder: six,
    singleVisitOrder: single,
    databaseEvidence: evidence,
  }, null, 2));
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
