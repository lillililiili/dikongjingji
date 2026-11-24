// 演示当天的准备：今天的活动场次、四类样例交付文件。可以重复跑。只对演示环境（19420）操作。
import fs from 'node:fs';
import path from 'node:path';

import { PORTS, REPO, STATE } from './config.mjs';

const BASE = `http://127.0.0.1:${PORTS.backend}`;
// 核销员 dev-organizer 所在的机构（开发种子：东营低空与机器人体验馆，已登记模拟收款账户）。
const ORGANIZER = 'a1000000-0000-4000-8000-000000000004';
const TITLE = '无人机飞行体验课（演示）';
const COVER = path.join(REPO, 'apps/mobile/src/static/images/preview');

async function call(method, route, token, body) {
  const response = await fetch(BASE + route, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${route} → ${response.status} ${json?.code ?? ''} ${json?.message ?? ''}`);
  return json;
}

export async function prepare(env) {
  const { accessToken } = await call('POST', '/api/v1/auth/login', null, { loginName: 'dev-operator', password: env.DEV_OPERATOR_PASSWORD });
  const minutes = (count) => new Date(Date.now() + count * 60_000).toISOString();
  const label = `演示场 ${new Date(Date.now() + 8 * 3600_000).toISOString().slice(5, 16).replace('T', ' ')}`;
  const found = (await call('GET', '/api/v1/ops/activities?page=1&size=50', accessToken)).items.find((item) => item.title === TITLE);
  let activity = found ? await call('GET', `/api/v1/ops/activities/${found.activityId}`, accessToken) : await createActivity(accessToken);
  const usable = activity.sessions.find((item) => Date.parse(item.startsAt) > Date.now() + 45 * 60_000 && Date.parse(item.startsAt) < Date.now() + 180 * 60_000);
  if (!usable) {
    activity = await call('POST', `/api/v1/ops/activities/${activity.activityId}/sessions`, accessToken, {
      label, startsAt: minutes(120), endsAt: minutes(240), checkInOpensAt: minutes(0.2), enrollmentClosesAt: minutes(119), capacity: 20,
    });
  }
  if (activity.status !== 'PUBLISHED') await call('POST', `/api/v1/ops/activities/${activity.activityId}/publish`, accessToken, { version: activity.version });
  console.log(usable ? `活动“${TITLE}”已有能用的场次“${usable.label}”。` : `活动“${TITLE}”（¥99/人）加了场次“${label}”，2 小时后开始，马上可以核销。`);
  const target = path.join(STATE, 'samples');
  writeSamples(target);
  console.log(`样例交付文件在 ${path.relative(REPO, target)}/（zip、xlsx、kml、pdf 各一份）。`);
}

async function createActivity(token) {
  const picture = fs.readdirSync(COVER, { recursive: true }).map(String).find((name) => /\.(jpe?g|png)$/i.test(name));
  const form = new FormData();
  form.append('file', new Blob([fs.readFileSync(path.join(COVER, picture))], { type: /png$/i.test(picture) ? 'image/png' : 'image/jpeg' }), path.basename(picture));
  const uploaded = await fetch(`${BASE}/api/v1/ops/media`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  if (!uploaded.ok) throw new Error(`上传封面 → ${uploaded.status}`);
  return call('POST', '/api/v1/ops/activities', token, {
    type: 'OPEN_DAY', title: TITLE, subtitle: '现场演示用', coverUrl: (await uploaded.json()).url, galleryUrls: [], organizerId: ORGANIZER, coOrganizerId: null, cityCode: '370500',
    venueName: '东营低空与机器人体验馆', venueAddress: '示例路 1 号（示例地址）', scheduleNote: null, priceMode: 'PER_PERSON', priceAmountMinor: 9900,
    minAge: 6, maxAge: 60, eligibilityNote: '6–60 岁', seatNote: '每人 1 个名额', refundNote: '开始前可取消，按人原路退回', enrollmentNotice: '现场演示用的活动，不真实举办。',
  });
}

function writeSamples(target) {
  fs.mkdirSync(target, { recursive: true });
  const write = (name, content) => fs.writeFileSync(path.join(target, name), content);
  write('巡检影像.zip', zip({ 'README.txt': '演示用的影像包，没有真实影像。\n' }));
  write('点位标注.kml', '<?xml version="1.0" encoding="UTF-8"?>\n<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>1 号杆塔（示例）</name><Point><coordinates>118.67,37.43,0</coordinates></Point></Placemark></Document></kml>\n');
  write('缺陷清单.xlsx', zip({
    '[Content_Types].xml': '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    '_rels/.rels': '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    'xl/_rels/workbook.xml.rels': '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
    'xl/workbook.xml': '<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="缺陷清单" sheetId="1" r:id="rId1"/></sheets></workbook>',
    'xl/worksheets/sheet1.xml': `<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${[['杆塔', '缺陷', '等级'], ['1 号（示例）', '绝缘子污损', '一般']].map((cells) => `<row>${cells.map((cell) => `<c t="inlineStr"><is><t>${cell}</t></is></c>`).join('')}</row>`).join('')}</sheetData></worksheet>`,
  }));
  write('巡检报告.pdf', pdf());
}

function pdf() {
  const stream = 'BT /F1 20 Tf 72 720 Td (Inspection report - demo sample) Tj ET';
  const objects = ['<</Type/Catalog/Pages 2 0 R>>', '<</Type/Pages/Kids[3 0 R]/Count 1>>',
    '<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>',
    `<</Length ${stream.length}>>\nstream\n${stream}\nendstream`, '<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>'];
  let text = '%PDF-1.4\n';
  const offsets = objects.map((object, index) => { const at = text.length; text += `${index + 1} 0 obj\n${object}\nendobj\n`; return at; });
  const xref = text.length;
  text += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((at) => `${String(at).padStart(10, '0')} 00000 n \n`).join('')}`;
  return `${text}trailer\n<</Size ${objects.length + 1}/Root 1 0 R>>\nstartxref\n${xref}\n%%EOF\n`;
}

/** 不压缩的 zip，够这几个小文件用。 */
function zip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, text] of Object.entries(entries)) {
    const path8 = Buffer.from(name);
    const data = Buffer.from(text);
    const fields = Buffer.alloc(26);
    fields.writeUInt16LE(20, 0); fields.writeUInt16LE(0x800, 2); fields.writeUInt16LE(0x5c78, 8);
    fields.writeUInt32LE(crc32(data), 10); fields.writeUInt32LE(data.length, 14); fields.writeUInt32LE(data.length, 18); fields.writeUInt16LE(path8.length, 22);
    const local = Buffer.concat([Buffer.from([0x50, 0x4b, 3, 4]), fields, path8, data]);
    const tail = Buffer.alloc(14);
    tail.writeUInt32LE(offset, 10);
    centrals.push(Buffer.concat([Buffer.from([0x50, 0x4b, 1, 2, 20, 0]), fields, tail, path8]));
    locals.push(local);
    offset += local.length;
  }
  const central = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(centrals.length, 8); end.writeUInt16LE(centrals.length, 10);
  end.writeUInt32LE(central.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}

function crc32(data) {
  let crc = ~0;
  for (const byte of data) { crc ^= byte; for (let i = 0; i < 8; i += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0); }
  return ~crc >>> 0;
}
