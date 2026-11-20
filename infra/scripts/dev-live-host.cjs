#!/usr/bin/env node
'use strict';

// 本地手工验收用：替直播主办方和直播服务商操作。主办方没有开播端页面（App 只做观看端），建直播间、设讲解清单、
// 以主播身份发弹幕只有受权接口；开播、断流、结束以直播服务商的回调为准，本机由后端 dev 配置里的模拟直播服务给出
// （开发控制接口 /api/v1/dev/live/rooms/<直播间 ID>/stream-events，签名后走和真实回调同一条路径）。
// 观看、设提醒、发弹幕、点赞、关注仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-live-host.cjs list
//   node infra/scripts/dev-live-host.cjs create <标题> [几分钟后开播，默认 10]
//   node infra/scripts/dev-live-host.cjs items <直播间 ID> <类型:ID:标题>... [--current=<序号>]
//   node infra/scripts/dev-live-host.cjs current <直播间 ID> <序号|none>
//   node infra/scripts/dev-live-host.cjs start|interrupt|end <直播间 ID>
//   node infra/scripts/dev-live-host.cjs say <直播间 ID> <文字>
//   node infra/scripts/dev-live-host.cjs hide <直播间 ID> <弹幕序号>
//   node infra/scripts/dev-live-host.cjs force-end <直播间 ID>
//
//   类型       PRODUCT / PRO_SERVICE / INSPECTION_SERVICE / ACTIVITY / VENUE
//   示例直播间  f4000000-0000-4000-8000-000000000001 机器狗园区夜巡演示（直播中，鹰眼智能巡检）
//              f4000000-0000-4000-8000-000000000002 编程无人机开箱与首飞（直播中，示例无人机厂家）
//              f4000000-0000-4000-8000-000000000003 编程机器人入门（预告，示例机器人商家）
//              f4000000-0000-4000-8000-000000000004 园区夜巡方案怎么定：服务商答疑（预告，鹰眼智能巡检）
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 账号：dev-merchant（示例机器人商家的操作员，开发种子给它开通了直播主办）管自己机构的直播间；
// 别的机构的示例直播间（鹰眼智能巡检、示例无人机厂家）的开播、断流、结束和 hide、force-end 用 dev-support（平台客服）。
// 只在本机开发环境运行：账号和密码来自开发种子 R__dev_identity_accounts.sql。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const CITY = '370500';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ITEM_TYPES = ['PRODUCT', 'PRO_SERVICE', 'INSPECTION_SERVICE', 'ACTIVITY', 'VENUE'];
const STREAM_EVENTS = { start: 'STARTED', interrupt: 'INTERRUPTED', end: 'ENDED' };
const STATUS_TEXT = { SCHEDULED: '预告', LIVE: '直播中', ENDED: '已结束' };
const ERRORS = {
  LIVE_ROOM_NOT_SCHEDULED: '直播已经开始或结束，不能再改预告',
  LIVE_ROOM_NOT_LIVE: '直播不在进行中，先 start',
  LIVE_ROOM_ENDED: '直播已经结束',
  LIVE_STREAM_UNAVAILABLE: '没有可用的直播服务：后端要用 dev 配置启动（live.stream.provider=fake）',
  LIVE_CALLBACK_REJECTED: '模拟回调没有通过验签（后端重启过就再来一次）',
  LIVE_IDEMPOTENCY_KEY_REUSED: '幂等键冲突，再运行一次',
};

const say = (line) => console.log(line);

class Stop extends Error {}

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

async function call(method, route, { token, body, headers } = {}) {
  const sent = { 'X-Request-Id': `dev-live-${crypto.randomUUID()}`, ...headers };
  if (token) sent.Authorization = `Bearer ${token}`;
  if (body !== undefined) sent['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(BASE_URL + route, {
      method,
      headers: sent,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Stop(`连不上后端 ${BASE_URL}，先把后端跑起来`);
  }
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

function fail(result, what) {
  const code = result.json ? result.json.code : '';
  if (result.status === 404) throw new Stop(`${what}失败：没有这个直播间（或这条弹幕），或者它不归这个账号管。`);
  throw new Stop(`${what}失败：${ERRORS[code] || `${result.status} ${code} ${result.json ? result.json.message : ''}`}`);
}

const tokens = {};
async function login(loginName) {
  if (tokens[loginName]) return tokens[loginName];
  const result = await call('POST', '/api/v1/auth/login', { body: { loginName, password: readDevPassword() } });
  if (result.status !== 200) {
    throw new Stop(`${loginName} 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  }
  tokens[loginName] = result.json.accessToken;
  return tokens[loginName];
}

function roomId(value) {
  if (!UUID.test(value || '')) throw new Stop('直播间 ID 是 UUID，比如 f4000000-0000-4000-8000-000000000003（list 能看到）');
  return value;
}

function describe(room) {
  const when = room.status === 'LIVE' ? `开播于 ${room.startedAt}` : room.status === 'ENDED' ? `结束于 ${room.endedAt}` : `预计 ${room.scheduledAt}`;
  return `${room.roomId}  ${STATUS_TEXT[room.status] || room.status}${room.streamInterrupted ? '（断流中）' : ''}  ${room.title}  ${room.host.name}  ${when}  ${room.viewerCount} 观看 · ${room.likeCount} 赞`;
}

/** 先用主办方账号，不是它的直播间时换平台客服。 */
async function simulate(id, type) {
  const body = { type };
  for (const account of ['dev-merchant', 'dev-support']) {
    const result = await call('POST', `/api/v1/dev/live/rooms/${id}/stream-events`, { token: await login(account), body });
    if (result.status === 200) return account;
    if (result.status !== 404) fail(result, '模拟回调');
  }
  throw new Stop('没有这个直播间；或者后端没有用 dev 配置启动（开发控制接口只在 dev 下有）。');
}

async function main() {
  assertLoopback();
  const [command, ...args] = process.argv.slice(2);

  if (command === 'list') {
    const host = await call('GET', '/api/v1/live-host/rooms?size=20', { token: await login('dev-merchant') });
    if (host.status !== 200) fail(host, '查直播间');
    say('示例机器人商家（dev-merchant）的直播间：');
    if (host.json.items.length === 0) say('  （没有）');
    for (const item of host.json.items) say(`  ${describe(item.room)}`);
    const listed = await call('GET', `/api/v1/live/rooms?cityCode=${CITY}&size=20`);
    if (listed.status !== 200) fail(listed, '查直播列表');
    say(`东营（${CITY}）的直播列表（App 里看到的顺序）：`);
    for (const room of listed.json.items) say(`  ${describe(room)}`);
    return;
  }

  if (command === 'create') {
    const title = (args[0] || '').trim();
    const minutes = args[1] === undefined ? 10 : Number(args[1]);
    if (!title || !Number.isFinite(minutes) || minutes <= 0) {
      throw new Stop('用法：create <标题（1–30 字）> [几分钟后开播，默认 10]');
    }
    const scheduledAt = new Date(Date.now() + minutes * 60_000).toISOString();
    const result = await call('POST', '/api/v1/live-host/rooms', {
      token: await login('dev-merchant'),
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { title, cityCode: CITY, scheduledAt, speakerName: '李老师', speakerTitle: '课程讲师' },
    });
    if (result.status === 400) throw new Stop('标题 1–30 字一行；开播时间要在现在之后、90 天以内');
    if (result.status !== 201) fail(result, '建直播间');
    say(`已建直播间：${result.json.room.roomId}（预告，${scheduledAt}）`);
    say(`推流地址（模拟服务的，不能真推流）：${result.json.pushUrl}`);
    say(`开播：node infra/scripts/dev-live-host.cjs start ${result.json.room.roomId}`);
    return;
  }

  if (command === 'items') {
    const id = roomId(args[0]);
    const current = args.find((arg) => arg.startsWith('--current='));
    const items = args.slice(1).filter((arg) => !arg.startsWith('--')).map((spec) => {
      const [type, refId, ...title] = spec.split(':');
      if (!ITEM_TYPES.includes(type) || !UUID.test(refId || '') || !title.join(':').trim()) {
        throw new Stop(`讲解项写成 类型:ID:标题，类型是 ${ITEM_TYPES.join(' / ')}`);
      }
      return { type, refId, title: title.join(':').trim() };
    });
    const result = await call('PUT', `/api/v1/live-host/rooms/${id}/items`, {
      token: await login('dev-merchant'),
      body: { items, currentSeq: current ? Number(current.slice('--current='.length)) : null },
    });
    if (result.status === 400) throw new Stop('最多 10 项，同一个对象只能出现一次，当前讲解项要在清单里');
    if (result.status !== 200) fail(result, '设讲解清单');
    say(`讲解清单 ${result.json.room.items.length} 项，当前讲解第 ${result.json.room.currentItemSeq ?? '—'} 项。`);
    return;
  }

  if (command === 'current') {
    const id = roomId(args[0]);
    const seq = args[1] === 'none' ? null : Number(args[1]);
    if (seq !== null && !Number.isInteger(seq)) throw new Stop('用法：current <直播间 ID> <序号|none>');
    const result = await call('PUT', `/api/v1/live-host/rooms/${id}/current-item`, {
      token: await login('dev-merchant'),
      body: { seq },
    });
    if (result.status === 400) throw new Stop('序号不在讲解清单里');
    if (result.status !== 200) fail(result, '换当前讲解项');
    say(`当前讲解第 ${result.json.room.currentItemSeq ?? '—'} 项。`);
    return;
  }

  if (STREAM_EVENTS[command]) {
    const id = roomId(args[0]);
    const account = await simulate(id, STREAM_EVENTS[command]);
    const room = await call('GET', `/api/v1/live/rooms/${id}?cityCode=${CITY}`);
    say(`已模拟 ${STREAM_EVENTS[command]}（用 ${account}）。${room.status === 200 ? describe(room.json) : ''}`);
    if (command === 'start') say('设了提醒的账号会在“消息”里收到一条“直播开始了”（每个直播间每人一次）。');
    return;
  }

  if (command === 'say') {
    const id = roomId(args[0]);
    const text = args.slice(1).join(' ').trim();
    const result = await call('POST', `/api/v1/live-host/rooms/${id}/comments`, {
      token: await login('dev-merchant'),
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { text },
    });
    if (result.status === 400) throw new Stop('弹幕 1–50 字一行');
    if (result.status !== 201) fail(result, '发弹幕');
    say(`主播弹幕第 ${result.json.seq} 条：${result.json.text}`);
    return;
  }

  if (command === 'hide' || command === 'force-end') {
    const id = roomId(args[0]);
    const token = await login('dev-support');
    if (command === 'force-end') {
      const result = await call('POST', `/api/v1/platform/live/rooms/${id}/end`, { token });
      if (result.status !== 200) fail(result, '强制结束');
      say(`已强制结束：${describe(result.json)}`);
      return;
    }
    const seq = Number(args[1]);
    const comments = await call('GET', `/api/v1/live/rooms/${id}/comments?size=50`);
    if (comments.status !== 200) fail(comments, '查弹幕');
    const comment = comments.json.items.find((item) => item.seq === seq);
    if (!comment) throw new Stop('最近 50 条里没有这个序号的弹幕（或它已经隐藏了）');
    const result = await call('POST', `/api/v1/platform/live/rooms/${id}/comments/${comment.commentId}/hide`, { token });
    if (result.status !== 200) fail(result, '隐藏弹幕');
    say(`已隐藏第 ${seq} 条：${comment.authorName}：${comment.text}`);
    return;
  }

  throw new Stop('用法：list | create <标题> [分钟] | items <ID> <类型:ID:标题>... [--current=<序号>] | current <ID> <序号|none> | start|interrupt|end <ID> | say <ID> <文字> | hide <ID> <序号> | force-end <ID>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
