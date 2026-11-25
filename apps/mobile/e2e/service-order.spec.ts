import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { PLANNING, boardUrl } from './support/board-compare'
import {
  DEMAND_ID,
  FILE_REPORT,
  ORDER_ID,
  PROVIDER_ID,
  QUOTE_A,
  SERVICE_ID,
  TRADE_ORDER_ID,
  CITY,
  delivery,
  initialState,
  signIn,
  stubServiceOrderApi,
  type StubState,
} from './support/service-order-stubs'

// 通用服务订单（S4-3，计划 §28，契约 openapi-service-order.yaml 1.0.0）与需求、专业服务补字段（S4-4）：
// 报价比选（报价明细、对比项）→ 选定 → 服务订单详情（待出方案）→ 方案确认 → 一次付清去收银台 → 交付与验收 /
// 退回整改；开工前取消（已付款整笔原路退）；我的订单“服务”页签；需求与报价的页签计数和“查看订单”。
// 全部用 page.route 桩数据（e2e/support/service-order-stubs.ts），不连后端；服务商没有页面，出方案、开工、交付
// 由测试直接改桩的状态。E2E_SERVICE_ORDER_VISUAL=1 时另外截 390 宽对照图到 .planning/style-compare/service-order。
const compareOutput = resolve(PLANNING, 'style-compare/service-order')

const at = (route: string) => `/#${route}`
const detailUrl = at(`/pages/service-order/detail?orderId=${ORDER_ID}`)
const planUrl = at(`/pages/service-order/plan?orderId=${ORDER_ID}`)
const deliveryUrl = at(`/pages/service-order/delivery?orderId=${ORDER_ID}`)

async function open(page: Page, state: StubState, url: string): Promise<void> {
  await signIn(page)
  await stubServiceOrderApi(page, state)
  await page.goto(url)
}

const sentTo = (state: StubState, suffix: string) => state.sent.filter((item) => item.path.endsWith(suffix))

test.describe('通用服务订单（S4-3）', () => {
  test('SO-001 报价比选显示报价明细和对比项，选定后进服务订单详情（待出方案）', async ({ page }) => {
    const state = initialState()
    await open(page, state, at(`/pages/demand/quotes?demandId=${DEMAND_ID}`))

    const first = page.getByTestId(`demand-quote-${QUOTE_A}`)
    await expect(first).toBeVisible()
    await expect(first.getByTestId('demand-quote-line')).toHaveCount(2)
    await expect(first.getByTestId('demand-quote-line').first()).toContainText('4 台 × ¥590')
    await expect(first).toContainText('服务团队')
    await expect(first).toContainText('48 小时内')
    await expect(first).toContainText('90 天')

    await page.getByTestId(`demand-quote-choose-${QUOTE_A}`).click()
    await expect(page.getByTestId('demand-confirm')).toContainText('选定后生成服务订单，服务商出方案后由你确认并一次付清')
    await page.getByTestId('demand-confirm-go').click()

    await expect(page.getByTestId('service-order-detail')).toHaveAttribute('data-status', 'AWAITING_PLAN')
    expect(page.url()).toContain(`/pages/service-order/detail?orderId=${ORDER_ID}`)
    await expect(page.getByTestId('service-order-status-title')).toHaveText('待出方案 · 服务商正在出方案')
    await expect(page.getByTestId('service-order-plan-tag')).toHaveText('待出方案')
    await expect(page.getByTestId('service-order-pay-once')).toContainText('确认方案后支付')
    await expect(page.getByTestId('service-order-action')).toHaveCount(0)
    expect(sentTo(state, '/choice')[0]).toMatchObject({ body: { quoteId: QUOTE_A, quoteVersion: 2 }, ifMatch: '"4"' })

    // Back on the quotes page the chosen bar leads to the order, not the closed gate.
    await page.goBack()
    await expect(page.getByTestId('demand-open-service-order')).toBeVisible()
    await expect(page.getByTestId('demand-follow-up')).toHaveCount(0)
  })

  test('SO-002 对比明细：没填的对比项显示“—”，0 天影像存档显示“不含”，单家页签有报价明细', async ({ page }) => {
    await open(page, initialState(), at(`/pages/demand/compare?demandId=${DEMAND_ID}`))
    const table = page.getByTestId('demand-compare-table')
    await expect(table).toBeVisible()
    for (const label of ['服务团队', '首次进场', '报告时效', '影像存档']) await expect(table).toContainText(label)
    // Nobody filled 夜间作业: the row is left out.
    await expect(table).not.toContainText('夜间作业')
    await expect(table).toContainText('不含')

    await page.getByTestId(`demand-compare-tab-${QUOTE_A}`).click()
    await expect(page.getByTestId('demand-compare-line')).toHaveCount(2)
    await expect(page.getByTestId('demand-compare-lines')).toContainText('更换桨叶与电池保养')
  })

  test('SO-003 方案确认：勾选后确认方案 v2，按钮变“支付 ¥3,860”，去收银台', async ({ page }) => {
    const state = initialState({ status: 'PLAN_PENDING', planVersion: 2, demandStatus: 'CHOSEN' })
    await open(page, state, planUrl)

    await expect(page.getByTestId('service-order-plan-chip')).toHaveText('待确认')
    await expect(page.getByTestId('service-order-plan-line')).toHaveCount(2)
    await expect(page.getByTestId('service-order-plan-total')).toHaveText('¥3,860')
    await expect(page.getByTestId('service-order-plan-pay-state')).toHaveText('确认方案后支付')
    const confirm = page.getByTestId('service-order-plan-confirm')
    await expect(confirm).toHaveText('确认方案 v2')
    await expect(confirm).toHaveAttribute('aria-disabled', 'true')

    await page.getByTestId('service-order-plan-agree').click()
    await expect(confirm).toHaveAttribute('aria-disabled', 'false')
    await confirm.click()

    await expect(page.getByTestId('service-order-plan-status')).toContainText('已确认方案 v2')
    expect(sentTo(state, '/plan-confirmation')[0]).toMatchObject({ body: { planVersion: 2, agreed: true }, ifMatch: '"1"' })
    const pay = page.getByTestId('service-order-plan-pay')
    await expect(pay).toHaveText('支付 ¥3,860')
    await pay.click()

    await expect(page.getByTestId('cashier-head')).toBeVisible()
    expect(page.url()).toContain(`/pages/trade/cashier?orderId=${TRADE_ORDER_ID}`)
    await expect(page.getByTestId('cashier-payee')).toHaveText('鹰眼智能巡检')
    await expect(page.locator('body')).toContainText('设备维护需求 · 鹰眼智能巡检')
    await expect(page.locator('body')).toContainText('方案 v2')
  })

  test('SO-004 服务商改了方案（409 PLAN_CHANGED）：提示并重读，不当成已确认', async ({ page }) => {
    const state = initialState({ status: 'PLAN_PENDING', planVersion: 2 })
    state.failNext[`/api/v1/service-orders/${ORDER_ID}/plan-confirmation`] = {
      status: 409, code: 'SERVICE_ORDER_PLAN_CHANGED', message: '方案已更新',
    }
    await open(page, state, planUrl)
    await page.getByTestId('service-order-plan-agree').click()
    // The provider issues v3 meanwhile.
    state.planVersion = 3
    await page.getByTestId('service-order-plan-confirm').click()
    await expect(page.getByTestId('service-order-notice')).toContainText('服务商更新了方案，已刷新')
    await expect(page.getByTestId('service-order-plan-confirm')).toHaveText('确认方案 v3')
    await expect(page.getByTestId('service-order-plan-confirm')).toHaveAttribute('aria-disabled', 'true')
  })

  test('SO-005 服务商未开通在线收款：说明原因，不去收银台', async ({ page }) => {
    const state = initialState({ status: 'AWAITING_PAYMENT', planVersion: 2, planConfirmed: true, collectable: false })
    await open(page, state, planUrl)
    await expect(page.getByTestId('service-order-plan-uncollectable')).toHaveText('服务商暂未开通在线收款，请先联系服务商')
    await expect(page.getByTestId('service-order-plan-pay')).toHaveAttribute('aria-disabled', 'true')

    await page.goto(detailUrl)
    await expect(page.getByTestId('service-order-status-text')).toHaveText('服务商暂未开通在线收款，请先联系服务商')
    await expect(page.getByTestId('service-order-action')).toHaveCount(0)
  })

  test('SO-006 交付与验收：下载走短时链接，确认验收带第几次交付', async ({ page }) => {
    const state = initialState({
      status: 'DELIVERED', planVersion: 2, planConfirmed: true, paid: true, demandStatus: 'CHOSEN', deliveries: [delivery(1, 'SUBMITTED')],
    })
    await open(page, state, detailUrl)
    await expect(page.getByTestId('service-order-status-title')).toHaveText('待验收 · 第 1 次交付')
    await page.getByTestId('service-order-action').click()

    await expect(page.getByTestId('service-order-delivery-chip')).toHaveText('待验收')
    await page.getByTestId(`service-order-download-${FILE_REPORT}`).click()
    await expect(page.getByTestId(`service-order-file-link-${FILE_REPORT}`)).toContainText('下载链接已生成')
    expect(sentTo(state, `/files/${FILE_REPORT}/download-intents`)).toHaveLength(1)

    await page.getByTestId('service-order-accept').click()
    const sheet = page.getByTestId('service-order-accept-sheet')
    await expect(sheet).toContainText('确认第 1 次验收')
    await expect(sheet).toContainText('验收确认后不可撤销')
    await page.getByTestId('service-order-accept-sheet-go').click()

    await expect(page.getByTestId('service-order-delivery-accepted')).toContainText('第 1 次已验收')
    expect(sentTo(state, '/acceptance')[0]).toMatchObject({ body: { round: 1 }, ifMatch: '"1"' })
    await expect(page.getByTestId('service-order-accept')).toHaveCount(0)
  })

  test('SO-007 退回整改：写原因（≤200 字）后退回，历次交付显示原因', async ({ page }) => {
    const state = initialState({
      status: 'DELIVERED', planVersion: 2, planConfirmed: true, paid: true, deliveries: [delivery(1, 'SUBMITTED')],
    })
    await open(page, state, deliveryUrl)
    await page.getByTestId('service-order-rework').click()
    const go = page.getByTestId('service-order-rework-sheet-go')
    await expect(go).toHaveAttribute('aria-disabled', 'true')
    await page.getByTestId('service-order-rework-reason').locator('textarea').fill('缺少东侧机库那台的检测报告')
    await expect(go).toHaveAttribute('aria-disabled', 'false')
    await go.click()

    await expect(page.getByTestId('service-order-delivery-returned')).toContainText('退回原因：缺少东侧机库那台的检测报告')
    expect(sentTo(state, '/rework')[0]).toMatchObject({ body: { round: 1, reason: '缺少东侧机库那台的检测报告' } })

    // The provider delivers again: round 2 waits, round 1 shows why it went back.
    state.deliveries = [delivery(1, 'RETURNED', '缺少东侧机库那台的检测报告'), delivery(2, 'SUBMITTED')]
    state.status = 'DELIVERED'
    await page.reload()
    await expect(page.getByTestId('service-order-delivery-title')).toContainText('第 2 次交付')
    await expect(page.getByTestId('service-order-delivery-history')).toContainText('退回原因：缺少东侧机库那台的检测报告')
  })

  test('SO-008 开工前取消：未付款不涉及费用；已付款整笔原路退款并显示退款状态', async ({ page }) => {
    const unpaid = initialState({ status: 'AWAITING_PLAN' })
    await open(page, unpaid, detailUrl)
    await page.getByTestId('service-order-cancel').click()
    await expect(page.getByTestId('service-order-cancel-sheet-text')).toHaveText('还没有付款，取消不涉及费用。取消后服务商停止安排本次服务。')
    await page.getByTestId('service-order-cancel-sheet-keep').click()
    await expect(page.getByTestId('service-order-cancel-sheet')).toHaveCount(0)

    const paid = initialState({ status: 'AWAITING_START', planVersion: 2, planConfirmed: true, paid: true })
    await page.unroute('**/api/v1/**')
    await stubServiceOrderApi(page, paid)
    await page.reload()
    await expect(page.getByTestId('service-order-status-title')).toHaveText('待开工 · 已付款')
    await page.getByTestId('service-order-cancel').click()
    await expect(page.getByTestId('service-order-cancel-sheet-text')).toContainText('将整笔原路退回')
    await page.getByTestId('service-order-cancel-sheet-go').click()

    await expect(page.getByTestId('service-order-detail')).toHaveAttribute('data-status', 'CANCELLED')
    await expect(page.getByTestId('service-order-pay-refund-c5000000-0000-4000-8000-000000000a01')).toContainText('退款中 ¥3,860')
    expect(sentTo(paid, '/cancel')[0]).toMatchObject({ body: { reason: null }, ifMatch: '"1"' })
    await expect(page.getByTestId('service-order-cancel')).toHaveCount(0)
  })

  test('SO-009 我的订单“服务”页签有这张服务订单，待付款胶囊里也有', async ({ page }) => {
    const state = initialState({ status: 'AWAITING_PAYMENT', planVersion: 2, planConfirmed: true })
    await open(page, state, at('/pages/mine/orders?tab=service'))
    const card = page.getByTestId(`my-order-service-${ORDER_ID}`)
    await expect(card).toBeVisible()
    await expect(card.getByTestId('my-order-status')).toHaveText('待付款')
    await expect(card).toContainText('方案 v2 已确认 · 待一次付清')
    await page.getByTestId('my-orders-chip-pay').click()
    await expect(card).toBeVisible()
    await card.getByTestId('my-order-action-pay').click()
    await expect(page.getByTestId('service-order-plan')).toBeVisible()
  })

  test('SO-010 需求与报价：页签带计数，已选定的需求有“查看订单”', async ({ page }) => {
    const state = initialState({ demandStatus: 'CHOSEN' })
    await open(page, state, at('/pages/mine/demands'))
    // 需求单 5 − 1 草稿 = 4，询价单 1 + 2 = 3，草稿 1，全部 8.
    await expect(page.getByTestId('demand-tab-count-all')).toHaveText('8')
    await expect(page.getByTestId('demand-tab-count-demand')).toHaveText('4')
    await expect(page.getByTestId('demand-tab-count-inquiry')).toHaveText('3')
    await expect(page.getByTestId('demand-filter-draft')).toContainText('1')
    await page.getByTestId(`demand-card-order-${DEMAND_ID}`).click()
    await expect(page.getByTestId('service-order-detail')).toBeVisible()
  })

  test('SO-011 专业服务：设备维护详情显示质保期，服务商主页显示审核通过的资料', async ({ page }) => {
    await open(page, initialState(), at(`/pages/proservice/detail?serviceId=${SERVICE_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('pro-detail-warranty')).toHaveText('维修后质保 90 天')

    await page.goto(at(`/pages/provider/detail?providerId=${PROVIDER_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('provider-profile-entity')).toHaveText('示例鹰眼智能科技有限公司')
    await expect(page.getByTestId('provider-profile-until')).toHaveText('至 2027-10-31')
    await expect(page.getByTestId('provider-team')).toHaveText('东营本地团队')
    await expect(page.getByTestId('provider-deliverables')).toContainText('隐患清单')
  })
})

// 390-wide screenshots of the App beside the boards (OrderService, PlanConfirm, DeliveryOne, Quote,
// PlanCompare, DemandList, SvcMaint, ProviderPage). Run on demand: E2E_SERVICE_ORDER_VISUAL=1.
test.describe('通用服务订单对照图', () => {
  test.skip(!process.env.E2E_SERVICE_ORDER_VISUAL, 'Set E2E_SERVICE_ORDER_VISUAL=1 to write the comparison pictures.')

  async function tallShot(page: Page, path: string): Promise<void> {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.evaluate(() => window.scrollTo(0, 0))
    const height = await page.evaluate(() => document.documentElement.scrollHeight)
    await page.setViewportSize({ width: 390, height: Math.max(844, height) })
    await page.waitForTimeout(400)
    await page.screenshot({ path })
    await page.setViewportSize({ width: 390, height: 844 })
  }

  async function boardShot(page: Page, board: string): Promise<string> {
    const path = resolve(compareOutput, `${board}-board.png`)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(boardUrl(board))
    const frame = page.locator('#root > div').first()
    await expect(frame).toBeVisible()
    const height = Math.ceil((await frame.boundingBox())?.height ?? 844) + 60
    await page.setViewportSize({ width: 390, height })
    await page.waitForTimeout(300)
    await frame.screenshot({ path })
    return path
  }

  async function sideBySide(page: Page, name: string, board: string | null, apps: string[]): Promise<void> {
    const image = (path: string) => `data:image/png;base64,${readFileSync(path).toString('base64')}`
    const figures = [
      ...(board ? [[`画板 ${board}`, await boardShot(page, board)]] : []),
      ...apps.map((path, index) => [`App 390 宽${apps.length > 1 ? ` · ${index + 1}` : ''}`, path]),
    ]
    await page.setViewportSize({ width: 20 + figures.length * 410, height: 900 })
    await page.setContent(`
      <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
        <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
          ${figures.map(([caption, path]) => `<figure style="margin:0;width:390px"><figcaption>${caption}</figcaption>
            <img style="width:390px;display:block" src="${image(path)}"></figure>`).join('')}
        </div>
      </body>`)
    await page.waitForTimeout(200)
    await page.screenshot({ path: resolve(compareOutput, `${name}-compare.png`), fullPage: true })
  }

  test('SO-V01 390 宽并排对照', async ({ page }) => {
    test.setTimeout(240_000)
    mkdirSync(compareOutput, { recursive: true })
    const shot = (name: string) => resolve(compareOutput, `${name}-app.png`)
    // A new document each time: going to the same hash again would not read the order again.
    let visit = 0
    const fresh = (url: string) => page.goto(`/?visual=${++visit}${url.slice(1)}`)
    const state = initialState()
    await signIn(page)
    await stubServiceOrderApi(page, state)

    const detailStates: Array<[string, Partial<StubState>]> = [
      ['detail-awaiting-plan', { status: 'AWAITING_PLAN' }],
      ['detail-awaiting-payment', { status: 'AWAITING_PAYMENT', planVersion: 2, planConfirmed: true }],
      ['detail-in-service', { status: 'IN_SERVICE', planVersion: 2, planConfirmed: true, paid: true }],
      ['detail-delivered', { status: 'DELIVERED', planVersion: 2, planConfirmed: true, paid: true, deliveries: [delivery(1, 'SUBMITTED')] }],
    ]
    for (const [name, overrides] of detailStates) {
      Object.assign(state, initialState(overrides))
      await fresh(detailUrl)
      await expect(page.getByTestId('service-order-status')).toBeVisible()
      await tallShot(page, shot(name))
    }
    await sideBySide(page, 'OrderService', 'OrderService', detailStates.map(([name]) => shot(name)))

    Object.assign(state, initialState({ status: 'PLAN_PENDING', planVersion: 2 }))
    await fresh(planUrl)
    await expect(page.getByTestId('service-order-plan-lines')).toBeVisible()
    await tallShot(page, shot('plan-pending'))
    Object.assign(state, initialState({ status: 'AWAITING_PAYMENT', planVersion: 2, planConfirmed: true }))
    await fresh(planUrl)
    await expect(page.getByTestId('service-order-plan-pay')).toBeVisible()
    await tallShot(page, shot('plan-confirmed'))
    await sideBySide(page, 'PlanConfirm', 'PlanConfirm', [shot('plan-pending'), shot('plan-confirmed')])

    Object.assign(state, initialState({
      status: 'DELIVERED', planVersion: 2, planConfirmed: true, paid: true,
      deliveries: [delivery(1, 'RETURNED', '缺少东侧机库那台的检测报告'), delivery(2, 'SUBMITTED')],
    }))
    await fresh(deliveryUrl)
    await expect(page.getByTestId('service-order-delivery-files')).toBeVisible()
    await tallShot(page, shot('delivery'))
    await sideBySide(page, 'DeliveryOne', 'DeliveryOne', [shot('delivery')])

    Object.assign(state, initialState())
    await fresh(at(`/pages/demand/quotes?demandId=${DEMAND_ID}`))
    await expect(page.getByTestId(`demand-quote-${QUOTE_A}`)).toBeVisible()
    await tallShot(page, shot('quotes'))
    await sideBySide(page, 'Quote', 'Quote', [shot('quotes')])

    await fresh(at(`/pages/demand/compare?demandId=${DEMAND_ID}`))
    await expect(page.getByTestId('demand-compare-table')).toBeVisible()
    await tallShot(page, shot('compare'))
    await page.getByTestId(`demand-compare-tab-${QUOTE_A}`).click()
    await expect(page.getByTestId('demand-compare-lines')).toBeVisible()
    await tallShot(page, shot('compare-plan'))
    await sideBySide(page, 'PlanCompare', 'PlanCompare', [shot('compare'), shot('compare-plan')])

    Object.assign(state, initialState({ demandStatus: 'CHOSEN' }))
    await fresh(at('/pages/mine/demands'))
    await expect(page.getByTestId('demand-tab-count-all')).toBeVisible()
    await tallShot(page, shot('demands'))
    await sideBySide(page, 'DemandList', 'DemandList', [shot('demands')])

    await fresh(at(`/pages/provider/detail?providerId=${PROVIDER_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('provider-profile')).toBeVisible()
    await tallShot(page, shot('provider'))
    await sideBySide(page, 'ProviderPage', 'ProviderPage', [shot('provider')])

    await fresh(at(`/pages/proservice/detail?serviceId=${SERVICE_ID}&cityCode=${CITY}`))
    await expect(page.getByTestId('pro-detail-warranty')).toBeVisible()
    await tallShot(page, shot('maintenance'))
    await sideBySide(page, 'SvcMaint', 'SvcMaint', [shot('maintenance')])

    Object.assign(state, initialState({ status: 'AWAITING_PAYMENT', planVersion: 2, planConfirmed: true }))
    await fresh(at('/pages/mine/orders?tab=service'))
    await expect(page.getByTestId(`my-order-service-${ORDER_ID}`)).toBeVisible()
    await tallShot(page, shot('my-orders'))
    await sideBySide(page, 'OrderList', 'OrderList', [shot('my-orders')])
  })
})
