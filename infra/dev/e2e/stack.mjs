import { spawn, spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as wait } from 'node:timers/promises'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../../..')
const runtime = join(root, '.runtime', 'e2e')
const files = join(runtime, 'files')
const credentials = join(runtime, 'credentials.json')
const mobile = join(root, 'apps', 'mobile')
const api = 'http://127.0.0.1:18428'
const admin = 'http://127.0.0.1:17438'
const h5 = 'http://127.0.0.1:5185'
const fake = 'http://127.0.0.1:17468'
const compose = ['compose', '-p', 'inspection-e2e', '-f', join(here, 'compose.yaml')]
const children = new Set()
const [mode, ...args] = process.argv.slice(2)
if (!['up','test','stop'].includes(mode)) {
  throw new Error('运行 stack.mjs up、stack.mjs test [Playwright 参数] 或 stack.mjs stop')
}
mkdirSync(files, {recursive: true})
if (!existsSync(credentials)) {
  const token = () => randomBytes(32).toString('base64')
  writeFileSync(credentials, JSON.stringify({
    E2E_DATABASE_PASSWORD: token(), E2E_MINIO_PASSWORD: token(), DEV_OPERATOR_PASSWORD: token(),
    INSPECTION_JWT_HMAC_BASE64_SECRET: token(), INSPECTION_DATA_ENCRYPTION_BASE64_KEY: token(),
  }), {flag:'wx', mode:0o600})
}
const keys = JSON.parse(readFileSync(credentials, 'utf8'))
// A developer terminal may already contain production or daily-stack connection settings.
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
  !/^(?:SPRING_|SERVER_|INSPECTION_|IDENTITY_|TRADE_|OPS_|MALL_|EXPERIENCE_|PLATFORM_|LIVE_|SERVICE_ORDER_|DEV_MEDIA_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$)/i.test(key)))
Object.assign(env, keys)
function run(command, argv, cwd=root, overrides={}) {
  const result = spawnSync(command, argv, {cwd,env:{...env,...overrides},stdio:'inherit',windowsHide:true})
  if (result.error) throw result.error
  return result.status ?? 1
}
function launch(command, argv, cwd, overrides) {
  const child = spawn(command, argv, {cwd,env:{...env,...overrides},stdio:'inherit',windowsHide:true})
  children.add(child)
  child.once('error', error => { console.error(error.message); halt(1) })
  child.once('exit', code => { children.delete(child); if (children.size) halt(code || 1) })
  return child
}
let stopping = false
function halt(code) {
  if (stopping) return
  stopping = true
  for (const child of children) child.kill()
  process.exit(code)
}
async function checkPort(port) {
  const probe = createServer()
  await new Promise((ok, fail) => probe.once('error',fail).listen(port,'127.0.0.1',ok))
  await new Promise(ok => probe.close(ok))
}
async function ready(url) {
  for (let n=0;n<120;n++) {
    try { if ((await fetch(url,{signal:AbortSignal.timeout(2000)})).ok) return } catch {}
    await wait(1000)
  }
  throw new Error('测试服务启动超时 '+url)
}

function bucketRequest(method, bucket) {
  const command = process.platform === 'win32' ? 'curl.exe' : 'curl'
  const result = spawnSync(command, ['--config','-', '--silent','--show-error','--output',
    process.platform === 'win32' ? 'NUL' : '/dev/null','--write-out','%{http_code}',
    ...(method === 'HEAD' ? ['--head'] : ['--request','PUT']),
    'http://127.0.0.1:19018/'+bucket], {
    input:'aws-sigv4 = "aws:amz:us-east-1:s3"\nuser = "inspection-e2e:'+keys.E2E_MINIO_PASSWORD+'"\n',
    encoding:'utf8', windowsHide:true,
  })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error('初始化测试存储失败 '+result.stderr)
  return Number(result.stdout)
}

if (mode==='stop') {
  process.exit(run('docker',[...compose,'stop']))
}
if (mode==='test') {
  const playwright=join(mobile,'node_modules','@playwright','test','cli.js')
  process.exit(run(process.execPath,[playwright,'test',...args],mobile,{
    E2E_API_URL:api,PLAYWRIGHT_API_BASE_URL:api,PLAYWRIGHT_BASE_URL:h5,ADMIN_BASE_URL:admin,
    E2E_FAKE_WECHAT_URL:fake,E2E_PG_CONTAINER:'inspection-e2e-postgres-1',E2E_PG_DATABASE:'inspection_e2e',
    E2E_FILES_ROOT:files,
  }))
}
const jar = resolve(process.env.E2E_BACKEND_JAR || join(root,'backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar'))
if (!existsSync(jar)) throw new Error('请先使用 JDK 21 执行 mvnw -pl :bootstrap -am package 或指定 E2E_BACKEND_JAR')
for (const port of [18428,17438,17468]) await checkPort(port)
if (run('docker',[...compose,'up','-d','--wait','postgres','redis','minio'])) process.exit(1)
process.once('SIGINT',()=>halt(0))
process.once('SIGTERM',()=>halt(0))
try {
  await ready('http://127.0.0.1:19018/minio/health/live')
  for (const bucket of ['dev-inspection-quarantine','dev-inspection-private']) {
    const status = bucketRequest('HEAD',bucket)
    if (status !== 200 && !(status === 404 && bucketRequest('PUT',bucket) === 200)) {
      throw new Error('请检查测试存储配置，无法初始化 '+bucket)
    }
  }
  const fakeDir=join(runtime,'fake-wechat')
  launch(process.execPath,[join(root,'infra/dev/fake-wechat/server.mjs')],root,{
    FAKE_WECHAT_PORT:'17468',FAKE_WECHAT_DIR:fakeDir,FAKE_WECHAT_BACKEND_URL:api,
  })
  await ready(fake+'/_control/orders')
  const pay=Object.fromEntries(readFileSync(join(fakeDir,'backend.env'),'utf8').trim().split(/\r?\n/).map(line=>{
    const pos=line.indexOf('=')
    return [line.slice(0,pos),line.slice(pos+1)]
  }))
  launch(process.env.E2E_JAVA || 'java',['-Xms64m','-Xmx512m','-Djava.io.tmpdir='+files,'-jar',jar],root,{
    ...pay, SERVER_PORT:'18428',SERVER_ADDRESS:'127.0.0.1',SPRING_PROFILES_ACTIVE:'dev',INSPECTION_ENVIRONMENT:'dev',
    INSPECTION_DB_URL:'jdbc:postgresql://127.0.0.1:15438/inspection_e2e',INSPECTION_DB_USERNAME:'inspection_e2e',
    INSPECTION_DB_PASSWORD:keys.E2E_DATABASE_PASSWORD,INSPECTION_REDIS_HOST:'127.0.0.1',INSPECTION_REDIS_PORT:'16388',
    INSPECTION_REDIS_PASSWORD:'',INSPECTION_REDIS_APPLICATION_NAMESPACE:'e2e',
    INSPECTION_MINIO_ENABLED:'true',INSPECTION_MINIO_ENDPOINT:'http://127.0.0.1:19018',
    INSPECTION_MINIO_PUBLIC_ENDPOINT:'http://127.0.0.1:19018',INSPECTION_MINIO_ACCESS_KEY:'inspection-e2e',
    INSPECTION_MINIO_SECRET_KEY:keys.E2E_MINIO_PASSWORD,INSPECTION_RESERVATION_ALLOWED_MIME_TYPES:'application/pdf,image/jpeg,image/png',
    INSPECTION_CORS_ALLOWED_ORIGINS:h5+','+admin, PLATFORM_DEMO_DATA_ENABLED:'true',PLATFORM_DEMO_DATA_REFRESH_AT_MIDNIGHT:'false',
    PLATFORM_WEATHER_ENABLED:'false',PLATFORM_BOOTSTRAP_OPERATOR_LOGIN:'',PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD:'',
    INSPECTION_PAYMENT_NODES_ENABLED:'true', DEV_MEDIA_ROOT:join(mobile,'src/static'),MALL_MEDIA_PUBLIC_BASE_URL:api+'/api/v1/mall/media',
    MALL_MEDIA_DIR:join(files,'lowaltitude-dev-mall-media'),OPS_MEDIA_DIR:join(files,'lowaltitude-dev-ops-media'),
    OPS_MEDIA_PUBLIC_BASE_URL:'https://media.dev.localhost',
  })
  await ready(api+'/actuator/health')
  const adminDir = join(root,'apps/admin')
  launch(process.execPath,[join(adminDir,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','17438','--strictPort'],
    adminDir,{ADMIN_API_TARGET:api})
  await ready(admin)
  console.log('独立测试栈已就绪。另开终端运行 node infra/dev/e2e/stack.mjs test <测试文件>。')
} catch (error) {
  console.error(error.message)
  halt(1)
}
