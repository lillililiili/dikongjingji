import { randomUUID } from 'node:crypto'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { compareFolder, compareWithBoard, shootApp } from './support/board-compare'
import {
  API,
  BUYER,
  MERCHANT,
  addAddressByApi,
  apiLogin,
  clearAddresses,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatRefunds,
  latestRefundNo,
  offerId,
  payByApi,
  purgeBuyerOrders,
  readOrderByApi,
  shipByApi,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// 商品售后（S4-2，docs/redesign/09-商品售后与商家主页.md §1；画板 OrderGoods、AftersaleApply、AftersaleDetail、
// AftersaleFlow、AftersaleList、OrderList）。真实后端 + 本机模拟微信（FAKE_WECHAT=1）+ 开发库。
// 商家没有 App 页面：同意、拒绝、确认收货都由 dev-merchant 登录后调受权接口（和 Web 工作台调的是同一组接口）。
// 退款到账由模拟微信发回调。每条用例前删掉 dev-customer 的订单和售后单。
const KIT_FACTORY = offerId('101', '1')
const KIT_PRICE = 129900
const MERCHANT_DESK = `${API}/api/v1/provider/trade/aftersales`
const folder = compareFolder('aftersale')

// A 1×1 PNG: the server checks the file header, so it has to be a real picture.
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

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

function aftersaleIdFromUrl(page: Page): string {
  return new URL(page.url().replace('/#/', '/')).searchParams.get('aftersaleId') as string
}

// The merchant reads the case for its version (ETag) before every command, like the workbench does.
async function merchantCommand(
  request: APIRequestContext,
  merchant: Session,
  aftersaleId: string,
  command: 'approval' | 'rejection' | 'receipt' | 'receipt-rejection',
  data: Record<string, unknown> = {},
): Promise<void> {
  const read = await request.get(`${MERCHANT_DESK}/${aftersaleId}`, { headers: merchant.headers })
  expect(read.status()).toBe(200)
  const { version } = await read.json()
  const done = await request.post(`${MERCHANT_DESK}/${aftersaleId}/${command}`, {
    headers: { ...merchant.headers, 'If-Match': `"${version}"` },
    data,
  })
  expect(done.status()).toBe(200)
}

async function buyerAftersale(request: APIRequestContext, buyer: Session, aftersaleId: string): Promise<{ status: string; orderId: string }> {
  const read = await request.get(`${API}/api/v1/trade/aftersales/${aftersaleId}`, { headers: buyer.headers })
  expect(read.status()).toBe(200)
  return read.json()
}

async function shippedOrder(request: APIRequestContext, buyer: Session, merchant: Session, trackingNo: string): Promise<string> {
  const addressId = await addAddressByApi(request, buyer)
  const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
  await payByApi(request, buyer, order.orderId)
  await shipByApi(request, merchant, await readOrderByApi(request, buyer, order.orderId), trackingNo)
  return order.orderId
}

test.describe('商品售后', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let merchant: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试订单')
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    buyer = signedIn as Session
    merchant = (await apiLogin(request, MERCHANT)) as Session
    purgeBuyerOrders()
    await clearAddresses(request, buyer)
  })

  test.afterAll(async ({ request }) => {
    if (!databaseAvailable()) return
    purgeBuyerOrders()
    const signedIn = await apiLogin(request, BUYER)
    if (signedIn) await clearAddresses(request, signedIn)
  })

  test('ASL-001 已发货订单：订单详情交易主体与渠道交易号 → 七天无理由带一张凭证 → 商家同意 → 填运单 → 商家确认收货 → 微信退款回调 → 已退款', async ({ page, request }) => {
    test.setTimeout(150_000)
    const orderId = await shippedOrder(request, buyer, merchant, 'SF2026112300001')
    await signIn(page, buyer)
    await page.setViewportSize({ width: 390, height: 844 })

    // 订单详情（画板 OrderGoods）：交易主体五行、渠道交易号、售后条款、底栏“申请售后”。
    await page.goto(`/#/pages/mine/goods-order?orderId=${orderId}`)
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('已发货')
    const parties = page.getByTestId('goods-order-parties')
    await expect(parties).toContainText('销售方')
    await expect(parties.getByTestId('trade-party-invoice')).toHaveText('示例机器人商家')
    await expect(parties.getByTestId('trade-party-aftersale')).toHaveText('示例机器人商家 · 厂家保修')
    await expect(parties).toContainText('发货履约')
    await expect(page.getByTestId('goods-order-transaction-id')).not.toBeEmpty()
    await expect(page.getByTestId('goods-order-terms')).toContainText('未拆封 7 天无理由退货（按销售方规则）')
    await expect(page.getByTestId('goods-order-terms')).toContainText('协商不成可联系平台客服协助沟通（平台不裁决）')
    await expect(page.getByTestId('goods-order-view-aftersale')).toHaveCount(0)
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'OrderGoods')

    // 申请售后（画板 AftersaleApply）：已发货不能仅退款；默认七天无理由；金额是实付。
    await page.getByTestId('goods-order-aftersale-apply').click()
    await expect(page).toHaveURL(/pages\/trade\/aftersale-apply\?orderId=/)
    await expect(page.getByTestId('aftersale-kind-REFUND_ONLY')).toHaveAttribute('data-available', 'false')
    await expect(page.getByTestId('aftersale-kind-REFUND_ONLY-sub')).toHaveText('订单已发货，不可选')
    await expect(page.getByTestId('aftersale-kind-RETURN_REFUND')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('aftersale-apply-reason-value')).toHaveText('不想要了（未拆封，7 天无理由）')
    await expect(page.getByTestId('aftersale-apply-amount')).toHaveText('¥1,299')
    await expect(page.getByTestId('aftersale-apply-paid')).toContainText('实付 ¥1,299 · ')
    await expect(page.getByTestId('aftersale-apply-handler')).toHaveText('由 示例机器人商家 · 厂家保修 处理，平台协助沟通')
    // 原因面板只列退货退款的原因。
    await page.getByTestId('aftersale-apply-reason').click()
    await expect(page.getByTestId('aftersale-reason-sheet')).toBeVisible()
    await expect(page.getByTestId('aftersale-reason-NOT_WANTED')).toHaveCount(0)
    await page.getByTestId('aftersale-reason-NO_REASON_7D').click()
    await expect(page.getByTestId('aftersale-reason-sheet')).toHaveCount(0)
    // 商品照片一格传一张，缩略图带登录读回。
    const chooser = page.waitForEvent('filechooser')
    await page.getByTestId('aftersale-slot-PRODUCT').click()
    await (await chooser).setFiles({ name: 'goods.png', mimeType: 'image/png', buffer: PNG })
    await expect(page.getByTestId('aftersale-slot-PRODUCT-count')).toHaveText('已上传 1 张')
    await expect(page.getByTestId('aftersale-apply-thumbs').locator('[data-testid^="aftersale-thumb-"]')).toHaveCount(1)
    await page.getByTestId('aftersale-apply-description').locator('textarea').fill('未拆封，不想要了')
    await page.waitForTimeout(150)
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'AftersaleApply')
    await page.getByTestId('aftersale-apply-submit').click()

    // 售后详情（画板 AftersaleDetail）：等待商家处理。
    await expect(page).toHaveURL(/pages\/trade\/aftersale\?aftersaleId=/)
    const aftersaleId = aftersaleIdFromUrl(page)
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('等待商家处理')
    await expect(page.getByTestId('aftersale-info-amount')).toHaveText('¥1,299')
    await expect(page.getByTestId('aftersale-info-route')).toHaveText('经平台支付渠道原路退回')
    await expect(page.getByTestId('aftersale-step-review')).toHaveAttribute('data-kind', 'cur')
    await expect(page.getByTestId('aftersale-kind')).toHaveText('退货退款')
    await expect(page.getByTestId('aftersale-reason')).toHaveText('不想要了（未拆封，7 天无理由）')
    await expect(page.getByTestId('aftersale-action-revoke')).toBeVisible()
    await expect(page.getByTestId('aftersale-action-view_order')).toBeVisible()
    await shootApp(page, folder, 'AftersaleDetail')

    // 商家同意（不填地址，用默认寄回地址）；买家“刷新进度”看到待寄回和寄回地址。
    await merchantCommand(request, merchant, aftersaleId, 'approval')
    await page.getByTestId('aftersale-refresh').click()
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('商家已同意，请寄回商品')
    await expect(page.getByTestId('aftersale-address-line')).toHaveText('山东省东营市东营区 示例路 120 号（示例）')
    await expect(page.getByTestId('aftersale-address')).toContainText('寄回运费按销售方规则承担')
    await expect(page.getByTestId('aftersale-step-ship')).toHaveAttribute('data-kind', 'cur')
    await expect(page.getByTestId('aftersale-action-submit_return')).toBeVisible()
    await shootApp(page, folder, 'AftersaleFlow-待寄回')

    // 填写运单（承运商“其他承运商”时手填名称），提交带 If-Match。
    await page.getByTestId('aftersale-action-submit_return').click()
    await expect(page.getByTestId('aftersale-waybill-sheet')).toBeVisible()
    await page.getByTestId('aftersale-tracking-no').locator('input').fill('2026112300012')
    await page.waitForTimeout(150)
    await page.getByTestId('aftersale-waybill-submit').click()
    await expect(page.getByTestId('aftersale-waybill-sheet')).toHaveCount(0)
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('等待商家收货')
    await expect(page.getByTestId('aftersale-waybill-line')).toHaveText('示例快递 · 运单号 2026112300012')
    await expect(page.getByTestId('aftersale-action-edit_return')).toBeVisible()
    await expect(page.getByTestId('aftersale-action-revoke')).toHaveCount(0)
    await shootApp(page, folder, 'AftersaleFlow-待商家收货')

    // 改运单：换成其他承运商。
    await page.getByTestId('aftersale-action-edit_return').click()
    await page.getByTestId('aftersale-carrier-other').click()
    await page.getByTestId('aftersale-carrier-name').locator('input').fill('圆通速递')
    await page.waitForTimeout(150)
    await page.getByTestId('aftersale-waybill-submit').click()
    await expect(page.getByTestId('aftersale-waybill-line')).toHaveText('圆通速递 · 运单号 2026112300012')

    // 商家确认收货并退款；退款到账只由模拟微信的回调推进。
    await merchantCommand(request, merchant, aftersaleId, 'receipt')
    await page.getByTestId('aftersale-refresh').click()
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('退款中')
    await expect(page.getByTestId('aftersale-step-refund')).toHaveAttribute('data-kind', 'cur')
    await shootApp(page, folder, 'AftersaleFlow-退款中')
    await fakeWechatRefunds(request, latestRefundNo(orderId))
    await expect.poll(async () => (await buyerAftersale(request, buyer, aftersaleId)).status).toBe('REFUNDED')
    await page.reload()
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('已退款')
    await expect(page.getByTestId('aftersale-status-sub')).toHaveText('¥1,299 已原路退回，本次售后已完成')
    await expect(page.getByTestId('aftersale-info-arrival')).toBeVisible()
    await expect(page.getByTestId('aftersale-step-refund')).toHaveAttribute('data-kind', 'done')
    await expect(page.getByTestId('aftersale-refresh')).toHaveCount(0)
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'AftersaleFlow-已退款')

    // 查看订单：订单上记着退款和“查看售后 ›”。
    await page.getByTestId('aftersale-action-view_order').click()
    await expect(page.getByTestId('goods-order-refunds')).toContainText('¥1,299')
    await expect(page.getByTestId('goods-order-view-aftersale')).toBeVisible()
  })

  test('ASL-002 未发货订单仅退款：退货退款灰掉 → 申请即退款中 → 微信回调 → 已退款', async ({ page, request }) => {
    test.setTimeout(90_000)
    const addressId = await addAddressByApi(request, buyer)
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await payByApi(request, buyer, order.orderId)
    await signIn(page, buyer)

    await page.goto(`/#/pages/mine/goods-order?orderId=${order.orderId}`)
    await expect(page.getByTestId('goods-order-status-title')).toHaveText('待发货')
    await page.getByTestId('goods-order-aftersale-apply').click()
    await expect(page.getByTestId('aftersale-kind-RETURN_REFUND')).toHaveAttribute('data-available', 'false')
    await expect(page.getByTestId('aftersale-kind-RETURN_REFUND-sub')).toHaveText('订单还没发货，不可选（可以仅退款）')
    await expect(page.getByTestId('aftersale-kind-REFUND_ONLY')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('aftersale-apply-reason-value')).toHaveText('不想要了')
    await expect(page.getByTestId('aftersale-apply-paid')).toHaveText('实付 ¥1,299 · 未发货')
    await page.getByTestId('aftersale-apply-submit').click()

    await expect(page.getByTestId('aftersale-status-title')).toHaveText('退款中')
    await expect(page.getByTestId('aftersale-status-sub')).toHaveText('¥1,299 正在原路退回')
    await expect(page.getByTestId('aftersale-kind')).toHaveText('仅退款')
    await expect(page.locator('[data-testid^="aftersale-step-"]')).toHaveCount(2)
    const aftersaleId = aftersaleIdFromUrl(page)
    await fakeWechatRefunds(request, latestRefundNo(order.orderId))
    await expect.poll(async () => (await buyerAftersale(request, buyer, aftersaleId)).status).toBe('REFUNDED')
    await page.reload()
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('已退款')
  })

  test('ASL-003 商家拒绝：商家说明、你提交的凭证、联系平台客服与重新申请', async ({ page, request }) => {
    test.setTimeout(90_000)
    const orderId = await shippedOrder(request, buyer, merchant, 'SF2026112300003')
    const applied = await request.post(`${API}/api/v1/trade/orders/${orderId}/aftersales`, {
      headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
      data: { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D' },
    })
    expect(applied.status()).toBe(201)
    const { aftersaleId } = await applied.json()
    await merchantCommand(request, merchant, aftersaleId, 'rejection', {
      reason: '凭证不足，无法确认商品未拆封',
      note: '请补充商品和外包装照片后重新申请',
    })
    await signIn(page, buyer)
    await page.setViewportSize({ width: 390, height: 844 })

    await page.goto(`/#/pages/trade/aftersale?aftersaleId=${aftersaleId}`)
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('商家未同意退货')
    await expect(page.getByTestId('aftersale-info')).toHaveCount(0)
    const refusal = page.getByTestId('aftersale-refusal')
    await expect(refusal).toContainText('拒绝原因')
    await expect(refusal).toContainText('凭证不足，无法确认商品未拆封')
    await expect(refusal).toContainText('请补充商品和外包装照片后重新申请')
    await expect(refusal).toContainText('平台不裁决')
    await expect(page.getByTestId('aftersale-evidence-PRODUCT')).toHaveText('未上传')
    await expect(page.getByTestId('aftersale-step-review')).toHaveAttribute('data-kind', 'end')
    await expect(page.getByTestId('aftersale-action-contact_platform')).toBeVisible()
    await expect(page.getByTestId('aftersale-action-reapply')).toBeVisible()
    await expect(page.getByTestId('aftersale-action-view_order')).toHaveCount(0)
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'AftersaleFlow-商家拒绝')

    // 重新申请回到申请页（还能申请的话）。
    await page.getByTestId('aftersale-action-reapply').click()
    await expect(page).toHaveURL(new RegExp(`pages/trade/aftersale-apply\\?orderId=${orderId}`))
    await expect(page.getByTestId('aftersale-kind-RETURN_REFUND')).toHaveAttribute('aria-checked', 'true')
  })

  test('ASL-004 退款/售后：我的订单胶囊列出售后单 → 全部售后单 → 三个页签带数量；我的页直达', async ({ page, request }) => {
    test.setTimeout(90_000)
    const orderId = await shippedOrder(request, buyer, merchant, 'SF2026112300004')
    const applied = await request.post(`${API}/api/v1/trade/orders/${orderId}/aftersales`, {
      headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
      data: { kind: 'RETURN_REFUND', reason: 'DAMAGED' },
    })
    expect(applied.status()).toBe(201)
    const created = await applied.json()
    await merchantCommand(request, merchant, created.aftersaleId, 'approval')
    await signIn(page, buyer)
    await page.setViewportSize({ width: 390, height: 844 })

    // 我的订单（画板 OrderList）：“退款/售后”胶囊在页内列出售后单，下面“全部售后单 ›”。
    await page.goto('/#/pages/mine/orders?status=refund')
    const card = page.getByTestId(`my-order-aftersale-${created.aftersaleNo}`)
    await expect(card).toBeVisible()
    await expect(card).toContainText('退货退款')
    await expect(card.getByTestId('my-order-status')).toHaveText('待寄回')
    await expect(card.getByTestId('my-order-action-waybill')).toHaveText('填写运单')
    // 这张订单不再作为订单卡重复出现。
    await expect(page.getByTestId(`my-order-goods-${orderId}`)).toHaveCount(0)
    await shootApp(page, folder, 'OrderList-退款售后')
    // 其他胶囊不列售后单。
    await page.getByTestId('my-orders-chip-pay').click()
    await expect(card).toHaveCount(0)
    await page.getByTestId('my-orders-chip-refund').click()
    await page.getByTestId('my-orders-all-aftersales').click()

    // 退款/售后（画板 AftersaleList）。
    await expect(page).toHaveURL(/pages\/mine\/aftersales\?tab=DOING$/)
    await expect(page.getByTestId('aftersales-tab-DOING')).toHaveText(/售后中 1/)
    await expect(page.getByTestId('aftersales-tab-DONE')).toHaveText(/已完成 0/)
    await expect(page.getByTestId('aftersales-note')).toContainText('平台客服协助沟通，不裁决')
    const listed = page.getByTestId(`aftersale-card-${created.aftersaleNo}`)
    await expect(listed.getByTestId('my-order-line-note')).toContainText('商家已同意')
    await assertNoHorizontalOverflow(page)
    await shootApp(page, folder, 'AftersaleList')
    // “填写运单”进详情就打开运单弹层。
    await listed.getByTestId('my-order-action-waybill').click()
    await expect(page.getByTestId('aftersale-waybill-sheet')).toBeVisible()
    await page.goBack()

    // 撤销后到“已关闭”。
    await page.goto(`/#/pages/trade/aftersale?aftersaleId=${created.aftersaleId}`)
    await page.getByTestId('aftersale-action-revoke').click()
    await confirmModal(page, '撤销后这次售后申请结束')
    await expect(page.getByTestId('aftersale-status-title')).toHaveText('已撤销')
    await page.goto('/?n=2#/pages/mine/aftersales?tab=CLOSED')
    await expect(page.getByTestId('aftersales-tab-CLOSED')).toHaveText(/已关闭 1/)
    await expect(page.getByTestId(`aftersale-card-${created.aftersaleNo}`).getByTestId('my-order-status')).toHaveText('已撤销')

    // 我的 → 退款/售后 直达售后列表（画板 Mine）。
    await page.goto('/#/pages/mine/index')
    await page.locator('.platform-mine__status-item').nth(4).click()
    await expect(page).toHaveURL(/pages\/mine\/aftersales\?tab=DOING$/)
  })

  test('ASL-009 与画板同宽（390）并排：OrderGoods、AftersaleApply、AftersaleDetail、AftersaleFlow 各帧、AftersaleList、OrderList', async ({ page }) => {
    test.setTimeout(120_000)
    const pairs: Array<{ board: string; app: string; frame?: string; prepare?: (board: Page) => Promise<void> }> = [
      { board: 'OrderGoods', app: 'OrderGoods' },
      { board: 'AftersaleApply', app: 'AftersaleApply' },
      { board: 'AftersaleDetail', app: 'AftersaleDetail' },
      { board: 'AftersaleFlow', frame: '待寄回', app: 'AftersaleFlow-待寄回' },
      { board: 'AftersaleFlow', frame: '待商家收货', app: 'AftersaleFlow-待商家收货' },
      { board: 'AftersaleFlow', frame: '退款中', app: 'AftersaleFlow-退款中' },
      { board: 'AftersaleFlow', frame: '已退款', app: 'AftersaleFlow-已退款' },
      { board: 'AftersaleFlow', frame: '商家拒绝', app: 'AftersaleFlow-商家拒绝' },
      { board: 'AftersaleList', app: 'AftersaleList' },
      {
        board: 'OrderList',
        app: 'OrderList-退款售后',
        prepare: async (board) => { await board.locator('#root').getByText('退款/售后', { exact: true }).first().click() },
      },
    ]
    for (const pair of pairs) await compareWithBoard(page, folder, pair)
  })
})
