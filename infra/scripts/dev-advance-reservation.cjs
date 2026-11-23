#!/usr/bin/env node
'use strict';

// 本地手工验收用：替服务商一侧把一张巡检预约往前推一步。
// 服务商没有页面（工作台不在范围内），发布方案、安排、排期、履约、交付都只有接口，
// 客户在 App 里提交预约后就卡在“待方案”。这个脚本用开发账号 dev-provider 调这些接口，
// 客户一侧的动作（确认方案、验收）仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-advance-reservation.cjs [订单编号] [--visits N] [--step]
//
//   订单编号   App 里显示的预约编号（IR 开头）；不写就取 dev-customer 最近提交的一张
//   --visits N 发布方案时安排几次巡检，默认 1，最多 12
//   --step     履约阶段一次只做一个动作（安排 → 排期 → 开始 → 进度 → 现场完成 → 交付），
//              方便在 App 的履约页上逐步看变化；不写就一直推到“等你验收”
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 只在本机开发环境运行：账号和密码来自开发种子 R__dev_identity_accounts.sql，
// 脚本会在数据库里留下真实的方案、服务单和交付文件（内容都是示例）。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const PROVIDER_ID = '22222222-2222-2222-2222-222222222222';
const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const DAY_MS = 86_400_000;

const uuid = () => crypto.randomUUID();
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const say = (line) => console.log(line);

class Stop extends Error {}

function parseArguments(argv) {
  const options = { reservationNo: null, visits: 1, step: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--step') {
      options.step = true;
    } else if (argument === '--visits') {
      options.visits = Number(argv[index + 1]);
      index += 1;
    } else if (argument.startsWith('--')) {
      throw new Stop(`不认识的参数 ${argument}`);
    } else {
      options.reservationNo = argument;
    }
  }
  if (!Number.isInteger(options.visits) || options.visits < 1 || options.visits > 12) {
    throw new Stop('--visits 要写 1 到 12 的整数');
  }
  return options;
}

// 账号的密码是公开的，脚本还会写数据，所以只对本机后端运行。
function assertLoopback() {
  const host = new URL(BASE_URL).hostname;
  if (!['127.0.0.1', 'localhost', '[::1]', '::1'].includes(host)) {
    throw new Stop(`BASE_URL 只能是本机地址，现在是 ${host}`);
  }
}

function readDevPassword() {
  const seed = fs.readFileSync(SEED_FILE, 'utf8');
  const match = /crypt\('([^']+)'/.exec(seed);
  if (!match) throw new Stop(`没有在 ${SEED_FILE} 里找到开发账号的密码`);
  return match[1];
}

async function call(method, route, { token, key, ifMatch, body } = {}) {
  const headers = { 'X-Request-Id': `dev-advance-${uuid()}` };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(BASE_URL + route, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Stop(`连不上后端 ${BASE_URL}，先把后端跑起来`);
  }
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

async function ok(method, route, options) {
  const result = await call(method, route, options);
  if (result.status >= 300) {
    const reason = result.json ? `${result.json.code} ${result.json.message}` : '';
    throw new Stop(`${method} ${route} 返回 ${result.status} ${reason}`);
  }
  return result.json;
}

async function login(loginName, password) {
  const result = await call('POST', '/api/v1/auth/login', { body: { loginName, password } });
  if (result.status !== 200) {
    throw new Stop(`${loginName} 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  }
  return result.json.accessToken;
}

// ---- 示例交付文件：都是能正常打开的小文件，内容标明是示例 ----

function zipOf(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBytes = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x0800, 6); // 文件名按 UTF-8
    header.writeUInt32LE(zlib.crc32(data), 14);
    header.writeUInt32LE(data.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt32LE(zlib.crc32(data), 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(header, nameBytes, data);
    centrals.push(central, nameBytes);
    offset += header.length + nameBytes.length + data.length;
  }
  const centralBytes = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBytes, end]);
}

function sampleWorkbook(rows) {
  const cell = (value) => `<c t="inlineStr"><is><t>${value}</t></is></c>`;
  const sheet = rows.map((row) => `<row>${row.map(cell).join('')}</row>`).join('');
  const xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  return zipOf([
    ['[Content_Types].xml', `${xml}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`],
    ['_rels/.rels', `${xml}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
    ['xl/workbook.xml', `${xml}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="缺陷清单" sheetId="1" r:id="rId1"/></sheets></workbook>`],
    ['xl/_rels/workbook.xml.rels', `${xml}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`],
    ['xl/worksheets/sheet1.xml', `${xml}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${sheet}</sheetData></worksheet>`],
  ]);
}

// 一页英文的 PDF：不嵌字体就写不了中文，所以正文用英文。
function samplePdf(lines) {
  const text = lines
    .map((line, index) => `BT /F1 ${index === 0 ? 18 : 12} Tf 56 ${760 - index * 28} Td (${line.replace(/[()\\]/g, '')}) Tj ET`)
    .join('\n');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${Buffer.byteLength(text)} >>\nstream\n${text}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let body = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(body));
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = Buffer.byteLength(body);
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  body += offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}

function sampleKml(location) {
  const points = [0, 1, 2].map((index) => {
    const longitude = (location.longitude + index * 0.004).toFixed(6);
    const latitude = (location.latitude + index * 0.002).toFixed(6);
    return `<Placemark><name>示例点位 ${index + 1}</name><Point><coordinates>${longitude},${latitude},0</coordinates></Point></Placemark>`;
  });
  return Buffer.from(
    `<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>示例点位标注</name>${points.join('')}</Document></kml>`,
    'utf8',
  );
}

function deliveryFiles(reservation, visit) {
  const label = `第${visit.visitNo}次`;
  return [
    {
      type: 'IMAGE_ARCHIVE',
      name: `${label}-巡检影像（示例）.zip`,
      contentType: 'application/zip',
      bytes: zipOf([['说明.txt', `这是开发环境的示例影像包，不含真实影像。\n预约 ${reservation.reservationNo}，${label}巡检。\n`]]),
      itemCount: 1,
    },
    {
      type: 'DEFECT_LIST',
      name: `${label}-缺陷清单（示例）.xlsx`,
      contentType: XLSX_TYPE,
      bytes: sampleWorkbook([
        ['序号', '类别', '位置', '说明'],
        ['1', '设备本体', '示例点位 12', '示例数据：外观轻微破损'],
        ['2', '连接部件', '示例点位 18', '示例数据：紧固件松动'],
        ['3', '周边环境', '示例点位 21-22', '示例数据：遮挡物距离偏小'],
      ]),
      itemCount: 3,
    },
    {
      type: 'POINT_KML',
      name: `${label}-点位标注（示例）.kml`,
      contentType: 'application/vnd.google-earth.kml+xml',
      bytes: sampleKml(reservation.location),
      pointCount: 3,
    },
    {
      type: 'INSPECTION_REPORT',
      name: `${label}-巡检报告（示例）.pdf`,
      contentType: 'application/pdf',
      bytes: samplePdf([
        'Sample inspection report',
        'Development data only. Not a real report.',
        `Reservation ${reservation.reservationNo}, visit ${visit.visitNo}`,
        'Findings: 3 sample items, see the defect list.',
      ]),
    },
  ];
}

async function uploadDeliveryFile(token, visitId, file) {
  const digest = sha256(file.bytes);
  const intent = await ok('POST', '/api/v1/inspection/file-objects/upload-intents', {
    token,
    key: uuid(),
    body: {
      purpose: 'DELIVERY_ARTIFACT',
      ownerId: visitId,
      deliveryFileType: file.type,
      originalName: file.name,
      contentType: file.contentType,
      sizeBytes: file.bytes.length,
      sha256: digest,
    },
  });
  const uploaded = await fetch(intent.uploadUrl, {
    method: 'PUT',
    body: file.bytes,
    headers: { 'Content-Type': file.contentType },
  });
  if (!uploaded.ok) throw new Stop(`${file.name} 写入对象存储失败（${uploaded.status}）`);
  await ok('POST', `/api/v1/inspection/file-objects/${intent.fileId}/complete`, {
    token,
    key: uuid(),
    body: { etag: `dev-advance-${intent.fileId}`, sizeBytes: file.bytes.length, sha256: digest },
  });
  return {
    fileId: intent.fileId,
    type: file.type,
    displayName: file.name,
    pointCount: file.pointCount ?? null,
    itemCount: file.itemCount ?? null,
  };
}

// ---- 各阶段 ----

async function findRow(customer, reservationNo) {
  const list = await ok('GET', '/api/v1/inspection/reservations?page=1&size=50&stage=ALL', {
    token: customer,
  });
  if (list.items.length === 0) {
    throw new Stop('dev-customer 还没有预约。先在 App 里用 dev-customer 登录，提交一张预约。');
  }
  if (!reservationNo) return list.items[0];
  const row = list.items.find((item) => item.reservationNo === reservationNo);
  if (!row) throw new Stop(`dev-customer 的最近 50 张预约里没有 ${reservationNo}`);
  return row;
}

function visitDates(reservation, count) {
  // 首次用客户选的日期，之后每 7 天一次。
  const first = new Date(`${reservation.preferredServiceDate}T00:00:00Z`).getTime();
  return Array.from({ length: count }, (_, index) =>
    new Date(first + index * 7 * DAY_MS).toISOString().slice(0, 10));
}

async function publishPlan(provider, reservation, visitCount) {
  const versions = reservation.currentPlanId ? 1 : 0;
  const plan = await ok('POST', `/api/v1/provider/inspection/reservations/${reservation.id}/plans`, {
    token: provider,
    key: uuid(),
    ifMatch: versions,
    body: {
      providerId: PROVIDER_ID,
      validUntil: new Date(Date.now() + 2 * DAY_MS).toISOString(),
      visits: visitDates(reservation, visitCount).map((plannedDate) => ({ plannedDate })),
      summary: `示例方案：${reservation.serviceTitle}，共 ${visitCount} 次巡检`,
      scopeSummary: `巡检范围约 ${(reservation.lineLengthMeters / 1000).toFixed(1)} 公里（示例）`,
      serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
      deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
      changeNote: null,
      planItems: reservation.inspectionItems.map((item) => ({
        code: item.code,
        name: item.label,
        quantity: visitCount,
        unit: '次',
        unitPriceMinor: 69_900,
      })),
      deliverables: [
        { code: 'HD_MEDIA', name: '高清影像', fileType: 'IMAGE_ARCHIVE' },
        { code: 'DEFECT_LIST', name: '缺陷清单', fileType: 'DEFECT_LIST' },
        { code: 'POINT_MARKS', name: '点位标注', fileType: 'POINT_KML' },
        { code: 'REPORT', name: '巡检报告', fileType: 'INSPECTION_REPORT' },
      ],
      currency: 'CNY',
    },
  });
  say(`已发布方案 ${plan.planNo}（第 ${plan.planVersion} 版，${visitCount} 次巡检，合计 ¥${(plan.totalAmountMinor / 100).toFixed(2)}）。`);
  say('下一步在 App 里：我的 → 全部订单 → 这一单 → 查看并确认方案。确认后再运行本脚本。');
}

async function arrange(provider, order) {
  await ok('PUT', `/api/v1/provider/inspection/service-orders/${order.id}/arrangement`, {
    token: provider,
    key: uuid(),
    ifMatch: order.version,
    body: {
      assignments: [
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777701',
          displayName: '示例巡检班组',
          roleLabel: '项目负责人',
          contactMobile: '13812346621',
        },
        // S4-4：两名持证飞手，履约页显示“持证飞手 2 名”（画板 Fulfillment）。
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777703',
          displayName: '示例飞手甲',
          roleLabel: '飞手',
          certifiedPilot: true,
        },
        {
          type: 'TEAM_MEMBER',
          resourceId: '77777777-7777-4777-8777-777777777704',
          displayName: '示例飞手乙',
          roleLabel: '飞手',
          certifiedPilot: true,
        },
        {
          type: 'DEVICE',
          resourceId: '77777777-7777-4777-8777-777777777702',
          displayName: 'M350 RTK + H30T（示例）',
          roleLabel: null,
        },
      ],
    },
  });
  say('已安排服务团队和设备。');
}

const hasEvent = (visit, eventCode) =>
  (visit.progress ?? []).some((event) => event.eventCode === eventCode);

// 返回这一次巡检还差的服务商动作，按顺序。
function pendingActions(visit) {
  const actions = [];
  if (visit.status === 'PENDING_SCHEDULE') actions.push('SCHEDULE');
  if (['PENDING_SCHEDULE', 'SCHEDULED'].includes(visit.status)) actions.push('SERVICE_STARTED');
  if (!hasEvent(visit, 'SERVICE_PROGRESS') && !hasEvent(visit, 'SERVICE_COMPLETED')) {
    actions.push('SERVICE_PROGRESS');
  }
  if (!hasEvent(visit, 'SERVICE_COMPLETED')) actions.push('SERVICE_COMPLETED');
  actions.push('DELIVER');
  return actions;
}

async function advanceVisit(provider, reservation, order, firstVisit, step) {
  const route = `/api/v1/provider/inspection/service-orders/${order.id}/visits/${firstVisit.id}`;
  let visit = firstVisit;
  for (const action of pendingActions(visit)) {
    if (action === 'SCHEDULE') {
      visit = await ok('PUT', `${route}/schedule`, {
        token: provider,
        key: uuid(),
        ifMatch: visit.version,
        body: { plannedStartAt: `${visit.plannedDate}T01:00:00Z` },
      });
      say(`第 ${visit.visitNo} 次：已排期，${visit.plannedDate} 上午 9 点开始。`);
    } else if (action === 'DELIVER') {
      const files = [];
      for (const file of deliveryFiles(reservation, visit)) {
        files.push(await uploadDeliveryFile(provider, visit.id, file));
      }
      await ok('POST', `${route}/delivery`, {
        token: provider,
        key: uuid(),
        ifMatch: visit.version,
        body: {
          inspectedLengthMeters: reservation.lineLengthMeters,
          metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 420000, unit: '㎡' }],
          issueCount: 3,
          issueGroupBy: 'CATEGORY',
          issueSummary: [
            { groupCode: 'EQUIPMENT', groupName: '设备本体', count: 1 },
            { groupCode: 'CONNECTION', groupName: '连接部件', count: 1 },
            { groupCode: 'SURROUNDINGS', groupName: '周边环境', count: 1 },
          ],
          files,
        },
      });
      say(`第 ${visit.visitNo} 次：已发布交付（4 个示例文件）。`);
      say(`下一步在 App 里：这一单 → 查看报告并验收第 ${visit.visitNo} 次。验收后再运行本脚本。`);
      return;
    } else {
      visit = await ok('POST', `${route}/progress-events`, {
        token: provider,
        key: uuid(),
        ifMatch: visit.version,
        body: {
          eventCode: action,
          occurredAt: new Date().toISOString(),
          message: { SERVICE_STARTED: '团队已到场，开始巡检', SERVICE_PROGRESS: '已完成一半线路', SERVICE_COMPLETED: '现场巡检完成，整理报告中' }[action],
        },
      });
      say(`第 ${visit.visitNo} 次：${{ SERVICE_STARTED: '已开始', SERVICE_PROGRESS: '已记一条进度', SERVICE_COMPLETED: '现场已完成' }[action]}。`);
    }
    if (step) {
      say('（--step：这次只做一个动作。到 App 的履约页下拉刷新看变化，再运行本脚本做下一个。）');
      return;
    }
  }
}

async function advanceOrder(provider, reservation, row, step) {
  const order = await ok('GET', `/api/v1/inspection/service-orders/${row.serviceOrderId}`, {
    token: provider,
  });
  const waiting = order.visits.find((visit) => visit.status === 'DELIVERED');
  if (waiting) {
    say(`第 ${waiting.visitNo} 次已交付，等你在 App 里验收。验收后再运行本脚本。`);
    return;
  }
  const next = order.visits.find((visit) => visit.status !== 'ACCEPTED');
  if (!next) {
    say('这一单的巡检已经全部验收，没有要推进的了。');
    return;
  }
  if ((order.assignments ?? []).length === 0) {
    await arrange(provider, order);
    if (step) {
      say('（--step：这次只做一个动作。到 App 的履约页下拉刷新看变化，再运行本脚本做下一个。）');
      return;
    }
  }
  await advanceVisit(provider, reservation, order, next, step);
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  assertLoopback();
  const password = readDevPassword();
  const customer = await login('dev-customer', password);
  const provider = await login('dev-provider', password);

  // 客户账号在这里只用来读订单列表，找到要推进的那一张。
  const row = await findRow(customer, options.reservationNo);
  const reservation = await ok('GET', `/api/v1/inspection/reservations/${row.reservationId}`, {
    token: provider,
  });
  say(`预约 ${row.reservationNo}（${row.serviceTitle}），现在是“${{
    AWAITING_PLAN: '待方案',
    AWAITING_CONFIRMATION: '待确认',
    IN_PROGRESS: '进行中',
    AWAITING_ACCEPTANCE: '待验收',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
  }[row.stage] ?? row.stage}”。`);

  switch (row.stage) {
    case 'AWAITING_PLAN':
      await publishPlan(provider, reservation, options.visits);
      break;
    case 'AWAITING_CONFIRMATION':
      say('方案已经发布，等你在 App 里确认：我的 → 全部订单 → 这一单。确认后再运行本脚本。');
      break;
    case 'IN_PROGRESS':
    case 'AWAITING_ACCEPTANCE':
      await advanceOrder(provider, reservation, row, options.step);
      break;
    case 'COMPLETED':
      say('这一单已经全部验收，没有要推进的了。');
      break;
    default:
      say('这一单已经取消，推进不了。');
  }
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
  } else {
    console.error(error);
  }
  process.exit(1);
});
