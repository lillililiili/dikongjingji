import { randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  API,
  BUYER,
  CITY,
  MERCHANT,
  apiLogin,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  fakeWechatRefunds,
  latestRefundNo,
  offerId,
  openPaymentNo,
  psql,
  type Session,
} from './support/trade-fixtures'

// 设备租赁（F3，计划 §24）：商品详情“联系询价” → 填写租赁询价单 → 租赁报价单（等报价）→ 商家报价、改价（没有商家页面，
// 用受权接口以 dev-merchant“示例机器人商家”操作）→ 过期的确认被拒后重读 → 确认合同 → 收银台 → 模拟微信付款 →
// 支付结果 → 租赁订单（待交接 → 商家录入交接 → 确认交接 → 使用中 → 商家录入归还 → 确认归还 → 已结束）→ 合同；
// 商家拒绝、关闭询价、交接前商家取消整单退款、不能在线收款的出租方、我的 → 租赁订单、需求与报价里的询价单。
// 真实后端和本机模拟微信。每条用例前后把 dev-customer 的询价单、租赁订单、合同、事件、RENTAL 交易订单和测试地址删掉。
const DOG_PRODUCT = 'c2000000-0000-4000-8000-000000000401'
// 示例机器人商家出租的巡检机器狗（¥380/天，能在线收款）；示例设备租赁商的同一款没有登记收款账户。
const DOG_OFFER = offerId('401', '1')
const UNPAID_OFFER = offerId('401', '6')
const DAILY = 38_000
const ADDRESS_DETAIL = 'E2E 租赁 东营港 示例港路 6 号'
const visualOutput = resolve(__dirname, '../../../../../.planning/rental/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/rental')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/**
 * Removes what the tests made: every rental inquiry of the buyer with its quotes, orders, contracts,
 * records and events, the RENTAL trade orders behind them with payments and refunds, and the test
 * address. The trade orders are locked first so the timeout job skips them while they are deleted.
 */
function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_inquiries ON COMMIT DROP AS SELECT id FROM rental_inquiry WHERE account_id = ${buyerId()};
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT trade_order_id AS id FROM rental_order WHERE inquiry_id IN (SELECT id FROM e2e_inquiries)
      UNION SELECT id FROM trade_order WHERE buyer_id = ${buyerId()} AND source = 'RENTAL';
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_orders) FOR UPDATE) locked;
    DELETE FROM rental_order WHERE inquiry_id IN (SELECT id FROM e2e_inquiries);
    DELETE FROM rental_inquiry WHERE id IN (SELECT id FROM e2e_inquiries);
    DELETE FROM trade_channel_notification
      WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders))
         OR out_refund_no IN (SELECT out_refund_no FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders));
    DELETE FROM trade_state_transition WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_delivery WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_line WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order WHERE id IN (SELECT id FROM e2e_orders);
    DELETE FROM account_address WHERE owner_id = ${buyerId()} AND detail = '${ADDRESS_DETAIL}';
    COMMIT;
  `)
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function tallShot(page: Page, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  await page.evaluate(() => window.scrollTo(0, 0))
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

// YYYY-MM-DD in Beijing, `days` from today.
function beijingDay(days: number): string {
  return new Date(Date.now() + 8 * 3_600_000 + days * 86_400_000).toISOString().slice(0, 10)
}

const monthDay = (date: string) => `${date.slice(5, 7)}/${date.slice(8, 10)}`

function orderIdFromUrl(page: Page): string {
  return new URL(page.url().replace('/#/', '/')).searchParams.get('orderId') as string
}

async function addAddress(request: APIRequestContext, session: Session): Promise<string> {
  const created = await request.post(`${API}/api/v1/account/addresses`, {
    headers: session.headers,
    data: { recipientName: '王女士', phone: '13800002026', cityCode: CITY, detail: ADDRESS_DETAIL, isDefault: true },
  })
  expect(created.status(), await created.text()).toBe(201)
  return (await created.json()).addressId
}

interface InquirySnapshot {
  inquiryId: string
  status: string
  version: number
  quote: { version: number; totalMinor: number } | null
  order: { rentalOrderId: string; tradeOrderId: string; status: string } | null
}

async function submitInquiry(
  request: APIRequestContext,
  session: Session,
  addressId: string,
  overrides: Record<string, unknown> = {},
): Promise<InquirySnapshot> {
  const response = await request.post(`${API}/api/v1/rental-inquiries`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: {
      offerId: DOG_OFFER,
      quantity: 1,
      startDate: beijingDay(3),
      days: 5,
      handoverMode: 'DELIVERY',
      returnMode: 'COLLECT',
      addressId,
      note: null,
      ...overrides,
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  return response.json()
}

async function readInquiry(request: APIRequestContext, session: Session, inquiryId: string): Promise<InquirySnapshot> {
  const response = await request.get(`${API}/api/v1/rental-inquiries/${inquiryId}`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return response.json()
}

async function merchantQuotes(
  request: APIRequestContext,
  merchant: Session,
  inquiryId: string,
  daily: number,
  overrides: Record<string, unknown> = {},
): Promise<InquirySnapshot> {
  const response = await request.put(`${API}/api/v1/provider/rental-inquiries/${inquiryId}/quote`, {
    headers: merchant.headers,
    data: {
      dailyRentMinor: daily,
      days: 4,
      quantity: 1,
      fees: [{ name: '送达与取回', note: '东营市内', amountMinor: 0 }, { name: '保险', note: null, amountMinor: 5000 }],
      scheduleAvailable: true,
      validUntil: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      terms: '押金：当前不在线收取，按合同约定。\n超期：超过到期日后按天计费，费率以合同为准。\n损坏处理：归还时核对交接记录。',
      ...overrides,
    },
  })
  expect(response.status(), await response.text()).toBe(200)
  return response.json()
}

async function merchantPost(request: APIRequestContext, merchant: Session, route: string, data: Record<string, unknown>): Promise<void> {
  const response = await request.post(`${API}/api/v1/provider/${route}`, { headers: merchant.headers, data })
  expect(response.status(), await response.text()).toBe(200)
}

// Quote, confirm and pay an inquiry by API: a rental order waiting for its handover.
async function paidOrder(
  request: APIRequestContext,
  buyer: Session,
  merchant: Session,
  addressId: string,
): Promise<{ inquiryId: string; rentalOrderId: string; tradeOrderId: string }> {
  const inquiry = await submitInquiry(request, buyer, addressId)
  const quoted = await merchantQuotes(request, merchant, inquiry.inquiryId, DAILY)
  const confirmed = await request.post(`${API}/api/v1/rental-inquiries/${inquiry.inquiryId}/confirm`, {
    headers: { ...buyer.headers, 'If-Match': `"${quoted.version}"` },
    data: { quoteVersion: quoted.quote?.version, agreed: true },
  })
  expect(confirmed.status(), await confirmed.text()).toBe(200)
  const { rentalOrderId, tradeOrderId } = await confirmed.json()
  const launched = await request.post(`${API}/api/v1/trade/orders/${tradeOrderId}/payments`, {
    headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
    data: { channel: 'WECHAT_APP' },
  })
  expect(launched.status()).toBe(200)
  await fakeWechatPays(request, openPaymentNo(tradeOrderId))
  await expect.poll(async () => (await readOrder(request, buyer, rentalOrderId)).status).toBe('AWAITING_HANDOVER')
  return { inquiryId: inquiry.inquiryId, rentalOrderId, tradeOrderId }
}

async function readOrder(request: APIRequestContext, session: Session, rentalOrderId: string): Promise<{ status: string; refund: { status: string } | null }> {
  const response = await request.get(`${API}/api/v1/rental-orders/${rentalOrderId}`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return response.json()
}

// uni-button shows as enabled to Playwright even when disabled, so the attribute is checked.
const confirmOn = (page: Page) => page.locator('[data-testid="rental-quote-confirm"]:not([disabled])')
const confirmOff = (page: Page) => page.locator('[data-testid="rental-quote-confirm"][disabled]')

test.describe('设备租赁', () => {
  let buyer: Session
  let merchant: Session
  let addressId: string

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const buyerSession = await apiLogin(request, BUYER)
    const merchantSession = await apiLogin(request, MERCHANT)
    test.skip(!buyerSession || !merchantSession, '本地后端没有开发账号（不是 dev 配置）')
    buyer = buyerSession as Session
    merchant = merchantSession as Session
  })

  test.beforeEach(async ({ request }) => {
    cleanup()
    addressId = await addAddress(request, buyer)
  })

  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('RENT-001 商品详情 → 询价单 → 等报价 → 商家报价、改价（过期的确认被拒后重读）→ 确认合同 → 付租金 → 交接 → 归还 → 合同', async ({ page, request }) => {
    test.setTimeout(180_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    await signIn(page, buyer)

    // 商品详情：选能在线收款的商家，“联系询价”进询价单。
    await page.goto(`/#/pages/mall/product?productId=${DOG_PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('巡检机器狗')
    await page.getByTestId(`mall-offer-${DOG_OFFER}`).click()
    await expect(page.getByTestId('mall-product-primary')).toHaveText('联系询价')
    await page.getByTestId('mall-product-primary').click()

    // 填写租赁询价单：设备、台数、起租日和天数、参考租金、地点、交接和归还、身份、说明。
    await expect(page.getByTestId('rental-inquiry-name')).toHaveText('巡检机器狗')
    await expect(page.getByTestId('rental-inquiry-merchant')).toHaveText('出租方 示例机器人商家')
    await expect(page.getByTestId('rental-inquiry-price')).toHaveText('¥380/天 起')
    await page.getByTestId('rental-qty-plus').click()
    await expect(page.getByTestId('rental-qty')).toHaveText('2')
    await page.getByTestId('rental-qty-minus').click()
    await expect(page.getByTestId('rental-qty')).toHaveText('1')
    await page.getByTestId('rental-days-3').click()
    await page.getByTestId('rental-days-plus').click()
    await expect(page.getByTestId('rental-days')).toHaveText('4')
    await expect(page.getByTestId('rental-days-total')).toHaveText('共 4 天')
    await expect(page.getByTestId('rental-reference-formula')).toHaveText('¥380 × 4 天 × 1 台')
    await expect(page.getByTestId('rental-reference-amount')).toHaveText('¥1,520 起')

    const start = beijingDay(3)
    await page.getByTestId('rental-start').click()
    await expect(page.getByTestId('rental-start-sheet')).toBeVisible()
    // The sheet starts tomorrow: today is not offered.
    await expect(page.getByTestId(`rental-start-${beijingDay(0)}`)).toHaveCount(0)
    await page.getByTestId(`rental-start-${start}`).click()
    await expect(page.getByTestId('rental-start-value')).toContainText(monthDay(start))
    await expect(page.getByTestId('rental-end')).toContainText(monthDay(beijingDay(6)))

    await expect(page.getByTestId('rental-place-value')).toHaveText(ADDRESS_DETAIL)
    await expect(page.getByTestId('rental-contact')).toHaveText('王女士')
    await expect(page.getByTestId('rental-phone')).toHaveText('138****2026')
    await page.getByTestId('rental-handover-pickup').click()
    await expect(page.getByTestId('rental-handover-note')).toContainText('示例机器人商家 东营仓取机')
    await page.getByTestId('rental-handover-delivery').click()
    await expect(page.getByTestId('rental-return-collect')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('rental-lessee')).toContainText('东营示例物流公司（经办人 王女士（示例））')
    await page.getByTestId('rental-note').locator('textarea').fill('夜间巡逻，需要现场讲解')
    await expect(page.getByTestId('rental-inquiry')).toContainText('提交询价不产生费用，确认合同后才付款')
    await expect(page.getByTestId('rental-inquiry')).toContainText('押金和赔付以合同约定为准')

    await page.getByTestId('rental-submit').click()

    // 租赁报价单：还没报价。
    await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'WAITING')
    await expect(page.getByTestId('rental-header-badge')).toHaveText('企业 · 东营示例物流公司')
    await expect(page.getByTestId('rental-quote-status')).toContainText('等待商家报价')
    await expect(page.getByTestId('rental-quote-row-period')).toHaveText(`${monthDay(start)}–${monthDay(beijingDay(6))} · 共 4 天`)
    await expect(page.getByTestId('rental-quote-row-note')).toHaveText('夜间巡逻，需要现场讲解')
    await expect(page.getByTestId('rental-quote-waiting')).toHaveText('等待商家报价')
    const inquiryId = new URL(page.url().replace('/#/', '/')).searchParams.get('inquiryId') as string
    const sent = await readInquiry(request, buyer, inquiryId)
    expect(sent).toMatchObject({ status: 'PENDING', quote: null })

    // 商家报价：日租金 ¥380 × 4 天 + 保险 ¥50，送达取回含。
    await merchantQuotes(request, merchant, inquiryId, DAILY)
    await page.reload()
    await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'OPEN')
    await expect(page.getByTestId('rental-quote-chip')).toHaveText('待确认')
    await expect(page.getByTestId('rental-quote-version')).toContainText('v1')
    await expect(page.getByTestId('rental-quote-line-rent')).toContainText('4 天 × ¥380 × 1 台')
    await expect(page.getByTestId('rental-quote-line-rent')).toContainText('¥1,520')
    await expect(page.getByTestId('rental-quote-line-fee-送达与取回')).toContainText('含')
    await expect(page.getByTestId('rental-quote-line-fee-保险')).toContainText('¥50')
    await expect(page.getByTestId('rental-quote-total')).toHaveText('¥1,570')
    await expect(page.getByTestId('rental-quote-schedule')).toHaveText('档期可用')
    await expect(page.getByTestId('rental-quote-terms')).toContainText('当前不在线收取')
    await expect(page.getByTestId('rental-quote-pay-state')).toHaveText('合同确认后一次付清')
    await expect(confirmOff(page)).toHaveText('确认合同并支付 ¥1,570')

    // 商家在页面打开时改价：确认 v1 被拒，页面重读到 v2 并说明，勾选要重新做。
    await merchantQuotes(request, merchant, inquiryId, 36_000)
    await page.getByTestId('rental-quote-agree').click()
    const refused = page.waitForResponse((response) => response.url().endsWith('/confirm'))
    await confirmOn(page).click()
    expect([409, 412]).toContain((await refused).status())
    await expect(page.getByTestId('rental-quote-notice')).toContainText('已刷新')
    await expect(page.getByTestId('rental-quote-version')).toContainText('v2')
    await expect(page.getByTestId('rental-quote-total')).toHaveText('¥1,490')
    await expect(page.getByTestId('rental-quote-agree')).toHaveAttribute('aria-checked', 'false')
    await expect(confirmOff(page)).toHaveCount(1)

    // 确认合同并支付 → 收银台。
    await page.getByTestId('rental-quote-agree').click()
    await confirmOn(page).click()
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥1,490')
    await expect(page.getByTestId('cashier-due-note')).toHaveText('租金 · 合同确认后一次付清')
    await expect(page.getByTestId('cashier-countdown')).toHaveText(/^剩余支付时间 (30:00|2\d:\d\d) · 超时订单关闭，需重新询价$/)
    await expect(page.getByTestId('cashier-payee')).toHaveText('示例机器人商家')
    // 画板 CashierRental：租赁合同、收款方（经平台支付渠道）、付款节点。
    await expect(page.getByTestId('cashier-rental-contract')).toContainText('巡检机器狗 ×1 · ')
    await expect(page.getByTestId('cashier-rental-contract')).toContainText('共 4 天')
    await expect(page.getByTestId('cashier-rental-node')).toHaveText('租金 ¥1,490 · 合同确认后一次付清')
    await expect(page.getByTestId('cashier-order-no')).toHaveText(/^RO\d{16}$/)
    const tradeOrderId = orderIdFromUrl(page)

    // H5 调不起微信：结果页由服务端查单，没付款就是“支付失败”；模拟微信付款后刷新。
    await page.getByTestId('cashier-pay').click()
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('pay-result-failed')).toContainText('租金仍为待支付，超时订单关闭、报价失效')
    await fakeWechatPays(request, openPaymentNo(tradeOrderId))
    await page.getByTestId('pay-result-refresh').click()
    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('租金已支付')
    await expect(page.getByTestId('pay-result-amount')).toHaveText('¥1,490')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看租赁订单')
    await page.getByTestId('pay-result-view-order').click()

    // 租赁订单：待交接，费用约定，交接和归还都还没开始。
    const order = page.getByTestId('rental-order')
    await expect(order).toHaveAttribute('data-status', 'AWAITING_HANDOVER')
    await expect(page.getByTestId('rental-order-title')).toHaveText('待交接 · 商家准备设备')
    await expect(page.getByTestId('rental-order-total')).toHaveText('¥1,490')
    await expect(page.getByTestId('rental-order-fee-rent')).toContainText('已支付')
    await expect(page.getByTestId('rental-order-fee-deposit')).toContainText('不在线收取，按合同约定')
    await expect(page.getByTestId('rental-order-fee-extra')).toContainText('没有发生')
    await expect(page.getByTestId('rental-order-fee-extra')).toContainText('¥0')
    await expect(page.getByTestId('rental-order-handover-tag')).toHaveText('未开始')
    await expect(page.getByTestId('rental-order-action')).toHaveCount(0)
    const rentalOrderId = new URL(page.url().replace('/#/', '/')).searchParams.get('rentalOrderId') as string

    // 商家录入交接 → 待你确认 → 确认交接 → 使用中。
    await merchantPost(request, merchant, `rental-orders/${rentalOrderId}/handover`, { note: '编号 DOG-0118，配件齐全，外观无损伤' })
    await page.reload()
    await expect(order).toHaveAttribute('data-status', 'HANDOVER_RECORDED')
    await expect(page.getByTestId('rental-order-title')).toHaveText('待交接 · 请核对交接记录')
    await expect(page.getByTestId('rental-order-handover-tag')).toHaveText('待你确认')
    await expect(page.getByTestId('rental-order-handover')).toContainText('编号 DOG-0118')
    await page.getByTestId('rental-order-action').click()
    await expect(page.getByTestId('rental-confirm')).toContainText('确认后开始计租')
    await expect(page.getByTestId('rental-confirm-rows')).toContainText('编号 DOG-0118')
    await page.getByTestId('rental-confirm-go').click()
    await expect(order).toHaveAttribute('data-status', 'IN_USE')
    await expect(page.getByTestId('rental-order-title')).toHaveText(/^使用中 · /)
    await expect(page.getByTestId('rental-order-handover-tag')).toHaveText('双方已确认')

    // 商家录入归还 → 确认归还 → 已结束。
    await merchantPost(request, merchant, `rental-orders/${rentalOrderId}/return`, { note: '配件齐全，与交接记录一致，无新增损伤' })
    await page.reload()
    await expect(order).toHaveAttribute('data-status', 'RETURN_RECORDED')
    await expect(page.getByTestId('rental-order-return-tag')).toHaveText('待你确认')
    await expect(page.getByTestId('rental-order-action')).toHaveText('确认归还')
    await page.getByTestId('rental-order-action').click()
    await page.getByTestId('rental-confirm-go').click()
    await expect(order).toHaveAttribute('data-status', 'ENDED')
    await expect(page.getByTestId('rental-order-title')).toHaveText('已结束')
    await expect(page.getByTestId('rental-order-return-tag')).toHaveText('双方已确认')
    await expect(page.getByTestId('rental-order-action')).toHaveText('再次租赁')

    // 合同：只读，编号、金额、签约主体、条款；没有下载。
    await page.getByTestId('rental-order-contract').click()
    await expect(page.getByTestId('rental-contract-name')).toHaveText('巡检机器狗租赁合同')
    await expect(page.getByTestId('rental-contract-tag')).toHaveText('已履行完毕')
    await expect(page.getByTestId('rental-contract-no')).toHaveText(/^RC\d{14}$/)
    await expect(page.getByTestId('rental-contract-amount')).toHaveText('¥1,490')
    await expect(page.getByTestId('rental-contract-parties')).toContainText('东营示例物流公司')
    await expect(page.getByTestId('rental-contract-parties')).toContainText('示例机器人商家（出租方）')
    await expect(page.getByTestId('rental-contract-terms')).toContainText('当前不在线收取')
    await expect(page.getByTestId('rental-contract-files')).toContainText('暂不提供在线下载')
    await expect(page.getByText('下载合同')).toHaveCount(0)
    await page.getByTestId('rental-contract-order').click()
    await expect(order).toHaveAttribute('data-status', 'ENDED')

    // 再次租赁 → 商品详情。
    await page.getByTestId('rental-order-action').click()
    await expect(page.getByTestId('mall-product-name')).toHaveText('巡检机器狗')

    // 需求与报价里的询价单：已确认，能看报价和订单。
    await page.goto('/?n=list#/pages/mine/demands')
    const card = page.getByTestId(`rental-inquiry-card-${inquiryId}`)
    await expect(card.getByTestId('rental-inquiry-card-status')).toHaveText('已确认')
    await expect(card.getByTestId('rental-inquiry-card-line')).toHaveText('已确认合同 · ¥1,490')
    await expect(card).toContainText('巡检机器狗 租赁询价')
    await expect(card).toContainText('询价单')
    await card.getByTestId(`rental-inquiry-card-order-${inquiryId}`).click()
    await expect(order).toHaveAttribute('data-status', 'ENDED')
  })

  test('RENT-002 商家拒绝后重新询价；报价单和列表里都能关闭询价', async ({ page, request }) => {
    const declined = await submitInquiry(request, buyer, addressId)
    await merchantPost(request, merchant, `rental-inquiries/${declined.inquiryId}/decline`, { reason: '这几天设备已排满' })
    await signIn(page, buyer)

    await page.goto(`/#/pages/rental/quote?inquiryId=${declined.inquiryId}`)
    await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'DECLINED')
    await expect(page.getByTestId('rental-quote-status')).toContainText('商家已拒绝')
    await expect(page.getByTestId('rental-quote-status')).toContainText('这几天设备已排满')
    await expect(page.getByTestId('rental-quote-close')).toHaveCount(0)
    await page.getByTestId('rental-quote-reinquire').click()
    await expect(page.getByTestId('rental-inquiry-name')).toHaveText('巡检机器狗')
    await expect(page.getByTestId('rental-inquiry-merchant')).toHaveText('出租方 示例机器人商家')

    // 报价单上关闭（有报价时说明档期不再保留）。
    const quoted = await submitInquiry(request, buyer, addressId)
    await merchantQuotes(request, merchant, quoted.inquiryId, DAILY)
    await page.goto(`/?n=quoted#/pages/rental/quote?inquiryId=${quoted.inquiryId}`)
    await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'OPEN')
    await page.getByTestId('rental-quote-close').click()
    await expect(page.getByTestId('rental-confirm')).toContainText('关闭后这份报价失效，商家不再为你保留档期。')
    await page.getByTestId('rental-confirm-keep').click()
    await expect(page.getByTestId('rental-confirm')).toHaveCount(0)
    await page.getByTestId('rental-quote-close').click()
    await page.getByTestId('rental-confirm-go').click()
    await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'CLOSED')
    await expect(page.getByTestId('rental-quote-status')).toContainText('询价已关闭')
    expect((await readInquiry(request, buyer, quoted.inquiryId)).status).toBe('CLOSED')

    // 需求与报价：待报价的询价单能直接关闭，筛选按状态归类。
    const pending = await submitInquiry(request, buyer, addressId)
    await page.goto('/?n=list#/pages/mine/demands')
    const card = page.getByTestId(`rental-inquiry-card-${pending.inquiryId}`)
    await expect(card.getByTestId('rental-inquiry-card-status')).toHaveText('待报价')
    await expect(card.getByTestId('rental-inquiry-card-line')).toContainText('等待 示例机器人商家 报价')
    await expect(page.getByTestId(`rental-inquiry-card-${declined.inquiryId}`).getByTestId('rental-inquiry-card-line'))
      .toHaveText('商家已拒绝：这几天设备已排满')
    await card.getByTestId(`rental-inquiry-card-close-${pending.inquiryId}`).click()
    await expect(page.getByTestId('demand-confirm')).toContainText('关闭后商家不再报价')
    await page.getByTestId('demand-confirm-go').click()
    await expect(card.getByTestId('rental-inquiry-card-status')).toHaveText('已关闭')
    await expect(card.getByTestId('rental-inquiry-card-line')).toHaveText('你已关闭这份询价')

    await page.getByTestId('demand-filter-open').click()
    await expect(page.locator('[data-testid^="rental-inquiry-card-"][data-status]')).toHaveCount(0)
    await page.getByTestId('demand-filter-closed').click()
    await expect(page.locator('[data-testid^="rental-inquiry-card-"][data-status]')).toHaveCount(3)
    await card.getByTestId(`rental-inquiry-card-reinquire-${pending.inquiryId}`).click()
    await expect(page.getByTestId('rental-inquiry-name')).toHaveText('巡检机器狗')
  })

  test('RENT-003 不能在线收款的出租方：打开就说明原因（商品详情 1.2.0 起告诉页面）、不能提交；登录前先去登录', async ({ page }) => {
    await page.goto(`/#/pages/rental/inquiry?offerId=${UNPAID_OFFER}&productId=${DOG_PRODUCT}&cityCode=${CITY}`)
    await expect(page).toHaveURL(/pages\/service\/inspection\/login/)

    await signIn(page, buyer)
    await page.goto(`/?n=unpaid#/pages/rental/inquiry?offerId=${UNPAID_OFFER}&productId=${DOG_PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('rental-inquiry-merchant')).toHaveText('出租方 示例设备租赁商')
    await expect(page.getByTestId('rental-inquiry-unpaid')).toHaveText('暂未开通在线支付')
    await expect(page.getByTestId('rental-blocked')).toContainText('还没有开通在线收款')
    await page.getByTestId('rental-submit').click()
    await expect(page.locator('[data-testid="rental-submit"][disabled]')).toHaveCount(1)

    // 一个不出租的商品：如实说明，能回商城。
    await page.goto(`/?n=missing#/pages/rental/inquiry?offerId=${DOG_OFFER}&productId=c2000000-0000-4000-8000-000000000101&cityCode=${CITY}`)
    await expect(page.getByTestId('rental-inquiry-missing')).toContainText('这件设备已下架或本城暂不出租')
  })

  test('RENT-004 交接前商家取消：订单显示整单退款，退款到账后显示已退款', async ({ page, request }) => {
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const { rentalOrderId, tradeOrderId } = await paidOrder(request, buyer, merchant, addressId)
    await merchantPost(request, merchant, `rental-orders/${rentalOrderId}/cancel`, { reason: '设备临时检修' })
    await signIn(page, buyer)

    await page.goto(`/#/pages/rental/order?rentalOrderId=${rentalOrderId}`)
    await expect(page.getByTestId('rental-order')).toHaveAttribute('data-status', 'CANCELLED')
    await expect(page.getByTestId('rental-order-title')).toHaveText('已取消 · 租金原路退回')
    await expect(page.getByTestId('rental-order-text')).toContainText('设备临时检修')
    await expect(page.getByTestId('rental-order-refund-text')).toHaveText(/^退款中 ¥1,570$/)

    await fakeWechatRefunds(request, latestRefundNo(tradeOrderId))
    await expect.poll(async () => (await readOrder(request, buyer, rentalOrderId)).refund?.status).toBe('SUCCEEDED')
    await page.reload()
    await expect(page.getByTestId('rental-order-refund-text')).toHaveText('已退款 ¥1,570')
    await expect(page.getByTestId('rental-order-action')).toHaveText('再次租赁')
  })

  test('RENT-005 我的 → 租赁订单：入口、状态页签、待付款去付款', async ({ page, request }) => {
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const paid = await paidOrder(request, buyer, merchant, addressId)
    // A second contract left unpaid.
    const unpaidInquiry = await submitInquiry(request, buyer, addressId, { startDate: beijingDay(5) })
    const quoted = await merchantQuotes(request, merchant, unpaidInquiry.inquiryId, DAILY)
    const confirmed = await request.post(`${API}/api/v1/rental-inquiries/${unpaidInquiry.inquiryId}/confirm`, {
      headers: { ...buyer.headers, 'If-Match': `"${quoted.version}"` },
      data: { quoteVersion: quoted.quote?.version, agreed: true },
    })
    expect(confirmed.status()).toBe(200)
    const unpaid = await confirmed.json()
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-rental_orders').click()
    await expect(page.getByTestId('rental-orders')).toBeVisible()
    await expect(page.locator('[data-testid^="rental-order-card-"][data-status]')).toHaveCount(2)
    const unpaidCard = page.getByTestId(`rental-order-card-${unpaid.rentalOrderId}`)
    await expect(unpaidCard.getByTestId('rental-order-card-status')).toHaveText('待付款')

    await page.getByTestId('rental-orders-tab-to_handover').click()
    await expect(page.locator('[data-testid^="rental-order-card-"][data-status]')).toHaveCount(1)
    await expect(page.getByTestId(`rental-order-card-${paid.rentalOrderId}`).getByTestId('rental-order-card-status')).toHaveText('待交接')
    await page.getByTestId('rental-orders-tab-ended').click()
    await expect(page.getByTestId('rental-orders-empty')).toContainText('没有符合条件的订单')

    await page.getByTestId('rental-orders-tab-to_pay').click()
    await page.getByTestId(`rental-order-card-pay-${unpaid.rentalOrderId}`).click()
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page.getByTestId('cashier-due-note')).toHaveText('租金 · 合同确认后一次付清')
    // Back from the cashier is the list.
    await page.getByTestId('cashier-back').click()
    await expect(page.getByTestId('rental-orders')).toBeVisible()

    await page.getByTestId(`rental-order-card-view-${unpaid.rentalOrderId}`).click()
    await expect(page.getByTestId('rental-order')).toHaveAttribute('data-status', 'PENDING_PAYMENT')
    await expect(page.getByTestId('rental-order-title')).toHaveText(/^待付款 · \d\d\/\d\d \d\d:\d\d 前付清$/)
    await expect(page.getByTestId('rental-order-action')).toHaveText('去付款')
  })

  test('VIS-RENTAL 询价单、报价单、租赁订单、合同、我的租赁订单在 320/375/430 无横向溢出并与画板 390 宽对照', async ({ page, request }) => {
    test.setTimeout(240_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const inUse = await paidOrder(request, buyer, merchant, addressId)
    await merchantPost(request, merchant, `rental-orders/${inUse.rentalOrderId}/handover`, { note: '编号 DOG-0118，遥控手柄、电池 ×2、充电器、运输箱，外观无损伤' })
    const confirmedHandover = await request.post(`${API}/api/v1/rental-orders/${inUse.rentalOrderId}/handover-confirmation`, { headers: buyer.headers })
    expect(confirmedHandover.status()).toBe(200)
    const open = await submitInquiry(request, buyer, addressId, { startDate: beijingDay(4) })
    await merchantQuotes(request, merchant, open.inquiryId, DAILY)
    await submitInquiry(request, buyer, addressId, { startDate: beijingDay(6) })
    await signIn(page, buyer)
    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })

    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      // A new document each time: a hash change alone can leave the previous page of the same route.
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at(`#/pages/rental/inquiry?offerId=${DOG_OFFER}&productId=${DOG_PRODUCT}&cityCode=${CITY}`))
      await expect(page.getByTestId('rental-reference')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('RentalInquiry'))

      await page.goto(at(`#/pages/rental/quote?inquiryId=${open.inquiryId}`))
      await expect(page.getByTestId('rental-quote')).toHaveAttribute('data-frame', 'OPEN')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('RentalQuote'))

      await page.goto(at(`#/pages/rental/order?rentalOrderId=${inUse.rentalOrderId}`))
      await expect(page.getByTestId('rental-order')).toHaveAttribute('data-status', 'IN_USE')
      await expect(page.getByTestId('rental-item')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('OrderRental'))

      await page.goto(at(`#/pages/rental/contract?rentalOrderId=${inUse.rentalOrderId}`))
      await expect(page.getByTestId('rental-contract-no')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Contract'))
      if (width === 390) continue

      await page.goto(at('#/pages/mine/rental-orders'))
      await expect(page.locator('[data-testid^="rental-order-card-"][data-status]')).toHaveCount(1)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('rental-orders'))

      await page.goto(at('#/pages/mine/demands'))
      await expect(page.locator('[data-testid^="rental-inquiry-card-"][data-status]')).toHaveCount(3)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('demand-list-inquiries'))

      await page.goto(at(`#/pages/trade/cashier?orderId=${inUse.tradeOrderId}`))
      await expect(page.getByTestId('pay-result-paid-title')).toHaveText('租金已支付')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('pay-result'))

      await page.goto(at(`#/pages/rental/inquiry?offerId=${DOG_OFFER}&productId=${DOG_PRODUCT}&cityCode=${CITY}`))
      await page.getByTestId('rental-start').click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('start-sheet') })
    }

    // The boards at 390 next to the App at 390. Contract opens in its rental frame (a copy of the
    // board with that frame chosen, resolving its files from the prototype folder).
    const contractCopy = resolve(compareOutput, 'Contract-rental-board.html')
    writeFileSync(contractCopy, readFileSync(resolve(boardDir, 'Contract.html'), 'utf8')
      .replace('<head>', `<head><base href="file://${boardDir}/">`)
      .replace('{"contract":"服务合同"}', '{"contract":"租赁合同"}'))
    const boards: Array<[string, string]> = [
      ['RentalInquiry', resolve(boardDir, 'RentalInquiry.html')],
      ['RentalQuote', resolve(boardDir, 'RentalQuote.html')],
      ['OrderRental', resolve(boardDir, 'OrderRental.html')],
      ['Contract', contractCopy],
    ]
    for (const [board, file] of boards) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${file}`)
      const frame = page.locator('#root > div').first()
      await expect(frame).toBeVisible()
      const height = Math.ceil((await frame.boundingBox())?.height ?? 844) + 60
      await page.setViewportSize({ width: 390, height })
      await page.waitForTimeout(300)
      await frame.screenshot({ path: resolve(compareOutput, `${board}-board.png`) })

      const image = (name: string) => `data:image/png;base64,${readFileSync(resolve(compareOutput, name)).toString('base64')}`
      await page.setViewportSize({ width: 820, height: 900 })
      await page.setContent(`
        <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
          <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
            <figure style="margin:0;width:390px"><figcaption>画板 ${board}</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-board.png`)}"></figure>
            <figure style="margin:0;width:390px"><figcaption>App 390 宽</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-app.png`)}"></figure>
          </div>
        </body>`)
      await page.waitForTimeout(200)
      await page.screenshot({ path: resolve(compareOutput, `${board}-compare.png`), fullPage: true })
    }
  })
})
