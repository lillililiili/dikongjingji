#!/usr/bin/env node
'use strict';

// 本地手工验收用：替商城商家处理订单。商家没有页面（商家工作台不在范围内），发货、自提核销、退款只有受权接口。
// 这个脚本用开发账号 dev-merchant（示例商家“示例机器人商家”的操作员）调这些接口；
// 下单、付款、取消、确认收货仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-merchant-order.cjs list [状态]
//   node infra/scripts/dev-merchant-order.cjs ship <订单编号> [物流公司] [运单号]
//   node infra/scripts/dev-merchant-order.cjs pickup <订单编号> <取货码>
//   node infra/scripts/dev-merchant-order.cjs refund <订单编号> <金额（元）> <原因>
//
//   订单编号   App 订单详情里的“订单编号”，形如 MO2026111812345678
//   状态       PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CLOSED / REFUNDING / REFUNDED / REFUND_FAILED
//   取货码     买家订单详情里的 6 位数字
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 退款要真的走一遍渠道：本机用 .planning/stack/fake-wechat 的模拟微信（后端用 FAKE_WECHAT=1 启动），
// 退款结果由模拟微信的 /_control/refund/<退款单号> 给出。脚本不绕过任何规则：状态不对、金额超了都会如实告诉你。
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
const ORDER_NO = /^MO\d{16}$/;
const STATUSES = ['PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'CLOSED', 'REFUNDING', 'REFUNDED', 'REFUND_FAILED'];
const STATUS_TEXT = {
  PENDING_PAYMENT: '待付款', PAID: '已付款', SHIPPED: '已发货', COMPLETED: '已完成', CLOSED: '已关闭',
  REFUNDING: '退款中', REFUNDED: '已退款', REFUND_FAILED: '退款异常',
};
const REFUND_TEXT = { REQUESTED: '已建，等提交微信', PROCESSING: '微信处理中', SUCCEEDED: '已到账', CLOSED: '已关闭', ABNORMAL: '退款异常' };
const ERRORS = {
  TRADE_ORDER_STATE_INVALID: '订单现在的状态不能做这个操作',
  TRADE_VERSION_CONFLICT: '订单刚刚变过，再运行一次',
  TRADE_PICKUP_CODE_INVALID: '取货码不对（连续输错 10 次后这张单会锁定）',
  TRADE_REFUND_EXCEEDS_PAID: '退款金额超过了还能退的钱',
  TRADE_REFUND_LIMIT_REACHED: '这张单已不能再退款（最多 50 次、付款后一年内）',
  TRADE_CHANNEL_UNAVAILABLE: '支付渠道没开或连不上。本机要先启动模拟微信，后端用 FAKE_WECHAT=1 启动',
};

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

async function call(method, route, { token, body, headers } = {}) {
  const sent = { 'X-Request-Id': `dev-merchant-${crypto.randomUUID()}`, ...headers };
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
  if (result.status === 404) throw new Stop('没有这张订单，或者它不是“示例机器人商家”卖出的。');
  throw new Stop(`${what}失败：${ERRORS[code] || `${result.status} ${code} ${result.json ? result.json.message : ''}`}`);
}

async function login() {
  const result = await call('POST', '/api/v1/auth/login', {
    body: { loginName: 'dev-merchant', password: readDevPassword() },
  });
  if (result.status !== 200) {
    throw new Stop(`dev-merchant 登录失败（${result.status}）。后端要用 dev 配置启动，开发账号才存在。`);
  }
  return result.json.accessToken;
}

async function findOrder(token, orderNo) {
  if (!ORDER_NO.test(orderNo)) throw new Stop('订单编号形如 MO2026111812345678（App 订单详情里有）');
  for (let page = 1; page <= 20; page += 1) {
    const result = await call('GET', `/api/v1/provider/trade/orders?page=${page}&size=50`, { token });
    if (result.status !== 200) fail(result, '查订单');
    const found = result.json.items.find((order) => order.orderNo === orderNo);
    if (found) return found;
    if (!result.json.hasMore) break;
  }
  throw new Stop('没有这张订单，或者它不是“示例机器人商家”卖出的。');
}

function describe(order) {
  const delivery = order.delivery.mode === 'PICKUP' ? `自提（${order.delivery.pickupPoint}）` : '快递';
  const refunded = order.amounts.refundedMinor > 0 ? `，已退 ¥${yuan(order.amounts.refundedMinor)}` : '';
  return `${order.orderNo}  ${STATUS_TEXT[order.status] || order.status}  ¥${yuan(order.amounts.payableMinor)}${refunded}  ${delivery}  ${order.lines.map((line) => `${line.productName}×${line.quantity}`).join('、')}`;
}

async function main() {
  assertLoopback();
  const [command, ...args] = process.argv.slice(2);
  const token = await login();

  if (command === 'list') {
    const status = (args[0] || '').trim().toUpperCase();
    if (status && !STATUSES.includes(status)) throw new Stop(`状态只能是 ${STATUSES.join(' / ')}`);
    const result = await call('GET', `/api/v1/provider/trade/orders?size=50${status ? `&status=${status}` : ''}`, { token });
    if (result.status !== 200) fail(result, '查订单');
    if (result.json.items.length === 0) say('没有订单。');
    for (const order of result.json.items) say(describe(order));
    if (result.json.hasMore) say(`……一共 ${result.json.total} 张，只列了最新的 50 张`);
    return;
  }

  if (command === 'ship') {
    const order = await findOrder(token, args[0] || '');
    const carrierName = args[1] || '示例快递';
    const trackingNo = args[2] || `SL${Date.now()}`;
    const result = await call('POST', `/api/v1/provider/trade/orders/${order.orderId}/shipment`, {
      token,
      headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${order.version}"` },
      body: { carrierName, trackingNo },
    });
    if (result.status !== 200) fail(result, '发货');
    say(`已发货：${carrierName} ${trackingNo}。买家在 App 里能看到物流单号，10 天内不确认收货会自动确认。`);
    return;
  }

  if (command === 'pickup') {
    const order = await findOrder(token, args[0] || '');
    const result = await call('POST', `/api/v1/provider/trade/orders/${order.orderId}/pickup`, {
      token,
      headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${order.version}"` },
      body: { pickupCode: (args[1] || '').trim() },
    });
    if (result.status === 400) throw new Stop('取货码是 6 位数字');
    if (result.status !== 200) fail(result, '核销');
    say('核销成功，订单已完成。');
    return;
  }

  if (command === 'refund') {
    const order = await findOrder(token, args[0] || '');
    const amount = Math.round(Number(args[1]) * 100);
    const reason = args.slice(2).join(' ').trim();
    if (!Number.isFinite(amount) || amount < 1 || !reason) {
      throw new Stop('用法：refund <订单编号> <金额（元）> <原因>，例如 refund MO2026111812345678 20 少发一个配件');
    }
    const result = await call('POST', `/api/v1/provider/trade/orders/${order.orderId}/refunds`, {
      token,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { amountMinor: amount, reason },
    });
    if (result.status === 400) throw new Stop('金额要大于 0，原因 1 到 80 个字');
    if (result.status !== 201) fail(result, '退款');
    say(`已申请退款 ¥${yuan(result.json.amountMinor)}：${REFUND_TEXT[result.json.status] || result.json.status}。`);
    say('到账以微信的结果为准；本机用模拟微信时，在 /_control/refunds 找到退款单号，再用 /_control/refund/<退款单号>?notify=1 给出结果。');
    return;
  }

  throw new Stop('用法：list [状态] | ship <订单编号> [物流公司] [运单号] | pickup <订单编号> <取货码> | refund <订单编号> <金额（元）> <原因>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
