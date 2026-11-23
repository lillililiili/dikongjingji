#!/usr/bin/env node
'use strict';

// E06 对账：账单生成中、四种差异、CSV、重跑跳过、文件被改坏不盖掉旧结果。临时建一个平台运营账号，跑完删掉。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉 dev-customer 的订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/reconciliation.cjs

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { BASE, FAKE, FAKE_DIR, BUYER, offerId, check, call, fake, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./lib.cjs');
const KIT = offerId('101', '1');
const PRICE = 129900;
const SUB = '1900000101';
const OPERATOR = 'f0000000-0000-4000-8000-0000000000e6';
const OPERATOR_ORG = 'f0000000-0000-4000-8000-0000000000e0';
const tempPassword = crypto.randomBytes(18).toString('base64url');

const yesterday = new Date(Date.now() + 8 * 3600 * 1000 - 86400000).toISOString().slice(0, 10);
const today = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);

function cleanup() {
  const ids = `SELECT id FROM trade_order WHERE buyer_id='${BUYER}'`;
  psql(`DELETE FROM trade_channel_notification WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (${ids}))`);
  psql(`DELETE FROM trade_refund WHERE order_id IN (${ids})`);
  psql(`DELETE FROM trade_payment WHERE order_id IN (${ids})`);
  for (const table of ['trade_state_transition', 'trade_order_line', 'trade_order_delivery']) psql(`DELETE FROM ${table} WHERE order_id IN (${ids})`);
  // Trade notices (R6-5) and fake pushes point at the orders; delete them with the orders (S6-03).
  for (const table of ['notification_message', 'notification_push_fake_delivery']) {
    const owner = table === 'notification_message' ? 'recipient_id' : 'account_id';
    psql(`DELETE FROM ${table} WHERE ${owner}='${BUYER}' AND target_type='TRADE_ORDER' AND target_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}')`);
  }
  psql(`DELETE FROM trade_order WHERE buyer_id='${BUYER}'`);
  psql(`DELETE FROM account_address WHERE owner_id='${BUYER}'`);
  psql(`DELETE FROM trade_reconciliation_difference WHERE reconciliation_id IN (SELECT id FROM trade_reconciliation WHERE bill_date IN ('${yesterday}', '${today}'))`);
  psql(`DELETE FROM trade_reconciliation WHERE bill_date IN ('${yesterday}', '${today}')`);
  psql(`DELETE FROM identity_user_role WHERE user_id='${OPERATOR}'`);
  psql(`DELETE FROM identity_organization_member WHERE user_id='${OPERATOR}'`);
  // Refresh tokens (identity V008) reference the user.
  psql(`DELETE FROM identity_refresh_token WHERE user_id='${OPERATOR}'`);
  // Login sessions (identity V010) reference the user too.
  psql(`DELETE FROM identity_login_session WHERE user_id='${OPERATOR}'`);
  psql(`DELETE FROM identity_user WHERE id='${OPERATOR}'`);
  psql(`DELETE FROM identity_organization WHERE id='${OPERATOR_ORG}'`);
}

function createOperator() {
  psql(`INSERT INTO identity_organization (id, organization_code, organization_name, organization_type) VALUES ('${OPERATOR_ORG}', 'TMP-E06-PLATFORM', '临时平台运营（E06 冒烟）', 'PLATFORM')`);
  psql(`INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id) VALUES ('${OPERATOR}', 'tmp-e06-platform', '临时平台运营', crypt('${tempPassword}', gen_salt('bf', 10)), '${OPERATOR_ORG}')`);
  psql(`INSERT INTO identity_organization_member (organization_id, user_id) VALUES ('${OPERATOR_ORG}', '${OPERATOR}')`);
  psql(`INSERT INTO identity_user_role (user_id, role_id, organization_id) SELECT '${OPERATOR}', id, NULL FROM identity_role WHERE role_code = 'PLATFORM_OPERATOR'`);
}

let customer;
let addressId;
async function paidOrder(notifyTotal) {
  const submitted = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: { cityCode: '370500', offerId: KIT, quantity: 1, deliveryMode: 'EXPRESS', addressId, expectedPayableMinor: PRICE, agreementAccepted: true } });
  const id = submitted.json.orderId;
  await call('POST', `/api/v1/trade/orders/${id}/payments`, { token: customer, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });
  const no = psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${id}'`);
  await fake('POST', `/_control/pay/${no}?notify=1${notifyTotal ? `&total=${notifyTotal}` : ''}`);
  return { id, no, version: Number(psql(`SELECT version FROM trade_order WHERE id='${id}'`)) };
}

async function main() {
  cleanup();
  // 模拟微信把以前跑过的单都留在内存里，先清空，账单里才只有这一轮的。
  await fake('POST', '/_control/reset');
  createOperator();
  customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  const platform = await login('tmp-e06-platform', tempPassword);
  addressId = (await call('POST', '/api/v1/account/addresses', { token: customer, body: { recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '东营区 示例路 88 号', isDefault: true } })).json.addressId;

  // 造一天的单：A、B 对得上（B 还有一笔部分退款），C 微信账单里没有，D 金额被改，E 平台记为金额不符，外加一笔平台没有的
  const a = await paidOrder();
  const b = await paidOrder();
  const refund = await call('POST', `/api/v1/provider/trade/orders/${b.id}/refunds`, { token: merchant, headers: { 'Idempotency-Key': key() }, body: { amountMinor: 10000, reason: '少发一个配件' } });
  check('partial refund accepted', refund.status === 201 && refund.json.status === 'PROCESSING', refund.json);
  const refundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${b.id}'`);
  check('refund acceptance time recorded', psql(`SELECT accepted_at IS NOT NULL FROM trade_refund WHERE order_id='${b.id}'`) === 't');
  const c = await paidOrder();
  const d = await paidOrder();
  const e = await paidOrder(1);
  check('E is held as MISMATCH by the platform', psql(`SELECT status FROM trade_payment WHERE order_id='${e.id}'`) === 'MISMATCH');

  // 挪到昨天：平台的付款、受理时间和模拟微信的记录一起往前挪一天
  psql(`UPDATE trade_payment SET succeeded_at = succeeded_at - interval '1 day' WHERE order_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}') AND succeeded_at IS NOT NULL`);
  psql(`UPDATE trade_refund SET accepted_at = accepted_at - interval '1 day' WHERE order_id IN (SELECT id FROM trade_order WHERE buyer_id='${BUYER}')`);
  await fake('POST', '/_control/shift-days?days=-1');
  // E 在平台是 MISMATCH、没有付款时间，挪不挪都一样；账单里按微信的付款时间出现在昨天。
  await fake('POST', `/_control/bill?drop=${c.no}&amount=${d.no}:129800&extra=${SUB}`);

  check('reconciliation needs the platform role', (await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: merchant })).status === 403);
  let r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: platform });
  check('nothing imported yet is 409', r.status === 409 && r.json.code === 'TRADE_RECONCILIATION_NOT_READY', r.json);
  check('today cannot be reconciled', (await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${today}`, { token: platform })).status === 400);

  await fake('POST', '/_control/bill?creating=1');
  r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, { token: platform });
  check('bill still being generated: recorded as failed', r.status === 200 && r.json.failed === r.json.merchants && r.json.imported === 0, r.json);
  check('failed run is still not ready', (await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: platform })).status === 409);
  check('failure reason kept', psql(`SELECT failure_reason FROM trade_reconciliation WHERE bill_date='${yesterday}' AND sub_mchid='${SUB}'`).startsWith('STATEMENT_CREATING'));

  await fake('POST', '/_control/bill?creating=0');
  r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, { token: platform });
  check('second run imports every merchant', r.status === 200 && r.json.imported === r.json.merchants && r.json.failed === 0 && r.json.differences === 4, r.json);
  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: platform });
  const diffs = r.json?.differences || [];
  const find = (kind) => diffs.filter((x) => x.kind === kind);
  check('A, B and B\'s refund match', r.status === 200 && r.json.matchedCount === 3, r.json);
  check('C is only in the platform', find('ONLY_IN_PLATFORM').length === 1 && find('ONLY_IN_PLATFORM')[0].outTradeNo === c.no && find('ONLY_IN_PLATFORM')[0].platformAmountMinor === PRICE, diffs);
  check('D has a different amount', find('AMOUNT_MISMATCH').length === 1 && find('AMOUNT_MISMATCH')[0].outTradeNo === d.no && find('AMOUNT_MISMATCH')[0].channelAmountMinor === 129800, diffs);
  check('the extra one is only in the channel', find('ONLY_IN_CHANNEL').length === 1 && find('ONLY_IN_CHANNEL')[0].outTradeNo.startsWith('PFAKE') && find('ONLY_IN_CHANNEL')[0].channelAmountMinor === 990, diffs);
  check('E has a status mismatch', find('STATUS_MISMATCH').length === 1 && find('STATUS_MISMATCH')[0].outTradeNo === e.no, diffs);
  check('every difference names the sub merchant', diffs.every((x) => x.subMchid === SUB));
  check('refund matched, not reported', diffs.every((x) => x.outRefundNo !== refundNo));

  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}&format=CSV`, { token: platform });
  const lines = r.text.replace(/^﻿/, '').trim().split('\r\n');
  check('CSV download', r.status === 200 && r.type.startsWith('text/csv') && r.disposition.includes(`reconciliation-${yesterday}.csv`) && r.bom && lines.length === 5 && lines[0].startsWith('日期,类型,说明,子商户号'), [r.status, r.type, r.disposition, lines]);
  check('CSV amounts in yuan', lines.some((line) => line.includes('AMOUNT_MISMATCH') && line.endsWith('1299.00,1298.00')), lines);
  check('bad format is 400', (await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}&format=XML`, { token: platform })).status === 400);

  r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}`, { token: platform });
  check('running again skips what is imported', r.json.skipped === r.json.merchants && r.json.imported === 0, r.json);
  await fake('POST', '/_control/bill?corrupt=1');
  r = await call('POST', `/api/v1/platform/trade/reconciliation/runs?date=${yesterday}&redo=true`, { token: platform });
  check('a corrupted bill fails the redo', r.json.failed >= 1, r.json);
  r = await call('GET', `/api/v1/platform/trade/reconciliation?date=${yesterday}`, { token: platform });
  check('but does not erase the earlier import', r.status === 200 && r.json.differences.length === 4, r.json);

}
const done = finish(cleanup);
const resetBill = () => fake('POST', '/_control/bill?reset=1').catch(() => {});
main().then(() => resetBill().then(() => done()), (error) => resetBill().then(() => done(error)));
