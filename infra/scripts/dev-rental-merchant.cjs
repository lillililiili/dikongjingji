#!/usr/bin/env node
'use strict';

// 本地手工验收用：替出租方（商家）处理租赁询价和租赁订单。商家没有页面（商家工作台不在范围内），报价、改价、拒绝、
// 录入交接和归还、交接前取消只有受权接口。这个脚本用开发账号 dev-merchant（示例商家“示例机器人商家”的操作员，
// 出租“巡检机器狗 ¥380/天”）调这些接口；询价、确认合同、付租金、确认交接和归还仍然由你在 App 里用 dev-customer 做。
//
//   node infra/scripts/dev-rental-merchant.cjs inquiries [状态]
//   node infra/scripts/dev-rental-merchant.cjs show <询价编号>
//   node infra/scripts/dev-rental-merchant.cjs quote <询价编号> <日租金（元）> [天数] [台数] [有效天数] [不可用]
//   node infra/scripts/dev-rental-merchant.cjs revise <询价编号> <日租金（元）> [天数] [台数] [有效天数] [不可用]
//   node infra/scripts/dev-rental-merchant.cjs decline <询价编号> <原因>
//   node infra/scripts/dev-rental-merchant.cjs orders [状态]
//   node infra/scripts/dev-rental-merchant.cjs handover <订单编号> <说明>
//   node infra/scripts/dev-rental-merchant.cjs return <订单编号> <说明>
//   node infra/scripts/dev-rental-merchant.cjs cancel <订单编号> <原因>
//
//   询价编号、订单编号   inquiries / orders 里每行开头的 8 位（ID 的前 8 位），也可以给完整 ID
//   天数、台数           不填时按询价单（改价时按上一版报价）；有效天数默认 3 天（最多 7 天）
//   不可用               写“不可用”时报价说这段时间不能出租（客户不能确认合同）
//   说明                 交接、归还记录，如“编号 DOG-0118，配件齐全，外观无损伤”
//   状态                 询价单 PENDING / QUOTED / EXPIRED / CONFIRMED / DECLINED / CLOSED；
//                        订单 PENDING_PAYMENT / CLOSED / AWAITING_HANDOVER / HANDOVER_RECORDED / IN_USE / RETURN_RECORDED / ENDED / CANCELLED
//
//   BASE_URL   后端地址，默认 http://127.0.0.1:8080，只允许回环地址
//
// 交接前取消会整单原路退款：本机用模拟微信时，退款结果由 /_control/refund/<退款单号> 给出。脚本不绕过任何规则。
// 只在本机开发环境运行：账号和密码来自开发种子 R__dev_identity_accounts.sql。

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const SEED_FILE = path.join(
  __dirname,
  '../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
);
const INQUIRY_TEXT = {
  PENDING: '待报价', QUOTED: '待确认', EXPIRED: '报价已过期', CONFIRMED: '已确认合同', DECLINED: '已拒绝', CLOSED: '已关闭',
};
const ORDER_TEXT = {
  PENDING_PAYMENT: '待付款', CLOSED: '已关闭（未付款）', AWAITING_HANDOVER: '待交接', HANDOVER_RECORDED: '待客户确认交接',
  IN_USE: '使用中', RETURN_RECORDED: '待客户确认归还', ENDED: '已结束', CANCELLED: '已取消（整单退款）',
};
const MODE_TEXT = { DELIVERY: '商家送达', PICKUP: '到店自提', COLLECT: '商家上门取回', DROP_OFF: '送回门店' };
const ERRORS = {
  RENTAL_STATE_INVALID: '询价单或订单现在的状态不能做这个操作',
  RENTAL_REFUND_NOT_ALLOWED: '交易订单现在不能退款',
  COMMON_FORBIDDEN: 'dev-merchant 不是服务商操作员，或令牌里没有机构',
};
// 合同条款的默认文字（原型 RentalQuote 画板）。
const TERMS = [
  '押金：押金规则待定，当前不在线收取。',
  '超期：超过到期日 18:00 后按天计费，费率以合同为准。',
  '损坏处理：归还时核对交接记录，人为损坏按合同赔付；非人为故障由商家维修或更换。',
  '取消：确认合同前可以直接关闭询价；确认后取消按合同约定处理。',
].join('\n');

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
  const sent = { 'X-Request-Id': `dev-rental-${crypto.randomUUID()}` };
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
  if (result.status === 404) throw new Stop('没有这份询价单或订单，或者出租方不是“示例机器人商家”。');
  if (result.status === 400) throw new Stop(`${what}失败：内容不对（${result.json ? result.json.message : ''}）`);
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

async function listAll(token, route, status) {
  const items = [];
  for (let page = 1; page <= 20; page += 1) {
    const result = await call('GET', `${route}?page=${page}&size=50${status ? `&status=${status}` : ''}`, { token });
    if (result.status !== 200) fail(result, '查询');
    items.push(...result.json.items);
    if (!result.json.hasMore) break;
  }
  return items;
}

async function find(token, route, idField, ref) {
  const key = (ref || '').trim().toLowerCase();
  if (!/^[0-9a-f-]{8,36}$/.test(key)) throw new Stop('编号是列表里每行开头的 8 位，或者完整的 ID');
  const found = (await listAll(token, route)).filter((item) => item[idField].startsWith(key));
  if (found.length === 0) throw new Stop('没有这份询价单或订单，或者出租方不是“示例机器人商家”。');
  if (found.length > 1) throw new Stop('有几份以这几位开头，多给几位');
  return found[0];
}

const date = (text) => text.slice(5).replace('-', '/');

function describeInquiry(inquiry) {
  const quote = inquiry.quote
    ? `报价 v${inquiry.quote.version} 合计 ¥${yuan(inquiry.quote.totalMinor)}${inquiry.quote.expired ? '（已过期）' : ''}`
    : '未报价';
  const who = inquiry.lessee.kind === 'ORGANIZATION' ? `企业 · ${inquiry.lessee.organizationName}` : `个人 · ${inquiry.lessee.contactName}`;
  return `${inquiry.inquiryId.slice(0, 8)}  ${INQUIRY_TEXT[inquiry.status] || inquiry.status}  ${inquiry.item.productName} ×${inquiry.quantity}  `
    + `${date(inquiry.startDate)}–${date(inquiry.endDate)} 共 ${inquiry.days} 天  ${who}  ${quote}`;
}

function describeOrder(order) {
  const c = order.contract;
  return `${order.rentalOrderId.slice(0, 8)}  ${ORDER_TEXT[order.status] || order.status}  ${c.contractNo}  ${c.productName} ×${c.quantity}  `
    + `${date(c.startDate)}–${date(c.endDate)}  ¥${yuan(order.payment.amountMinor)}`;
}

async function quote(token, args, revise) {
  const inquiry = await find(token, '/api/v1/provider/rental-inquiries', 'inquiryId', args[0]);
  if (revise && !inquiry.quote) throw new Stop('还没有报过价，用 quote');
  const daily = Math.round(Number(args[1]) * 100);
  const base = inquiry.quote || inquiry;
  const days = args[2] === undefined ? base.days : Number(args[2]);
  const quantity = args[3] === undefined ? base.quantity : Number(args[3]);
  const validDays = args[4] === undefined ? 3 : Number(args[4]);
  if (!Number.isFinite(daily) || daily < 1 || !Number.isInteger(days) || !Number.isInteger(quantity)
      || !Number.isFinite(validDays) || validDays <= 0 || validDays > 7) {
    throw new Stop('用法：quote <询价编号> <日租金（元）> [天数] [台数] [有效天数 1–7] [不可用]，例如 quote 1a2b3c4d 380 5 1 3');
  }
  const result = await call('PUT', `/api/v1/provider/rental-inquiries/${inquiry.inquiryId}/quote`, {
    token,
    body: {
      dailyRentMinor: daily,
      days,
      quantity,
      fees: [
        { name: '随机配件', note: '按随箱清单', amountMinor: 0 },
        { name: '送达与取回', note: '东营市内', amountMinor: 0 },
      ],
      scheduleAvailable: args[5] !== '不可用',
      validUntil: new Date(Date.now() + validDays * 24 * 3600 * 1000).toISOString(),
      terms: TERMS,
    },
  });
  if (result.status !== 200) fail(result, revise ? '改价' : '报价');
  const q = result.json.quote;
  say(`已${revise ? '改价' : '报价'}：v${q.version} ¥${yuan(q.dailyRentMinor)}/天 × ${q.days} 天 × ${q.quantity} 台，合计 ¥${yuan(q.totalMinor)}，`
    + `有效到 ${new Date(q.validUntil).toLocaleString('zh-CN', { hour12: false })}${q.scheduleAvailable ? '' : '，档期不可用'}。`);
  say('客户在 App“我的 → 需求与报价”里打开租赁报价单确认合同。');
}

async function record(token, args, kind) {
  const order = await find(token, '/api/v1/provider/rental-orders', 'rentalOrderId', args[0]);
  const note = args.slice(1).join(' ').trim();
  if (!note) throw new Stop(`用法：${kind} <订单编号> <说明>，例如 ${kind} 1a2b3c4d 编号 DOG-0118，配件齐全，外观无损伤`);
  const result = await call('POST', `/api/v1/provider/rental-orders/${order.rentalOrderId}/${kind}`, {
    token, body: { note },
  });
  if (result.status !== 200) fail(result, kind === 'handover' ? '录入交接' : '录入归还');
  say(`已录入${kind === 'handover' ? '交接' : '归还'}记录：${ORDER_TEXT[result.json.status]}。客户在 App 的租赁订单里确认。`);
}

async function main() {
  assertLoopback();
  const [command, ...args] = process.argv.slice(2);
  const token = await login();

  if (command === 'inquiries') {
    const items = await listAll(token, '/api/v1/provider/rental-inquiries', (args[0] || '').toUpperCase());
    if (items.length === 0) say('没有询价单。在 App 里用 dev-customer 打开“巡检机器狗”，点“联系询价”提交一份。');
    for (const inquiry of items) say(describeInquiry(inquiry));
    return;
  }

  if (command === 'show') {
    const inquiry = await find(token, '/api/v1/provider/rental-inquiries', 'inquiryId', args[0]);
    say(describeInquiry(inquiry));
    say(`交接：${MODE_TEXT[inquiry.handoverMode]}；归还：${MODE_TEXT[inquiry.returnMode]}`);
    say(`地点：${inquiry.place.cityName} ${inquiry.place.detail}（${inquiry.place.recipientName} ${inquiry.place.phone}）`);
    if (inquiry.note) say(`补充说明：${inquiry.note}`);
    if (inquiry.declineReason) say(`拒绝原因：${inquiry.declineReason}`);
    return;
  }

  if (command === 'quote' || command === 'revise') {
    await quote(token, args, command === 'revise');
    return;
  }

  if (command === 'decline') {
    const inquiry = await find(token, '/api/v1/provider/rental-inquiries', 'inquiryId', args[0]);
    const reason = args.slice(1).join(' ').trim();
    if (!reason) throw new Stop('用法：decline <询价编号> <原因>');
    const result = await call('POST', `/api/v1/provider/rental-inquiries/${inquiry.inquiryId}/decline`, {
      token, body: { reason },
    });
    if (result.status !== 200) fail(result, '拒绝');
    say('已拒绝这份询价。');
    return;
  }

  if (command === 'orders') {
    const items = await listAll(token, '/api/v1/provider/rental-orders', (args[0] || '').toUpperCase());
    if (items.length === 0) say('没有租赁订单。');
    for (const order of items) say(describeOrder(order));
    return;
  }

  if (command === 'handover' || command === 'return') {
    await record(token, args, command);
    return;
  }

  if (command === 'cancel') {
    const order = await find(token, '/api/v1/provider/rental-orders', 'rentalOrderId', args[0]);
    const reason = args.slice(1).join(' ').trim();
    if (!reason) throw new Stop('用法：cancel <订单编号> <原因>，例如 cancel 1a2b3c4d 档期临时冲突');
    const result = await call('POST', `/api/v1/provider/rental-orders/${order.rentalOrderId}/cancel`, {
      token, body: { reason },
    });
    if (result.status !== 200) fail(result, '取消');
    say(`已取消，租金 ¥${yuan(result.json.refund.amountMinor)} 整单原路退回（${result.json.refund.status}）。`);
    say('本机用模拟微信时，在 /_control/refunds 找到退款单号，再用 /_control/refund/<退款单号>?notify=1 给出结果。');
    return;
  }

  throw new Stop('用法：inquiries [状态] | show <询价编号> | quote|revise <询价编号> <日租金（元）> [天数] [台数] [有效天数] [不可用] | '
    + 'decline <询价编号> <原因> | orders [状态] | handover|return <订单编号> <说明> | cancel <订单编号> <原因>');
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(error.message);
    process.exit(1);
  }
  console.error(error);
  process.exit(2);
});
