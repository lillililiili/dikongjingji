import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'
import { testAdmin, testApi } from './support/test-stack'

test.use({ trace: 'off', screenshot: 'off', video: 'off' })

async function login(page: Page, name: string, password: string) {
  await page.locator('input[autocomplete="username"]').fill(name)
  await page.locator('input[autocomplete="current-password"]').fill(password)
  const authenticated = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/auth/login')
  await page.locator('form').getByRole('button', { name: '登录', exact: true }).click()
  expect((await authenticated).status()).toBe(200)
}

test('运营新建机构账号并更换角色，同一账号重新登录后菜单切换到直播主办', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const operatorPassword = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!operatorPassword || process.env.E2E_OPS_CONTENT_TEST !== '1', '需要隔离运营测试栈')
  const auth = await request.post(testApi + '/api/v1/auth/login', {
    data: { loginName: 'dev-operator', password: operatorPassword },
  })
  expect(auth.ok()).toBe(true)
  const headers = { Authorization: 'Bearer ' + (await auth.json()).accessToken }
  const suffix = randomUUID().slice(0, 8)
  const base = testApi + '/api/v1/ops/organizations'
  const organization = await request.post(base, { headers: { ...headers, 'Idempotency-Key': randomUUID() },
    data: { type: 'PROVIDER', name: '机构账号验收' + suffix, cityCode: '370500' } })
  expect(organization.ok()).toBe(true)
  const orgId = (await organization.json()).organization.organizationId
  const loginName = 'opsroles-' + suffix
  const admin = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  const employee = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  let accountId: string | undefined
  try {
    const ops = await admin.newPage()
    await ops.goto(testAdmin + '/organizations/' + orgId)
    await login(ops, 'dev-operator', operatorPassword!)
    await ops.getByRole('tab', { name: /账号与角色/ }).click()
    await ops.getByRole('button', { name: '新建账号' }).click()
    const create = ops.getByRole('dialog', { name: '新建账号', exact: true })
    await create.getByRole('textbox', { name: '登录名' }).fill(loginName)
    await create.getByRole('textbox', { name: '显示名' }).fill('机构测试操作员')
    await expect(create.getByRole('checkbox', { name: /^服务商操作员/ })).toBeChecked()
    await expect(create.getByRole('checkbox', { name: /^直播主办/ })).not.toBeChecked()
    await create.getByRole('button', { name: '新建', exact: true }).click()
    const posted = ops.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/ops/organizations/' + orgId + '/accounts'
      && r.request().method() === 'POST')
    await ops.locator('.el-message-box').getByRole('button', { name: '新建', exact: true }).click()
    const result = await posted
    expect(result.ok()).toBe(true)
    accountId = (await result.json()).account.accountId
    const provided = ops.getByRole('dialog', { name: '账号已建好', exact: true })
    const password = (await provided.locator('.created__password').innerText()).trim()
    await provided.getByRole('button', { name: '已转告，关闭' }).click()
    const row = ops.getByRole('row').filter({ hasText: loginName })
    await expect(row).toContainText('服务商操作员')
    const staff = await employee.newPage()
    await staff.goto(testAdmin + '/')
    await login(staff, loginName, password)
    await expect(staff.locator('.home .card[href="/workbench/mall-orders"]')).toBeVisible()
    await expect(staff.locator('.home .card[href="/workbench/live-rooms"]')).toHaveCount(0)
    await row.getByRole('button', { name: '改角色' }).click()
    const roles = ops.getByRole('dialog', { name: '修改角色：' + loginName, exact: true })
    await roles.locator('label.el-checkbox').filter({ hasText: '直播主办' }).click()
    await roles.locator('label.el-checkbox').filter({ hasText: '服务商操作员' }).click()
    await roles.getByRole('button', { name: '保存', exact: true }).click()
    await ops.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
    await expect(row).toContainText('直播主办')
    await expect(row).not.toContainText('服务商操作员')
    await staff.getByRole('button', { name: '退出', exact: true }).click()
    await staff.locator('.el-message-box').getByRole('button', { name: '退出', exact: true }).click()
    await staff.waitForURL(url => url.pathname === '/login')
    await login(staff, loginName, password)
    const roomMenu = staff.locator('.home .card[href="/workbench/live-rooms"]')
    await expect(roomMenu).toBeVisible()
    await expect(staff.locator('.home .card[href="/workbench/mall-orders"]')).toHaveCount(0)
    const listed = staff.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/live-host/rooms' && r.request().method() === 'GET')
    await roomMenu.click()
    expect((await listed).status()).toBe(200)
  } finally {
    try {
      if (accountId) {
        const removed = await request.put(base + '/' + orgId + '/accounts/' + accountId + '/roles', { headers, data: { roles: [] } })
        expect(removed.ok()).toBe(true)
      }
    } finally {
      await employee.close()
      await admin.close()
    }
  }
})
