'use strict';

// 交易冒烟脚本共用的小工具。只对本机后端和本机模拟微信运行：开发账号的密码是公开的，脚本还会删测试数据。
//
//   BASE_URL          后端地址，默认 http://127.0.0.1:8080
//   FAKE_WECHAT_URL   模拟微信地址，默认 http://127.0.0.1:17460（infra/dev/fake-wechat/server.mjs）
//   FAKE_WECHAT_DIR   模拟微信的状态目录，默认仓库下的 .runtime/fake-wechat
//   PG_CONTAINER      必填，Postgres 容器名（脚本要清理测试数据、挪动时间）
//   PG_USER / PG_DB   可选；不填时用容器自己的 POSTGRES_USER / POSTGRES_DB
//
// 脚本只删 dev-customer 名下的订单、地址和对应的支付、退款、回调记录。

const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '../../..');
const BASE = (process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const FAKE = (process.env.FAKE_WECHAT_URL || 'http://127.0.0.1:17460').replace(/\/+$/, '');
const FAKE_DIR = process.env.FAKE_WECHAT_DIR || path.join(REPO, '.runtime', 'fake-wechat');
const PG_CONTAINER = process.env.PG_CONTAINER || '';
const BUYER = '11111111-1111-1111-1111-111111111111';
const SEED_FILE = path.join(REPO, 'backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql');

for (const url of [BASE, FAKE]) {
  const host = new URL(url).hostname;
  if (!['127.0.0.1', 'localhost', '[::1]', '::1'].includes(host)) {
    console.error(`只对本机运行：${url}`);
    process.exit(2);
  }
}
if (!PG_CONTAINER) {
  console.error('要设置 PG_CONTAINER（Postgres 容器名），脚本要清理测试数据');
  process.exit(2);
}

const password = (() => {
  const match = /crypt\('([^']+)'/.exec(fs.readFileSync(SEED_FILE, 'utf8'));
  if (!match) throw new Error(`没有在 ${SEED_FILE} 里找到开发账号的密码`);
  return match[1];
})();

/** 和开发种子 R__dev_mall_catalog.sql 一样的供给 ID：md5('dev-mall-offer:' || 商品 || ':' || 商家)。 */
function offerId(product, merchant) {
  const h = crypto.createHash('md5').update(`dev-mall-offer:${product}:${merchant}`).digest('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

let failures = 0;
function check(label, ok, detail) {
  if (!ok) failures += 1;
  const shown = detail === undefined ? '' : ` — ${(typeof detail === 'string' ? detail : JSON.stringify(detail)).slice(0, 700)}`;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${ok ? '' : shown}`);
}

async function call(method, route, { token, body, headers } = {}) {
  const response = await fetch(BASE + route, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const bytes = Buffer.from(await response.arrayBuffer());
  const text = bytes.toString('utf8');
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return {
    status: response.status,
    json,
    text,
    etag: response.headers.get('etag'),
    type: response.headers.get('content-type'),
    disposition: response.headers.get('content-disposition'),
    bom: bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])),
  };
}

async function fake(method, route) {
  const response = await fetch(FAKE + route, { method });
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: response.status, json };
}

/** 跑一条 SQL，返回去掉首尾空白的输出。SQL 从标准输入传，不经过 shell 转义。 */
function psql(sql) {
  return execFileSync('docker', [
    'exec', '-i',
    '-e', `PGU=${process.env.PG_USER || ''}`,
    '-e', `PGD=${process.env.PG_DB || ''}`,
    PG_CONTAINER, 'sh', '-c',
    'psql -v ON_ERROR_STOP=1 -At -U "${PGU:-$POSTGRES_USER}" -d "${PGD:-$POSTGRES_DB}"',
  ], { input: sql, encoding: 'utf8' }).trim();
}

async function login(name, pw = password) {
  const result = await call('POST', '/api/v1/auth/login', { body: { loginName: name, password: pw } });
  if (result.status !== 200) throw new Error(`${name} 登录失败（${result.status}）。后端要用 dev 配置启动。`);
  return result.json.accessToken;
}

/** 删掉 dev-customer 名下的订单、地址，以及这些订单的支付、退款、回调和状态记录。 */
/**
 * 删 dev-customer 名下的交易订单，连同指向这些订单的数据（S6-03）：交易站内信（交易物流，跳转目标是这张订单）和
 * 推送模拟记录、巡检付款尝试（inspection_payment_attempt.trade_order_id，没有外键）。巡检的预约、服务单和付款节点按设计保留
 * （S6-05），节点上记的首付退款单 ID 会指向已删的退款单（没有外键，只读页面上不显示退款进度）。
 */
function cleanupBuyer() {
  const ids = `SELECT id FROM trade_order WHERE buyer_id = '${BUYER}'`;
  // 先锁住这些订单：定时任务（超时关单、自动确认）用 SKIP LOCKED 会跳过它们，不会在删到一半时插进新的状态记录。
  psql(`
    BEGIN;
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE buyer_id = '${BUYER}' FOR UPDATE) locked;
    DELETE FROM trade_channel_notification WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (${ids}));
    DELETE FROM trade_refund WHERE order_id IN (${ids});
    DELETE FROM trade_payment WHERE order_id IN (${ids});
    DELETE FROM trade_state_transition WHERE order_id IN (${ids});
    DELETE FROM trade_order_line WHERE order_id IN (${ids});
    DELETE FROM trade_order_delivery WHERE order_id IN (${ids});
    DELETE FROM notification_message WHERE recipient_id = '${BUYER}' AND target_type = 'TRADE_ORDER' AND target_id IN (${ids});
    DELETE FROM notification_push_fake_delivery WHERE account_id = '${BUYER}' AND target_type = 'TRADE_ORDER' AND target_id IN (${ids});
    DELETE FROM inspection_payment_attempt WHERE trade_order_id IN (${ids});
    DELETE FROM trade_order WHERE buyer_id = '${BUYER}';
    DELETE FROM mall_cart_item WHERE account_id = '${BUYER}';
    DELETE FROM account_address WHERE owner_id = '${BUYER}';
    COMMIT;
  `);
}

const key = () => crypto.randomUUID();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate, seconds) {
  for (let waited = 0; waited < seconds; waited += 3) {
    if (predicate()) return true;
    await sleep(3000);
  }
  return predicate();
}

/** 结束：打印结果并退出；退出前一定清理。 */
function finish(cleanup) {
  return (error) => {
    if (error) {
      console.error(error);
      failures += 1;
    }
    try {
      if (!process.argv.includes('--keep')) cleanup();
    } catch (cleanupError) {
      console.error('清理失败', cleanupError);
      failures += 1;
    }
    console.log(failures === 0 ? '\nALL OK' : `\n${failures} FAILED`);
    process.exit(failures === 0 ? 0 : 1);
  };
}

module.exports = {
  BASE, FAKE, FAKE_DIR, BUYER, REPO, password,
  offerId, check, call, fake, psql, login, cleanupBuyer, key, sleep, waitFor, finish,
  failures: () => failures,
};
