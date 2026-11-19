#!/usr/bin/env node
'use strict';

// 本地手工验收用：替服务商报价。服务商没有页面（服务商工作台不在范围内），看需求、报价、改价、撤回只有受权接口。
// 这个脚本用开发账号 dev-provider（示例服务商“云翼科技”的操作员，服务东营、济南）调这些接口；
// 发布需求、比选、选定、取消仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-provider-quote.cjs list
//   node infra/scripts/dev-provider-quote.cjs show <需求编号>
//   node infra/scripts/dev-provider-quote.cjs quote <需求编号> <金额（元）> [工期（天）] [有效天数]
//   node infra/scripts/dev-provider-quote.cjs revise <需求编号> <金额（元）> [工期（天）] [有效天数]
//   node infra/scripts/dev-provider-quote.cjs withdraw <需求编号>
//
//   需求编号   list 里每行开头的 8 位（需求 ID 的前 8 位），也可以给完整 ID
//   quote      第一次报价；revise 改价（版本号加 1）。两者调的是同一个接口，revise 只是先确认已经报过价
//   工期       默认 90 天（改价时默认沿用上一版）；有效天数默认 7 天（最多 30 天）
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 脚本不绕过任何规则：报价截止、已被选中、看不到的需求都会如实告诉你。
// 只在本机开发环境运行：账号和密码来自开发种子 R__dev_identity_accounts.sql。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const STATUS_TEXT = { OPEN: '待报价', QUOTED: '报价中', CHOSEN: '已选定', CLOSED: '已关闭' };
const QUOTE_TEXT = {
  ACTIVE: '有效', EXPIRED: '已过有效期', WITHDRAWN: '已撤回', CHOSEN: '已选中', NOT_CHOSEN: '未选中', CLOSED: '已关闭',
};
const ERRORS = {
  DEMAND_QUOTING_CLOSED: '报价已截止，或者需求已选定、已关闭',
  DEMAND_QUOTE_LOCKED: '这份报价已被选中、未被选中或已随需求关闭，不能再改',
  COMMON_FORBIDDEN: '“云翼科技”没有服务商资料，或账号不是服务商操作员',
};
// 报价内容的默认值（和原型 Quote 画板一致）。
const METHOD = '无人机 + 机器狗';
const SCHEDULE = '共 6 次 · 首次进场按约定';
const DELIVERABLES = '巡检报告、隐患清单';

const say = (line) => console.log(line);
const yuan = (minor) => (minor / 100).toFixed(2);

class Stop extends Error {}

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

async function call(method, route, { token, body } = {}) {
  const sent = { 'X-Request-Id': `dev-provider-${crypto.randomUUID()}` };
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
  if (result.status === 404) throw new Stop('没有这份需求，或者“云翼科技”看不到它（不在服务城市、没被邀请、也没报过价）。');
  throw new Stop(`${what}失败：${ERRORS[code] || `${result.status} ${code} ${result.json ? result.json.message : ''}`}`);
}

async function login() {
  const result = await call('POST', '/api/v1/auth/login', {
    body: { loginName: 'dev-provider', password: readDevPassword() },
  });
  if (result.status !== 200) {
    throw new Stop(`dev-provider 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  }
  return result.json.accessToken;
}

async function listAll(token) {
  const items = [];
  for (let page = 1; page <= 20; page += 1) {
    const result = await call('GET', `/api/v1/provider/demands?page=${page}&size=50`, { token });
    if (result.status !== 200) fail(result, '查需求');
    items.push(...result.json.items);
    if (!result.json.hasMore) break;
  }
  return items;
}

async function findDemand(token, ref) {
  const key = (ref || '').trim().toLowerCase();
  if (!/^[0-9a-f-]{8,36}$/.test(key)) throw new Stop('需求编号是 list 里每行开头的 8 位，或者完整的需求 ID');
  const found = (await listAll(token)).filter((demand) => demand.demandId.startsWith(key));
  if (found.length === 0) throw new Stop('没有这份需求，或者“云翼科技”看不到它（不在服务城市、没被邀请、也没报过价）。');
  if (found.length > 1) throw new Stop('有几份需求以这几位开头，多给几位');
  return found[0];
}

function budget(demand) {
  if (demand.budgetMinMinor == null && demand.budgetMaxMinor == null) return '预算面议';
  const min = demand.budgetMinMinor == null ? '' : `¥${yuan(demand.budgetMinMinor)}`;
  const max = demand.budgetMaxMinor == null ? '' : `¥${yuan(demand.budgetMaxMinor)}`;
  return `预算 ${min}–${max}`;
}

function myQuote(demand) {
  const quote = demand.myQuote;
  if (!quote) return '未报价';
  return `我的报价 v${quote.version} ¥${yuan(quote.amountMinor)} ${quote.durationDays} 天 ${QUOTE_TEXT[quote.status] || quote.status}`;
}

function describe(demand) {
  const source = demand.invited ? '邀请' : '平台匹配';
  const open = demand.quotingOpen ? `截止 ${new Date(demand.quoteDeadline).toLocaleString('zh-CN', { hour12: false })}` : '不再收报价';
  return `${demand.demandId.slice(0, 8)}  ${STATUS_TEXT[demand.status] || demand.status}  ${demand.title}  ${demand.cityName} · ${demand.address}  ${budget(demand)}  ${source}  ${open}  ${myQuote(demand)}`;
}

// 改价时没给工期就沿用上一版的工期。
function quoteBody(args, previous) {
  const amount = Math.round(Number(args[1]) * 100);
  const days = args[2] === undefined ? (previous ? previous.durationDays : 90) : Number(args[2]);
  const validDays = args[3] === undefined ? 7 : Number(args[3]);
  if (!Number.isFinite(amount) || amount < 1 || !Number.isInteger(days) || days < 1
      || !Number.isFinite(validDays) || validDays <= 0 || validDays > 30) {
    throw new Stop('用法：quote <需求编号> <金额（元）> [工期（天）] [有效天数 1–30]，例如 quote 1a2b3c4d 18600 90 7');
  }
  return {
    amountMinor: amount,
    method: METHOD,
    schedule: SCHEDULE,
    durationDays: days,
    deliverables: DELIVERABLES,
    validUntil: new Date(Date.now() + validDays * 24 * 3600 * 1000).toISOString(),
  };
}

async function main() {
  assertLoopback();
  const [command, ...args] = process.argv.slice(2);
  const token = await login();

  if (command === 'list') {
    const items = await listAll(token);
    if (items.length === 0) say('没有能看到的需求。在 App 里用 dev-customer 发布一份东营或济南的需求再试。');
    for (const demand of items) say(describe(demand));
    return;
  }

  if (command === 'show') {
    const demand = await findDemand(token, args[0]);
    say(describe(demand));
    say(`作业范围：${demand.scope}`);
    say(`期望完成：${demand.dueDate || '时间可协商'}`);
    say(`交付物：${demand.deliverables.map((item) => item.label).join('、') || '无'}`);
    if (demand.note) say(`补充说明：${demand.note}`);
    return;
  }

  if (command === 'quote' || command === 'revise') {
    const demand = await findDemand(token, args[0]);
    if (command === 'revise' && !demand.myQuote) throw new Stop('还没有报过价，用 quote');
    const result = await call('PUT', `/api/v1/provider/demands/${demand.demandId}/quote`, {
      token,
      body: quoteBody(args, demand.myQuote),
    });
    if (result.status === 400) throw new Stop(`报价内容不对：${result.json ? result.json.message : ''}`);
    if (result.status !== 200) fail(result, '报价');
    const quote = result.json.myQuote;
    say(`已${command === 'quote' ? '报价' : '改价'}：v${quote.version} ¥${yuan(quote.amountMinor)}，${quote.durationDays} 天，有效到 ${new Date(quote.validUntil).toLocaleString('zh-CN', { hour12: false })}。`);
    if (command === 'quote') say('客户会收到一条站内信，在 App“我的 → 需求与报价”里比选。');
    return;
  }

  if (command === 'withdraw') {
    const demand = await findDemand(token, args[0]);
    const result = await call('DELETE', `/api/v1/provider/demands/${demand.demandId}/quote`, { token });
    if (result.status !== 200) fail(result, '撤回');
    say('已撤回。截止前还可以再报价。');
    return;
  }

  throw new Stop('用法：list | show <需求编号> | quote <需求编号> <金额（元）> [工期（天）] [有效天数] | revise <需求编号> <金额（元）> [工期（天）] [有效天数] | withdraw <需求编号>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
