// 现场演示环境：一台笔记本 + 一台手机，不连外网（docs/ops/现场演示.md）。
//   node infra/demo/demo.mjs build     打后端包、运营端、App 的 H5 和 App 资源（改过代码或换了机器后跑一次）
//   node infra/demo/demo.mjs up        启动数据库、模拟微信、后端、运营端、演示控制台
//   node infra/demo/demo.mjs prepare   当天准备：给示例收费活动加一个今天的场次，生成四类样例交付文件
//   node infra/demo/demo.mjs status    看各服务是否在跑
//   node infra/demo/demo.mjs down      停掉（只停自己启动的进程），数据保留
//   node infra/demo/demo.mjs reset     停掉并清空演示库，下次 up 是干净的
// DEMO_HOST=192.168.x.x 可以指定笔记本的局域网地址；不设时自动取第一个局域网 IPv4。
//
// 它和日常开发栈完全分开：自己的容器（compose 项目 low-altitude-demo）、端口（194xx）、密钥和数据，都在 .runtime/demo/
// （不进仓库）。只用于本机演示：dev profile、模拟微信、模拟短信，不能部署到服务器。
import { execFileSync, spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { H5_ORIGIN, PORTS, PROJECT, REPO, STATE, readEnv } from './config.mjs';
import { prepare } from './prepare.mjs';

/** 演示图片的假主机，App 和运营端都会换成能读到的地址（apps/mobile/src/shared/http/media-url.ts）。 */
const MEDIA_ORIGIN = 'https://media.demo.localhost';
const JAR = path.join(STATE, 'backend.jar');
const H5 = path.join(STATE, 'h5');
const APP = path.join(REPO, 'output', 'demo-app');
const file = (name) => path.join(STATE, name);

function lanHost() {
  if (process.env.DEMO_HOST) return process.env.DEMO_HOST;
  const isPrivate = (ip) => /^10\./.test(ip) || /^192\.168\./.test(ip) || /^172\.(1[6-9]|2\d|3[01])\./.test(ip);
  const found = Object.values(os.networkInterfaces()).flat().find((item) => item && item.family === 'IPv4' && !item.internal && isPrivate(item.address));
  if (!found) throw new Error('没有找到局域网地址。先开手机热点或连上演示用的路由器，或者用 DEMO_HOST 指定。');
  return found.address;
}

/** 演示环境的密钥只生成一次，留在 .runtime/demo/stack.env（权限 600）。 */
function secrets() {
  fs.mkdirSync(STATE, { recursive: true, mode: 0o700 });
  if (!fs.existsSync(file('stack.env'))) {
    const random = (bytes, encoding) => crypto.randomBytes(bytes).toString(encoding);
    fs.writeFileSync(file('stack.env'), [
      'INSPECTION_ENVIRONMENT=dev', 'INSPECTION_DB_NAME=inspection', 'INSPECTION_DB_USERNAME=demo',
      `INSPECTION_DB_PASSWORD=${random(12, 'hex')}`, 'INSPECTION_REDIS_PASSWORD=',
      'INSPECTION_MINIO_ACCESS_KEY=demo', `INSPECTION_MINIO_SECRET_KEY=${random(16, 'hex')}`,
      'INSPECTION_RESERVATION_ALLOWED_MIME_TYPES=image/jpeg,image/png,application/pdf',
      `INSPECTION_JWT_HMAC_BASE64_SECRET=${random(48, 'base64')}`, `INSPECTION_DATA_ENCRYPTION_BASE64_KEY=${random(32, 'base64')}`,
      'PLATFORM_WEATHER_ENABLED=false', 'INSPECTION_PAYMENT_NODES_ENABLED=true', `DEV_OPERATOR_PASSWORD=Demo${random(6, 'hex')}`, '',
    ].join('\n'), { mode: 0o600 });
  }
  return readEnv(file('stack.env'));
}

function stackEnv(host) {
  return {
    ...process.env, ...secrets(),
    INSPECTION_APP_BIND_ADDRESS: '127.0.0.1', INSPECTION_BACKEND_BIND_ADDRESS: '0.0.0.0', INSPECTION_MINIO_BIND_ADDRESS: '0.0.0.0',
    INSPECTION_APP_PORT: String(PORTS.backend), INSPECTION_POSTGRES_PORT: String(PORTS.postgres), INSPECTION_REDIS_PORT: String(PORTS.redis),
    INSPECTION_MINIO_API_PORT: String(PORTS.minio), INSPECTION_MINIO_CONSOLE_PORT: String(PORTS.minioConsole),
    // 交付文件的短时下载链接要手机也打得开。
    INSPECTION_MINIO_PUBLIC_ENDPOINT: `http://${host}:${PORTS.minio}`,
    INSPECTION_CORS_ALLOWED_ORIGINS: [`http://${host}:${PORTS.h5}`, `http://127.0.0.1:${PORTS.h5}`, `http://127.0.0.1:${PORTS.admin}`].join(','),
  };
}

function backendEnv(host) {
  const env = stackEnv(host);
  const area = (name) => path.join(STATE, 'files', name);
  return {
    ...env, ...readEnv(file('wechat/backend.env')),
    SPRING_PROFILES_ACTIVE: 'dev',
    INSPECTION_DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.postgres}/${env.INSPECTION_DB_NAME}`,
    INSPECTION_REDIS_HOST: '127.0.0.1', INSPECTION_MINIO_ENABLED: 'true', INSPECTION_MINIO_ENDPOINT: `http://127.0.0.1:${PORTS.minio}`,
    MALL_MEDIA_DIR: area('mall-media'), MALL_MEDIA_PUBLIC_BASE_URL: `${MEDIA_ORIGIN}/api/v1/mall/media`,
    OPS_MEDIA_DIR: area('ops-media'), OPS_MEDIA_PUBLIC_BASE_URL: `${MEDIA_ORIGIN}/api/v1/dev`,
    IDENTITY_QUALIFICATION_FILES_DIR: area('qualification'), IDENTITY_DATA_EXPORT_DIR: area('data-exports'),
    OPS_IMPORT_FILES_DIR: area('imports'), SERVICE_ORDER_FILES_DIR: area('service-orders'), TRADE_AFTERSALE_IMAGE_DIR: area('aftersale'),
  };
}

function compose(host, ...args) {
  execFileSync('docker', ['compose', '-p', PROJECT, '-f', path.join(REPO, 'infra/compose.yaml'), ...args], { env: stackEnv(host), stdio: 'inherit' });
}

// ---- 进程：只认自己记下的 PID ----
const pids = () => (fs.existsSync(file('pids.json')) ? JSON.parse(fs.readFileSync(file('pids.json'), 'utf8')) : {});
const alive = (pid) => { try { process.kill(pid, 0); return true; } catch { return false; } };

function start(name, command, args, env, cwd = REPO) {
  const known = pids();
  if (known[name] && alive(known[name])) return;
  fs.mkdirSync(file('logs'), { recursive: true });
  const log = fs.openSync(file(`logs/${name}.log`), 'a');
  const child = spawn(command, args, { cwd, env, detached: true, stdio: ['ignore', log, log] });
  child.unref();
  fs.writeFileSync(file('pids.json'), JSON.stringify({ ...known, [name]: child.pid }, null, 2));
}

async function waitFor(url, seconds, accept = (status) => status < 500) {
  for (let i = 0; i < seconds; i += 1) {
    try { if (accept((await fetch(url, { signal: AbortSignal.timeout(2000) })).status)) return; } catch { /* 还没起来 */ }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`${url} 没有起来，看 .runtime/demo/logs/`);
}

function appEnv(host, discovery) {
  return {
    ...process.env,
    VITE_INSPECTION_API_BASE_URL: `http://${host}:${PORTS.backend}`,
    VITE_INSPECTION_ALLOW_INSECURE_LAN_API: 'true', VITE_INSPECTION_ENABLE_LAN_DISCOVERY: String(discovery),
    VITE_PLATFORM_PREVIEW_ENABLED: 'true', VITE_SIMULATED_PAYMENT: 'true', VITE_DEMO_MEDIA_REWRITE: 'true',
    VITE_INSPECTION_DEFAULT_CITY_CODE: '370500', VITE_INSPECTION_DEFAULT_CITY_NAME: '东营市',
  };
}

const commands = {
  build() {
    const host = lanHost();
    secrets();
    const run = (command, args, options) => execFileSync(command, args, { stdio: 'inherit', ...options });
    console.log('1/4 后端'); run('mvn', ['-o', '-q', '-DskipTests', 'package'], { cwd: path.join(REPO, 'backend') });
    fs.copyFileSync(path.join(REPO, 'backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar'), JAR);
    console.log('2/4 运营端'); run('npm', ['run', 'build', '--silent'], { cwd: path.join(REPO, 'apps/admin') });
    const mobile = path.join(REPO, 'apps/mobile');
    const uni = path.join(mobile, 'node_modules/.bin/uni');
    console.log('3/4 App 的 H5（手机浏览器备用）'); run(uni, ['build'], { cwd: mobile, env: { ...appEnv(host, false), VITE_INSPECTION_API_BASE_URL: H5_ORIGIN, VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API: 'true', UNI_OUTPUT_DIR: H5 } });
    console.log('4/4 App 资源'); run(uni, ['build', '-p', 'app'], { cwd: mobile, env: { ...appEnv(host, true), UNI_OUTPUT_DIR: APP } });
    console.log(`完成。App 资源在 output/demo-app（后端地址 ${host}，App 会在同一网段自动找后端）。`);
  },

  async up() {
    const host = lanHost();
    if (!fs.existsSync(JAR)) throw new Error('先跑 node infra/demo/demo.mjs build');
    compose(host, 'up', '-d', '--wait', 'postgres', 'redis', 'minio');
    compose(host, 'up', '--no-log-prefix', 'minio-init');
    start('wechat', process.execPath, ['infra/dev/fake-wechat/server.mjs'], {
      ...process.env, FAKE_WECHAT_DIR: file('wechat'), FAKE_WECHAT_PORT: String(PORTS.wechat),
      FAKE_WECHAT_BACKEND_URL: `http://127.0.0.1:${PORTS.backend}`, FAKE_WECHAT_AUTO_REFUND_MS: '3000',
    });
    await waitFor(`http://127.0.0.1:${PORTS.wechat}/_control/orders`, 10);
    start('backend', 'java', ['-jar', JAR, `--server.port=${PORTS.backend}`, '--server.address=0.0.0.0'], backendEnv(host), STATE);
    await waitFor(`http://127.0.0.1:${PORTS.backend}/actuator/health`, 90, (status) => status === 200);
    start('admin', process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--port', String(PORTS.admin)],
      { ...process.env, ADMIN_API_TARGET: `http://127.0.0.1:${PORTS.backend}` }, path.join(REPO, 'apps/admin'));
    start('console', process.execPath, ['infra/demo/console.mjs'], process.env);
    await waitFor(`http://127.0.0.1:${PORTS.console}/`, 10);
    await this.status();
  },

  async status() {
    const host = lanHost();
    const known = pids();
    for (const name of ['wechat', 'backend', 'admin', 'console']) console.log(`${known[name] && alive(known[name]) ? '在跑' : '没跑'}  ${name}`);
    console.log(`\n运营端和工作台   http://127.0.0.1:${PORTS.admin}`);
    console.log(`演示控制台       http://127.0.0.1:${PORTS.console}（短信验证码、付款和退款流水）`);
    console.log(`手机浏览器备用   http://${host}:${PORTS.h5}`);
    console.log(`App 连的后端     http://${host}:${PORTS.backend}`);
    console.log('平台运营账号     dev-operator，口令在 .runtime/demo/stack.env 的 DEV_OPERATOR_PASSWORD');
  },

  async prepare() { await prepare(readEnv(file('stack.env'))); },

  down() {
    for (const [name, pid] of Object.entries(pids())) if (alive(pid)) { process.kill(pid); console.log(`停掉 ${name}（${pid}）`); }
    fs.rmSync(file('pids.json'), { force: true });
    compose('127.0.0.1', 'stop');
  },

  reset() {
    this.down();
    compose('127.0.0.1', 'down', '-v');
    fs.rmSync(file('files'), { recursive: true, force: true });
    console.log('演示库和上传的文件已清空。');
  },
};

const command = commands[process.argv[2]];
if (!command) { console.error('用法：node infra/demo/demo.mjs build|up|prepare|status|down|reset'); process.exit(2); }
try { await command.call(commands); } catch (error) { console.error(error.message); process.exit(1); }
