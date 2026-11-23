#!/usr/bin/env node
'use strict';

// 微信支付联调前的自检（S2-1，清单见 docs/ops/微信支付联调清单.md）。在部署后端的服务器上运行，分两步：
//
//   1. 本机检查：读后端的环境变量文件（路径由 WECHAT_PREFLIGHT_ENV_FILE 给），逐项看 TRADE_WECHAT_* 是否齐全、
//      格式对不对，两个 PEM 文件能不能解析、是不是放反了、权限是否只让属主读。
//   2. 后端自检：用平台运营账号登录，调受权接口 POST /api/v1/platform/trade/channel-checks。后端再查一遍它
//      实际加载的配置，并按一个不存在的单号向微信查一次单、查一次退款（只读，不下单、不退款、不写数据库）。
//
//   WECHAT_PREFLIGHT_ENV_FILE   后端的环境变量文件，例如 /etc/low-altitude/backend.env（必填）
//   PREFLIGHT_BASE_URL          后端地址，例如 https://api.example.com；本机回环可以用 http（第 2 步必填）
//   PREFLIGHT_LOGIN_NAME        平台运营账号（PLATFORM_OPERATOR 角色）的登录名（第 2 步必填）
//   PREFLIGHT_PASSWORD_FILE     放这个账号密码的文件（只让自己可读）；或者 PREFLIGHT_PASSWORD（会进 shell 历史，不推荐）
//   PREFLIGHT_SUB_MCHID         一家已在服务商后台绑定的二级商户号；不给时后端跳过查单、查退款两项
//
//   node infra/scripts/wechat-pay-preflight.cjs            两步都做
//   node infra/scripts/wechat-pay-preflight.cjs --offline  只做第 1 步
//
// 输出只说哪一项通过、哪一项不对、怎么改，不打印任何密钥、密码、令牌和文件内容。有 FAIL 时退出码为 1。

const crypto = require('node:crypto');
const fs = require('node:fs');

const CALLBACK_PATH = '/api/v1/trade/channels/wechat/notifications';
const OFFICIAL_HOSTS = new Set(['api.mch.weixin.qq.com', 'api2.mch.weixin.qq.com']);
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);
const LABEL = { PASS: '通过', WARN: '注意', FAIL: '不通过', SKIPPED: '跳过' };

class Stop extends Error {}

const results = [];
function record(step, code, status, message) {
  results.push({ step, code, status, message });
  console.log(`[${LABEL[status] || status}] ${step} ${code}  ${message}`);
}

// ---- 第 1 步：本机检查 ----

/** 读 KEY=VALUE 文件：支持注释、空行、export 前缀和成对引号；不展开变量。 */
function readEnvFile(file) {
  const values = {};
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    values[match[1]] = value;
  }
  return values;
}

function isSimulated(values) {
  try {
    const base = new URL(values.TRADE_WECHAT_API_BASE || 'https://api.mch.weixin.qq.com');
    return base.protocol === 'http:' && LOOPBACK.has(base.hostname);
  } catch {
    return false;
  }
}

function privateHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '');
  if (LOOPBACK.has(hostname) || LOOPBACK.has(host)) return true;
  const v4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 10 || a === 127 || a === 0 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
      || (a === 169 && b === 254);
  }
  if (host.includes(':')) return /^(fc|fd|fe80)/i.test(host) || host === '::1';
  return false;
}

function fileMode(code, file, what) {
  if (process.platform === 'win32') return record('本机', code, 'SKIPPED', `Windows 上不检查${what}的权限`);
  const mode = fs.statSync(file).mode & 0o777;
  if (mode & 0o077) {
    record('本机', code, 'WARN', `${what}对其他用户可读写（${mode.toString(8)}），改成 chmod 400 或 600，属主是运行后端的用户`);
  } else {
    record('本机', code, 'PASS', `${what}只有属主能读`);
  }
}

function readPem(file) {
  // 内容只在内存里解析，不打印。
  return fs.readFileSync(file, 'latin1');
}

function checkLocal(envFile) {
  if (!fs.existsSync(envFile)) throw new Stop('WECHAT_PREFLIGHT_ENV_FILE 指的文件不存在');
  const values = readEnvFile(envFile);
  fileMode('ENV_FILE', envFile, '环境变量文件（里面有 APIv3 密钥）');

  if (values.TRADE_WECHAT_ENABLED !== 'true') {
    record('本机', 'ENABLED', 'FAIL', 'TRADE_WECHAT_ENABLED 不是 true：后端不会建支付渠道');
    return;
  }
  const simulated = isSimulated(values);
  record('本机', 'ENABLED', simulated ? 'WARN' : 'PASS',
    simulated ? '指向本机模拟微信：只能用于开发机' : '已开启，指向真实微信支付');

  // 接口域名
  if (!values.TRADE_WECHAT_API_BASE) {
    record('本机', 'API_BASE', 'PASS', '接口域名用默认的 api.mch.weixin.qq.com');
  } else if (!simulated) {
    let base;
    try { base = new URL(values.TRADE_WECHAT_API_BASE); } catch { base = null; }
    if (!base || base.protocol !== 'https:') record('本机', 'API_BASE', 'FAIL', 'TRADE_WECHAT_API_BASE 必须是 https 域名');
    else if (base.pathname !== '/' || base.search) record('本机', 'API_BASE', 'FAIL', 'TRADE_WECHAT_API_BASE 只写到域名，不带路径');
    else if (!OFFICIAL_HOSTS.has(base.hostname)) record('本机', 'API_BASE', 'WARN', '接口域名不是微信支付官方域名，确认不是写错了');
    else record('本机', 'API_BASE', 'PASS', '接口域名是微信支付官方域名');
  }

  // 服务商号、APPID、证书序列号、公钥 ID、APIv3 密钥
  const mchid = (values.TRADE_WECHAT_SP_MCHID || '').trim();
  if (!mchid) record('本机', 'SP_MCHID', 'FAIL', '没有 TRADE_WECHAT_SP_MCHID');
  else if (!/^[0-9]{8,32}$/.test(mchid)) record('本机', 'SP_MCHID', 'FAIL', '服务商户号只能是数字');
  else if (!simulated && !/^[0-9]{10}$/.test(mchid)) record('本机', 'SP_MCHID', 'WARN', '服务商户号通常是 10 位数字');
  else record('本机', 'SP_MCHID', 'PASS', '服务商户号格式对');

  const appid = (values.TRADE_WECHAT_SP_APPID || '').trim();
  if (!appid) record('本机', 'SP_APPID', 'FAIL', '没有 TRADE_WECHAT_SP_APPID');
  else if (!/^[0-9A-Za-z_]{6,32}$/.test(appid)) record('本机', 'SP_APPID', 'FAIL', 'APPID 格式不对');
  else if (!appid.startsWith('wx')) record('本机', 'SP_APPID', 'WARN', 'APPID 通常以 wx 开头');
  else record('本机', 'SP_APPID', 'PASS', 'APPID 格式对');

  const serial = (values.TRADE_WECHAT_MERCHANT_SERIAL_NO || '').trim();
  if (!serial) record('本机', 'MERCHANT_SERIAL_NO', 'FAIL', '没有 TRADE_WECHAT_MERCHANT_SERIAL_NO');
  else if (!/^[0-9A-Fa-f]{8,64}$/.test(serial)) record('本机', 'MERCHANT_SERIAL_NO', 'FAIL', '证书序列号只能是十六进制字符');
  else if (serial.length !== 40) record('本机', 'MERCHANT_SERIAL_NO', 'WARN', '证书序列号通常是 40 个十六进制字符');
  else record('本机', 'MERCHANT_SERIAL_NO', 'PASS', '证书序列号格式对');

  const apiV3 = (values.TRADE_WECHAT_API_V3_KEY || '').trim();
  if (!apiV3) record('本机', 'API_V3_KEY', 'FAIL', '没有 TRADE_WECHAT_API_V3_KEY');
  else if (Buffer.byteLength(apiV3, 'utf8') !== 32) record('本机', 'API_V3_KEY', 'FAIL', 'APIv3 密钥必须是 32 个字符');
  else record('本机', 'API_V3_KEY', 'PASS', 'APIv3 密钥长度对');

  const keyId = (values.TRADE_WECHAT_PLATFORM_PUBLIC_KEY_ID || '').trim();
  if (!keyId) record('本机', 'PLATFORM_PUBLIC_KEY_ID', 'FAIL', '没有 TRADE_WECHAT_PLATFORM_PUBLIC_KEY_ID');
  else if (!/^PUB_KEY_ID_[0-9A-Za-z]{1,64}$/.test(keyId)) record('本机', 'PLATFORM_PUBLIC_KEY_ID', 'FAIL', '公钥 ID 应以 PUB_KEY_ID_ 开头');
  else record('本机', 'PLATFORM_PUBLIC_KEY_ID', 'PASS', '公钥 ID 格式对');

  // 两个 PEM
  let merchantKey = null;
  const privatePath = values.TRADE_WECHAT_MERCHANT_PRIVATE_KEY_PATH;
  if (!privatePath) {
    record('本机', 'MERCHANT_PRIVATE_KEY', 'FAIL', '没有 TRADE_WECHAT_MERCHANT_PRIVATE_KEY_PATH');
  } else if (!fs.existsSync(privatePath)) {
    record('本机', 'MERCHANT_PRIVATE_KEY', 'FAIL', 'API 证书私钥文件不存在（看 TRADE_WECHAT_MERCHANT_PRIVATE_KEY_PATH）');
  } else {
    const text = readPem(privatePath);
    try {
      if (!text.includes('-----BEGIN PRIVATE KEY-----')) throw new Error('not pkcs8');
      merchantKey = crypto.createPrivateKey(text);
      const bits = merchantKey.asymmetricKeyDetails && merchantKey.asymmetricKeyDetails.modulusLength;
      if (merchantKey.asymmetricKeyType !== 'rsa' || !(bits >= 2048)) {
        record('本机', 'MERCHANT_PRIVATE_KEY', 'FAIL', 'API 证书私钥不是 2048 位以上的 RSA 私钥');
        merchantKey = null;
      } else {
        record('本机', 'MERCHANT_PRIVATE_KEY', 'PASS', `API 证书私钥能解析（RSA ${bits} 位，PKCS#8）`);
      }
    } catch {
      record('本机', 'MERCHANT_PRIVATE_KEY', 'FAIL',
        'API 证书私钥要用 apiclient_key.pem（-----BEGIN PRIVATE KEY-----），不是 apiclient_cert.pem，也不是 PKCS#1');
    }
    if (!simulated) fileMode('MERCHANT_PRIVATE_KEY_FILE', privatePath, 'API 证书私钥文件');
  }

  let platformKey = null;
  const publicPath = values.TRADE_WECHAT_PLATFORM_PUBLIC_KEY_PATH;
  if (!publicPath) {
    record('本机', 'PLATFORM_PUBLIC_KEY', 'FAIL', '没有 TRADE_WECHAT_PLATFORM_PUBLIC_KEY_PATH');
  } else if (!fs.existsSync(publicPath)) {
    record('本机', 'PLATFORM_PUBLIC_KEY', 'FAIL', '微信支付公钥文件不存在（看 TRADE_WECHAT_PLATFORM_PUBLIC_KEY_PATH）');
  } else {
    try {
      const text = readPem(publicPath);
      if (!text.includes('-----BEGIN PUBLIC KEY-----')) throw new Error('not spki');
      platformKey = crypto.createPublicKey(text);
      const bits = platformKey.asymmetricKeyDetails && platformKey.asymmetricKeyDetails.modulusLength;
      if (platformKey.asymmetricKeyType !== 'rsa' || !(bits >= 2048)) {
        record('本机', 'PLATFORM_PUBLIC_KEY', 'FAIL', '微信支付公钥不是 2048 位以上的 RSA 公钥');
        platformKey = null;
      } else {
        record('本机', 'PLATFORM_PUBLIC_KEY', 'PASS', '微信支付公钥能解析');
      }
    } catch {
      record('本机', 'PLATFORM_PUBLIC_KEY', 'FAIL', '微信支付公钥要用商户平台“API 安全”下载的 pub_key.pem（-----BEGIN PUBLIC KEY-----）');
    }
  }
  if (merchantKey && platformKey) {
    const derived = crypto.createPublicKey(merchantKey).export({ type: 'spki', format: 'der' });
    const platform = platformKey.export({ type: 'spki', format: 'der' });
    if (crypto.timingSafeEqual(crypto.createHash('sha256').update(derived).digest(),
      crypto.createHash('sha256').update(platform).digest())) {
      record('本机', 'KEYS_DISTINCT', 'FAIL', '微信支付公钥和 API 证书私钥是一对：公钥文件放错了');
    } else {
      record('本机', 'KEYS_DISTINCT', 'PASS', '微信支付公钥和 API 证书私钥不是同一对');
    }
  }

  // 回调地址
  for (const [code, name, value] of [
    ['PAYMENT_NOTIFY_URL', '支付回调地址', values.TRADE_WECHAT_PAYMENT_NOTIFY_URL],
    ['REFUND_NOTIFY_URL', '退款回调地址', values.TRADE_WECHAT_REFUND_NOTIFY_URL],
  ]) {
    let url;
    try { url = value ? new URL(value) : null; } catch { url = null; }
    if (!url) { record('本机', code, 'FAIL', `${name}没有配置或不是完整地址`); continue; }
    if (url.search || url.hash || value.length > 255) { record('本机', code, 'FAIL', `${name}不能带查询参数，最长 255 个字符`); continue; }
    if (simulated) { record('本机', code, 'WARN', `${name}指向本机（模拟模式）`); continue; }
    if (url.protocol !== 'https:') { record('本机', code, 'FAIL', `${name}必须是 https`); continue; }
    if (privateHost(url.hostname)) { record('本机', code, 'FAIL', `${name}是本机或内网地址，微信访问不到`); continue; }
    if (url.pathname !== CALLBACK_PATH) { record('本机', code, 'WARN', `${name}的路径不是 ${CALLBACK_PATH}，确认 Nginx 转到了后端回调接口`); continue; }
    record('本机', code, 'PASS', `${name}是外网 https，路径对`);
  }
}

// ---- 第 2 步：后端自检 ----

function readPassword() {
  if (process.env.PREFLIGHT_PASSWORD_FILE) {
    return fs.readFileSync(process.env.PREFLIGHT_PASSWORD_FILE, 'utf8').trim();
  }
  if (process.env.PREFLIGHT_PASSWORD) return process.env.PREFLIGHT_PASSWORD;
  throw new Stop('第 2 步要平台运营账号的密码：PREFLIGHT_PASSWORD_FILE（推荐）或 PREFLIGHT_PASSWORD');
}

async function call(base, method, route, { token, body } = {}) {
  const headers = { Accept: 'application/json', 'X-Request-Id': `preflight-${crypto.randomUUID()}` };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(base + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new Stop(`连不上后端（${new URL(base).host}）：确认后端在运行、地址和证书对`);
  }
  const text = await response.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = null; }
  return { status: response.status, json };
}

async function checkBackend() {
  const raw = process.env.PREFLIGHT_BASE_URL;
  if (!raw) throw new Stop('第 2 步要 PREFLIGHT_BASE_URL（后端地址）；只做本机检查用 --offline');
  const base = new URL(raw);
  if (base.protocol !== 'https:' && !LOOPBACK.has(base.hostname)) {
    throw new Stop('PREFLIGHT_BASE_URL 必须是 https（本机回环除外），密码不能明文走网络');
  }
  const loginName = process.env.PREFLIGHT_LOGIN_NAME;
  if (!loginName) throw new Stop('第 2 步要 PREFLIGHT_LOGIN_NAME（平台运营账号）');
  const origin = base.origin;

  const login = await call(origin, 'POST', '/api/v1/auth/login', { body: { loginName, password: readPassword() } });
  if (login.status !== 200 || !login.json || !login.json.accessToken) {
    throw new Stop(`登录失败（HTTP ${login.status}）：账号或密码不对，或账号被锁`);
  }
  const sub = (process.env.PREFLIGHT_SUB_MCHID || '').trim();
  if (sub && !/^[0-9]{8,32}$/.test(sub)) throw new Stop('PREFLIGHT_SUB_MCHID 应是 8–32 位数字');
  const query = sub ? `?subMchid=${encodeURIComponent(sub)}` : '';
  const result = await call(origin, 'POST', `/api/v1/platform/trade/channel-checks${query}`, { token: login.json.accessToken });
  if (result.status === 403) throw new Stop('这个账号没有平台运营（PLATFORM_OPERATOR）角色');
  if (result.status === 404) throw new Stop('后端没有支付通道自检接口：后端版本太旧，先部署含 S2-1 的版本');
  if (result.status !== 200 || !result.json) throw new Stop(`后端自检失败（HTTP ${result.status}）`);
  const MODE = { WECHAT: '真实微信', SIMULATED: '本机模拟微信', DISABLED: '没有开启' };
  console.log(`后端模式：${MODE[result.json.mode] || result.json.mode}`);
  for (const item of result.json.items) record('后端', item.code, item.status, item.message);
}

async function main() {
  const offline = process.argv.includes('--offline');
  const envFile = process.env.WECHAT_PREFLIGHT_ENV_FILE;
  if (!envFile) throw new Stop('要 WECHAT_PREFLIGHT_ENV_FILE：后端的环境变量文件路径');
  console.log('第 1 步 本机检查');
  checkLocal(envFile);
  if (offline) {
    console.log('（--offline：不调后端）');
  } else {
    console.log('第 2 步 后端自检');
    await checkBackend();
  }
  const counts = { PASS: 0, WARN: 0, FAIL: 0, SKIPPED: 0 };
  for (const result of results) counts[result.status] = (counts[result.status] || 0) + 1;
  console.log(`合计：通过 ${counts.PASS}，注意 ${counts.WARN}，不通过 ${counts.FAIL}，跳过 ${counts.SKIPPED}`);
  if (counts.FAIL > 0) process.exitCode = 1;
}

main().catch((error) => {
  if (error instanceof Stop) {
    console.error(`停止：${error.message}`);
  } else {
    // 不打印堆栈里的值，只给类型。
    console.error(`出错：${error && error.name ? error.name : 'Error'}`);
  }
  process.exitCode = 2;
});
