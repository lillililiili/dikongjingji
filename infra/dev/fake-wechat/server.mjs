// 本机模拟的微信支付：只给开发机上跑通交易链路用（计划 docs/redesign/03-App实现任务计划.md §19.12–§19.16）。
// 只听 127.0.0.1。第一次运行时现生成一套测试密钥（不是任何真实商户的），写到 FAKE_WECHAT_DIR（默认仓库下被 git 忽略的
// .runtime/fake-wechat/，权限 600），并写出 backend.env 给后端用：
//   node infra/dev/fake-wechat/server.mjs
//   set -a; . .runtime/fake-wechat/backend.env; set +a   # 再启动后端
//   FAKE_WECHAT_PORT          默认 17460
//   FAKE_WECHAT_BACKEND_URL   后端地址（回调发到这里），默认 http://127.0.0.1:8080
// 回调和账单都按微信文档的格式签名、加密；它只是测试替身，真实微信的差别只有联调能发现。
// 实现：APP 下单、按商户单号查单、关单；应答用“平台私钥”签名，后端用对应公钥验。
// 控制入口（不签名，只在 127.0.0.1）：
//   POST /_control/pay/<out_trade_no>?notify=1&total=<分>   让这一单付款成功；notify=1 时按下单时的 notify_url 发回调
//   POST /_control/renotify/<out_trade_no>                    把上一条回调原样再发一次（同一个通知 ID）
//   GET  /_control/orders                                     看所有单
// 退款（E05）：
//   POST /_control/refund/<out_refund_no>?status=SUCCESS|CLOSED|ABNORMAL&notify=1   给出退款结果；notify=1 时发退款回调
//   POST /_control/reject-next-refund?code=NOT_ENOUGH                               下一次申请退款直接拒绝
//   GET  /_control/refunds                                                          看所有退款
// 账单（E06）：
//   POST /_control/bill?creating=1|0&corrupt=1|0&drop=<单号>&amount=<单号>:<分>&extra=<子商户号>&reset=1   改之后生成的账单（extra：给这个子商户的账单多加一笔平台没有的单）
//   POST /_control/reset                                                             清空所有单、退款、账单和规则
//   POST /_control/shift-days?days=-1                                                把所有支付、退款的时间挪几天（对账只能对今天以前）
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../../..');
const STATE = process.env.FAKE_WECHAT_DIR || path.join(REPO, '.runtime', 'fake-wechat');
const SECRETS = path.join(STATE, 'secrets');
const BACKEND_URL = (process.env.FAKE_WECHAT_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const PORT = Number(process.env.FAKE_WECHAT_PORT || 17460);
const SP_MCHID = '1900000001';
const SP_APPID = 'wxdevfakeapp000001';
const MERCHANT_SERIAL = '5157F09EFDC096DE15EBE81A47057A7232F1B8E1';
const PUB_KEY_ID = 'PUB_KEY_ID_0000000000000000000000000001';

function ensureSecrets() {
  fs.mkdirSync(SECRETS, { recursive: true, mode: 0o700 });
  const file = (name) => path.join(SECRETS, name);
  if (!fs.existsSync(file('apiclient_key.pem'))) {
    const merchant = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
    const platform = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
    const write = (name, text) => fs.writeFileSync(file(name), text, { mode: 0o600 });
    write('apiclient_key.pem', merchant.privateKey.export({ type: 'pkcs8', format: 'pem' }));
    write('merchant_pub.pem', merchant.publicKey.export({ type: 'spki', format: 'pem' }));
    write('platform_key.pem', platform.privateKey.export({ type: 'pkcs8', format: 'pem' }));
    write('pub_key.pem', platform.publicKey.export({ type: 'spki', format: 'pem' }));
    write('apiv3.txt', crypto.randomBytes(16).toString('hex'));
  }
  const apiV3 = fs.readFileSync(file('apiv3.txt'), 'utf8').trim();
  fs.writeFileSync(path.join(STATE, 'backend.env'), [
    'TRADE_WECHAT_ENABLED=true',
    `TRADE_WECHAT_SP_MCHID=${SP_MCHID}`,
    `TRADE_WECHAT_SP_APPID=${SP_APPID}`,
    `TRADE_WECHAT_MERCHANT_SERIAL_NO=${MERCHANT_SERIAL}`,
    `TRADE_WECHAT_MERCHANT_PRIVATE_KEY_PATH=${file('apiclient_key.pem')}`,
    `TRADE_WECHAT_API_V3_KEY=${apiV3}`,
    `TRADE_WECHAT_PLATFORM_PUBLIC_KEY_ID=${PUB_KEY_ID}`,
    `TRADE_WECHAT_PLATFORM_PUBLIC_KEY_PATH=${file('pub_key.pem')}`,
    `TRADE_WECHAT_API_BASE=http://127.0.0.1:${PORT}`,
    `TRADE_WECHAT_PAYMENT_NOTIFY_URL=${BACKEND_URL}/api/v1/trade/channels/wechat/notifications`,
    `TRADE_WECHAT_REFUND_NOTIFY_URL=${BACKEND_URL}/api/v1/trade/channels/wechat/notifications`,
    '',
  ].join('\n'), { mode: 0o600 });
  return {
    merchantPublic: crypto.createPublicKey(fs.readFileSync(file('merchant_pub.pem'))),
    platformPrivate: crypto.createPrivateKey(fs.readFileSync(file('platform_key.pem'))),
    apiV3: Buffer.from(apiV3, 'utf8'),
  };
}

const keys = ensureSecrets();
const orders = new Map();
const lastNotification = new Map();
const refunds = new Map();
let rejectNextRefund = null;
const bills = new Map();
let billRules = { creating: false, corrupt: false, drop: new Set(), amount: new Map(), extra: null };

function signedHeaders(body) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = crypto.randomBytes(16).toString('hex').toUpperCase();
  const signature = crypto.sign('sha256', Buffer.from(`${timestamp}\n${nonce}\n${body}\n`), keys.platformPrivate).toString('base64');
  return {
    'Wechatpay-Timestamp': timestamp,
    'Wechatpay-Nonce': nonce,
    'Wechatpay-Signature': signature,
    'Wechatpay-Serial': PUB_KEY_ID,
    'Wechatpay-Signature-Type': 'WECHATPAY2-SHA256-RSA2048',
  };
}

function reply(res, status, object) {
  const body = object === undefined ? '' : JSON.stringify(object);
  res.writeHead(status, { ...signedHeaders(body), ...(body ? { 'Content-Type': 'application/json' } : {}) });
  res.end(body);
}

// 按 5 行规则核对后端的请求签名。
function checkAuthorization(req, body) {
  const header = req.headers.authorization || '';
  if (!header.startsWith('WECHATPAY2-SHA256-RSA2048 ')) return 'schema';
  const fields = Object.fromEntries([...header.matchAll(/(\w+)="([^"]*)"/g)].map((m) => [m[1], m[2]]));
  if (fields.mchid !== SP_MCHID || fields.serial_no !== MERCHANT_SERIAL) return 'identity';
  if (Math.abs(Date.now() / 1000 - Number(fields.timestamp)) > 300) return 'timestamp';
  const message = `${req.method}\n${req.url}\n${fields.timestamp}\n${fields.nonce_str}\n${body}\n`;
  return crypto.verify('sha256', Buffer.from(message), keys.merchantPublic, Buffer.from(fields.signature || '', 'base64')) ? null : 'signature';
}

function rfc3339(date) {
  const beijing = new Date(date.getTime() + 8 * 3600 * 1000);
  return beijing.toISOString().replace(/\.\d{3}Z$/, '+08:00');
}

function queryView(order) {
  const view = {
    sp_appid: SP_APPID, sp_mchid: SP_MCHID, sub_mchid: order.sub_mchid, out_trade_no: order.out_trade_no,
    trade_type: 'APP', trade_state: order.state, trade_state_desc: order.state,
    amount: { total: order.paid_total ?? order.total, payer_total: order.paid_total ?? order.total, currency: 'CNY', payer_currency: 'CNY' },
  };
  if (order.state === 'SUCCESS') {
    Object.assign(view, { transaction_id: order.transaction_id, success_time: order.success_time, bank_type: 'OTHERS', payer: { sp_openid: 'o-dev-fake' } });
  }
  return view;
}

function refundView(refund) {
  const view = {
    refund_id: refund.refund_id, out_refund_no: refund.out_refund_no, transaction_id: refund.transaction_id,
    out_trade_no: refund.out_trade_no, channel: 'ORIGINAL', user_received_account: '支付用户零钱',
    create_time: refund.create_time, status: refund.status,
    amount: { refund: refund.refund, payer_refund: refund.refund, discount_refund: 0, currency: 'CNY' },
  };
  if (refund.status === 'SUCCESS') view.success_time = refund.success_time;
  return view;
}

async function notifyRefund(refund) {
  const plain = JSON.stringify({
    sp_mchid: SP_MCHID, sub_mchid: refund.sub_mchid, out_trade_no: refund.out_trade_no, transaction_id: refund.transaction_id,
    out_refund_no: refund.out_refund_no, refund_id: refund.refund_id, refund_status: refund.status,
    ...(refund.status === 'SUCCESS' ? { success_time: refund.success_time } : {}),
    user_received_account: '支付用户零钱',
    amount: { total: refund.total, refund: refund.refund, payer_total: refund.total, payer_refund: refund.refund },
  });
  const nonce = crypto.randomBytes(6).toString('hex');
  const cipher = crypto.createCipheriv('aes-256-gcm', keys.apiV3, Buffer.from(nonce, 'utf8'));
  cipher.setAAD(Buffer.from('refund', 'utf8'));
  const sealed = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]).toString('base64');
  const body = JSON.stringify({
    id: crypto.randomUUID(), create_time: rfc3339(new Date()), resource_type: 'encrypt-resource', event_type: `REFUND.${refund.status}`,
    summary: '退款结果', resource: { original_type: 'refund', algorithm: 'AEAD_AES_256_GCM', ciphertext: sealed, associated_data: 'refund', nonce },
  });
  lastNotification.set(refund.out_refund_no, body);
  return post(refund.notify_url, body);
}

const BILL_HEADER = ['交易时间', '公众账号ID', '商户号', '特约商户号', '设备号', '微信订单号', '商户订单号', '用户标识', '交易类型', '交易状态', '付款银行', '货币种类', '应结订单金额', '代金券金额', '微信退款单号', '商户退款单号', '退款金额', '充值券退款金额', '退款类型', '退款状态', '商品名称', '商户数据包', '手续费', '费率', '订单金额', '申请退款金额', '费率备注'];
const yuanOf = (minor) => (minor / 100).toFixed(2);
const billTime = (rfc) => rfc.slice(0, 19).replace('T', ' ');
const shiftTime = (rfc, days) => rfc3339(new Date(new Date(rfc).getTime() + days * 86400000));

function buildBill(date, subMchid) {
  const rows = [];
  for (const order of orders.values()) {
    if (order.sub_mchid !== subMchid || !order.success_time || order.success_time.slice(0, 10) !== date) continue;
    if (billRules.drop.has(order.out_trade_no)) continue;
    const amount = billRules.amount.get(order.out_trade_no) ?? order.paid_total ?? order.total;
    rows.push([billTime(order.success_time), SP_APPID, SP_MCHID, subMchid, '', order.transaction_id, order.out_trade_no, 'o-dev-fake', 'APP', 'SUCCESS', 'OTHERS', 'CNY', yuanOf(amount), '0.00', '0', '0', '0.00', '0.00', '', '', (order.description || '').replace(/,/g, '，'), '', '0.00', '0.00%', yuanOf(amount), '0.00', '']);
  }
  for (const refund of refunds.values()) {
    if (refund.sub_mchid !== subMchid || refund.create_time.slice(0, 10) !== date) continue;
    if (billRules.drop.has(refund.out_refund_no)) continue;
    const amount = billRules.amount.get(refund.out_refund_no) ?? refund.refund;
    rows.push([billTime(refund.create_time), SP_APPID, SP_MCHID, subMchid, '', refund.transaction_id, refund.out_trade_no, 'o-dev-fake', 'APP', 'REFUND', 'OTHERS', 'CNY', '0.00', '0.00', refund.refund_id, refund.out_refund_no, yuanOf(amount), '0.00', 'ORIGINAL', refund.status === 'SUCCESS' ? 'SUCCESS' : 'PROCESSING', '', '', '0.00', '0.00%', '0.00', yuanOf(amount), '']);
  }
  if (billRules.extra === subMchid) {
    rows.push([`${date} 12:00:00`, SP_APPID, SP_MCHID, subMchid, '', '4200000000' + crypto.randomInt(1000000, 9999999), 'PFAKE' + crypto.randomInt(100000, 999999), 'o-dev-fake', 'APP', 'SUCCESS', 'OTHERS', 'CNY', '9.90', '0.00', '0', '0', '0.00', '0.00', '', '', '平台没有的单', '', '0.00', '0.00%', '9.90', '0.00', '']);
  }
  if (rows.length === 0) return null;
  const sum = (index) => yuanOf(rows.reduce((total, row) => total + Math.round(Number(row[index]) * 100), 0));
  const lines = [BILL_HEADER.join(',')];
  for (const row of rows) lines.push(row.map((value) => '`' + value).join(','));
  lines.push('总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额');
  lines.push(['' + rows.length, sum(12), sum(16), '0.00', '0.00', sum(24), sum(25)].map((value) => '`' + value).join(','));
  return Buffer.from(lines.join('\r\n') + '\r\n', 'utf8');
}

async function notify(order, id) {
  const plain = JSON.stringify({ ...queryView(order), sp_mchid: SP_MCHID });
  const nonce = crypto.randomBytes(6).toString('hex');
  const cipher = crypto.createCipheriv('aes-256-gcm', keys.apiV3, Buffer.from(nonce, 'utf8'));
  cipher.setAAD(Buffer.from('transaction', 'utf8'));
  const sealed = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]).toString('base64');
  const body = JSON.stringify({
    id, create_time: rfc3339(new Date()), resource_type: 'encrypt-resource', event_type: 'TRANSACTION.SUCCESS', summary: '支付成功',
    resource: { original_type: 'transaction', algorithm: 'AEAD_AES_256_GCM', ciphertext: sealed, associated_data: 'transaction', nonce },
  });
  lastNotification.set(order.out_trade_no, body);
  return post(order.notify_url, body);
}

async function post(url, body) {
  const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...signedHeaders(body) }, body });
  return { status: response.status, body: await response.text() };
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', async () => {
    const body = Buffer.concat(chunks).toString('utf8');
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
    try {
      if (url.pathname.startsWith('/_control/')) {
        const no = decodeURIComponent(url.pathname.split('/')[3] || '');
        if (req.method === 'GET' && url.pathname === '/_control/orders') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify([...orders.values()], null, 2));
        }
        if (url.pathname === '/_control/bill') {
          if (url.searchParams.get('reset') === '1') billRules = { creating: false, corrupt: false, drop: new Set(), amount: new Map(), extra: null };
          if (url.searchParams.has('creating')) billRules.creating = url.searchParams.get('creating') === '1';
          if (url.searchParams.has('corrupt')) billRules.corrupt = url.searchParams.get('corrupt') === '1';
          if (url.searchParams.has('extra')) billRules.extra = url.searchParams.get('extra') || null;
          for (const drop of url.searchParams.getAll('drop')) billRules.drop.add(drop);
          for (const pair of url.searchParams.getAll('amount')) { const [no, minor] = pair.split(':'); billRules.amount.set(no, Number(minor)); }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ ...billRules, drop: [...billRules.drop], amount: [...billRules.amount] }));
        }
        if (url.pathname === '/_control/reset') {
          orders.clear(); refunds.clear(); bills.clear(); lastNotification.clear(); rejectNextRefund = null;
          billRules = { creating: false, corrupt: false, drop: new Set(), amount: new Map(), extra: null };
          res.writeHead(200); return res.end('reset');
        }
        if (url.pathname === '/_control/shift-days') {
          const days = Number(url.searchParams.get('days') || '-1');
          for (const order of orders.values()) if (order.success_time) order.success_time = shiftTime(order.success_time, days);
          for (const refund of refunds.values()) {
            refund.create_time = shiftTime(refund.create_time, days);
            if (refund.success_time) refund.success_time = shiftTime(refund.success_time, days);
          }
          res.writeHead(200); return res.end(`shifted ${days} days`);
        }
        if (req.method === 'GET' && url.pathname === '/_control/refunds') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify([...refunds.values()], null, 2));
        }
        if (url.pathname === '/_control/reject-next-refund') {
          rejectNextRefund = url.searchParams.get('code') || 'NOT_ENOUGH';
          res.writeHead(200); return res.end(`next refund will be rejected with ${rejectNextRefund}`);
        }
        if (url.pathname.startsWith('/_control/refund/')) {
          const refund = refunds.get(no);
          if (!refund) { res.writeHead(404); return res.end('no such refund'); }
          refund.status = url.searchParams.get('status') || 'SUCCESS';
          if (refund.status === 'SUCCESS') refund.success_time = rfc3339(new Date());
          const result = url.searchParams.get('notify') === '1' ? await notifyRefund(refund) : null;
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ refund, notification: result }));
        }
        if (url.pathname.startsWith('/_control/renotify/') && refunds.has(no)) {
          const last = lastNotification.get(no);
          if (!last) { res.writeHead(404); return res.end('nothing sent yet'); }
          const result = await post(refunds.get(no).notify_url, last);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify(result));
        }
        const order = orders.get(no);
        if (!order) { res.writeHead(404); return res.end('no such order'); }
        if (url.pathname.startsWith('/_control/pay/')) {
          if (order.state !== 'NOTPAY') { res.writeHead(409); return res.end(`order is ${order.state}`); }
          order.state = 'SUCCESS';
          order.transaction_id = '42000' + Date.now().toString() + crypto.randomInt(100000, 999999);
          order.success_time = rfc3339(new Date());
          if (url.searchParams.has('total')) order.paid_total = Number(url.searchParams.get('total'));
          const result = url.searchParams.get('notify') === '1' ? await notify(order, crypto.randomUUID()) : null;
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ order, notification: result }));
        }
        if (url.pathname.startsWith('/_control/renotify/')) {
          const last = lastNotification.get(no);
          if (!last) { res.writeHead(404); return res.end('nothing sent yet'); }
          const result = await post(order.notify_url, last);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify(result));
        }
        res.writeHead(404); return res.end();
      }

      const problem = checkAuthorization(req, body);
      if (problem) return reply(res, 401, { code: 'SIGN_ERROR', message: `签名错误（${problem}）` });

      if (req.method === 'POST' && url.pathname === '/v3/pay/partner/transactions/app') {
        const request = JSON.parse(body);
        if (request.sp_mchid !== SP_MCHID || request.sp_appid !== SP_APPID) return reply(res, 400, { code: 'APPID_MCHID_NOT_MATCH', message: 'appid 和 mchid 不匹配' });
        const existing = orders.get(request.out_trade_no);
        if (existing) {
          if (existing.total !== request.amount.total || existing.sub_mchid !== request.sub_mchid) return reply(res, 403, { code: 'OUT_TRADE_NO_USED', message: '商户订单号重复' });
          if (existing.state !== 'NOTPAY') return reply(res, 400, { code: 'ORDER_CLOSED', message: '订单已关闭' });
          return reply(res, 200, { prepay_id: existing.prepay_id });
        }
        const order = {
          out_trade_no: request.out_trade_no, sub_mchid: request.sub_mchid, total: request.amount.total,
          description: request.description, time_expire: request.time_expire, notify_url: request.notify_url,
          profit_sharing: request.settle_info?.profit_sharing, state: 'NOTPAY', prepay_id: 'wx' + crypto.randomBytes(15).toString('hex'),
        };
        orders.set(order.out_trade_no, order);
        return reply(res, 200, { prepay_id: order.prepay_id });
      }
      const query = url.pathname.match(/^\/v3\/pay\/partner\/transactions\/out-trade-no\/([^/]+)$/);
      if (req.method === 'GET' && query) {
        const order = orders.get(query[1]);
        if (!order || url.searchParams.get('sub_mchid') !== order.sub_mchid || url.searchParams.get('sp_mchid') !== SP_MCHID) {
          return reply(res, 404, { code: 'ORDER_NOT_EXIST', message: '订单不存在' });
        }
        return reply(res, 200, queryView(order));
      }
      const close = url.pathname.match(/^\/v3\/pay\/partner\/transactions\/out-trade-no\/([^/]+)\/close$/);
      if (req.method === 'POST' && close) {
        const order = orders.get(close[1]);
        if (!order) return reply(res, 404, { code: 'ORDER_NOT_EXIST', message: '订单不存在' });
        if (order.state === 'SUCCESS') return reply(res, 403, { code: 'TRADE_ERROR', message: '订单已支付' });
        order.state = 'CLOSED';
        return reply(res, 204);
      }
      if (req.method === 'GET' && url.pathname === '/v3/bill/tradebill') {
        const date = url.searchParams.get('bill_date');
        const sub = url.searchParams.get('sub_mchid');
        if (url.searchParams.get('bill_type') !== 'ALL' || !/^\d{4}-\d{2}-\d{2}$/.test(date || '')) return reply(res, 400, { code: 'PARAM_ERROR', message: '参数错误' });
        if (billRules.creating) return reply(res, 400, { code: 'STATEMENT_CREATING', message: '账单生成中' });
        const bytes = buildBill(date, sub);
        if (!bytes) return reply(res, 400, { code: 'NO_STATEMENT_EXIST', message: '账单文件不存在' });
        const token = crypto.randomBytes(16).toString('hex');
        bills.set(token, billRules.corrupt ? Buffer.concat([bytes, Buffer.from('x')]) : bytes);
        const hash = crypto.createHash('sha1').update(bytes).digest('hex');
        return reply(res, 200, { hash_type: 'SHA1', hash_value: hash, download_url: `http://127.0.0.1:${PORT}/v3/bill/downloadurl?token=${token}` });
      }
      if (req.method === 'GET' && url.pathname === '/v3/bill/downloadurl') {
        const bytes = bills.get(url.searchParams.get('token'));
        if (!bytes) { res.writeHead(400, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ code: 'INVALID_REQUEST', message: '下载地址已失效' })); }
        // 和真的一样：下载应答不带微信的签名头。
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(bytes);
      }
      if (req.method === 'POST' && url.pathname === '/v3/ecommerce/refunds/apply') {
        const request = JSON.parse(body);
        if (rejectNextRefund) {
          const code = rejectNextRefund; rejectNextRefund = null;
          return reply(res, 403, { code, message: '模拟拒绝退款' });
        }
        const order = orders.get(request.out_trade_no);
        if (!order || order.sub_mchid !== request.sub_mchid || !['SUCCESS', 'REFUND'].includes(order.state)) {
          return reply(res, 404, { code: 'RESOURCE_NOT_EXISTS', message: '订单不存在或未支付' });
        }
        if (request.sp_appid !== SP_APPID || request.amount.total !== order.total) return reply(res, 400, { code: 'INVALID_REQUEST', message: '原订单金额不对' });
        const existing = refunds.get(request.out_refund_no);
        if (existing) {
          if (existing.refund !== request.amount.refund) return reply(res, 400, { code: 'INVALID_REQUEST', message: '退款单号重复但金额不同' });
          return reply(res, 200, { refund_id: existing.refund_id, out_refund_no: existing.out_refund_no, create_time: existing.create_time, amount: { refund: existing.refund, payer_refund: existing.refund, currency: 'CNY' } });
        }
        const refunded = [...refunds.values()].filter((r) => r.out_trade_no === order.out_trade_no && r.status !== 'CLOSED').reduce((sum, r) => sum + r.refund, 0);
        if (refunded + request.amount.refund > order.total) return reply(res, 400, { code: 'INVALID_REQUEST', message: '退款金额超过订单金额' });
        const refund = {
          out_refund_no: request.out_refund_no, refund_id: '50' + Date.now().toString() + crypto.randomInt(1000000, 9999999),
          out_trade_no: order.out_trade_no, transaction_id: order.transaction_id, sub_mchid: order.sub_mchid,
          refund: request.amount.refund, total: order.total, reason: request.reason, notify_url: request.notify_url,
          status: 'PROCESSING', create_time: rfc3339(new Date()),
        };
        refunds.set(refund.out_refund_no, refund);
        order.state = 'REFUND';
        return reply(res, 200, { refund_id: refund.refund_id, out_refund_no: refund.out_refund_no, create_time: refund.create_time, amount: { refund: refund.refund, payer_refund: refund.refund, currency: 'CNY' } });
      }
      const refundQuery = url.pathname.match(/^\/v3\/ecommerce\/refunds\/out-refund-no\/([^/]+)$/);
      if (req.method === 'GET' && refundQuery) {
        const refund = refunds.get(refundQuery[1]);
        if (!refund || url.searchParams.get('sub_mchid') !== refund.sub_mchid) return reply(res, 404, { code: 'RESOURCE_NOT_EXISTS', message: '退款单不存在' });
        return reply(res, 200, refundView(refund));
      }
      return reply(res, 404, { code: 'NOT_FOUND', message: 'fake wechat does not implement this' });
    } catch (error) {
      console.error(error);
      reply(res, 500, { code: 'SYSTEM_ERROR', message: String(error.message) });
    }
  });
});

server.listen(PORT, '127.0.0.1', () => console.log(`fake wechat pay on http://127.0.0.1:${PORT}`));
