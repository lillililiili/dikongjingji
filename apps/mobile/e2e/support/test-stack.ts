import { resolve } from 'node:path'

// Business-flow tests create and remove records. Use independent services and volumes.
export function testOrigin(configured: string | undefined, defaultPort: number): string {
  const url = new URL(configured || 'http://127.0.0.1:' + defaultPort)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      url.pathname !== '/' || url.search || url.hash) {
    throw new Error('测试服务地址须填写 HTTP 或 HTTPS 来源')
  }
  if (['17430', '18420', '5182', '17460'].includes(url.port)) {
    throw new Error('业务回归需要指定独立测试服务，当前地址指向日常维护端口 ' + url.port)
  }
  return url.origin
}

export const testApi = testOrigin(process.env.E2E_API_URL ?? process.env.PLAYWRIGHT_API_BASE_URL, 18428)
export const testAdmin = testOrigin(process.env.ADMIN_BASE_URL, 17438)
export const testMobile = testOrigin(process.env.PLAYWRIGHT_BASE_URL, 5185)
export const testPayment = testOrigin(process.env.E2E_FAKE_WECHAT_URL, 17468)

export const testContainer = process.env.E2E_PG_CONTAINER || 'inspection-e2e-postgres-1'
export const testDatabase = process.env.E2E_PG_DATABASE || 'inspection_e2e'
// Require an identifiable test database even when a restore lives in the same PG container.
if (!/^[a-z][a-z0-9_]*$/.test(testDatabase) ||
    !/(?:^|_)(?:e2e|test|qa|stage|demo|restore|batch[0-9]+)(?:_|$)/.test(testDatabase)) {
  throw new Error('E2E_PG_DATABASE 需要独立测试库名，如 inspection_e2e')
}
export const testFiles = resolve(process.env.E2E_FILES_ROOT || resolve(__dirname, '../../../../.runtime/e2e/files'))
