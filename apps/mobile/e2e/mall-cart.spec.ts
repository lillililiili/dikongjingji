import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { testApi as API } from './support/test-stack'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { databaseAvailable, purgeBuyerOrders } from './support/trade-fixtures'

// 购物车、收货地址、确认订单（D 步，停在“提交订单”）。真实后端、开发账号 dev-customer、商城示例数据。
// 每条用例开始前清空这个账号的购物车和地址，所以可以反复跑。失效商品的样子用接口桩（让真实商品下架要改库）。

const visualOutput = resolve(__dirname, '../../../.planning/mall-cart/visual')
const CITY = '370500'
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const DOG_ID = 'c2000000-0000-4000-8000-000000000401'
const ROBOT_MERCHANT = 'c1000000-0000-4000-8000-000000000001'
const STORE_MERCHANT = 'c1000000-0000-4000-8000-000000000003'

// The dev seed derives offer ids from the product and merchant numbers.
function offerId(product: string, merchant: string): string {
  const hex = createHash('md5').update(`dev-mall-offer:${product}:${merchant}`).digest('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
const KIT_FACTORY = offerId('101', '1')
const KIT_STORE = offerId('101', '3')
const FIELD_FACTORY = offerId('302', '1')

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

async function resetAccount(request: APIRequestContext, session: Session): Promise<void> {
  const cart = await (await request.get(`${API}/api/v1/mall/cart?cityCode=${CITY}`, { headers: session.headers })).json()
  const ids = [
    ...cart.groups.flatMap((group: { items: Array<{ cartItemId: string }> }) => group.items.map((item) => item.cartItemId)),
    ...cart.invalidItems.map((item: { cartItemId: string }) => item.cartItemId),
  ]
  if (ids.length > 0) {
    await request.post(`${API}/api/v1/mall/cart/remove`, { headers: session.headers, data: { cartItemIds: ids } })
  }
  const addresses = await (await request.get(`${API}/api/v1/account/addresses`, { headers: session.headers })).json()
  for (const address of addresses.items) {
    await request.delete(`${API}/api/v1/account/addresses/${address.addressId}`, { headers: session.headers })
  }
}

async function addByApi(request: APIRequestContext, session: Session, offer: string, quantity = 1): Promise<string> {
  const added = await request.post(`${API}/api/v1/mall/cart/items`, {
    headers: session.headers,
    data: { offerId: offer, quantity, cityCode: CITY },
  })
  expect(added.status()).toBe(201)
  return (await added.json()).cartItemId
}

async function addAddressByApi(request: APIRequestContext, session: Session): Promise<string> {
  const created = await request.post(`${API}/api/v1/account/addresses`, {
    headers: session.headers,
    data: { recipientName: '王女士', phone: '13800002026', cityCode: CITY, detail: '东营区 示例路 88 号', isDefault: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).addressId
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function openCartTab(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.locator('.root-tab-bar__item', { hasText: '购物车' }).click()
  await expect(page.getByTestId('cart')).toBeVisible()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

test.describe('购物车与确认订单', () => {
  test.describe.configure({ mode: 'serial' })

  let session: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    session = signedIn as Session
    await resetAccount(request, session)
  })

  test.afterAll(async ({ request }) => {
    const signedIn = await apiLogin(request)
    if (signedIn) await resetAccount(request, signedIn)
    // CART-002 submits a real order; this account's orders are test data.
    if (databaseAvailable()) purgeBuyerOrders()
  })

  test('CART-001 没登录点底栏“购物车”：先登录，登录后回到空购物车', async ({ page }) => {
    await openCartTab(page)
    await expect(page.getByTestId('cart-guest')).toBeVisible()
    await page.getByTestId('cart-login').click()
    // S5-8: preview builds open on the code login; the account form is one tap away.
    await page.getByTestId('login-switch-mode').click()
    await page.locator('.inspection-login__input input').nth(0).fill('dev-customer')
    await page.locator('.inspection-login__input input').nth(1).fill(devPassword())
    await page.getByTestId('login-agreement').click()
    await page.locator('.inspection-login__submit').click()

    await expect(page.getByTestId('cart-empty')).toBeVisible()
    await expect(page.getByTestId('cart-count')).toHaveText('(0)')
    await page.getByTestId('cart-browse').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
  })

  test('CART-002 商品加入购物车，按商家结算，没有地址时先添加，再到“提交订单”为止', async ({ page, request }) => {
    await signIn(page, session)
    // 同一个商家的第二样商品，和另一个商家的一样商品，先用接口放进去。
    await addByApi(request, session, FIELD_FACTORY)
    await addByApi(request, session, KIT_STORE)

    await page.goto(`/#/pages/mall/product?productId=${KIT_ID}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await page.getByTestId('mall-product-cart').click()
    const sheet = page.getByTestId('mall-buy-sheet')
    // S3-1 gives this offer a stock of 12 in the development seed (catalog 1.3.0); either line until
    // the seed is reloaded.
    await expect(sheet).toContainText(/示例机器人商家（厂家直营）|示例机器人商家 · 东营现货 12 件/)
    // 原型对齐 R3 renames the kit's spec in the development seed; either name until it is reloaded.
    await expect(sheet).toContainText(/标准版|标准套装（1 台 \+ 数字课程）/)
    await expect(page.getByTestId('mall-buy-minus')).toHaveAttribute('aria-disabled', 'true')
    await page.getByTestId('mall-buy-plus').click()
    await expect(page.getByTestId('mall-buy-qty')).toHaveText('2')
    await expect(page.getByTestId('mall-buy-amount')).toHaveText('¥2,598')
    await page.getByTestId('mall-buy-add').click()
    await expect(page.locator('uni-toast')).toContainText('已加入购物车')
    await expect(sheet).toHaveCount(0)

    await openCartTab(page)
    await expect(page.getByTestId('cart-count')).toHaveText('(3)')
    // 底栏角标是能买的商品种数。
    await expect(page.locator('.root-tab-bar__item', { hasText: '购物车' }).locator('.root-tab-bar__badge')).toHaveText('3')
    const robot = page.getByTestId(`cart-group-${ROBOT_MERCHANT}`)
    const store = page.getByTestId(`cart-group-${STORE_MERCHANT}`)
    await expect(robot.locator('.cart__item')).toHaveCount(2)
    await expect(store.locator('.cart__item')).toHaveCount(1)

    // 一次只结算一个商家：选中机器人商家后，器材店的商品写“需分开结算”。
    await page.getByTestId(`cart-merchant-${ROBOT_MERCHANT}`).click()
    await expect(page.getByTestId(`cart-merchant-${ROBOT_MERCHANT}`)).toHaveAttribute('aria-checked', 'true')
    await expect(store).toContainText('与已选商家不同，需分开结算')
    await expect(page.getByTestId('cart-total')).toHaveText('¥3,058')
    await expect(page.getByTestId('cart-checkout')).toHaveText('结算(2)')

    // 改数量走服务端。
    const kit = robot.locator('.cart__item', { hasText: '编程机器人探索套装' })
    await kit.locator('.cart__step').first().click()
    await expect(kit.locator('.cart__qty')).toHaveText('1')
    await expect(page.getByTestId('cart-total')).toHaveText('¥1,759')

    await page.locator('[data-testid="cart-checkout"]:not([disabled])').click()
    await expect(page.getByTestId('checkout-merchant')).toHaveText('示例机器人商家')
    await expect(page.getByTestId('checkout-goods')).toHaveText('¥1,759')
    await expect(page.getByTestId('checkout-shipping')).toHaveText('¥0')
    await expect(page.getByTestId('checkout-payable')).toHaveText('¥1,759')
    // 两样商品没有共同的自提点，只能快递。
    await expect(page.getByTestId('checkout-mode-pickup')).toHaveCount(0)
    await expect(page.getByTestId('checkout-mode-express')).toHaveAttribute('aria-checked', 'true')

    // 没勾协议点提交：只提示先同意。
    await page.getByTestId('checkout-submit').click()
    await expect(page.getByTestId('checkout-agree-tip')).toBeVisible()

    // 没有地址：先添加，省市从平台城市目录里选。
    await page.getByTestId('checkout-add-address').click()
    await expect(page.getByTestId('address-book-empty')).toBeVisible()
    await page.getByTestId('address-add').click()
    await page.getByTestId('address-name').locator('input').fill('王女士')
    await page.getByTestId('address-phone').locator('input').fill('13800002026')
    await page.getByTestId('address-detail').locator('input').fill('东营区 示例路 88 号')
    await expect(page.locator('[data-testid="address-save"][disabled]')).toHaveCount(1)
    await page.getByTestId('address-region').click()
    await page.getByTestId('address-province-370000').click()
    await page.getByTestId('address-city-370500').click()
    // The region dialog (board RegionPick) closes on 确认.
    await expect(page.getByTestId('address-region-picked')).toContainText('山东省 东营市')
    await page.getByTestId('address-region-confirm').click()
    await expect(page.getByTestId('address-region')).toContainText('山东省 东营市')
    await expect(page.getByTestId('address-default')).toHaveAttribute('aria-checked', 'true')
    await page.locator('[data-testid="address-save"]:not([disabled])').click()

    await expect(page.getByTestId('checkout-address')).toContainText('王女士 138****2026')
    await expect(page.getByTestId('checkout-address')).toContainText('山东省东营市东营区 示例路 88 号')
    await page.getByTestId('checkout-agree').click()
    await page.getByTestId('checkout-submit').click()
    // Since E07 the order is made and the cashier replaces the confirm page (trade-orders.spec.ts
    // covers paying it). The cart lines that were bought leave the cart.
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥1,759')
    await expect(page.getByTestId('cashier-goods')).toContainText('（共 2 件）')
  })

  test('CART-003 器材店的商品立即购买：只能到店自提，不要地址', async ({ page }) => {
    await signIn(page, session)
    await page.goto(`/#/pages/mall/product?productId=${KIT_ID}&cityCode=${CITY}`)
    await page.getByTestId(`mall-offer-${KIT_STORE}`).click()
    await page.locator('[data-testid="mall-product-primary"][data-intent="BUY_NOW"]').click()
    await expect(page.getByTestId('mall-buy-sheet')).toContainText('示例科创器材店（本地商家）')
    await page.getByTestId('mall-buy-now').click()

    await expect(page.getByTestId('checkout-merchant')).toHaveText('示例科创器材店')
    await expect(page.getByTestId('checkout-mode-express')).toHaveCount(0)
    await expect(page.getByTestId('checkout-mode-pickup')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('checkout-pickup')).toContainText('示例科创器材店 东营门店')
    await expect(page.getByTestId('checkout-address')).toHaveCount(0)
    await expect(page.getByTestId('checkout-add-address')).toHaveCount(0)
    await expect(page.getByTestId('checkout-payable')).toHaveText('¥1,380')
  })

  test('CART-004 只有一样商品时快递和自提可以切换；自提时不显示地址', async ({ page, request }) => {
    await addAddressByApi(request, session)
    const line = await addByApi(request, session, KIT_FACTORY)
    await signIn(page, session)
    await page.goto(`/#/pages/cart/checkout?items=${line}&cityCode=${CITY}`)

    await expect(page.getByTestId('checkout-address')).toContainText('王女士')
    await page.getByTestId('checkout-mode-pickup').click()
    await expect(page.getByTestId('checkout-mode-pickup')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('checkout-pickup')).toContainText('东营低空与机器人体验馆 自提点')
    await expect(page.getByTestId('checkout-address')).toHaveCount(0)
    await page.getByTestId('checkout-mode-express').click()
    await expect(page.getByTestId('checkout-address')).toBeVisible()
  })

  test('CART-005 管理：跨商家勾选删除，删除前确认', async ({ page, request }) => {
    await addByApi(request, session, KIT_FACTORY)
    await addByApi(request, session, KIT_STORE)
    await signIn(page, session)
    await openCartTab(page)
    await expect(page.locator('.cart__item')).toHaveCount(2)

    await page.getByTestId('cart-manage').click()
    await expect(page.getByTestId('cart-manage')).toHaveText('完成')
    await page.getByTestId('cart-all').click()
    await expect(page.getByTestId('cart-delete')).toHaveText('删除(2)')
    await page.locator('[data-testid="cart-delete"]:not([disabled])').click()
    await expect(page.getByTestId('cart-delete-sheet')).toContainText('删除选中的 2 件商品')
    await page.getByTestId('cart-delete-confirm').click()
    await expect(page.getByTestId('cart-empty')).toBeVisible()
  })

  test('CART-006 失效商品单独列出，可以清空（接口桩）', async ({ page, request }) => {
    const line = await addByApi(request, session, KIT_FACTORY)
    await signIn(page, session)
    const real = await (await request.get(`${API}/api/v1/mall/cart?cityCode=${CITY}`, { headers: session.headers })).json()
    let cleared = false
    await page.route('**/api/v1/mall/cart?**', async (route) => {
      const item = { ...real.groups[0].items[0], unitAmountMinor: null, invalidReason: 'OFFER_UNAVAILABLE' }
      await route.fulfill({ json: cleared ? { groups: [], invalidItems: [], validCount: 0 } : { groups: [], invalidItems: [item], validCount: 0 } })
    })
    await page.route('**/api/v1/mall/cart/remove-invalid', async (route) => {
      cleared = true
      await route.fulfill({ json: { removed: 1 } })
    })
    await openCartTab(page)
    await expect(page.getByTestId(`cart-invalid-${line}`)).toContainText('商家已下架')
    await expect(page.getByTestId('cart-invalid')).toContainText('失效商品 (1)')
    await expect(page.getByTestId('cart-checkout')).toHaveText('结算')
    await page.getByTestId('cart-clear-invalid').click()
    await expect(page.getByTestId('cart-empty')).toBeVisible()
  })

  test('CART-007 换过商家的购物车行一起结算会被拒，回到购物车', async ({ page, request }) => {
    const robotLine = await addByApi(request, session, KIT_FACTORY)
    const storeLine = await addByApi(request, session, KIT_STORE)
    await signIn(page, session)
    await page.goto(`/#/pages/cart/checkout?items=${robotLine},${storeLine}&cityCode=${CITY}`)
    await expect(page.getByTestId('checkout-stale')).toContainText('每次只能结算一个商家')
    await page.getByTestId('checkout-back-to-cart').click()
    await expect(page.getByTestId('cart')).toBeVisible()
  })

  test('CART-008 租赁商品没有加入购物车', async ({ page }) => {
    await signIn(page, session)
    await page.goto(`/#/pages/mall/product?productId=${DOG_ID}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-primary')).toHaveText('联系询价')
    await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
  })

  test('CART-009 没登录打开确认订单和地址，都先到登录页', async ({ page }) => {
    for (const url of [
      `/#/pages/cart/checkout?offerId=${KIT_FACTORY}&quantity=1&cityCode=${CITY}`,
      '/#/pages/mine/addresses',
    ]) {
      await page.goto(url)
      await expect(page.locator('.inspection-login__form')).toBeVisible()
    }
  })

  test('CART-011 在确认订单换地址；换上的地址被删掉后退回默认地址', async ({ page, request }) => {
    await addAddressByApi(request, session)
    const other = await request.post(`${API}/api/v1/account/addresses`, {
      headers: session.headers,
      data: { recipientName: '张工', phone: '13900000817', cityCode: CITY, detail: '东营港 示例港路 6 号', isDefault: false },
    })
    const otherId = (await other.json()).addressId
    const line = await addByApi(request, session, KIT_FACTORY)
    await signIn(page, session)
    await page.goto('/#/pages/cart/index')
    await page.goto(`/#/pages/cart/checkout?items=${line}&cityCode=${CITY}`)
    await expect(page.getByTestId('checkout-address')).toContainText('王女士')

    await page.getByTestId('checkout-address').click()
    await expect(page.getByTestId('address-book')).toContainText('点选地址即用于本单')
    await page.getByTestId(`address-pick-${otherId}`).click()
    await expect(page.getByTestId('checkout-address')).toContainText('张工 139****0817')

    await page.getByTestId('checkout-address').click()
    await page.getByTestId(`address-edit-${otherId}`).click()
    await page.getByTestId('address-delete').click()
    await page.getByTestId('address-delete-confirm').click()
    await expect(page.getByTestId(`address-${otherId}`)).toHaveCount(0)
    await page.getByTestId('address-book-back').click()
    await expect(page.getByTestId('checkout-address')).toContainText('王女士')
  })

  test('CART-010 购物车、确认订单、地址、规格弹层在 320 / 375 / 430 宽不横向溢出，并留截图', async ({ page, request }) => {
    await addAddressByApi(request, session)
    const robotLine = await addByApi(request, session, KIT_FACTORY, 2)
    const fieldLine = await addByApi(request, session, FIELD_FACTORY)
    await addByApi(request, session, KIT_STORE)
    await signIn(page, session)
    mkdirSync(visualOutput, { recursive: true })

    for (const width of [375, 320, 430]) {
      await page.setViewportSize({ width, height: 844 })

      await openCartTab(page)
      await expect(page.locator('.cart__item')).toHaveCount(3)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `cart-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/cart/checkout?items=${robotLine},${fieldLine}&cityCode=${CITY}`)
      await expect(page.getByTestId('checkout-address')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `checkout-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/mine/addresses`)
      await expect(page.locator('.addresses__card')).toHaveCount(1)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `addresses-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/mall/product?productId=${KIT_ID}&cityCode=${CITY}`)
      await page.getByTestId('mall-product-cart').click()
      await expect(page.getByTestId('mall-buy-sheet')).toBeVisible()
      // The thumbnail is a background image that arrives a moment after the sheet.
      await page.waitForTimeout(800)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `buy-sheet-${width}.png`) })
    }
  })
})
