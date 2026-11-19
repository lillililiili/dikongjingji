#!/usr/bin/env node
'use strict';

// 本地手工验收用：替回复方处理客服会话（G1）。商家、服务商和平台客服都没有工作台页面，读和回复会话只有受权接口。
// 默认用开发账号 dev-support（平台客服，看对方是“平台客服”的会话）；
// --as dev-merchant 用示例机器人商家的操作员，--as dev-provider 用云翼科技的操作员（看对方是本机构的商家、服务商会话）。
// 客户那一边（打开会话、发消息、带卡片）由你在 App 里用 dev-customer 做；回复后 App 的“消息”里会有一条“客服消息”。
//
//   node infra/scripts/dev-support-reply.cjs [--as dev-support|dev-merchant|dev-provider] list
//   node infra/scripts/dev-support-reply.cjs [--as …] show <会话编号> [条数]
//   node infra/scripts/dev-support-reply.cjs [--as …] reply <会话编号> <文字>
//   node infra/scripts/dev-support-reply.cjs [--as …] read <会话编号>
//
//   会话编号   list 里每行开头的 8 位（会话 ID 的前 8 位），也可以给完整 ID
//   条数       show 显示最近几条，默认 20（最多 50）
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 回复会让客户那边未读加一，并给客户记一条站内信（同一会话最多一条未读）。脚本不绕过任何规则：
// 看不到的会话（不是本机构的、不是平台客服的）一律当作不存在。
// 只在本机开发环境运行：账号和密码来自开发种子（R__dev_identity_accounts.sql 的公开开发密码）。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const DESKS = {
  'dev-support': { route: '/api/v1/platform/support/conversations', who: '平台客服' },
  'dev-merchant': { route: '/api/v1/provider/conversations', who: '示例机器人商家' },
  'dev-provider': { route: '/api/v1/provider/conversations', who: '云翼科技' },
};
const KIND_TEXT = { PLATFORM: '平台客服', MERCHANT: '商家', PROVIDER: '服务商' };
const CARD_TEXT = {
  PRODUCT: '商品', MALL_ORDER: '商城订单', MALL_INQUIRY: '询价单', INSPECTION_SERVICE: '巡检服务', INSPECTION_ORDER: '巡检服务单',
  PRO_SERVICE: '专业服务', ACTIVITY: '活动', ACTIVITY_ENROLLMENT: '活动报名', VENUE: '场馆', VENUE_BOOKING: '到馆预约',
  RENTAL_INQUIRY: '租赁询价单', RENTAL_ORDER: '租赁订单', DEMAND: '需求',
};
const ERRORS = {
  SUPPORT_IDEMPOTENCY_KEY_REUSED: '幂等键已用过（不该出现，每次回复都用新的键）',
  COMMON_FORBIDDEN: '账号没有这类会话的权限',
};

const say = (line) => console.log(line);
const time = (iso) => new Date(iso).toLocaleString('zh-CN', { hour12: false });

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
  const sent = { 'X-Request-Id': `dev-support-${crypto.randomUUID()}`, ...headers };
  if (token) sent.Authorization = `Bearer ${token}`;
  if (body !== undefined) sent['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(BASE_URL + route, { method, headers: sent, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new Stop(`连不上后端 ${BASE_URL}，先把后端跑起来`);
  }
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

function fail(result, what) {
  const code = result.json ? result.json.code : '';
  if (result.status === 404) throw new Stop('没有这个会话，或者它不归你这一方。');
  if (result.status === 400) throw new Stop(`${what}失败：内容不对（${result.json ? result.json.message : ''}）`);
  throw new Stop(`${what}失败：${ERRORS[code] || `${result.status} ${code} ${result.json ? result.json.message : ''}`}`);
}

async function login(name) {
  if (!DESKS[name]) throw new Stop(`--as 只能是 ${Object.keys(DESKS).join('、')}`);
  const result = await call('POST', '/api/v1/auth/login', { body: { loginName: name, password: readDevPassword() } });
  if (result.status !== 200) throw new Stop(`${name} 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  return result.json.accessToken;
}

async function listAll(token, desk) {
  const items = [];
  for (let page = 1; page <= 20; page += 1) {
    const result = await call('GET', `${desk.route}?page=${page}&size=50`, { token });
    if (result.status !== 200) fail(result, '查询');
    items.push(...result.json.items);
    if (!result.json.hasMore) break;
  }
  return items;
}

async function find(token, desk, ref) {
  const key = (ref || '').trim().toLowerCase();
  if (!/^[0-9a-f-]{8,36}$/.test(key)) throw new Stop('会话编号是 list 里每行开头的 8 位，或者完整的 ID');
  if (key.length === 36) return { conversationId: key };
  const found = (await listAll(token, desk)).filter((item) => item.conversationId.startsWith(key));
  if (found.length === 0) throw new Stop('没有这个会话，或者它不归你这一方（没发过消息的会话不在列表里，给完整 ID）。');
  if (found.length > 1) throw new Stop('有几个以这几位开头，多给几位');
  return found[0];
}

function preview(last) {
  if (!last) return '（还没有消息）';
  return `${last.senderSide === 'CUSTOMER' ? '客户' : '我方'}：${last.preview}`;
}

function describe(item) {
  const unread = item.unreadCount > 0 ? `  未读 ${item.unreadCount}` : '';
  return `${item.conversationId.slice(0, 8)}  ${KIND_TEXT[item.counterpartKind] || item.counterpartKind}会话  `
    + `客户 ${item.customer.name}${unread}  ${preview(item.lastMessage)}`;
}

function line(message) {
  const who = message.mine ? '我方' : '客户';
  const body = message.kind === 'CARD'
    ? `[${CARD_TEXT[message.card.type] || message.card.type}] ${message.card.title}${message.card.subtitle ? ` · ${message.card.subtitle}` : ''}（${message.card.refId}）`
    : message.text.replace(/\n/g, '\n      ');
  return `#${message.seq} ${time(message.sentAt)} ${who}：${body}`;
}

async function main() {
  assertLoopback();
  let args = process.argv.slice(2);
  let who = 'dev-support';
  if (args[0] === '--as') {
    who = args[1];
    args = args.slice(2);
  }
  const [command, ...rest] = args;
  const token = await login(who);
  const desk = DESKS[who];

  if (command === 'list') {
    const items = await listAll(token, desk);
    if (items.length === 0) say(`${desk.who}还没有会话。在 App 里用 dev-customer 找“联系客服 / 联系商家 / 联系服务商”发一条。`);
    for (const item of items) say(describe(item));
    return;
  }
  if (command === 'show') {
    const conversation = await find(token, desk, rest[0]);
    const size = rest[1] === undefined ? 20 : Number(rest[1]);
    if (!Number.isInteger(size) || size < 1 || size > 50) throw new Stop('条数是 1–50');
    const result = await call('GET', `${desk.route}/${conversation.conversationId}/messages?size=${size}`, { token });
    if (result.status !== 200) fail(result, '读取');
    say(describe(result.json.conversation));
    if (result.json.hasMore) say('（更早的消息没有显示）');
    for (const message of result.json.items) say(line(message));
    return;
  }
  if (command === 'reply') {
    const conversation = await find(token, desk, rest[0]);
    const text = rest.slice(1).join(' ').trim();
    if (!text) throw new Stop('用法：reply <会话编号> <文字>');
    const result = await call('POST', `${desk.route}/${conversation.conversationId}/messages`, {
      token,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { text },
    });
    if (result.status !== 201 && result.status !== 200) fail(result, '回复');
    say(`已回复（第 ${result.json.seq} 条）。客户那边未读加一，“消息”里有一条客服消息。`);
    return;
  }
  if (command === 'read') {
    const conversation = await find(token, desk, rest[0]);
    const result = await call('POST', `${desk.route}/${conversation.conversationId}/read`, { token });
    if (result.status !== 200) fail(result, '标为已读');
    say('已标为读过。');
    return;
  }
  throw new Stop('用法：[--as dev-support|dev-merchant|dev-provider] list | show <会话编号> [条数] | reply <会话编号> <文字> | read <会话编号>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
