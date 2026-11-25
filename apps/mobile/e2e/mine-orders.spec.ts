import { randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { purgeFreeEnrollments } from './support/activity-fixtures'
import {
  API,
  BUYER,
  CITY,
  MERCHANT,
  addAddressByApi,
  apiLogin,
  clearAddresses,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  offerId,
  openPaymentNo,
  payByApi,
  psql,
  purgeBuyerOrders,
  readOrderByApi,
  shipByApi,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// 我的订单（原型对齐 R6-1，画板 OrderList）：商品订单、租赁订单、巡检订单、活动报名、到馆预约合在一张列表里，
// 全部 / 商品 / 服务 / 报名四个页签，五个状态胶囊（再点取消选中）。真实后端：用接口给 dev-customer 建商品订单、
// 免费报名（和需要模拟微信时的租赁订单），每条用例前后删掉这些数据；dev-customer 的开发种子巡检订单（待确认）原样留着。
const KIT_FACTORY = offerId('101', '1')
const KIT_PRICE = 129900
const DOG_OFFER = offerId('401', '1')
const OPEN_DAY_ID = 'a2000000-0000-4000-8000-000000000005'
const OPEN_DAY_SESSION = 'a3000000-0000-4000-8000-000000000502'
const RENTAL_ADDRESS = 'E2E 我的订单 东营港 示例港路 6 号'
const compareOutput = resolve(__dirname, '../../../.planning/style-compare/fix-20261120/r6-orders')

const buyerId = `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

// Rental inquiries and orders first (their trade orders go with them), then every other trade order,
// then the free sign-ups.
function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_inquiries ON COMMIT DROP AS SELECT id FROM rental_inquiry WHERE account_id = ${buyerId};
    CREATE TEMP TABLE e2e_rental_orders ON COMMIT DROP AS
      SELECT trade_order_id AS id FROM rental_order WHERE inquiry_id IN (SELECT id FROM e2e_inquiries);
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_rental_orders) FOR UPDATE) locked;
    DELETE FROM rental_order WHERE inquiry_id IN (SELECT id FROM e2e_inquiries);
    DELETE FROM rental_inquiry WHERE id IN (SELECT id FROM e2e_inquiries);
    DELETE FROM account_address WHERE owner_id = ${buyerId} AND detail = '${RENTAL_ADDRESS}';
    COMMIT;
  `)
  purgeBuyerOrders()
  purgeFreeEnrollments()
}

async function cancelUpcomingEnrollments(request: APIRequestContext, session: Session): Promise<void> {
  const upcoming = await (await request.get(
    `${API}/api/v1/activity-enrollments?status=UPCOMING&size=50`, { headers: session.headers })).json()
  for (const enrollment of upcoming.items) {
    await request.post(`${API}/api/v1/activity-enrollments/${enrollment.enrollmentId}/cancel`, {
      headers: session.headers,
      data: {},
    })
  }
}

async function enrollSelf(request: APIRequestContext, session: Session): Promise<string> {
  const people = await (await request.get(`${API}/api/v1/activity-participants`, { headers: session.headers })).json()
  const self = people.items.find((person: { relation: string }) => person.relation === 'SELF').participantId
  const created = await request.post(`${API}/api/v1/activity-enrollments`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { activityId: OPEN_DAY_ID, sessionId: OPEN_DAY_SESSION, participantIds: [self], noticeAgreed: true },
  })
  expect(created.status(), await created.text()).toBe(201)
  return (await created.json()).enrollmentId
}

function beijingDay(offset: number): string {
  const day = new Date(Date.now() + 8 * 3_600_000 + offset * 86_400_000)
  return day.toISOString().slice(0, 10)
}

// An inquiry quoted by the merchant, confirmed and paid: a rental order waiting for its handover.
async function paidRental(request: APIRequestContext, buyer: Session, merchant: Session): Promise<string> {
  const address = await request.post(`${API}/api/v1/account/addresses`, {
    headers: buyer.headers,
    data: { recipientName: '王女士', phone: '13800002026', cityCode: CITY, detail: RENTAL_ADDRESS, isDefault: false },
  })
  expect(address.status()).toBe(201)
  const inquiry = await (await request.post(`${API}/api/v1/rental-inquiries`, {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: {
      offerId: DOG_OFFER,
      quantity: 1,
      startDate: beijingDay(3),
      days: 5,
      handoverMode: 'DELIVERY',
      returnMode: 'COLLECT',
      addressId: (await address.json()).addressId,
      note: null,
    },
  })).json()
  const quoted = await request.put(`${API}/api/v1/provider/rental-inquiries/${inquiry.inquiryId}/quote`, {
    headers: merchant.headers,
    data: {
      dailyRentMinor: 38_000,
      days: 5,
      quantity: 1,
      fees: [],
      scheduleAvailable: true,
      validUntil: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      terms: '押金：当前不在线收取，按合同约定。',
    },
  })
  expect(quoted.status(), await quoted.text()).toBe(200)
  const quote = await quoted.json()
  const confirmed = await request.post(`${API}/api/v1/rental-inquiries/${inquiry.inquiryId}/confirm`, {
    headers: { ...buyer.headers, 'If-Match': `"${quote.version}"` },
    data: { quoteVersion: quote.quote.version, agreed: true },
  })
  expect(confirmed.status(), await confirmed.text()).toBe(200)
  const { rentalOrderId, tradeOrderId } = await confirmed.json()
  const launched = await request.post(`${API}/api/v1/trade/orders/${tradeOrderId}/payments`, {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: { channel: 'WECHAT_APP' },
  })
  expect(launched.status()).toBe(200)
  await fakeWechatPays(request, openPaymentNo(tradeOrderId))
  await expect.poll(async () => (await (await request.get(`${API}/api/v1/rental-orders/${rentalOrderId}`, {
    headers: buyer.headers,
  })).json()).status).toBe('AWAITING_HANDOVER')
  return rentalOrderId
}

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

const tab = (page: Page, name: string) => page.getByTestId(`my-orders-tab-${name}`)
const chip = (page: Page, name: string) => page.getByTestId(`my-orders-chip-${name}`)

test.describe('我的订单', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let merchant: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试数据')
    buyer = signedIn as Session
    merchant = (await apiLogin(request, MERCHANT)) as Session
    await cancelUpcomingEnrollments(request, buyer)
    cleanup()
    await clearAddresses(request, buyer)
  })

  test.afterAll(async ({ request }) => {
    if (!databaseAvailable()) return
    const signedIn = await apiLogin(request, BUYER)
    if (signedIn) await cancelUpcomingEnrollments(request, signedIn)
    cleanup()
    if (signedIn) await clearAddresses(request, signedIn)
  })

  test('MYO-001 从“我的”的“全部 ›”进入；页签和状态胶囊过滤；点卡片进详情再返回', async ({ page, request }) => {
    test.setTimeout(90_000)
    const goods = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    const enrollmentId = await enrollSelf(request, buyer)
    const goodsCard = page.getByTestId(`my-order-goods-${goods.orderId}`)
    const enrollCard = page.getByTestId(`my-order-enroll-${enrollmentId}`)
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-orders-all').click()
    await expect(page).toHaveURL(/pages\/mine\/orders$/)
    await expect(page.getByTestId('my-orders-head')).toContainText('我的订单')
    await expect(tab(page, 'all')).toHaveAttribute('aria-selected', 'true')
    for (const name of ['pay', 'ship', 'attend', 'accept', 'refund']) {
      await expect(chip(page, name)).toHaveAttribute('aria-pressed', 'false')
    }
    await expect(goodsCard).toBeVisible()
    await expect(goodsCard.getByTestId('my-order-status')).toHaveText('待付款')
    await expect(goodsCard.getByTestId('my-order-amount')).toHaveText('¥1,299')
    await expect(goodsCard.getByTestId('my-order-line-hint')).toHaveText(/^剩 (29|30):\d\d 未支付将自动取消$/)
    await expect(enrollCard).toBeVisible()
    await expect(enrollCard.getByTestId('my-order-status')).toHaveText('待参加')
    await expect(enrollCard.getByTestId('my-order-amount')).toHaveText('免费')
    // Newest first: the sign-up was made after the goods order.
    const ids = await page.locator('[data-testid^="my-order-goods-"], [data-testid^="my-order-enroll-"]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid')).filter((id) => !id?.endsWith('-open')))
    expect(ids).toEqual([`my-order-enroll-${enrollmentId}`, `my-order-goods-${goods.orderId}`])
    await assertNoHorizontalOverflow(page)

    // Business tabs.
    await tab(page, 'goods').click()
    await expect(tab(page, 'goods')).toHaveAttribute('aria-selected', 'true')
    await expect(goodsCard).toBeVisible()
    await expect(enrollCard).toHaveCount(0)
    await tab(page, 'enroll').click()
    await expect(enrollCard).toBeVisible()
    await expect(goodsCard).toHaveCount(0)
    await tab(page, 'service').click()
    await expect(goodsCard).toHaveCount(0)
    await expect(enrollCard).toHaveCount(0)

    // Status chips under 全部; the chosen chip again shows every status.
    await tab(page, 'all').click()
    await chip(page, 'pay').click()
    await expect(chip(page, 'pay')).toHaveAttribute('aria-pressed', 'true')
    await expect(goodsCard).toBeVisible()
    await expect(enrollCard).toHaveCount(0)
    await chip(page, 'attend').click()
    await expect(chip(page, 'pay')).toHaveAttribute('aria-pressed', 'false')
    await expect(chip(page, 'attend')).toHaveAttribute('aria-pressed', 'true')
    await expect(enrollCard).toBeVisible()
    await expect(goodsCard).toHaveCount(0)
    await chip(page, 'attend').click()
    await expect(chip(page, 'attend')).toHaveAttribute('aria-pressed', 'false')
    await expect(goodsCard).toBeVisible()
    await expect(enrollCard).toBeVisible()

    // A tab and a chip with nothing in them.
    await tab(page, 'enroll').click()
    await chip(page, 'accept').click()
    await expect(page.getByTestId('my-orders-empty')).toHaveText('暂无相关订单')
    await chip(page, 'accept').click()
    await tab(page, 'all').click()

    // The goods card opens the goods order; back returns to the list as it was.
    await chip(page, 'pay').click()
    await goodsCard.getByTestId(`my-order-goods-${goods.orderId}-open`).click()
    await expect(page).toHaveURL(new RegExp(`pages/mine/goods-order\\?orderId=${goods.orderId}`))
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('待付款')
    await page.getByTestId('goods-order-back').click()
    await expect(page.getByTestId('my-orders')).toBeVisible()
    await expect(chip(page, 'pay')).toHaveAttribute('aria-pressed', 'true')
    await expect(goodsCard).toBeVisible()

    // The free sign-up opens on its ticket.
    await chip(page, 'pay').click()
    await enrollCard.getByTestId(`my-order-enroll-${enrollmentId}-open`).click()
    await expect(page).toHaveURL(new RegExp(`pages/activity/ticket\\?enrollmentId=${enrollmentId}`))
    await expect(page.getByTestId('activity-ticket')).toBeVisible()
    await page.getByTestId('ticket-back').click()
    await expect(page.getByTestId('my-orders')).toBeVisible()

    // The page's own back returns to 我的.
    await page.getByTestId('my-orders-back').click()
    await expect(page.getByTestId('mine-page')).toBeVisible()
  })

  test('MYO-002 “我的”订单前四阶段进我的订单的对应胶囊、退款/售后进售后列表；列表里取消待付款的商品订单', async ({ page, request }) => {
    const goods = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await signIn(page, buyer)

    const stages: Array<[number, string]> = [[0, 'pay'], [1, 'ship'], [2, 'attend'], [3, 'accept']]
    for (const [index, name] of stages) {
      await page.goto('/#/pages/mine/index')
      await page.locator('.platform-mine__status-item').nth(index).click()
      await expect(page).toHaveURL(new RegExp(`pages/mine/orders\\?status=${name}$`))
      await expect(chip(page, name)).toHaveAttribute('aria-pressed', 'true')
      await expect(tab(page, 'all')).toHaveAttribute('aria-selected', 'true')
    }
    // 退款/售后 opens the after-sales list (board Mine → AftersaleList, S4-2).
    await page.goto('/#/pages/mine/index')
    await page.locator('.platform-mine__status-item').nth(4).click()
    await expect(page).toHaveURL(/pages\/mine\/aftersales\?tab=DOING$/)
    await expect(page.getByTestId('aftersales-tab-DOING')).toHaveAttribute('aria-selected', 'true')

    // 待付款 is the goods-orders cell of 我的 (testid kept); the order can be cancelled on the list.
    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-goods-orders').click()
    const card = page.getByTestId(`my-order-goods-${goods.orderId}`)
    await expect(card.getByTestId('my-order-action-pay')).toHaveText('继续支付')
    await card.getByTestId('my-order-action-cancel').click()
    await confirmModal(page, '取消后订单将关闭')
    await expect(page.getByTestId('my-orders-empty')).toBeVisible()
    expect((await readOrderByApi(request, buyer, goods.orderId)).status).toBe('CLOSED')
    // A cancelled order is in no chip; 全部 still lists it.
    await chip(page, 'pay').click()
    await expect(card.getByTestId('my-order-status')).toHaveText('已关闭')
    await expect(card.getByTestId('my-order-line-hint')).toHaveText('订单已取消')
  })

  test('MYO-003 待发货/履约：付款未发货的商品和待交接的租赁；服务页签只有租赁', async ({ page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const goods = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await payByApi(request, buyer, goods.orderId)
    const rentalOrderId = await paidRental(request, buyer, merchant)
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/orders?status=ship')
    const goodsCard = page.getByTestId(`my-order-goods-${goods.orderId}`)
    const rentalCard = page.getByTestId(`my-order-rental-${rentalOrderId}`)
    await expect(goodsCard.getByTestId('my-order-status')).toHaveText('待自提')
    await expect(rentalCard.getByTestId('my-order-status')).toHaveText('待交接')
    await expect(rentalCard).toContainText('巡检机器狗 ×1')
    await expect(rentalCard).toContainText('示例机器人商家')

    await tab(page, 'service').click()
    await expect(rentalCard).toBeVisible()
    await expect(goodsCard).toHaveCount(0)
    await rentalCard.getByTestId('my-order-action-view').click()
    await expect(page).toHaveURL(new RegExp(`pages/rental/order\\?rentalOrderId=${rentalOrderId}`))
    await expect(page.getByTestId('rental-order')).toHaveAttribute('data-status', 'AWAITING_HANDOVER')
  })

  test('MYO-004 对照图：画板 OrderList 的五类卡片（390 宽和 320 宽）', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    // Board order, oldest first: inspection (seed), rental, sign-up, shipped goods, unpaid goods.
    await paidRental(request, buyer, merchant)
    await enrollSelf(request, buyer)
    const addressId = await addAddressByApi(request, buyer)
    const shipped = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await payByApi(request, buyer, shipped.orderId)
    await shipByApi(request, merchant, await readOrderByApi(request, buyer, shipped.orderId), 'SF2026112000001')
    await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await signIn(page, buyer)
    mkdirSync(compareOutput, { recursive: true })

    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 844 })
      await page.goto(`/?w=${width}#/pages/mine/orders`)
      await expect(page.locator('.my-order-card').first()).toBeVisible()
      await page.waitForTimeout(800)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(compareOutput, width === 390 ? 'OrderList-app.png' : 'OrderList-app-320.png'), fullPage: true })
    }
  })
})
