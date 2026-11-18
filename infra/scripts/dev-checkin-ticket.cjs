#!/usr/bin/env node
'use strict';

// 本地手工验收用：替活动主办方核销一张报名凭证。
// 主办方没有页面（核销工作台不在范围内），核销只有接口。这个脚本用开发账号 dev-organizer
// （示例场馆“东营低空与机器人体验馆”的操作员）调这个接口；报名和取消仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-checkin-ticket.cjs <凭证号>
//
//   凭证号     App 报名凭证页上的“凭证号”，形如 ACT-XXXX-XXXX-XXXX
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 核销有时间窗口：场次的签到开放时间（没设置时是开始前 1 小时）到场次结束。
// 脚本不绕过这个规则，窗口之外会如实告诉你“不在签到时间内”。
//
// 只在本机开发环境运行：账号和密码来自开发种子 R__dev_identity_accounts.sql。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const TICKET_CODE = /^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/;

const say = (line) => console.log(line);

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
  const headers = { 'X-Request-Id': `dev-checkin-${crypto.randomUUID()}` };
  if (token) headers.Authorization = `Bearer ${token}`;
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

function beijing(iso) {
  return new Date(iso).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

async function main() {
  assertLoopback();
  const ticketCode = (process.argv[2] || '').trim().toUpperCase();
  if (!TICKET_CODE.test(ticketCode)) {
    throw new Stop('用法：node infra/scripts/dev-checkin-ticket.cjs <凭证号>，凭证号形如 ACT-XXXX-XXXX-XXXX');
  }

  const login = await call('POST', '/api/v1/auth/login', {
    body: { loginName: 'dev-organizer', password: readDevPassword() },
  });
  if (login.status !== 200) {
    throw new Stop(`dev-organizer 登录失败（${login.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  }

  const result = await call('POST', '/api/v1/activity-checkins', {
    token: login.json.accessToken,
    body: { ticketCode },
  });
  if (result.status === 200) {
    const view = result.json;
    const session = [view.sessionLabel, beijing(view.sessionStartsAt)].filter(Boolean).join(' ');
    say(view.repeated
      ? `这张凭证已经核销过了：${view.participantName}，${beijing(view.checkedInAt)} 签到。没有重复记录。`
      : `签到成功：${view.participantName}，${view.activityTitle}（${session}）。`);
    say('在 App 的报名凭证页重新进入，可以看到“已签到”。');
    return;
  }
  const code = result.json ? result.json.code : '';
  if (result.status === 404) {
    throw new Stop('没有这张凭证，或者它不是“东营低空与机器人体验馆”主办或承办的活动。');
  }
  if (code === 'ACT_CHECKIN_NOT_OPEN') {
    throw new Stop('现在不在这一场的签到时间内（签到开放时间到场次结束之间才能核销）。');
  }
  if (code === 'ACT_TICKET_NOT_VALID') {
    throw new Stop('这张凭证已经取消，不能核销。');
  }
  throw new Stop(`核销返回 ${result.status} ${code} ${result.json ? result.json.message : ''}`);
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
