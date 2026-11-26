import { expect, test, type Page } from '@playwright/test'
import { formatBigMoney, formatCount, type Dashboard } from '../../admin/src/features/dashboard/dashboard-api'
import { testAdmin } from './support/test-stack'

test.use({ trace: 'off', screenshot: 'off', viewport: { width: 1366, height: 900 } })

const dashboard = '/api/v1/ops/dashboard'
const todos = [
  ['qualificationReviews', '机构资质待审核', '/organizations'],
  ['merchantProfileReviews', '商家资料待审核', '/merchant-profiles'],
  ['providerProfileReviews', '服务商资料待审核', '/provider-profiles'],
  ['pendingFeedback', '举报与反馈待处理', null], // 只有平台客服处理反馈
  ['conversationsAwaitingReply', '客服会话待回复', '/support/conversations'],
  ['refundFailedOrders', '退款异常的订单', '/lookup/orders?status=REFUND_FAILED'],
] as const

async function readPeriod(page: Page, days: number, action: () => Promise<unknown>) {
  const response = page.waitForResponse(res => {
    const url = new URL(res.url())
    return url.pathname === dashboard && url.searchParams.get('days') === String(days)
  })
  await action()
  const result = await response
  expect(result.status()).toBe(200)
  const data = await result.json() as Dashboard
  expect(data.days).toBe(days)
  expect(data.daily).toHaveLength(days)
  expect(data.daily[0].date).toBe(data.fromDate)
  expect(data.daily.at(-1)!.date).toBe(data.toDate)
  await expect(page.getByTestId('ops-dashboard')).toContainText(data.fromDate + ' 至 ' + data.toDate)
  return data
}

test('运营经营概览按统计期核对实付、退款、趋势和待办，切期失败可恢复', async ({ page }, testInfo) => {
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password, '需要隔离栈运营账号')
  test.setTimeout(90_000)
  await page.goto(testAdmin + '/login')
  await page.getByLabel('用户名').fill('dev-operator')
  await page.locator('input[autocomplete=current-password]').fill(password!)
  await readPeriod(page, 30, () => page.locator('form').getByRole('button', { name: '登录' }).click())

  const ranges = page.getByTestId('dashboard-days')
  const board = page.getByTestId('ops-dashboard')
  await readPeriod(page, 7, () => ranges.getByText('近 7 天').click())
  await page.route('**/api/v1/ops/dashboard?days=90', route => route.abort('connectionfailed'), { times: 1 })
  await ranges.getByText('近 90 天').click()
  await expect(board.getByRole('alert')).toBeVisible()
  await expect(page.getByTestId('dashboard-hero')).toHaveCount(0)
  await readPeriod(page, 90, () => board.getByRole('button', { name: '重新加载' }).click())
  await expect(board.getByRole('alert')).toHaveCount(0)
  const data = await readPeriod(page, 90, () => board.getByRole('button', { name: '刷新', exact: true }).click())

  const { trade } = data
  expect(trade.netPaidMinor).toBe(trade.paidMinor - trade.refundedMinor)
  expect(trade.averageOrderMinor).toBe(trade.paidOrders ? Math.trunc(trade.netPaidMinor / trade.paidOrders) : 0)
  const values = [
    ['net', formatBigMoney(trade.netPaidMinor)], ['average', formatBigMoney(trade.averageOrderMinor)],
    ['orders', formatCount(trade.paidOrders)], ['buyers', formatCount(trade.payingCustomers)],
    ['customers', formatCount(data.customers.total)], ['new', formatCount(data.customers.newInPeriod)],
    ['active', formatCount(data.customers.activeInPeriod)], ['providers', formatCount(data.organizations.providers)],
    ['merchants', formatCount(data.organizations.collectingMerchants)],
  ]
  for (const [key, value] of values) {
    await expect(page.getByTestId('kpi-' + key).locator('strong')).toHaveText(value)
  }
  await expect(page.getByTestId('dashboard-hero').locator('strong')).toHaveText([
    formatBigMoney(trade.lifetimeNetPaidMinor), formatBigMoney(trade.paidMinor),
    formatBigMoney(trade.refundedMinor), formatCount(data.supply.openCities),
  ])

  const latest = data.daily.at(-1)!
  for (const metric of ['付款订单', '新增客户', '净成交额']) {
    await page.getByTestId('trend-metric').getByText(metric).click()
    const chart = page.getByRole('img', { name: '每日' + metric, exact: true })
    await chart.scrollIntoViewIfNeeded()
    const bounds = await chart.boundingBox()
    expect(bounds).toBeTruthy()
    await chart.hover({ position: { x: Math.floor(bounds!.width) - 2, y: 80 } })
    const tip = page.getByTestId('trend-tooltip')
    await expect(tip.locator('strong')).toHaveText(latest.date)
    await expect(tip.locator('span')).toHaveText(['净成交额 ' + formatBigMoney(latest.netPaidMinor),
      '付款订单 ' + formatCount(latest.paidOrders), '新增客户 ' + formatCount(latest.newCustomers)])
  }

  let current = data
  const destinations: string[] = []
  for (const [key, label, href] of todos) {
    const item = page.getByTestId('dashboard-todo').getByRole('listitem').filter({ hasText: label })
    await expect(item.locator('strong')).toHaveText(formatCount(current.todo[key]))
    const link = item.getByRole('link', { name: label, exact: true })
    if (!href || current.todo[key] === 0) {
      await expect(link).toHaveCount(0)
      continue
    }
    await expect(link).toHaveAttribute('href', href)
    await link.click()
    await expect(page).toHaveURL(testAdmin + href)
    await expect(page.locator('.layout__main h1').first()).toBeVisible()
    await expect(page.locator('.layout__main .el-alert--error')).toHaveCount(0)
    destinations.push(href)
    current = await readPeriod(page, 30, () => page.locator('.layout__brand').click())
  }
  await testInfo.attach('待办跳转实测', { body: JSON.stringify({ counts: data.todo, destinations }), contentType: 'application/json' })
})
