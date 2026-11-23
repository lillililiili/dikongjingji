import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

// 商品详情的卖点标签、多规格与库存（S3-1 商品与供给 / S4-4；mall catalog 1.3.0、mall cart 1.2.0；画板 PdFull、PdSku）。
// 真实后端（带 S3-1 的版本）、预览构建、开发账号 dev-customer。开发种子给编程机器人探索套装加了：
// - 卖点“图形化编程”“避障练习”；
// - 三个规格：标准套装（1 台 + 数字课程）、教室套装（6 台 + 教师端）、扩展传感器包（没有任何商家供应）；
// - 示例机器人商家：标准套装 ¥1,299 现货 12 件、教室套装 ¥7,380 现货 3 件；另两家只有标准套装，库存不限。
// 每条用例前后清空这个账号的购物车。
const API = 'http://127.0.0.1:18420'
const visualOutput = resolve(__dirname, '../../../.planning/mall-sku-stock/visual')
const CITY = '370500'
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const SKU_STANDARD = 'c3000000-0000-4000-8000-000000000101'
const SKU_CLASSROOM = 'c3000000-0000-4000-8000-000000001011'
const SKU_SENSOR = 'c3000000-0000-4000-8000-000000001012'
const ROBOT_MERCHANT = 'c1000000-0000-4000-8000-000000000001'
const KIT_STANDARD_OFFER = 'fc8e8f99-c952-c199-4828-778f679286e6'
const KIT_CLASSROOM_OFFER = 'd62b9e6a-1178-1481-ca40-d3ebca1f66e2'

function devPassword(): string {
  const seed = readFileSync(resolve(
    __dirname,
    '../../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
  ), 'utf8')
  return /crypt\('([^']+)'/.exec(seed)?.[1] ?? ''
}

interface Session {
  token: string
  headers: { Authorization: string }
}

async function apiLogin(request: APIRequestContext): Promise<Session | null> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { loginName: 'dev-customer', password: devPassword() },
  })
  if (!login.ok()) return null
  const { accessToken } = await login.json()
  return { token: accessToken, headers: { Authorization: `Bearer ${accessToken}` } }
}

async function emptyCart(request: APIRequestContext, session: Session): Promise<void> {
  const cart = await (await request.get(`${API}/api/v1/mall/cart?cityCode=${CITY}`, { headers: session.headers })).json()
  const ids = [
    ...cart.groups.flatMap((group: { items: Array<{ cartItemId: string }> }) => group.items.map((item) => item.cartItemId)),
    ...cart.invalidItems.map((item: { cartItemId: string }) => item.cartItemId),
  ]
  if (ids.length > 0) {
    await request.post(`${API}/api/v1/mall/cart/remove`, { headers: session.headers, data: { cartItemIds: ids } })
  }
}

// The backend and seed of S3-1: the kit lists its three specs.
async function seedHasSpecs(request: APIRequestContext): Promise<boolean> {
  const response = await request.get(`${API}/api/v1/mall/products/${KIT_ID}?cityCode=${CITY}`)
  if (!response.ok()) return false
  const detail = await response.json()
  return Array.isArray(detail.skus) && detail.skus.length === 3
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function openKit(page: Page): Promise<void> {
  await page.goto(`/?kit=${Date.now()}#/pages/mall/product?productId=${KIT_ID}&cityCode=${CITY}`)
  await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
}

test.describe('商品详情：卖点、多规格与库存', () => {
  test.describe.configure({ mode: 'serial' })

  let session: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!(await seedHasSpecs(request)), '后端或开发种子还不是 S3-1 的版本（商品详情没有 skus）')
    session = signedIn as Session
    await emptyCart(request, session)
  })

  test.afterAll(async ({ request }) => {
    const signedIn = await apiLogin(request)
    if (signedIn) await emptyCart(request, signedIn)
  })

  test('SKU-001 卖点标签在名称下面，体验标识在后；三个规格按商家分组成三行', async ({ page }) => {
    await openKit(page)
    await expect(page.getByTestId('mall-product-selling-point')).toHaveText(['图形化编程', '避障练习'])
    await expect(page.getByTestId('mall-product-tags')).toContainText('可线上体验')
    // 示例机器人商家有两条供给（两个规格），仍只占一行。
    await expect(page.locator('.mall-product__offer')).toHaveCount(3)
    await expect(page.getByTestId(`mall-offer-${KIT_STANDARD_OFFER}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-product__offer-price')).toHaveText(['¥1,299', '¥1,350', '¥1,380'])
    await expect(page.getByTestId('mall-product-sellers')).toContainText('3 家在售')
    await expect(page.getByTestId('mall-product-price')).toHaveText('¥1,299')
  })

  test('SKU-002 规格面板：不供应的规格灰显，选教室套装换价格和现货，数量不超过现货，加入购物车的是教室套装', async ({ page, request }) => {
    await signIn(page, session)
    await openKit(page)
    await page.getByTestId('mall-product-cart').click()
    const sheet = page.getByTestId('mall-buy-sheet')
    await expect(sheet).toBeVisible()

    await expect(sheet.locator('[data-testid^="mall-buy-sku-c3"]')).toHaveCount(3)
    await expect(page.getByTestId(`mall-buy-sku-${SKU_STANDARD}`)).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId(`mall-buy-sku-price-${SKU_STANDARD}`)).toHaveText('¥1,299')
    await expect(page.getByTestId(`mall-buy-sku-price-${SKU_CLASSROOM}`)).toHaveText('¥7,380')
    const sensor = page.getByTestId(`mall-buy-sku-${SKU_SENSOR}`)
    await expect(sensor).toHaveAttribute('data-state', 'NOT_SUPPLIED')
    await expect(sensor).toHaveAttribute('aria-disabled', 'true')
    await expect(page.getByTestId(`mall-buy-sku-price-${SKU_SENSOR}`)).toHaveText('本商家暂不供应')
    await expect(page.getByTestId('mall-buy-merchant')).toHaveText('示例机器人商家 · 东营现货 12 件')

    // 点不供应的规格什么都不变。它带 aria-disabled，Playwright 默认不肯点，用 force 把点击真发到页面上。
    await sensor.click({ force: true })
    await expect(page.getByTestId(`mall-buy-sku-${SKU_STANDARD}`)).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('mall-buy-price')).toHaveText('¥1,299')

    await page.getByTestId(`mall-buy-sku-${SKU_CLASSROOM}`).click()
    await expect(page.getByTestId(`mall-buy-sku-${SKU_CLASSROOM}`)).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('mall-buy-price')).toHaveText('¥7,380')
    await expect(page.getByTestId('mall-buy-merchant')).toHaveText('示例机器人商家 · 东营现货 3 件')

    await page.getByTestId('mall-buy-plus').click()
    await page.getByTestId('mall-buy-plus').click()
    await expect(page.getByTestId('mall-buy-qty')).toHaveText('3')
    await expect(page.getByTestId('mall-buy-plus')).toHaveAttribute('aria-disabled', 'true')
    await page.getByTestId('mall-buy-plus').click()
    await expect(page.getByTestId('mall-buy-qty')).toHaveText('3')
    await expect(page.getByTestId('mall-buy-amount')).toHaveText('¥22,140')

    mkdirSync(visualOutput, { recursive: true })
    await page.screenshot({ path: resolve(visualOutput, 'pdsku-classroom-390.png') })

    await page.getByTestId('mall-buy-add').click()
    await expect(page.locator('uni-toast')).toContainText('已加入购物车')
    await expect(sheet).toHaveCount(0)
    // 已选写的是面板里选的规格和数量；商家行跟着换成这家的教室套装。
    await expect(page.getByTestId('mall-product-chosen')).toContainText('教室套装（6 台 + 教师端） · 3 件')
    await expect(page.getByTestId(`mall-offer-${KIT_CLASSROOM_OFFER}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.mall-product__offer')).toHaveCount(3)

    const cart = await (await request.get(`${API}/api/v1/mall/cart?cityCode=${CITY}`, { headers: session.headers })).json()
    const robot = cart.groups.find((group: { merchant: { organizationId: string } }) =>
      group.merchant.organizationId === ROBOT_MERCHANT)
    expect(robot.items).toHaveLength(1)
    expect(robot.items[0]).toMatchObject({ offerId: KIT_CLASSROOM_OFFER, quantity: 3, skuName: '教室套装（6 台 + 教师端）' })

    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.locator('.root-tab-bar__item', { hasText: '购物车' }).click()
    await expect(page.getByTestId('cart')).toBeVisible()
    const line = page.getByTestId(`cart-group-${ROBOT_MERCHANT}`).locator('.cart__item')
    await expect(line).toHaveCount(1)
    await expect(line).toContainText('教室套装（6 台 + 教师端）')
    await expect(line.locator('.cart__qty')).toHaveText('3')
  })

  test('SKU-003 加入购物车超过现货：服务端拒绝 MALL_CART_OUT_OF_STOCK', async ({ request }) => {
    const tooMany = await request.post(`${API}/api/v1/mall/cart/items`, {
      headers: session.headers,
      data: { offerId: KIT_CLASSROOM_OFFER, quantity: 4, cityCode: CITY },
    })
    expect(tooMany.status()).toBe(409)
    expect((await tooMany.json()).code).toBe('MALL_CART_OUT_OF_STOCK')
    const enough = await request.post(`${API}/api/v1/mall/cart/items`, {
      headers: session.headers,
      data: { offerId: KIT_STANDARD_OFFER, quantity: 2, cityCode: CITY },
    })
    expect(enough.status()).toBe(201)
  })
})
