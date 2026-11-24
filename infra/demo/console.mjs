// 演示控制台：只听 127.0.0.1，给主持演示的人看模拟短信验证码、模拟微信的付款和退款。不属于产品。
// 顺带把 App 的 H5 包放在局域网端口上，给手机浏览器备用。
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { promisify } from 'node:util';

import { H5_ORIGIN, PG_CONTAINER, PORTS, STATE } from './config.mjs';

const run = promisify(execFile);
const escape = (value) => String(value ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const time = (value) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '');

async function messages() {
  const sql = "SELECT coalesce(json_agg(x), '[]') FROM (SELECT phone, content, sent_at FROM identity_dev_sms_outbox ORDER BY sent_at DESC LIMIT 12) x";
  const { stdout } = await run('docker', ['exec', PG_CONTAINER, 'sh', '-c', 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', 'sh', sql]);
  return JSON.parse(stdout);
}

const wechat = async (route) => (await fetch(`http://127.0.0.1:${PORTS.wechat}/_control/${route}`)).json();
const safe = (promise) => promise.catch((error) => ({ error: error.message }));

const STATES = { NOTPAY: '待付款', SUCCESS: '已付款', REFUND: '有退款', CLOSED: '已关闭', PROCESSING: '退款中' };
function table(heads, rows) {
  if (rows.error) return `<p class="bad">读不到：${escape(rows.error)}</p>`;
  if (rows.length === 0) return '<p class="muted">还没有</p>';
  return `<table><tr>${heads.map((head) => `<th>${head}</th>`).join('')}</tr>${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</table>`;
}

async function page() {
  const [sms, orders, refunds] = await Promise.all([safe(messages()), safe(wechat('orders')), safe(wechat('refunds'))]);
  const yuan = (minor) => `¥${(minor / 100).toFixed(2)}`;
  const smsRows = sms.error ? sms : sms.map((row) => [escape(row.phone), `<b class="code">${escape(/\d{6}/.exec(row.content)?.[0])}</b>`, escape(row.content), time(row.sent_at)]);
  const orderRows = orders.error ? orders : orders.slice(-10).reverse().map((row) => [escape(row.out_trade_no), escape(row.description), yuan(row.total), escape(row.sub_mchid), STATES[row.state] ?? escape(row.state)]);
  const refundRows = refunds.error ? refunds : refunds.slice(-10).reverse().map((row) => [escape(row.out_refund_no), yuan(row.refund), escape(row.reason), row.status === 'SUCCESS' ? '已退款' : STATES[row.status] ?? escape(row.status)]);
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta http-equiv="refresh" content="3"><title>演示控制台</title>
<style>body{font:15px/1.6 -apple-system,"PingFang SC",sans-serif;margin:24px auto;max-width:1080px;padding:0 16px;color:#1f2937;background:#fafafa}
h1{font-size:20px}h2{font-size:16px;margin:24px 0 8px}table{border-collapse:collapse;width:100%;background:#fff}td,th{border:1px solid #e5e7eb;padding:6px 10px;text-align:left}
.code{font-size:20px;color:#d9480f;letter-spacing:2px}.muted{color:#6b7280}.bad{color:#b91c1c}</style>
<h1>演示控制台</h1><p class="muted">每 3 秒刷新。短信和微信支付都是本机模拟，不会真的发短信、扣款。</p>
<h2>短信验证码</h2>${table(['手机号', '验证码', '内容', '发送时间'], smsRows)}
<h2>模拟微信付款</h2>${table(['支付单号', '内容', '金额', '收款子商户号', '状态'], orderRows)}
<h2>模拟微信退款</h2>${table(['退款单号', '金额', '原因', '状态'], refundRows)}</html>`;
}

http.createServer(async (req, res) => {
  if (req.method !== 'GET' || req.url !== '/') { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(await page());
}).listen(PORTS.console, '127.0.0.1', () => console.log(`demo console on http://127.0.0.1:${PORTS.console}`));

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.mp4': 'video/mp4', '.json': 'application/json', '.ttf': 'font/ttf', '.woff2': 'font/woff2' };
const ROOT = path.join(STATE, 'h5');
/** /api 原样转给后端；同源，不涉及跨域，所以不带 Origin。 */
function forward(req, res) {
  const { origin, ...headers } = req.headers;
  const upstream = http.request({ host: '127.0.0.1', port: PORTS.backend, method: req.method, path: req.url, headers }, (answer) => {
    res.writeHead(answer.statusCode, answer.headers);
    answer.pipe(res);
  });
  upstream.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(upstream);
}

http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) return forward(req, res);
  let target;
  try { target = path.resolve(ROOT, `.${decodeURIComponent(new URL(req.url, 'http://x').pathname)}`); } catch { res.writeHead(400); return res.end(); }
  if (target !== ROOT && !target.startsWith(ROOT + path.sep)) { res.writeHead(404); return res.end(); }
  if (target === ROOT) target = path.join(ROOT, 'index.html');
  fs.readFile(target, (error, content) => {
    if (error) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(target).toLowerCase()] ?? 'application/octet-stream' });
    const host = /^[\w.-]+(:\d+)?$/.test(req.headers.host ?? '') ? req.headers.host : null;
    res.end(target.endsWith('.js') && host ? content.toString('utf8').replaceAll(H5_ORIGIN, `http://${host}`) : content);
  });
}).listen(PORTS.h5, '0.0.0.0', () => console.log(`h5 on port ${PORTS.h5}`));
