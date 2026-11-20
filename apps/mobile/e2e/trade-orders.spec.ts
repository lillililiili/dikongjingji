import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  API,
  BUYER,
  CITY,
  MERCHANT,
  addAddressByApi,
  apiLogin,
  beijingShortTime,
  clearAddresses,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  fakeWechatRefunds,
  latestRefundNo,
  offerId,
  openPaymentNo,
  payByApi,
  purgeBuyerOrders,
  readOrderByApi,
  shipByApi,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// 交易模块的 App 页面（E07）：确认订单提交、收银台、支付结果、商品订单列表和详情。
// 真实后端 + 本机模拟微信（FAKE_WECHAT=1）。H5 调不起微信，付款由模拟微信的控制入口代替买家完成，
// 和真机一样，订单只认服务端收到的回调或查单结果。每条用例开始前删掉 dev-customer 的全部订单。
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const DRONE_ID = 'c2000000-0000-4000-8000-000000000201'
const KIT_FACTORY = offerId('101', '1')
const DRONE_FACTORY = offerId('201', '4')
// The kit's spec: 原型对齐 R3 renames it in the development seed; until the backend reloads the seed the
// old name is still served, so either is accepted.
const KIT_SKU = '(?:标准版|标准套装（1 台 \\+ 数字课程）)'
const KIT_PRICE = 129900
const visualOutput = resolve(__dirname, '../../../.planning/trade-orders/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare')

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function confirmModal(page: Page, text?: string): Promise<void> {
  const modal = page.locator('uni-modal')
  await expect(modal).toBeVisible()
  if (text) await expect(modal).toContainText(text)
  await modal.locator('.uni-modal__btn_primary').click()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

// 我的 → 待付款 opens 我的订单 (R6-1) on its 待付款 chip; the goods-only list keeps its own address,
// opened here on its 待付款 tab as the cell used to.
async function openGoodsOrdersFromMine(page: Page): Promise<void> {
  await page.goto('/#/pages/mine/index')
  await page.getByTestId('mine-goods-orders').click()
  await expect(page).toHaveURL(/pages\/mine\/orders\?status=pay$/)
  await expect(page.getByTestId('my-orders-chip-pay')).toHaveAttribute('aria-pressed', 'true')
  // A query of its own, so a later goto of the same hash still loads the page afresh.
  await page.goto('/?from=mine#/pages/mine/goods-orders?tab=TO_PAY')
  await expect(page.getByTestId('goods-orders')).toBeVisible()
}

async function clearCart(request: APIRequestContext, session: Session): Promise<void> {
  const cart = await (await request.get(`${API}/api/v1/mall/cart?cityCode=${CITY}`, { headers: session.headers })).json()
  const ids = [
    ...cart.groups.flatMap((group: { items: Array<{ cartItemId: string }> }) => group.items.map((item) => item.cartItemId)),
    ...cart.invalidItems.map((item: { cartItemId: string }) => item.cartItemId),
  ]
  if (ids.length > 0) {
    await request.post(`${API}/api/v1/mall/cart/remove`, { headers: session.headers, data: { cartItemIds: ids } })
  }
}

test.describe('商品订单与支付', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let merchant: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试订单')
    buyer = signedIn as Session
    merchant = (await apiLogin(request, MERCHANT)) as Session
    purgeBuyerOrders()
    await clearAddresses(request, buyer)
    await clearCart(request, buyer)
  })

  test.afterAll(async ({ request }) => {
    if (!databaseAvailable()) return
    purgeBuyerOrders()
    const signedIn = await apiLogin(request, BUYER)
    if (signedIn) {
      await clearAddresses(request, signedIn)
      await clearCart(request, signedIn)
    }
  })

  test('TRD-001 立即购买到付款、发货、确认收货：收银台 → 支付结果（服务端查单）→ 订单详情 → 列表已完成', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    await addAddressByApi(request, buyer)
    await signIn(page, buyer)

    // 商品详情 → 立即购买 → 确认订单（快递，默认地址）。
    await page.goto(`/#/pages/mall/product?productId=${KIT_ID}&cityCode=${CITY}`)
    await page.locator('[data-testid="mall-product-primary"][data-intent="BUY_NOW"]').click()
    await page.getByTestId('mall-buy-now').click()
    await expect(page.getByTestId('checkout-address')).toContainText('王女士')
    await expect(page.getByTestId('checkout-payable')).toHaveText('¥1,299')
    await page.getByTestId('checkout-note').locator('input').fill('工作日送货')
    await page.waitForTimeout(150)
    await page.getByTestId('checkout-agree').click()
    await page.getByTestId('checkout-submit').click()

    // 收银台替换了确认订单。
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page).toHaveURL(/pages\/trade\/cashier\?orderId=/)
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥1,299')
    await expect(page.getByTestId('cashier-payee')).toHaveText('示例机器人商家')
    await expect(page.getByTestId('cashier-goods')).toHaveText(new RegExp(`^编程机器人探索套装 · ${KIT_SKU} × 1$`))
    await expect(page.getByTestId('cashier-countdown')).toHaveText(/^剩余支付时间 (29|30):\d\d · 超时订单自动取消$/)
    await expect(page.getByTestId('cashier-method')).toContainText('微信支付')
    const orderId = new URL(page.url().replace('/#/', '/')).searchParams.get('orderId') as string
    const orderNo = (await page.getByTestId('cashier-order-no').textContent())?.trim() as string
    expect(orderNo).toMatch(/^MO\d{16}$/)

    // H5 调不起微信：先提示，再进结果页由服务端查单；没付款，查完是“支付失败”。
    await page.getByTestId('cashier-pay').click()
    await expect(page.locator('uni-toast')).toContainText('请在 App 中完成微信支付')
    await expect(page.getByTestId('pay-result')).toBeVisible()
    await expect(page.getByTestId('pay-result-pending')).toBeVisible()
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('pay-result-failed')).toContainText('订单仍为待付款，超时订单自动取消')

    // 模拟微信替买家付款并发回调；“刷新结果”让服务端再查一次。
    await fakeWechatPays(request, openPaymentNo(orderId))
    await page.getByTestId('pay-result-refresh').click()
    await expect(page.getByTestId('pay-result-paid')).toBeVisible()
    await expect(page.getByTestId('pay-result-amount')).toHaveText('¥1,299')
    await expect(page.getByTestId('pay-result-order-no')).toHaveText(orderNo)
    await expect(page.getByTestId('pay-result-payee')).toHaveText('示例机器人商家')
    const paid = await readOrderByApi(request, buyer, orderId) as unknown as { paidAt: string }
    await expect(page.getByTestId('pay-result-paid-at')).toHaveText(beijingShortTime(paid.paidAt))

    await page.getByTestId('pay-result-view-order').click()
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('待发货')
    await expect(page.getByTestId('goods-order-recipient')).toHaveText('王女士 138****2026')
    await expect(page.getByTestId('goods-order-payment-status')).toHaveText('已支付')
    await expect(page.getByTestId('goods-order')).toContainText('工作日送货')
    // 已付款未发货可以取消（取消即退款），这条用例不取消。
    await expect(page.getByTestId('goods-order-action-cancel_and_refund')).toBeVisible()

    // 商家用受权接口发货，买家刷新后看到运单和自动确认收货的日期。
    await shipByApi(request, merchant, await readOrderByApi(request, buyer, orderId), 'SF2026111800001')
    const shipped = await readOrderByApi(request, buyer, orderId)
    await page.reload()
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('已发货')
    const autoReceipt = beijingShortTime(new Date(new Date(shipped.delivery.shippedAt as string).getTime() + 10 * 86_400_000).toISOString())
    await expect(page.getByTestId('goods-order-status-line')).toHaveText(`${autoReceipt} 将自动确认收货（发货 10 天后）`)
    await expect(page.getByTestId('goods-order-shipment')).toContainText('已发货 · 示例快递')
    await expect(page.getByTestId('goods-order-shipment')).toContainText('运单号 SF2026111800001')
    await expect(page.getByTestId('goods-order-action-cancel_and_refund')).toHaveCount(0)

    await page.getByTestId('goods-order-action-confirm_receipt').click()
    await confirmModal(page, '确认后交易完成')
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('已完成')
    await expect(page.getByTestId('goods-order-action-confirm_receipt')).toHaveCount(0)

    // “我的” → 商品订单 → 已完成。
    await openGoodsOrdersFromMine(page)
    await page.getByTestId('goods-orders-tab-DONE').click()
    const card = page.getByTestId(`goods-order-${orderNo}`)
    await expect(card.getByTestId('goods-order-status')).toHaveText('已完成')
    await expect(card.getByTestId('goods-order-amount')).toHaveText('¥1,299')
    await expect(card).toContainText(new RegExp(`${KIT_SKU} × 1 · 快递配送`))
  })

  test('TRD-002 待付款的订单在列表里取消：确认后关闭，待付款页签变空', async ({ page, request }) => {
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await signIn(page, buyer)
    await openGoodsOrdersFromMine(page)
    await page.getByTestId('goods-orders-tab-TO_PAY').click()
    const card = page.getByTestId(`goods-order-${order.orderNo}`)
    await expect(card.getByTestId('goods-order-status')).toHaveText('待付款')
    await expect(card.getByTestId('goods-order-hint')).toHaveText(/^剩 (29|30):\d\d 未支付将自动取消$/)
    await expect(card).toContainText(new RegExp(`${KIT_SKU} × 1 · 到店自提`))

    await card.getByTestId('goods-order-action-cancel').click()
    await confirmModal(page, '取消后订单将关闭')
    await expect(page.getByTestId('goods-orders-empty')).toHaveText('暂无相关订单')
    const closed = await readOrderByApi(request, buyer, order.orderId)
    expect(closed.status).toBe('CLOSED')
    expect(closed.closeReason).toBe('BUYER_CANCEL')

    await page.getByTestId('goods-orders-tab-ALL').click()
    await expect(card.getByTestId('goods-order-status')).toHaveText('已关闭')
    await expect(card.getByTestId('goods-order-hint')).toHaveText('订单已取消')
    await expect(card.getByTestId('goods-order-action-view')).toBeVisible()
  })

  test('TRD-003 自提单付款后显示取货码；未取货时取消即全额退款，退款到账后显示已退款', async ({ page, request }) => {
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await payByApi(request, buyer, order.orderId)
    const paid = await readOrderByApi(request, buyer, order.orderId)
    expect(paid.delivery.pickupCode).toMatch(/^\d{6}$/)

    await signIn(page, buyer)
    await page.goto(`/#/pages/mine/goods-order?orderId=${order.orderId}`)
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('待自提')
    await expect(page.getByTestId('goods-order-pickup-point')).toContainText('东营低空与机器人体验馆 自提点')
    await expect(page.getByTestId('goods-order-pickup-code')).toContainText(paid.delivery.pickupCode as string)
    await expect(page.getByTestId('goods-order-recipient')).toHaveCount(0)

    await page.getByTestId('goods-order-action-cancel_and_refund').click()
    await confirmModal(page, '货款将原路退回')
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('退款中')
    await expect(page.getByTestId('goods-order-refunds')).toContainText('¥1,299')
    await expect(page.getByTestId('goods-order-refund-status')).toHaveText('退款处理中')
    await expect(page.getByTestId('goods-order-pickup-code')).toHaveCount(0)

    await fakeWechatRefunds(request, latestRefundNo(order.orderId))
    await expect.poll(async () => (await readOrderByApi(request, buyer, order.orderId)).status).toBe('REFUNDED')
    await page.reload()
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('已退款')
    await expect(page.getByTestId('goods-order-status-line')).toHaveText('¥1,299 已原路退回')
    await expect(page.getByTestId('goods-order-refunded')).toHaveText('¥1,299')
    await expect(page.getByTestId('goods-order-refund-status')).toHaveText('已退回')
    await expect(page.getByTestId('goods-order-payment-status')).toHaveText('已支付 · 已全额退款')
    // Nothing left to do with the order; only 联系商家 (G1) stays in the bar.
    await expect(page.locator('.goods-order__bar-button')).toHaveCount(0)
    await expect(page.getByTestId('goods-order-contact')).toBeVisible()

    await page.goto('/#/pages/mine/goods-orders?tab=REFUND')
    await expect(page.getByTestId('goods-orders-tab-REFUND')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId(`goods-order-${order.orderNo}`).getByTestId('goods-order-status')).toHaveText('已退款')
  })

  test('TRD-004 没开通在线支付的商家：商品详情灰显、选不中；从链接进确认订单，提交时页面写明，不能再提交', async ({ page, request }) => {
    await addAddressByApi(request, buyer)
    await signIn(page, buyer)
    // Since 2026-11-20 (原型对齐 R3) the development seed registers 示例无人机厂家 too, so no sample
    // merchant of a listed product lacks a payment account. The page's handling of one that does is
    // kept by answering for 示例无人机厂家 as if it had none: the detail flag and the server's refusal.
    await page.route(`**/api/v1/mall/products/${DRONE_ID}?**`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      for (const offer of body.offers ?? []) if (offer.offerId === DRONE_FACTORY) offer.onlinePayment = false
      await route.fulfill({ response, json: body })
    })
    await page.route('**/api/v1/trade/orders', async (route) => {
      const body = route.request().postDataJSON() as { offerId?: string } | null
      if (route.request().method() !== 'POST' || body?.offerId !== DRONE_FACTORY) return route.continue()
      await route.fulfill({
        status: 409,
        json: { code: 'TRADE_MERCHANT_PAYMENT_UNAVAILABLE', message: '该商家暂未开通在线支付', traceId: 'e2e', details: null },
      })
    })
    await page.goto(`/#/pages/mall/product?productId=${DRONE_ID}&cityCode=${CITY}`)
    // Catalog 1.2.0 (G1): the detail knows the merchant cannot take payment online.
    await expect(page.getByTestId(`mall-offer-unpaid-${DRONE_FACTORY}`)).toHaveText('暂未开通在线支付')
    await page.getByTestId(`mall-offer-${DRONE_FACTORY}`).click()
    await expect(page.getByTestId(`mall-offer-${DRONE_FACTORY}`)).toHaveAttribute('aria-pressed', 'false')
    // The confirm page still refuses it when reached by a link.
    await page.goto(`/?n=checkout#/pages/cart/checkout?offerId=${DRONE_FACTORY}&quantity=1&cityCode=${CITY}`)
    await expect(page.getByTestId('checkout-merchant')).toHaveText('示例无人机厂家')
    await expect(page.getByTestId('checkout-address')).toBeVisible()
    await page.getByTestId('checkout-agree').click()
    await page.getByTestId('checkout-submit').click()

    await expect(page.getByTestId('checkout-payment-unavailable')).toHaveText('该商家暂未开通在线支付，暂时不能下单')
    await expect(page.getByTestId('checkout-submit')).toHaveAttribute('disabled', 'true')
    await page.getByTestId('checkout-submit').click({ force: true })
    await expect(page.getByTestId('checkout')).toBeVisible()
    await expect(page).toHaveURL(/pages\/cart\/checkout/)
    const list = await (await request.get(`${API}/api/v1/trade/orders`, { headers: buyer.headers })).json()
    expect(list.total).toBe(0)
  })

  test('TRD-005 页签切换和空态；地址带的页签直接选中', async ({ page, request }) => {
    await signIn(page, buyer)
    // 我的页的“待付款”格（board Mine 的订单五阶段）进我的订单的待付款胶囊；商品订单页的待付款页签。
    await openGoodsOrdersFromMine(page)
    await expect(page.getByTestId('goods-orders-tab-TO_PAY')).toHaveAttribute('aria-selected', 'true')
    await page.getByTestId('goods-orders-tab-ALL').click()
    await expect(page.getByTestId('goods-orders-tab-ALL')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('goods-orders-empty')).toHaveText('暂无相关订单')

    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await page.getByTestId('goods-orders-tab-TO_PAY').click()
    await expect(page.getByTestId(`goods-order-${order.orderNo}`)).toBeVisible()
    await expect(page.getByTestId('goods-orders-end')).toHaveText('没有更多了')
    for (const tab of ['TO_SHIP', 'TO_RECEIVE', 'DONE', 'REFUND']) {
      await page.getByTestId(`goods-orders-tab-${tab}`).click()
      await expect(page.getByTestId(`goods-orders-tab-${tab}`)).toHaveAttribute('aria-selected', 'true')
      await expect(page.getByTestId('goods-orders-empty')).toBeVisible()
    }

    // 继续支付进收银台。
    await page.goto('/#/pages/mine/goods-orders?tab=TO_PAY')
    await expect(page.getByTestId('goods-orders-tab-TO_PAY')).toHaveAttribute('aria-selected', 'true')
    await page.getByTestId(`goods-order-${order.orderNo}`).getByTestId('goods-order-action-pay').click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥1,299')
    await page.getByTestId('cashier-back').click()
    await expect(page.getByTestId('goods-orders')).toBeVisible()
  })

  test('TRD-006 收银台取消支付、过了付款时间显示“支付已超时”', async ({ page, request }) => {
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await signIn(page, buyer)
    await page.goto(`/#/pages/trade/cashier?orderId=${order.orderId}`)
    await page.getByTestId('cashier-cancel').click()
    await confirmModal(page)
    await expect(page.getByTestId('cashier-closed')).toContainText('订单已取消')
    await expect(page.getByTestId('cashier-pay')).toHaveCount(0)
    await page.getByTestId('cashier-view-order').click()
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('订单已取消')
    await expect(page.getByTestId('goods-order-status-line')).toHaveText('你已取消这笔订单，没有扣款')

    // 截止时间到了而服务端还没来得及关单：页面已按超时处理，不再给“确认支付”。
    const second = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await page.route(`**/api/v1/trade/orders/${second.orderId}`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      await route.fulfill({ response, json: { ...body, payBefore: new Date(Date.now() - 60_000).toISOString() } })
    })
    await page.goto(`/?expired=1#/pages/trade/cashier?orderId=${second.orderId}`)
    await expect(page.getByTestId('cashier-closed')).toContainText('支付已超时')
    await expect(page.getByTestId('cashier-closed')).toContainText('没有扣款，可以重新下单')
    await expect(page.getByTestId('cashier-rebuy')).toBeVisible()
  })

  test('TRD-007 订单页在 320 / 375 / 430 宽不横向溢出，并留截图', async ({ page, request }) => {
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const addressId = await addAddressByApi(request, buyer)
    const pending = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    const express = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await payByApi(request, buyer, express.orderId)
    await shipByApi(request, merchant, await readOrderByApi(request, buyer, express.orderId), 'SF2026111800002')
    await signIn(page, buyer)
    mkdirSync(visualOutput, { recursive: true })

    for (const width of [375, 320, 430]) {
      await page.setViewportSize({ width, height: 844 })
      await page.goto(`/?w=${width}#/pages/mine/goods-orders`)
      await expect(page.locator('.goods-orders__card')).toHaveCount(2)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `goods-orders-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/mine/goods-order?orderId=${express.orderId}`)
      await expect(page.getByTestId('goods-order-shipment')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `goods-order-shipped-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/trade/cashier?orderId=${pending.orderId}`)
      await expect(page.getByTestId('cashier-amount')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `cashier-${width}.png`), fullPage: true })

      await page.goto(`/?w=${width}#/pages/trade/pay-result?orderId=${express.orderId}`)
      await expect(page.getByTestId('pay-result-paid')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `pay-result-paid-${width}.png`), fullPage: true })
    }
  })

  test('TRD-008 与原型画板同宽（390）的截图：收银台、支付结果三种状态、订单列表、订单详情', async ({ page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const addressId = await addAddressByApi(request, buyer)
    const pickup = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await payByApi(request, buyer, pickup.orderId)
    const express = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await payByApi(request, buyer, express.orderId)
    await shipByApi(request, merchant, await readOrderByApi(request, buyer, express.orderId), 'SF2026111800003')
    const pending = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await signIn(page, buyer)
    await page.setViewportSize({ width: 390, height: 844 })
    const shot = async (folder: string, name: string) => {
      mkdirSync(resolve(compareOutput, folder), { recursive: true })
      await page.waitForTimeout(500)
      // A viewport as tall as the page, so the sticky bottom bar sits at the end as on the board
      // (a fullPage capture of an 844-high viewport leaves it in the middle).
      const height = await page.evaluate(() => document.documentElement.scrollHeight)
      await page.setViewportSize({ width: 390, height: Math.max(844, height) })
      await page.waitForTimeout(300)
      await page.screenshot({ path: resolve(compareOutput, folder, `${name}-app.png`) })
      await page.setViewportSize({ width: 390, height: 844 })
    }

    await page.goto(`/?s=1#/pages/trade/cashier?orderId=${pending.orderId}`)
    await expect(page.getByTestId('cashier-amount')).toBeVisible()
    await shot('e07-cashier', 'Cashier-pay')

    await page.route(`**/api/v1/trade/orders/${pending.orderId}`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      await route.fulfill({ response, json: { ...body, payBefore: new Date(Date.now() - 60_000).toISOString() } })
    })
    await page.goto(`/?s=2#/pages/trade/cashier?orderId=${pending.orderId}`)
    await expect(page.getByTestId('cashier-closed')).toBeVisible()
    await shot('e07-cashier', 'Cashier-expired')
    await page.unrouteAll()

    // 确认中：让查单晚 4 秒回来，截下等待的样子。
    await page.route(`**/api/v1/trade/orders/${pending.orderId}/payment-check`, async (route) => {
      await new Promise((done) => setTimeout(done, 4000))
      await route.continue().catch(() => undefined)
    })
    await page.goto(`/?s=3#/pages/trade/pay-result?orderId=${pending.orderId}`)
    await expect(page.getByTestId('pay-result-pending')).toBeVisible()
    await shot('e07-pay-result', 'PayResult-pending')
    await page.unrouteAll({ behavior: 'ignoreErrors' })

    await page.goto(`/?s=4#/pages/trade/pay-result?orderId=${pending.orderId}`)
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await shot('e07-pay-result', 'PayResult-failed')

    await page.goto(`/?s=5#/pages/trade/pay-result?orderId=${express.orderId}`)
    await expect(page.getByTestId('pay-result-paid')).toBeVisible()
    await shot('e07-pay-result', 'PayResult-paid')

    await page.goto('/?s=6#/pages/mine/goods-orders')
    await expect(page.locator('.goods-orders__card')).toHaveCount(3)
    await shot('e07-order-list', 'OrderList')

    await page.goto(`/?s=7#/pages/mine/goods-order?orderId=${express.orderId}`)
    await expect(page.getByTestId('goods-order-shipment')).toBeVisible()
    await shot('e07-order-goods', 'OrderGoods-shipped')

    await page.goto(`/?s=8#/pages/mine/goods-order?orderId=${pickup.orderId}`)
    await expect(page.getByTestId('goods-order-pickup-code')).toBeVisible()
    await shot('e07-order-goods', 'OrderGoods-pickup')

    await page.goto('/?s=9#/pages/mine/index')
    await expect(page.getByTestId('mine-goods-orders')).toBeVisible()
    await shot('e07-mine', 'Mine')
  })
})
