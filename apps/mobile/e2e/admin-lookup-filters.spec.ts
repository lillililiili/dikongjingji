import { expect, test, type Page } from '@playwright/test'
import { adminSignIn } from './support/admin-session'
import { testAdmin } from './support/test-stack'
import { SOURCE_LABELS, STATUS_LABELS, type OrderSummary, type UserSummary } from '../../admin/src/features/lookup/lookup-api'

test.use({ viewport: { width: 1366, height: 950 }, trace: 'off' })
test.setTimeout(90_000)

type LookupRow = OrderSummary | UserSummary
type ExpectedQuery = Record<string, string>

async function read<T extends LookupRow>(page: Page, list: 'orders' | 'users', query: ExpectedQuery, action: () => Promise<unknown>) {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/ops/' + list)
  await action()
  const r = await response
  expect(r.status()).toBe(200)
  expect(Object.fromEntries(new URL(r.url()).searchParams)).toEqual(query)
  const result = await r.json() as { page: number, size: number, total: number, items: T[] }
  expect(result.page).toBe(Number(query.page))
  expect(result.size).toBe(Number(query.size))
  const rows = page.locator('.paged-table .el-table__row')
  await expect(rows).toHaveCount(result.items.length)
  const show = (item: LookupRow) => 'orderNo' in item ? item.orderNo : item.displayName
  await expect(rows.getByRole('button')).toHaveText(result.items.map(show))
  return result
}

async function changeSize(page: Page) {
  await page.locator('.el-pagination__sizes .el-select').click()
  await page.getByRole('option', { name: /^10\s*条\/页$/ }).click()
}

async function choose(page: Page, index: number, label: string) {
  await page.locator('.toolbar .el-select').nth(index).click()
  await page.getByRole('option', { name: label, exact: true }).click()
}

async function clearChoice(page: Page, index: number) {
  const select = page.locator('.toolbar .el-select').nth(index)
  await select.hover()
  await select.locator('.el-select__clear').click()
}

test('客服查询订单：翻页、来源、状态、下单日期、清空，返回后保留正在查看的结果', async ({ page }) => {
  const list = (q: ExpectedQuery, act: () => Promise<unknown>) => read<OrderSummary>(page, 'orders', q, act)
  const base = { page: '1', size: '10' }
  await list({ page: '1', size: '20' }, () => adminSignIn(page, 'dev-support', '/lookup/orders'))
  const initial = await list(base, () => changeSize(page))
  expect(initial.items.length, '隔离库需有交易业务记录以核验详情').toBeGreaterThan(0)
  if (initial.total > 10) {
    await list({ ...base, page: '2' }, () => page.locator('.el-pagination .btn-next').click())
  } else {
    await expect(page.locator('.el-pagination .btn-next')).toBeDisabled()
  }

  const source = initial.items[0].source
  const sourcePage = await list({ ...base, source }, () => choose(page, 0, SOURCE_LABELS[source]))
  expect(sourcePage.items.every(item => item.source === source)).toBe(true)
  const status = sourcePage.items[0].status
  const filtered = await list({ ...base, source, status }, () => choose(page, 1, STATUS_LABELS[status]))
  expect(filtered.items.every(item => item.source === source && item.status === status)).toBe(true)

  const today = await page.evaluate(() => new Intl.DateTimeFormat('sv-SE').format(new Date()))
  const range = await list({ ...base, source, status, from: today, to: today }, async () => {
    await page.getByPlaceholder('下单开始').click()
    const date = page.locator('.el-date-range-picker td.today:visible').first()
    await date.click()
    await date.click()
  })
  expect(range.items.every(item => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(
    new Date(item.createdAt)) === today)).toBe(true)
  await list({ ...base, source, status }, async () => {
    await page.locator('.el-date-editor').hover()
    await page.locator('.el-range__close-icon').click()
  })
  const selected = filtered.items[0]
  await page.getByRole('button', { name: selected.orderNo, exact: true }).click()
  await expect(page.getByTestId('order-lookup-basics')).toBeVisible()
  await list({ ...base, source, status }, () => page.getByRole('button', { name: '返回查询' }).click())
  await list({ ...base, source }, () => clearChoice(page, 1))
  await list(base, () => clearChoice(page, 0))
  await page.getByTestId('order-lookup-q').fill(selected.orderNo)
  const exact = await list({ ...base, q: selected.orderNo }, () => page.getByTestId('order-lookup-search').click())
  expect(exact.items.map(item => item.id)).toEqual([selected.id])
  await list(base, async () => {
    await page.getByTestId('order-lookup-q').hover()
    await page.locator('.toolbar .el-input__clear').click()
  })
  const beforeMenu = await list({ ...base, source }, () => choose(page, 0, SOURCE_LABELS[source]))
  await page.getByRole('button', { name: beforeMenu.items[0].orderNo, exact: true }).click()
  await expect(page.getByTestId('order-lookup-basics')).toBeVisible()
  const all = await list({ page: '1', size: '20' }, () => page.getByRole('menuitem', { name: '订单查询', exact: true }).click())
  await expect(page.getByTestId('order-lookup-q')).toHaveValue('')
  const withBuyer = all.items[0]
  await page.getByRole('button', { name: withBuyer.orderNo, exact: true }).click()
  await page.getByTestId('order-lookup-basics').getByRole('button').click()
  await expect(page.getByTestId('user-lookup-basics')).toContainText(withBuyer.buyer.userId)
  const buyerQuery = { page: '1', size: '20', buyerId: withBuyer.buyer.userId }
  const buyersOrders = await list(buyerQuery, () => page.getByTestId('user-lookup-orders').click())
  expect(buyersOrders.items.length).toBeGreaterThan(0)
  expect(buyersOrders.items.every(item => item.buyer.userId === withBuyer.buyer.userId)).toBe(true)
  await page.getByRole('button', { name: buyersOrders.items[0].orderNo, exact: true }).click()
  await expect(page.getByTestId('order-lookup-basics')).toBeVisible()
  await list(buyerQuery, () => page.getByRole('button', { name: '返回查询' }).click())
  await list({ page: '1', size: '20', status: 'REFUND_FAILED' },
    () => page.goto(testAdmin + '/lookup/orders?status=REFUND_FAILED'))
})

test('客服查询用户：模糊查、翻页、账号精确查、从详情返回及清空', async ({ page }) => {
  const list = (q: ExpectedQuery, act: () => Promise<unknown>) => read<UserSummary>(page, 'users', q, act)
  const base = { page: '1', size: '10' }
  await list({ page: '1', size: '20' }, () => adminSignIn(page, 'dev-support', '/lookup/users'))
  await list(base, () => changeSize(page))
  const box = page.getByTestId('user-lookup-q')
  await box.fill('用户')
  const first = await list({ ...base, q: '用户' }, () => box.press('Enter'))
  expect(first.items.every(item => item.displayName.includes('用户'))).toBe(true)
  const pageNumber = first.total > 10 ? '2' : '1'
  const query = { ...base, page: pageNumber, q: '用户' }
  const matches = pageNumber === '2'
    ? await list(query, () => page.locator('.el-pagination .btn-next').click())
    : first
  expect(matches.items.length, '隔离库需包含手机号注册的测试账号').toBeGreaterThan(0)
  const selected = matches.items[0]
  await page.locator('.el-table__row').first().getByRole('button').click()
  await expect(page.getByTestId('user-lookup-basics')).toContainText(selected.id)
  await list(query, () => page.getByRole('button', { name: '返回查询' }).click())
  await box.fill(selected.id)
  const exact = await list({ ...base, q: selected.id }, () => page.getByTestId('user-lookup-search').click())
  expect(exact.items.map(item => item.id)).toEqual([selected.id])
  await page.locator('.el-table__row').first().getByRole('button').click()
  await expect(page.getByTestId('user-lookup-basics')).toContainText(selected.id)
  await list({ ...base, q: selected.id }, () => page.goBack())
  await list(base, async () => {
    await box.hover()
    await page.locator('.toolbar .el-input__clear').click()
  })
  await box.fill(selected.id)
  await list({ ...base, q: selected.id }, () => page.getByTestId('user-lookup-search').click())
  await page.locator('.el-table__row').first().getByRole('button').click()
  const byBuyer = await read<OrderSummary>(page, 'orders', { page: '1', size: '20', buyerId: selected.id },
    () => page.getByTestId('user-lookup-orders').click())
  expect(byBuyer.items.every(item => item.buyer.userId === selected.id)).toBe(true)
  await read<OrderSummary>(page, 'orders', { page: '1', size: '20', buyerId: selected.id, source: 'MALL' },
    () => choose(page, 0, '商城'))
  await read<OrderSummary>(page, 'orders', { page: '1', size: '20', source: 'MALL' },
    () => page.getByRole('button', { name: '看全部' }).click())
  await expect(page.getByRole('button', { name: '看全部' })).toHaveCount(0)
})
