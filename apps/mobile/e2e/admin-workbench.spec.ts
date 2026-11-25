import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { testAdmin as ADMIN } from './support/test-stack'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  BUYER,
  MERCHANT,
  addAddressByApi,
  apiLogin,
  clearAddresses,
  databaseAvailable,
  devPassword,
  fakeWechatAvailable,
  offerId,
  payByApi,
  purgeBuyerOrders,
  readOrderByApi,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// S3-2 机构工作台（Web 运营端 apps/admin，不是 App）。放在 App 的 e2e 目录里，是为了复用真实后端、模拟微信和
// 测试库的工具。后台开发服务代理至测试 API，启动方式见 infra/README.md。
// 运营端没起时整组跳过。买家一侧（下单、付款）用接口建好，商家一侧全在运营端页面上点：发货、自提核销、部分退款。
// 每条用例开始前删掉 dev-customer 的全部订单（和 trade-orders.spec.ts 一样）。

const KIT_FACTORY = offerId('101', '1')
const KIT_PRICE = 129900
// 截图保存在当前检出的 .planning/style-compare。
const compareOutput = resolve(__dirname, '../../../.planning/style-compare/admin-workbench')

test.use({ viewport: { width: 1366, height: 860 } })

async function adminAvailable(request: APIRequestContext): Promise<boolean> {
  try {
    return (await request.get(`${ADMIN}/login`)).ok()
  } catch {
    return false
  }
}

async function adminSignIn(page: Page, loginName: string, target: string): Promise<void> {
  await page.goto(`${ADMIN}${target}`)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.locator('input[autocomplete="username"]').fill(loginName)
  await page.locator('input[autocomplete="current-password"]').fill(devPassword())
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  // 按路径比：登录页的地址 /login?redirect=<target> 结尾也是 target，用“以 target 结尾”会在登录请求回来前就算通过。
  await expect(page).toHaveURL((url) => url.pathname === target)
}

/** 确认框（ElMessageBox）里点确认。 */
async function confirmBox(page: Page, button: string): Promise<void> {
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: button }).click()
}

async function noErrorAlert(page: Page): Promise<void> {
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  await expect(page.locator('.el-alert--error')).toHaveCount(0)
}

test.describe('机构工作台（运营端）', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let merchant: Session

  test.beforeEach(async ({ request }) => {
    test.skip(!(await adminAvailable(request)), `运营端开发服务没有运行（${ADMIN}）`)
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试订单')
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

  test('ADM-WB-001 商家在“待发货”里发货：带订单版本提交，买家那边变成已发货，订单进“已发货”页签', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const addressId = await addAddressByApi(request, buyer)
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })
    await payByApi(request, buyer, order.orderId)

    await adminSignIn(page, MERCHANT, '/workbench/mall-orders')
    await expect(page.locator('.layout__menu')).toContainText('商城订单')
    await expect(page.locator('.layout__menu')).not.toContainText('城市开通')
    const row = page.locator('.el-table__row', { hasText: order.orderNo })
    await expect(row).toBeVisible()
    await expect(row).toContainText('待发货')

    await row.getByRole('button', { name: '发货' }).click()
    const dialog = page.locator('.el-dialog', { hasText: `发货：${order.orderNo}` })
    await expect(dialog).toContainText('王女士')
    await dialog.locator('.ship-carrier input').fill('顺丰速运')
    await dialog.locator('.ship-tracking input').fill('SF2026112300001')
    await dialog.getByRole('button', { name: '确认发货' }).click()
    await confirmBox(page, '确认发货')
    await expect(page.locator('.el-message--success')).toContainText('已发货')

    await expect.poll(async () => (await readOrderByApi(request, buyer, order.orderId)).status).toBe('SHIPPED')
    await page.locator('.el-radio-button', { hasText: '已发货' }).click()
    await expect(page.locator('.el-table__row', { hasText: order.orderNo })).toContainText('已发货')
  })

  test('ADM-WB-002 自提核销输错取货码给出提示，输对后完成；详情页部分退款', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'PICKUP', payableMinor: KIT_PRICE })
    await payByApi(request, buyer, order.orderId)
    const paid = await readOrderByApi(request, buyer, order.orderId)
    const code = paid.delivery.pickupCode as string
    expect(code).toMatch(/^\d{6}$/)
    const wrong = code === '000000' ? '111111' : '000000'

    await adminSignIn(page, MERCHANT, '/workbench/mall-orders')
    await page.locator('.el-radio-button', { hasText: '待自提' }).click()
    const row = page.locator('.el-table__row', { hasText: order.orderNo })
    await row.getByRole('button', { name: '核销' }).click()
    const dialog = page.locator('.el-dialog', { hasText: `自提核销：${order.orderNo}` })
    await dialog.locator('.pickup-code input').fill(wrong)
    await dialog.getByRole('button', { name: '核销' }).click()
    await confirmBox(page, '确认核销')
    await expect(dialog).toContainText('取货码不对')
    await dialog.locator('.pickup-code input').fill(code)
    await dialog.getByRole('button', { name: '核销' }).click()
    await confirmBox(page, '确认核销')
    await expect.poll(async () => (await readOrderByApi(request, buyer, order.orderId)).status).toBe('COMPLETED')

    // 已完成的单在详情页退一部分：金额按元填，校验不超过可退。
    await page.goto(`${ADMIN}/workbench/mall-orders/${order.orderId}`)
    await page.getByRole('button', { name: '退款' }).click()
    const refund = page.locator('.el-dialog', { hasText: `退款：${order.orderNo}` })
    await expect(refund).toContainText('¥1,299.00')
    await refund.locator('.refund-amount input').fill('2000')
    await refund.locator('.refund-reason textarea').fill('少发一件配件')
    await refund.getByRole('button', { name: '确认退款' }).click()
    await expect(refund).toContainText('不能超过')
    await refund.locator('.refund-amount input').fill('10.5')
    await refund.getByRole('button', { name: '确认退款' }).click()
    await confirmBox(page, '确认退款')
    await expect(page.locator('.el-message--success')).toBeVisible()
    await expect(page.locator('body')).toContainText('¥10.50')
  })

  test('ADM-WB-003 各工作台页面对着真实后端都能打开、没有报错；保存对照截图', async ({ page }) => {
    test.setTimeout(120_000)
    mkdirSync(compareOutput, { recursive: true })
    const merchantPages = [
      ['mall-orders', '商城订单'],
      ['mall-inquiries', '商城询价'],
      ['rental-inquiries', '租赁询价'],
      ['rental-orders', '租赁订单'],
      ['conversations', '客服会话'],
      ['live-rooms', '直播间'],
      ['merchant-account', '收款账户'],
    ] as const
    await adminSignIn(page, MERCHANT, '/workbench/mall-orders')
    for (const [path, title] of merchantPages) {
      await page.goto(`${ADMIN}/workbench/${path}`)
      await expect(page.locator('.page-section__title').first()).toContainText(title)
      await noErrorAlert(page)
      await page.screenshot({ path: resolve(compareOutput, `merchant-${path}.png`), fullPage: true })
    }
    // 收款账户（最后打开的一页）：子商户号只露后 4 位。
    await expect(page.locator('body')).toContainText('0101')
    await expect(page.locator('body')).not.toContainText('1900000101')

    await page.context().clearCookies()
    await page.evaluate(() => sessionStorage.clear())
    await adminSignIn(page, 'dev-provider', '/workbench/inspection')
    await expect(page.locator('.layout__menu')).not.toContainText('直播间')
    for (const [path, title] of [['inspection', '巡检预约'], ['demands', '需求报价'], ['conversations', '客服会话']] as const) {
      await page.goto(`${ADMIN}/workbench/${path}`)
      await expect(page.locator('.page-section__title').first()).toContainText(title)
      await noErrorAlert(page)
      await page.screenshot({ path: resolve(compareOutput, `provider-${path}.png`), fullPage: true })
    }
    await page.goto(`${ADMIN}/workbench/live-rooms`)
    await expect(page.locator('body')).toContainText('没有权限')
  })

  test('ADM-WB-004 客户账号进不了运营端', async ({ page }) => {
    await page.goto(`${ADMIN}/login`)
    await page.locator('input[autocomplete="username"]').fill(BUYER)
    await page.locator('input[autocomplete="current-password"]').fill(devPassword())
    await page.locator('form').getByRole('button', { name: '登录' }).click()
    await expect(page.locator('body')).toContainText('没有运营端权限')
    await expect(page).toHaveURL(/\/login/)
  })

  // 订单查询、用户查询（契约 openapi-ops-lookup.yaml）：平台客服按登录名找到人，看详情（手机号打码），跳到这个人的订单，
  // 再按订单号打开订单详情。
  test('ADM-WB-005 平台客服查用户、查订单', async ({ page, request }) => {
    test.setTimeout(120_000)
    const addressId = await addAddressByApi(request, buyer)
    const order = await submitOrderByApi(request, buyer, { offer: KIT_FACTORY, mode: 'EXPRESS', payableMinor: KIT_PRICE, addressId })

    await adminSignIn(page, 'dev-support', '/lookup/users')
    await page.getByPlaceholder('手机号 / 账号 ID / 登录名 / 显示名').fill(BUYER)
    await page.getByTestId('user-lookup-search').click()
    await page.locator('.el-table__row').first().getByRole('button').first().click()
    await expect(page.getByTestId('user-lookup-basics')).toContainText(BUYER)
    await expect(page.getByTestId('user-lookup-basics')).toContainText(/1\d{2}\*{4}\d{4}/)
    await noErrorAlert(page)

    await page.getByTestId('user-lookup-orders').click()
    await expect(page).toHaveURL(/\/lookup\/orders\?buyerId=/)
    await expect(page.locator('.el-table__row')).toHaveCount(1)
    await page.getByRole('button', { name: order.orderNo }).click()
    await expect(page.locator('.page-section__title').first()).toContainText(order.orderNo)
    await expect(page.locator('.page-section__description').first()).toContainText('商城 · 待付款')
    await expect(page.getByTestId('order-lookup-basics')).toContainText('付款截止')
    await expect(page.locator('body')).toContainText('详细地址不在这里显示')
    // 没有完整手机号（单号里也有长串数字，所以要求前后不是数字）。
    await expect(page.locator('body')).not.toContainText(/(?<!\d)1[3-9]\d{9}(?!\d)/)
    await noErrorAlert(page)
    await page.screenshot({ path: resolve(compareOutput, 'support-order-lookup.png'), fullPage: true })
  })

  // 首页经营概览（契约 openapi-ops-dashboard.yaml）：页面上的数字就是接口给的数，切换统计期重新取；待处理只给能进的页面加链接。
  test('ADM-WB-006 平台客服在首页看到经营概览', async ({ page }) => {
    test.setTimeout(90_000)
    await adminSignIn(page, 'dev-support', '/lookup/orders')
    const first = page.waitForResponse((response) => response.url().includes('/api/v1/ops/dashboard?days=30'))
    await page.goto(`${ADMIN}/`)
    const thirty = await (await first).json() as {
      trade: { paidOrders: number }, customers: { total: number }, organizations: { providers: number }
    }
    await expect(page.getByTestId('ops-dashboard')).toContainText('经营概览')
    await expect(page.getByTestId('kpi-orders')).toContainText(thirty.trade.paidOrders.toLocaleString('en-US'))
    await expect(page.getByTestId('kpi-customers')).toContainText(thirty.customers.total.toLocaleString('en-US'))
    await expect(page.getByTestId('kpi-providers')).toContainText(thirty.organizations.providers.toLocaleString('en-US'))
    await expect(page.getByTestId('dashboard-todo').getByRole('link', { name: '商家资料待审核' })).toHaveCount(0)
    await noErrorAlert(page)

    const seven = page.waitForResponse((response) => response.url().includes('/api/v1/ops/dashboard?days=7'))
    await page.getByTestId('dashboard-days').getByText('近 7 天').click()
    const week = await (await seven).json() as { fromDate: string, toDate: string, trade: { paidOrders: number } }
    await expect(page.getByTestId('ops-dashboard')).toContainText(`${week.fromDate} 至 ${week.toDate}`)
    await expect(page.getByTestId('kpi-orders')).toContainText(week.trade.paidOrders.toLocaleString('en-US'))
    await noErrorAlert(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.screenshot({ path: resolve(compareOutput, 'ops-dashboard.png'), fullPage: true })
  })
})
