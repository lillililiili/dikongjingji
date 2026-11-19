#!/usr/bin/env node
'use strict';

// 本地手工验收用：替商家处理询价商品的询价单（F4）。商家没有页面（商家工作台不在范围内），看询价、报价、改价、拒绝只有受权接口。
// 默认用开发账号 dev-merchant（示例机器人商家的操作员）；加 --as dev-merchant-2 用示例行业装备代理的操作员。
// 两家都卖“行业测绘无人机”（询价商品，东营）。询价、比选、选定、下单付款仍然由你在 App 里用 dev-customer 做；
// 下单后的发货、退款用 infra/scripts/dev-merchant-order.cjs（只有 dev-merchant）。
//
//   node infra/scripts/dev-inquiry-merchant.cjs [--as dev-merchant-2] inquiries [状态]
//   node infra/scripts/dev-inquiry-merchant.cjs [--as dev-merchant-2] show <询价编号>
//   node infra/scripts/dev-inquiry-merchant.cjs [--as dev-merchant-2] quote <询价编号> <单价（元）> <交期（天）> [有效天数] [型号配置]
//   node infra/scripts/dev-inquiry-merchant.cjs [--as dev-merchant-2] revise <询价编号> <单价（元）> <交期（天）> [有效天数] [型号配置]
//   node infra/scripts/dev-inquiry-merchant.cjs [--as dev-merchant-2] decline <询价编号> <原因>
//
//   询价编号   inquiries 里每行开头的 8 位（询价单 ID 的前 8 位），也可以给完整 ID
//   有效天数   默认 3 天（最多 7 天）；型号配置默认“测绘版 · RTK · 五镜头倾斜相机”，包含内容固定写“含 2 名飞手培训”
//   状态       OPEN / QUOTED / CHOSEN / CLOSED
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 脚本不绕过任何规则：报价截止、已选定、已关闭、拒绝过的都会如实告诉你。
// 只在本机开发环境运行：账号和密码来自开发种子（R__dev_identity_accounts.sql 的公开开发密码）。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const STATUS_TEXT = { OPEN: '待报价', QUOTED: '报价中', CHOSEN: '已选定', CLOSED: '已关闭' };
const INVITATION_TEXT = { INVITED: '待你报价', QUOTED: '已报价', DECLINED: '已拒绝' };
const QUOTE_TEXT = { ACTIVE: '有效', EXPIRED: '已过期', WITHDRAWN: '已撤回', CHOSEN: '已被选中', LAPSED: '未被选中' };
const ERRORS = {
  MALL_INQUIRY_STATE_INVALID: '询价单已选定、已关闭，或你已经拒绝过',
  MALL_INQUIRY_QUOTING_CLOSED: '报价已截止（提交后 72 小时）',
  COMMON_FORBIDDEN: '账号不是服务商操作员，或令牌里没有机构',
};
const MODEL = '测绘版 · RTK · 五镜头倾斜相机';

const say = (line) => console.log(line);
const yuan = (minor) => (minor / 100).toFixed(2);

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

async function call(method, route, { token, body } = {}) {
  const sent = { 'X-Request-Id': `dev-inquiry-${crypto.randomUUID()}` };
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
  if (result.status === 404) throw new Stop('没有这张询价单，或者它没有发给你这家。');
  if (result.status === 400) throw new Stop(`${what}失败：内容不对（${result.json ? result.json.message : ''}）`);
  throw new Stop(`${what}失败：${ERRORS[code] || `${result.status} ${code} ${result.json ? result.json.message : ''}`}`);
}

async function login(name) {
  if (!['dev-merchant', 'dev-merchant-2'].includes(name)) throw new Stop('--as 只能是 dev-merchant 或 dev-merchant-2');
  const result = await call('POST', '/api/v1/auth/login', { body: { loginName: name, password: readDevPassword() } });
  if (result.status !== 200) throw new Stop(`${name} 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  return result.json.accessToken;
}

async function listAll(token, status) {
  const items = [];
  for (let page = 1; page <= 20; page += 1) {
    const result = await call('GET', `/api/v1/provider/mall/inquiries?page=${page}&size=50${status ? `&status=${status}` : ''}`, { token });
    if (result.status !== 200) fail(result, '查询');
    items.push(...result.json.items);
    if (!result.json.hasMore) break;
  }
  return items;
}

async function find(token, ref) {
  const key = (ref || '').trim().toLowerCase();
  if (!/^[0-9a-f-]{8,36}$/.test(key)) throw new Stop('询价编号是 inquiries 里每行开头的 8 位，或者完整的 ID');
  const found = (await listAll(token)).filter((item) => item.inquiryId.startsWith(key));
  if (found.length === 0) throw new Stop('没有这张询价单，或者它没有发给你这家。');
  if (found.length > 1) throw new Stop('有几张以这几位开头，多给几位');
  return found[0];
}

function describe(inquiry) {
  const quote = inquiry.myQuote
    ? `我的报价 v${inquiry.myQuote.version} ¥${yuan(inquiry.myQuote.unitAmountMinor)}/件 合计 ¥${yuan(inquiry.myQuote.totalMinor)} ${QUOTE_TEXT[inquiry.myQuote.status] || inquiry.myQuote.status}`
    : '未报价';
  const deadline = inquiry.quotingOpen ? `截止 ${new Date(inquiry.quoteDeadline).toLocaleString('zh-CN', { hour12: false })}` : '不再收报价';
  return `${inquiry.inquiryId.slice(0, 8)}  ${STATUS_TEXT[inquiry.status] || inquiry.status}  ${inquiry.product.name} ×${inquiry.quantity}  `
    + `${INVITATION_TEXT[inquiry.invitationStatus]}  ${deadline}  ${quote}`;
}

async function quote(token, args, revise) {
  const inquiry = await find(token, args[0]);
  if (revise && !inquiry.myQuote) throw new Stop('还没有报过价，用 quote');
  const unit = Math.round(Number(args[1]) * 100);
  const lead = Number(args[2]);
  const validDays = args[3] === undefined ? 3 : Number(args[3]);
  const model = args.slice(4).join(' ').trim() || (inquiry.myQuote ? inquiry.myQuote.model : MODEL);
  if (!Number.isFinite(unit) || unit < 1 || !Number.isInteger(lead) || lead < 1 || !Number.isFinite(validDays) || validDays <= 0 || validDays > 7) {
    throw new Stop('用法：quote <询价编号> <单价（元）> <交期（天）> [有效天数 1–7] [型号配置]，例如 quote 1a2b3c4d 29800 7 3');
  }
  const result = await call('PUT', `/api/v1/provider/mall/inquiries/${inquiry.inquiryId}/quote`, {
    token,
    body: { unitAmountMinor: unit, model, leadDays: lead, includes: '含 2 名飞手培训', validUntil: new Date(Date.now() + validDays * 86_400_000).toISOString() },
  });
  if (result.status !== 200) fail(result, revise ? '改价' : '报价');
  const q = result.json.myQuote;
  say(`已${revise ? '改价' : '报价'}：v${q.version} ¥${yuan(q.unitAmountMinor)}/件 × ${q.quantity}，合计 ¥${yuan(q.totalMinor)}，交期 ${q.leadDays} 天，`
    + `有效到 ${new Date(q.validUntil).toLocaleString('zh-CN', { hour12: false })}。`);
}

async function main() {
  assertLoopback();
  let args = process.argv.slice(2);
  let who = 'dev-merchant';
  if (args[0] === '--as') {
    who = args[1];
    args = args.slice(2);
  }
  const [command, ...rest] = args;
  const token = await login(who);

  if (command === 'inquiries') {
    const items = await listAll(token, (rest[0] || '').toUpperCase());
    if (items.length === 0) say('没有询价单。在 App 里用 dev-customer 打开“行业测绘无人机”，点“联系询价”提交一份。');
    for (const inquiry of items) say(describe(inquiry));
    return;
  }
  if (command === 'show') {
    const inquiry = await find(token, rest[0]);
    say(describe(inquiry));
    say(`配置和用途：${inquiry.requirement}`);
    say(`交付城市：${inquiry.cityCode}  期望交付：${inquiry.expectedDate || '不限'}`);
    if (inquiry.declineReason) say(`拒绝原因：${inquiry.declineReason}`);
    return;
  }
  if (command === 'quote' || command === 'revise') {
    await quote(token, rest, command === 'revise');
    return;
  }
  if (command === 'decline') {
    const inquiry = await find(token, rest[0]);
    const reason = rest.slice(1).join(' ').trim();
    if (!reason) throw new Stop('用法：decline <询价编号> <原因>');
    const result = await call('POST', `/api/v1/provider/mall/inquiries/${inquiry.inquiryId}/decline`, { token, body: { reason } });
    if (result.status !== 200) fail(result, '拒绝');
    say('已拒绝这张询价单。');
    return;
  }
  throw new Stop('用法：[--as dev-merchant-2] inquiries [状态] | show <询价编号> | quote|revise <询价编号> <单价（元）> <交期（天）> [有效天数] [型号配置] | decline <询价编号> <原因>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
