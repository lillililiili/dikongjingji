import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { PLANNING, boardUrl } from './support/board-compare'
import {
  API,
  BUYER,
  CITY,
  MERCHANT,
  apiLogin,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  offerId,
  openPaymentNo,
  psql,
  type Session,
} from './support/trade-fixtures'
import { stubSupportChat } from './support/support-fixtures'

// 询价商品（F4，计划 §25）：商品详情“联系询价”（先讲“询价怎么走”）→ 填写询价单（不能在线收款的商家置灰）→ 报价比选（等报价）→
// 两家商家报价（没有商家页面，用受权接口：dev-merchant 是示例机器人商家，dev-merchant-2 是示例行业装备代理）→ 一家改价 →
// 排序 → 按旧版本选择被拒后重读 → 选定 → 转为订单 → 确认订单（数量、单价按报价锁定）→ 提交 → 收银台 → 模拟微信付款 →
// 商品订单按报价金额 → 比选页变成“查看订单”；同一份报价不能再下单；商家拒绝写原因；关闭询价；过期的报价不能下单；
// “需求与报价”里的询价单。真实后端和本机模拟微信。每条用例前后把 dev-customer 的询价单（连同报价、事件）、用报价下的
// 订单（连同支付）和测试地址删掉。
const PRODUCT = 'c2000000-0000-4000-8000-000000000203'
const ROBOT_OFFER = offerId('203', '1')
const AGENT_OFFER = offerId('203', '5')
// 示例无人机厂家没有登记收款账户：询价单上置灰。
const MAKER_OFFER = offerId('203', '4')
const MERCHANT_2 = 'dev-merchant-2'
const MODEL = '测绘版 · RTK · 五镜头倾斜相机'
const ADDRESS_DETAIL = 'E2E 询价 东营港 示例港路 8 号'
const visualOutput = resolve(PLANNING, 'mall-inquiry/visual')
const compareOutput = resolve(PLANNING, 'style-compare/mall-inquiry')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/**
 * Removes what the tests made: every goods inquiry of the buyer with its invitations, quotes and
 * events, the mall orders made from those quotes with their payments, and the test address. The
 * chosen quote is unlinked first (the inquiry points at it), the orders are locked so the timeout
 * job skips them while they are deleted.
 */
function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_inquiries ON COMMIT DROP AS SELECT id FROM mall_inquiry WHERE account_id = ${buyerId()};
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT order_id AS id FROM mall_inquiry_event WHERE inquiry_id IN (SELECT id FROM e2e_inquiries) AND order_id IS NOT NULL
      UNION SELECT last_order_id FROM mall_inquiry_quote WHERE inquiry_id IN (SELECT id FROM e2e_inquiries) AND last_order_id IS NOT NULL;
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_orders) FOR UPDATE) locked;
    UPDATE mall_inquiry SET status = 'CLOSED', close_reason = 'CUSTOMER', closed_at = now(), chosen_quote_id = NULL, chosen_at = NULL
      WHERE id IN (SELECT id FROM e2e_inquiries);
    DELETE FROM mall_inquiry WHERE id IN (SELECT id FROM e2e_inquiries);
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

const queryOf = (page: Page, key: string) => new URL(page.url().replace('/#/', '/')).searchParams.get(key) as string

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
  chosen: { quoteId: string; orderId: string | null } | null
}

async function submitInquiry(request: APIRequestContext, session: Session, offerIds: string[]): Promise<InquirySnapshot> {
  const response = await request.post(`${API}/api/v1/mall/inquiries`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { productId: PRODUCT, requirement: `${MODEL}\n需要正射影像与三维建模`, quantity: 2, cityCode: CITY, expectedDate: null, offerIds },
  })
  expect(response.status(), await response.text()).toBe(201)
  return response.json()
}

interface MerchantInquiry {
  myQuote: { quoteId: string; version: number } | null
}

async function merchantQuotes(
  request: APIRequestContext,
  merchant: Session,
  inquiryId: string,
  unitYuan: number,
  leadDays: number,
  overrides: Record<string, unknown> = {},
): Promise<MerchantInquiry> {
  const response = await request.put(`${API}/api/v1/provider/mall/inquiries/${inquiryId}/quote`, {
    headers: merchant.headers,
    data: {
      unitAmountMinor: unitYuan * 100,
      model: '示例 M6 测绘版',
      leadDays,
      includes: 'RTK、五镜头倾斜相机、2 名飞手培训',
      validUntil: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      ...overrides,
    },
  })
  expect(response.status(), await response.text()).toBe(200)
  return response.json()
}

async function merchantDeclines(request: APIRequestContext, merchant: Session, inquiryId: string, reason: string): Promise<void> {
  const response = await request.post(`${API}/api/v1/provider/mall/inquiries/${inquiryId}/decline`, {
    headers: merchant.headers,
    data: { reason },
  })
  expect(response.status(), await response.text()).toBe(200)
}

async function readInquiry(request: APIRequestContext, session: Session, inquiryId: string): Promise<{ inquiry: InquirySnapshot }> {
  const response = await request.get(`${API}/api/v1/mall/inquiries/${inquiryId}`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return response.json()
}

async function chooseByApi(request: APIRequestContext, session: Session, inquiryId: string, quote: { quoteId: string; version: number }): Promise<void> {
  const { inquiry } = await readInquiry(request, session, inquiryId)
  const response = await request.post(`${API}/api/v1/mall/inquiries/${inquiryId}/choice`, {
    headers: { ...session.headers, 'If-Match': `"${inquiry.version}"` },
    data: { quoteId: quote.quoteId, quoteVersion: quote.version },
  })
  expect(response.status(), await response.text()).toBe(200)
}

// uni-button shows as enabled to Playwright even when disabled, so the attribute is checked.
const quoteCard = (page: Page, offer: string) => page.getByTestId(`inquiry-quote-${offer}`)

test.describe('询价商品', () => {
  let buyer: Session
  let robot: Session
  let agent: Session

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const sessions = await Promise.all([apiLogin(request, BUYER), apiLogin(request, MERCHANT), apiLogin(request, MERCHANT_2)])
    test.skip(sessions.some((session) => !session), '本地后端没有开发账号（不是 dev 配置）')
    ;[buyer, robot, agent] = sessions as Session[]
  })

  test.beforeEach(async ({ request }) => {
    cleanup()
    await addAddress(request, buyer)
  })

  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('MINQ-001 商品详情 → 询价单 → 两家报价、一家改价 → 排序 → 旧版本被拒后重读 → 选定 → 转为订单（锁定数量单价）→ 付款 → 商品订单 → 查看订单', async ({ page, request }) => {
    test.setTimeout(180_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    await signIn(page, buyer)

    // Since 2026-11-20 (原型对齐 R3) the development seed registers 示例无人机厂家 too; the form's
    // handling of a merchant that cannot collect is kept by answering for it as if it could not.
    await page.route('**/api/v1/mall/inquiry-offers?**', async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      for (const item of body.items ?? []) if (item.offerId === MAKER_OFFER) item.canInquire = false
      await route.fulfill({ response, json: body })
    })
    // 商品详情：询价商品先讲“询价怎么走”，三家可报价商家，主按钮“联系询价”进询价单。
    await page.goto(`/#/pages/mall/product?productId=${PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('行业测绘无人机')
    const steps = page.getByTestId('mall-product-inquiry-steps')
    await expect(steps).toContainText('询价怎么走')
    await expect(steps).toContainText('提交询价不产生费用')
    await expect(steps).toContainText('72 小时内')
    await expect(page.getByTestId('mall-product')).toContainText('可报价商家')
    await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
    await page.getByTestId('mall-product-primary').click()

    // 填写询价单：商品、说明、数量、城市、期望交期、身份；三家商家里不能在线收款的置灰。
    await expect(page.getByTestId('mall-inquiry-name')).toHaveText('行业测绘无人机')
    await expect(page.getByTestId('mall-inquiry-meta')).toHaveText('2 家可报价 · 东营可售')
    await expect(page.getByTestId('mall-inquiry-city')).toHaveText('东营市')
    await expect(page.getByTestId('mall-inquiry-buyer')).toHaveText('企业 · 东营示例物流公司')
    await expect(page.getByTestId(`mall-inquiry-offer-${ROBOT_OFFER}`)).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId(`mall-inquiry-offer-${AGENT_OFFER}`)).toHaveAttribute('aria-checked', 'true')
    const maker = page.getByTestId(`mall-inquiry-offer-${MAKER_OFFER}`)
    await expect(maker).toHaveAttribute('aria-checked', 'false')
    await expect(maker).toHaveAttribute('aria-disabled', 'true')
    await expect(page.getByTestId(`mall-inquiry-offer-note-${MAKER_OFFER}`)).toHaveText('暂未开通在线支付')
    await maker.click({ force: true })
    await expect(page.locator('.uni-sample-toast')).toContainText('暂未开通在线支付')
    await expect(maker).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByTestId('mall-inquiry-picked')).toHaveText('已选 2 / 3')
    await expect(page.getByTestId('mall-inquiry-footnote'))
      .toHaveText('提交后商家在 72 小时内报价，在“我的 → 需求与报价”中比选。提交询价不产生费用。')

    // 没写说明不能提交。
    await page.getByTestId('mall-inquiry-submit').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('请填写配置和用途')

    await page.getByTestId('mall-inquiry-requirement').locator('textarea').fill(`${MODEL}\n需要正射影像与三维建模`)
    await expect(page.getByTestId('mall-inquiry-counter')).toHaveText(/^\d+\/200$/)
    await page.getByTestId('mall-inquiry-qty-plus').click()
    await expect(page.getByTestId('mall-inquiry-qty')).toHaveText('2')
    await page.getByTestId('mall-inquiry-date').click()
    await expect(page.getByTestId('mall-inquiry-date-sheet')).toBeVisible()
    const expected = beijingDay(15)
    await page.getByTestId('mall-inquiry-date-15').click()
    await expect(page.getByTestId('mall-inquiry-date-value'))
      .toHaveText(`${Number(expected.slice(5, 7))} 月 ${Number(expected.slice(8, 10))} 日前`)
    await page.getByTestId('mall-inquiry-submit').click()

    // 报价比选：两家都还没报。
    const quotes = page.getByTestId('inquiry-quotes')
    await expect(quotes).toHaveAttribute('data-status', 'OPEN')
    const inquiryId = queryOf(page, 'inquiryId')
    await expect(page.getByTestId('inquiry-header-badge')).toHaveText('企业 · 东营示例物流公司')
    await expect(page.getByTestId('inquiry-quotes-title')).toHaveText('行业测绘无人机')
    await expect(page.getByTestId('inquiry-quotes-spec')).toHaveText(`${MODEL} · 2 台`)
    await expect(page.getByTestId('inquiry-quotes-requirement')).toHaveText('需要正射影像与三维建模')
    await expect(page.getByTestId('inquiry-quotes-delivery')).toHaveText(`交付东营市 · 期望 ${Number(expected.slice(5, 7))} 月 ${Number(expected.slice(8, 10))} 日前`)
    await expect(page.getByTestId('inquiry-quotes-status')).toHaveText('待报价')
    await expect(page.getByTestId('inquiry-quotes-line')).toHaveText(/^已收到 0 份报价，截止 \d\d\/\d\d \d\d:\d\d$/)
    await expect(page.getByTestId(`inquiry-merchant-${ROBOT_OFFER}`).getByTestId('inquiry-merchant-badge')).toHaveText('待报价')
    await expect(page.getByTestId(`inquiry-merchant-${AGENT_OFFER}`)).toHaveAttribute('data-invitation', 'WAITING')
    await expect(page.getByTestId('inquiry-quotes-bar')).toHaveAttribute('data-bar', 'PICK')
    await expect(page.locator('[data-testid="inquiry-quotes-checkout"][disabled]')).toHaveCount(1)

    // 两家报价：机器人商家 ¥68,000 × 2、5 天；装备代理 ¥64,500 × 2、12 天。
    await merchantQuotes(request, robot, inquiryId, 68_000, 5)
    const agentV1 = await merchantQuotes(request, agent, inquiryId, 64_500, 12)
    expect(agentV1.myQuote?.version).toBe(1)
    await page.reload()
    await expect(quotes).toHaveAttribute('data-status', 'QUOTED')
    await expect(page.getByTestId('inquiry-quotes-status')).toHaveText('报价中')
    await expect(page.getByTestId('inquiry-quotes-line')).toContainText('已收到 2 份报价')
    await expect(quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-badge')).toHaveText('报价最低')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-badge')).toHaveText('交期最短')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-unit')).toHaveText('¥68,000')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-total')).toHaveText('¥136,000')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-lead')).toHaveText('5 天')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-model')).toHaveText('型号：示例 M6 测绘版')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-includes')).toHaveText('含：RTK、五镜头倾斜相机、2 名飞手培训')
    await expect(quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-version')).toHaveText('授权经销 · 第 1 版报价')

    // 排序：总价（代理在前）、交期（机器人在前）。
    const order = () => page.locator('[data-testid^="inquiry-quote-"][data-quote-status]').evaluateAll(
      (cards) => cards.map((card) => card.getAttribute('data-testid')))
    await page.getByTestId('inquiry-quotes-sort-price').click()
    await expect.poll(order).toEqual([`inquiry-quote-${AGENT_OFFER}`, `inquiry-quote-${ROBOT_OFFER}`])
    await page.getByTestId('inquiry-quotes-sort-lead_time').click()
    await expect.poll(order).toEqual([`inquiry-quote-${ROBOT_OFFER}`, `inquiry-quote-${AGENT_OFFER}`])
    await page.getByTestId('inquiry-quotes-sort-all').click()

    // 咨询（G1）进报价商家的会话，带这份询价单的卡片。会话接口用桩，不在开发库里留会话。
    const opened = await stubSupportChat(page, { kind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001', name: '示例机器人商家' })
    await quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-consult').click()
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    expect(opened).toEqual([{ counterpartKind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001' }])
    await expect(page.getByTestId('support-chat-pending-card')).toContainText('询价单')
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await page.getByTestId('support-chat-back').click()
    await expect(quoteCard(page, ROBOT_OFFER)).toBeVisible()

    // 代理在页面打开时改价（第 2 版 ¥63,000）：按第 1 版选择被拒，页面重读并说明。
    await merchantQuotes(request, agent, inquiryId, 63_000, 12)
    await quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-choose').click()
    await expect(page.getByTestId('inquiry-confirm')).toContainText('销售方为示例行业装备代理')
    await expect(page.getByTestId('inquiry-confirm-rows')).toContainText('¥64,500 / 台')
    const refused = page.waitForResponse((response) => response.url().endsWith('/choice'))
    await page.getByTestId('inquiry-confirm-go').click()
    expect([409, 412]).toContain((await refused).status())
    await expect(page.getByTestId('inquiry-quotes-notice')).toContainText('已刷新')
    await expect(quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-version')).toHaveText('授权经销 · 第 2 版报价')
    await expect(quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-unit')).toHaveText('¥63,000')
    await expect(quotes).toHaveAttribute('data-status', 'QUOTED')

    // 选定第 2 版。
    await quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-choose').click()
    await expect(page.getByTestId('inquiry-confirm-rows')).toContainText('¥126,000（2 台）')
    await page.getByTestId('inquiry-confirm-go').click()
    await expect(quotes).toHaveAttribute('data-status', 'CHOSEN')
    await expect(page.getByTestId('inquiry-quotes-status')).toHaveText('已选定')
    await expect(page.getByTestId('inquiry-quotes-line')).toHaveText('已选定 示例行业装备代理 · ¥126,000')
    await expect(quoteCard(page, AGENT_OFFER)).toHaveAttribute('data-quote-status', 'CHOSEN')
    await expect(quoteCard(page, AGENT_OFFER).getByTestId('inquiry-quote-choose')).toHaveText('已选择')
    await expect(quoteCard(page, ROBOT_OFFER).getByTestId('inquiry-quote-badge')).toHaveText('已失效')
    await expect(page.getByTestId('inquiry-quotes-close')).toHaveCount(0)
    await expect(page.getByTestId('inquiry-quotes-chosen')).toHaveText('已选 示例行业装备代理')
    await expect(page.getByTestId('inquiry-quotes-chosen-total')).toHaveText('¥126,000')

    // 转为订单 → 确认订单：一行，数量和单价按报价，型号作规格，不能改。
    await page.getByTestId('inquiry-quotes-checkout').click()
    await expect(page.getByTestId('checkout')).toBeVisible()
    const line = page.getByTestId(`checkout-line-${AGENT_OFFER}`)
    await expect(line).toContainText('行业测绘无人机')
    await expect(line).toContainText('示例 M6 测绘版')
    await expect(line).toContainText('¥63,000')
    await expect(line).toContainText('× 2')
    await expect(page.getByTestId('checkout-quote-note')).toHaveText('按所选报价下单，数量和单价不能修改')
    await expect(page.getByTestId('checkout-merchant')).toHaveText('示例行业装备代理')
    await expect(page.getByTestId('checkout-goods')).toHaveText('¥126,000')
    const payable = (await page.getByTestId('checkout-payable').textContent())?.trim() ?? ''
    await page.getByTestId('checkout-agree').click()
    await page.getByTestId('checkout-submit').click()

    // 收银台 → 模拟微信付款 → 支付结果。
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page.getByTestId('cashier-amount')).toHaveText(payable)
    await expect(page.getByTestId('cashier-payee')).toHaveText('示例行业装备代理')
    await expect(page.getByTestId('cashier-order-no')).toHaveText(/^MO\d+$/)
    const orderId = queryOf(page, 'orderId')
    await page.getByTestId('cashier-pay').click()
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await fakeWechatPays(request, openPaymentNo(orderId))
    await page.getByTestId('pay-result-refresh').click()
    await expect(page.getByTestId('pay-result-paid')).toBeVisible()
    await expect(page.getByTestId('pay-result-amount')).toHaveText(payable)

    // 商品订单：按报价的单价和数量。
    await page.goto(`/?n=order#/pages/mine/goods-order?orderId=${orderId}`)
    await expect(page.getByTestId('goods-order-merchant')).toHaveText('示例行业装备代理')
    await expect(page.locator('.goods-order__line')).toHaveCount(1)
    await expect(page.locator('.goods-order__line')).toContainText('¥63,000')
    await expect(page.locator('.goods-order__line')).toContainText('× 2')
    await expect(page.getByTestId('goods-order-payable')).toHaveText(payable)

    // 报价比选：已经下过单，底栏是“查看订单”。
    await page.goto(`/?n=quotes#/pages/mall/inquiry-quotes?inquiryId=${inquiryId}`)
    await expect(page.getByTestId('inquiry-quotes-bar')).toHaveAttribute('data-bar', 'VIEW_ORDER')
    await page.getByTestId('inquiry-quotes-view-order').click()
    await expect(page.getByTestId('goods-order-merchant')).toHaveText('示例行业装备代理')

    // 同一份报价再去下单：服务端拒绝，页面说明并回到报价比选。
    const chosen = (await readInquiry(request, buyer, inquiryId)).inquiry.chosen
    expect(chosen?.orderId).toBe(orderId)
    await page.goto(`/?n=again#/pages/cart/checkout?inquiryQuoteId=${chosen?.quoteId}&inquiryId=${inquiryId}&cityCode=${CITY}`)
    const stale = page.getByTestId('checkout-stale')
    const ready = page.getByTestId('checkout-submit')
    await expect(stale.or(ready)).toBeVisible()
    if (await ready.isVisible()) {
      await page.getByTestId('checkout-agree').click()
      await ready.click()
    }
    await expect(stale).toContainText('这份报价不能下单')
    await expect(stale).toContainText('已经下过单')
    await page.getByTestId('checkout-back-to-quotes').click()
    await expect(page.getByTestId('inquiry-quotes-bar')).toHaveAttribute('data-bar', 'VIEW_ORDER')

    // 需求与报价：询价单已选定，能看订单。
    await page.goto('/?n=list#/pages/mine/demands')
    const card = page.getByTestId(`mall-inquiry-card-${inquiryId}`)
    await expect(card.getByTestId('mall-inquiry-card-status')).toHaveText('已选定')
    await expect(card.getByTestId('mall-inquiry-card-line')).toHaveText('已选定 示例行业装备代理 · ¥126,000')
    await expect(card.getByTestId('mall-inquiry-card-meta')).toHaveText(`${MODEL} · 2 台`)
    await expect(card).toContainText('询价单')
    await card.getByTestId(`mall-inquiry-card-order-${inquiryId}`).click()
    await expect(page.getByTestId('goods-order-merchant')).toHaveText('示例行业装备代理')
  })

  test('MINQ-002 商家拒绝写原因、另一家待报价；关闭询价后重新询价；需求与报价按状态筛选', async ({ page, request }) => {
    const inquiry = await submitInquiry(request, buyer, [ROBOT_OFFER, AGENT_OFFER])
    await merchantDeclines(request, robot, inquiry.inquiryId, '这个配置暂时没有货源')
    await signIn(page, buyer)

    await page.goto(`/#/pages/mall/inquiry-quotes?inquiryId=${inquiry.inquiryId}`)
    const declined = page.getByTestId(`inquiry-merchant-${ROBOT_OFFER}`)
    await expect(declined).toHaveAttribute('data-invitation', 'DECLINED')
    await expect(declined.getByTestId('inquiry-merchant-badge')).toHaveText('已拒绝')
    await expect(declined.getByTestId('inquiry-merchant-line')).toHaveText('商家已拒绝：这个配置暂时没有货源')
    await expect(page.getByTestId(`inquiry-merchant-${AGENT_OFFER}`).getByTestId('inquiry-merchant-badge')).toHaveText('待报价')
    // No quotes yet: nothing to sort.
    await expect(page.getByTestId('inquiry-quotes-sort-price')).toHaveCount(0)

    // 需求与报价：待报价里有它，卡片能关闭。
    await page.goto('/?n=open#/pages/mine/demands?status=OPEN')
    const card = page.getByTestId(`mall-inquiry-card-${inquiry.inquiryId}`)
    await expect(card.getByTestId('mall-inquiry-card-status')).toHaveText('待报价')
    await expect(card.getByTestId('mall-inquiry-card-line')).toHaveText(/^等待商家报价 · 截止 \d\d\/\d\d \d\d:\d\d$/)
    await card.getByTestId(`mall-inquiry-card-quotes-${inquiry.inquiryId}`).click()

    // 关闭询价 → 已关闭 → 重新询价进新的询价单。
    await page.getByTestId('inquiry-quotes-close').click()
    await expect(page.getByTestId('inquiry-confirm')).toContainText('关闭后商家不再报价')
    await page.getByTestId('inquiry-confirm-go').click()
    await expect(page.getByTestId('inquiry-quotes')).toHaveAttribute('data-status', 'CLOSED')
    await expect(page.getByTestId('inquiry-quotes-status')).toHaveText('已关闭')
    await expect(page.getByTestId('inquiry-quotes-bar-text')).toHaveText('你已关闭这份询价')
    await expect(page.getByTestId('inquiry-quotes-close')).toHaveCount(0)
    await page.getByTestId('inquiry-quotes-reinquire').click()
    await expect(page.getByTestId('mall-inquiry-name')).toHaveText('行业测绘无人机')

    await page.goto('/?n=closed#/pages/mine/demands?status=CLOSED')
    await expect(card.getByTestId('mall-inquiry-card-status')).toHaveText('已关闭')
    await expect(card.getByTestId(`mall-inquiry-card-reinquire-${inquiry.inquiryId}`)).toHaveText('重新询价')
    await page.getByTestId('demand-filter-open').click()
    await expect(card).toHaveCount(0)
  })

  test('MINQ-003 选定的报价过了有效期：确认订单说明不能下单，比选页改为重新询价', async ({ page, request }) => {
    const inquiry = await submitInquiry(request, buyer, [ROBOT_OFFER])
    const quoted = await merchantQuotes(request, robot, inquiry.inquiryId, 68_000, 5)
    const quote = quoted.myQuote as { quoteId: string; version: number }
    await chooseByApi(request, buyer, inquiry.inquiryId, quote)
    // 报价有效期最长 7 天，测试里把它挪到过去（只改这一份报价的时间）。
    psql(`UPDATE mall_inquiry_quote SET quoted_at = now() - interval '2 days', valid_until = now() - interval '1 minute'
      WHERE id = '${quote.quoteId}';`)
    await signIn(page, buyer)

    await page.goto(`/?n=expired#/pages/cart/checkout?inquiryQuoteId=${quote.quoteId}&inquiryId=${inquiry.inquiryId}&cityCode=${CITY}`)
    await expect(page.getByTestId('checkout-stale')).toContainText('这份报价已过有效期')
    await page.getByTestId('checkout-back-to-quotes').click()
    await expect(page.getByTestId('inquiry-quotes')).toHaveAttribute('data-status', 'CHOSEN')
    await expect(page.getByTestId('inquiry-quotes-bar')).toHaveAttribute('data-bar', 'EXPIRED')
    await expect(page.getByTestId('inquiry-quotes-chosen')).toHaveText('已选 示例机器人商家 · 报价已过有效期')
    await page.getByTestId('inquiry-quotes-reinquire').click()
    await expect(page.getByTestId('mall-inquiry-name')).toHaveText('行业测绘无人机')
  })

  test('VIS-MINQ 询价单、报价比选、确认订单、需求与报价在 320/375/430 无横向溢出并与画板 390 宽对照', async ({ page, request }) => {
    test.setTimeout(240_000)
    const inquiry = await submitInquiry(request, buyer, [ROBOT_OFFER, AGENT_OFFER])
    await merchantQuotes(request, robot, inquiry.inquiryId, 68_000, 5)
    await merchantQuotes(request, agent, inquiry.inquiryId, 64_500, 12)
    const chosenInquiry = await submitInquiry(request, buyer, [ROBOT_OFFER])
    const chosenQuote = (await merchantQuotes(request, robot, chosenInquiry.inquiryId, 68_000, 5)).myQuote as { quoteId: string; version: number }
    await chooseByApi(request, buyer, chosenInquiry.inquiryId, chosenQuote)
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

      await page.goto(at(`#/pages/mall/inquiry?productId=${PRODUCT}&cityCode=${CITY}`))
      await expect(page.getByTestId('mall-inquiry-merchants')).toBeVisible()
      await page.getByTestId('mall-inquiry-requirement').locator('textarea').fill(MODEL)
      await page.getByTestId('mall-inquiry-qty-plus').click()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('PdInquiry'))

      await page.goto(at(`#/pages/mall/inquiry-quotes?inquiryId=${inquiry.inquiryId}`))
      await expect(page.getByTestId('inquiry-quotes')).toHaveAttribute('data-status', 'QUOTED')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('QuoteGoods'))
      if (width === 390) continue

      await page.goto(at(`#/pages/mall/inquiry-quotes?inquiryId=${chosenInquiry.inquiryId}`))
      await expect(page.getByTestId('inquiry-quotes')).toHaveAttribute('data-status', 'CHOSEN')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('QuoteGoods-chosen'))

      await page.goto(at(`#/pages/cart/checkout?inquiryQuoteId=${chosenQuote.quoteId}&inquiryId=${chosenInquiry.inquiryId}&cityCode=${CITY}`))
      await expect(page.getByTestId('checkout-quote-note')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('checkout-quote'))

      await page.goto(at(`#/pages/mall/product?productId=${PRODUCT}&cityCode=${CITY}`))
      await expect(page.getByTestId('mall-product-inquiry-steps')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('PdInquiryFull'))

      await page.goto(at('#/pages/mine/demands'))
      await expect(page.locator('[data-testid^="mall-inquiry-card-"][data-status]')).toHaveCount(2)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('demand-list-inquiries'))

      await page.goto(at(`#/pages/mall/inquiry?productId=${PRODUCT}&cityCode=${CITY}`))
      await page.getByTestId('mall-inquiry-date').click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('date-sheet') })
    }

    // The boards at 390 next to the App at 390.
    for (const board of ['PdInquiry', 'QuoteGoods']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(boardUrl(board))
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
