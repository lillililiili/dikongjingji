import { randomInt, randomUUID } from 'node:crypto'

import { testAdmin as admin, testContainer, testDatabase, testFiles, testPayment } from './support/test-stack'
import { expect, test, type APIResponse, type Page } from '@playwright/test'

import { API, BUYER, CITY, apiLogin, psql } from './support/trade-fixtures'


// 口令与验证码不写入 Playwright 截图和 trace。测试使用开发短信、独立数据库及真实业务接口。
test.use({ trace: 'off', screenshot: 'off', actionTimeout: 15_000 })

async function signIn(page: Page, login: string, password: string) {
  await page.locator('input[autocomplete="username"]').fill(login)
  await page.locator('input[autocomplete="current-password"]').fill(password)
  const response = page.waitForResponse(r => r.url().endsWith('/api/v1/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const result = await response
  expect(result.status()).toBe(200)
  await expect(page).not.toHaveURL(/\/login(\?|$)/)
  const { accessToken } = await result.json() as { accessToken: string }
  return { Authorization: `Bearer ${accessToken}` }
}

async function confirm(page: Page, label: string) {
  await page.locator('.el-message-box').getByRole('button', { name: label, exact: true }).click()
}

async function success(response: APIResponse) {
  expect(response.ok(), `${response.status()} ${new URL(response.url()).pathname}`).toBe(true)
  return response.json()
}

function rememberSession(ids: Set<string>, token: string) {
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')) as { sid?: string }
  if (payload.sid) ids.add(payload.sid)
}

test('商家入驻审核后上架，资质变更时 H5 隐藏商品与商家，重新审核自动恢复，下架后不能购买', async ({ browser, page, request, baseURL }) => {
  test.setTimeout(180_000)
  const operatorPassword = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!operatorPassword, '需独立测试库及运营账号 DEV_OPERATOR_PASSWORD')
  for (const url of [admin, API, baseURL!]) expect(['127.0.0.1', 'localhost']).toContain(new URL(url).hostname)
  expect((await request.get(`${API}/actuator/health`)).ok(), '开发后端未就绪').toBe(true)
  // 复用 S6 按本次创建的 ID 清理数据库与本机附件的工具。加载时会读取数据库开始时间。
  process.env.PG_CONTAINER = testContainer
  process.env.PG_DB = testDatabase
  process.env.S6_JAVA_TMPDIR = testFiles
  process.env.FAKE_WECHAT_URL = testPayment
  process.env.BASE_URL = API
  const fixture = require('../../../infra/scripts/s6-rehearsal/lib.cjs') as {
    PNG: Buffer; PDF: Buffer; fakeCreditCode(): string
    purge(ids: string[]): { residue: string; dryRun: boolean }
    removeLocalFiles(ids: Set<string>): { remaining: number }
  }
  const suffix = randomUUID().slice(0, 8)
  const phone = `199${String(randomInt(0, 100_000_000)).padStart(8, '0')}`
  const login = `merchant-e2e-${suffix}`
  const password = `Test-${randomUUID()}`
  const shop = `验收商家${suffix}`
  const productName = `验收机器人${suffix}`
  const ids = new Set([login, phone])
  const operator = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  const operatorPage = await operator.newPage()
  const merchant = page
  await merchant.setViewportSize({ width: 1366, height: 900 })
  try {
    await merchant.goto(`${admin}/login`)
    await merchant.getByRole('link', { name: '申请入驻' }).click()
    await merchant.getByLabel('机构名称（对外显示的商家名）').fill(shop)
    await merchant.getByTestId('signup-city').locator('.el-cascader').click()
    await merchant.getByRole('menuitem', { name: '山东省' }).click()
    await merchant.getByRole('menuitem', { name: '东营市' }).click()
    await merchant.getByLabel('主体名称').fill(shop)
    await merchant.getByLabel('统一社会信用代码').fill(fixture.fakeCreditCode())
    await merchant.getByLabel('联系人姓名').fill('验收员')
    await merchant.locator('input[autocomplete="tel"]').fill(phone)
    const sms = merchant.waitForResponse(r => r.url().endsWith('/organization-signups/sms-codes'))
    await merchant.getByTestId('send-code').click()
    expect((await sms).status()).toBe(202)
    const message = psql(`SELECT content FROM identity_dev_sms_outbox WHERE phone='${phone}' ORDER BY sent_at DESC LIMIT 1`)
    const code = /验证码 (\d{6})/.exec(message)?.[1]
    if (!code) throw new Error('开发短信发件箱没有收到本次入驻验证码')
    await merchant.locator('input[autocomplete="one-time-code"]').fill(code)
    await merchant.getByLabel('用户名', { exact: true }).fill(login)
    await merchant.getByLabel('密码', { exact: true }).fill(password)
    await merchant.getByLabel('再输一次密码').fill(password)
    const registration = merchant.waitForResponse(r => r.url().endsWith('/organization-signups') && r.request().method() === 'POST')
    await merchant.getByRole('button', { name: '提交申请' }).click()
    const registered = await registration
    expect(registered.status()).toBe(201)
    const { organizationId } = await registered.json() as { organizationId: string }
    ids.add(organizationId)
    await expect(merchant.getByTestId('signup-login-name')).toHaveText(login)
    await merchant.getByRole('link', { name: '去登录' }).click()
    const merchantHeaders = await signIn(merchant, login, password)
    await merchant.goto(`${admin}/workbench/organization`)
    await expect(merchant.getByTestId('qualification-status')).toHaveText('待审核')
    await merchant.locator('input[type=file]').setInputFiles({ name: 'license.pdf', mimeType: 'application/pdf', buffer: fixture.PDF })
    await expect(merchant.getByRole('cell', { name: 'license.pdf' })).toBeVisible()

    await merchant.goto(`${admin}/workbench/products`)
    await merchant.getByRole('button', { name: '新建商品' }).click()
    const dialog = merchant.getByRole('dialog', { name: '新建商品' })
    await dialog.getByPlaceholder('App 上显示的名称').fill(productName)
    await dialog.getByRole('button', { name: '新建', exact: true }).click()
    await confirm(merchant, '新建')
    await expect(merchant).toHaveURL(/\/workbench\/products\/[\da-f-]{36}$/)
    const productId = new URL(merchant.url()).pathname.split('/').pop()!
    ids.add(productId)
    // 图片、规格和库存是上架前置，通过本机构的工作台 API 准备，以上下架页面验证发布边界。
    const catalog = `${API}/api/v1/provider/catalog`
    const product = `${catalog}/products/${productId}`
    const draft = await success(await request.get(product, { headers: merchantHeaders }))
    const beforeReview = await request.post(`${product}/status`, {
      headers: merchantHeaders, data: { action: 'PUBLISH', version: draft.product.version },
    })
    expect(beforeReview.status()).toBe(409)
    expect((await beforeReview.json()).code).toBe('OPS_MERCHANT_NOT_QUALIFIED')
    expect((await request.get(`${API}/api/v1/mall/products/${productId}?cityCode=${CITY}`)).status()).toBe(404)
    expect((await request.get(`${API}/api/v1/mall/merchants/${organizationId}?cityCode=${CITY}`)).status()).toBe(404)

    await operatorPage.goto(`${admin}/organizations/${organizationId}`)
    const platformHeaders = await signIn(operatorPage, 'dev-operator', operatorPassword!)
    rememberSession(ids, platformHeaders.Authorization.slice('Bearer '.length))
    await operatorPage.getByRole('button', { name: '审核通过', exact: true }).click()
    await confirm(operatorPage, '审核通过')
    await expect(operatorPage.locator('.summary')).toContainText('资质已通过')
    await merchant.goto(`${admin}/workbench/organization`)
    await expect(merchant.getByTestId('qualification-status')).toHaveText('已通过')
    await merchant.goto(`${admin}/workbench/merchant-account`)
    await expect(merchant.locator('.account__empty')).toContainText('还没有登记收款账户')
    await success(await request.put(`${API}/api/v1/ops/merchant-accounts/${organizationId}`, {
      headers: platformHeaders, data: { subMchid: `19${phone.slice(-8)}`, active: true },
    }))
    await merchant.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(merchant.locator('.account__number')).toHaveText('******' + phone.slice(-4))
    await expect(merchant.locator('.account')).toContainText('可以收款')
    const media = await success(await request.post(`${catalog}/media`, {
      headers: merchantHeaders, multipart: { file: { name: 'robot.png', mimeType: 'image/png', buffer: fixture.PNG } },
    }))
    if (media.mediaId) ids.add(media.mediaId)
    const mediaKey = /([\da-f-]{36})\.[a-z]+$/.exec(media.url)?.[1]
    if (mediaKey) ids.add(mediaKey)
    const sku = await success(await request.post(`${product}/skus`, { headers: merchantHeaders, data: { name: '标准版' } }))
    const offered = await success(await request.post(`${product}/offers`, {
      headers: { ...merchantHeaders, 'Idempotency-Key': randomUUID() },
      data: { skuId: sku.skus[0].skuId, merchantKind: 'LOCAL_STORE', cityCode: CITY, priceAmountMinor: 999,
        priceUnit: 'ITEM', stockQuantity: 3, expressAvailable: true, shippingFeeMinor: 0 },
    }))
    const offer = offered.offers[0]
    const active = await success(await request.post(`${product}/offers/${offer.offerId}/status`, {
      headers: merchantHeaders, data: { action: 'ACTIVATE', version: offer.version },
    }))
    await success(await request.put(product, { headers: merchantHeaders, data: {
      category: 'ROBOT', name: productName, summary: '机器人入驻验收商品', coverUrl: media.url,
      sortOrder: 999, sellingPoints: [], specs: [{ label: '型号', value: '验收型' }], packItems: [{ name: '机器人', quantity: 1 }],
      gallery: [], introBlocks: [{ type: 'TEXT', text: '验收商品说明' }], version: active.product.version,
    } }))
    await merchant.goto(`${admin}/workbench/products/${productId}`)
    await merchant.getByTestId('publish-button').click()
    await confirm(merchant, '上架')
    await expect(merchant.getByTestId('product-status')).toContainText('已上架')
    const mobile = await browser.newPage({ baseURL, viewport: { width: 390, height: 844 } })
    try {
      await merchant.goto(`${admin}/workbench/merchant-profile`)
      const entity = shop + '科技有限公司'
      await merchant.getByPlaceholder('营业执照上的主体名称').fill(entity)
      await merchant.getByRole('button', { name: '提交审核' }).click()
      const submitted = merchant.waitForResponse(r => r.url().endsWith('/merchant-profile') && r.request().method() === 'PUT')
      await confirm(merchant, '提交审核')
      const profile = await submitted
      expect(profile.ok()).toBe(true)
      const { latest } = await profile.json()
      ids.add(latest.submissionId)
      const publicShop = `/#/pages/mall/merchant?merchantId=${organizationId}&cityCode=${CITY}`
      await mobile.goto(publicShop)
      await expect(mobile.getByTestId('mall-merchant-name')).toHaveText(shop)
      await expect(mobile.getByTestId('mall-merchant')).not.toContainText(entity)
      await operatorPage.goto(`${admin}/merchant-profiles/${latest.submissionId}`)
      await operatorPage.getByRole('button', { name: '审核通过', exact: true }).click()
      await confirm(operatorPage, '审核通过')
      await expect(operatorPage.locator('.el-descriptions').first()).toContainText('已通过')
      await mobile.reload()
      await expect(mobile.getByTestId('mall-merchant-info-entity')).toHaveText(entity)

      await operatorPage.goto(`${admin}/organizations/${organizationId}`)
      await operatorPage.getByRole('tab', { name: '服务商资料' }).click()
      await operatorPage.getByRole('button', { name: '开通服务商资料' }).click()
      await confirm(operatorPage, '开通')
      await expect(operatorPage.locator('[data-test="review-provider-profile"]')).toBeVisible()
      await merchant.goto(`${admin}/workbench/provider-profile`)
      await merchant.getByPlaceholder('营业执照上的名称').fill(entity)
      const modes = '无人机巡检和人工复核'
      await merchant.getByPlaceholder('如 无人机巡检 · 机器狗夜巡').fill(modes)
      await merchant.getByRole('button', { name: '提交审核' }).click()
      await confirm(merchant, '提交审核')
      await expect(merchant.locator('.status')).toContainText('待审核')
      await operatorPage.goto(`${admin}/provider-profiles/${organizationId}`)
      await operatorPage.getByRole('button', { name: '审核通过', exact: true }).click()
      await confirm(operatorPage, '审核通过')
      await expect(operatorPage.locator('.status')).toContainText('已通过')
      await mobile.goto(`/#/pages/provider/detail?providerId=${organizationId}&cityCode=${CITY}`)
      await expect(mobile.getByTestId('provider-profile-entity')).toHaveText(entity)
      await expect(mobile.getByTestId('provider-profile-modes')).toHaveText(modes)

      await merchant.goto(`${admin}/workbench/products/${productId}`)
      await mobile.goto(`/#/pages/mall/product?productId=${productId}&cityCode=${CITY}`)
      await expect(mobile.getByTestId('mall-product-name')).toHaveText(productName)
      await expect(mobile.getByTestId('mall-product-primary')).toHaveText('立即购买')
      await mobile.getByTestId('mall-product-primary').click()
      await expect(mobile.getByTestId('mall-buy-sheet')).toContainText(shop)
      await mobile.getByTestId('mall-buy-close').click()

      await merchant.goto(`${admin}/workbench/organization`)
      await merchant.getByLabel('经营范围').fill('设备销售和巡检服务')
      await merchant.getByRole('button', { name: '保存修改' }).click()
      await confirm(merchant, '保存')
      await expect(merchant.getByTestId('qualification-status')).toHaveText('待审核')
      await mobile.reload()
      await expect(mobile.getByTestId('mall-product-missing')).toBeVisible()
      await expect(mobile.getByTestId('mall-product-primary')).toHaveCount(0)
      await mobile.goto(publicShop)
      await expect(mobile.getByTestId('mall-merchant-missing')).toBeVisible()

      await operatorPage.goto(`${admin}/organizations/${organizationId}`)
      await operatorPage.getByRole('button', { name: '审核通过', exact: true }).click()
      await confirm(operatorPage, '审核通过')
      await expect(operatorPage.locator('.summary')).toContainText('资质已通过')
      await mobile.reload()
      await expect(mobile.getByTestId('mall-merchant-name')).toHaveText(shop)
      await mobile.goto(`/#/pages/mall/product?productId=${productId}&cityCode=${CITY}`)
      await expect(mobile.getByTestId('mall-product-primary')).toHaveText('立即购买')
      await merchant.goto(`${admin}/workbench/products/${productId}`)
      await merchant.getByTestId('publish-button').click()
      await confirm(merchant, '下架')
      await expect(merchant.getByTestId('publish-button')).toHaveText('上架')
      await mobile.reload()
      await expect(mobile.getByTestId('mall-product-missing')).toContainText('商品不存在或本城暂不销售')
      await expect(mobile.getByTestId('mall-product-primary')).toHaveCount(0)
      const buyer = await apiLogin(request, BUYER)
      if (!buyer) throw new Error('开发买家登录失败')
      rememberSession(ids, buyer.token)
      const blocked = await request.post(`${API}/api/v1/mall/cart/items`, {
        headers: buyer.headers, data: { offerId: offer.offerId, cityCode: CITY, quantity: 1 },
      })
      expect(blocked.status()).toBe(404)
      expect((await blocked.json()).code).toBe('COMMON_NOT_FOUND')
    } finally {
      await mobile.close()
    }
  } finally {
    try {
      await operator.close()
    } finally {
      const cleanup = fixture.purge([...ids])
      expect(cleanup.dryRun).toBe(false)
      expect(cleanup.residue).toBe('none')
      expect(psql(`SELECT count(*) FROM mall_product WHERE name='${productName}'`)).toBe('0')
      expect(psql(`SELECT count(*) FROM identity_user WHERE login_name='${login}'`)).toBe('0')
      expect(fixture.removeLocalFiles(ids).remaining).toBe(0)
    }
  }
})
