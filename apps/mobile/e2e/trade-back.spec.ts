import { expect, test, type Page } from '@playwright/test'

const ID = '40000000-0000-4000-8000-000000000001'
const SUBMITTED_ROUTE = `/pages/service/inspection/booking-submitted?reservationId=${ID}`
const PLAN_ROUTE = `/pages/service/inspection/plan-confirmation?planId=${ID}`
const FULFILLMENT_ROUTE = `/pages/service/inspection/fulfillment?serviceOrderId=${ID}`
const EMPTY_ORDERS = {
  items: [],
  page: 1,
  size: 20,
  total: 0,
  hasMore: false,
  stageCounts: {
    AWAITING_PLAN: 0,
    AWAITING_CONFIRMATION: 0,
    IN_PROGRESS: 0,
    AWAITING_ACCEPTANCE: 0,
    COMPLETED: 0,
  },
}

declare const uni: {
  navigateTo(options: { url: string }): void
  navigateBack(options: { from: string; success(): void; fail(): void }): void
  removeInterceptor(name: string): void
}
declare function getCurrentPages(): Array<{ route: string }>

const header = (page: Page, name: string) => page.locator(`.${name}__header`).filter({ visible: true })
const submitted = (page: Page) => header(page, 'booking-submitted')
const plan = (page: Page) => header(page, 'plan-confirmation')
const fulfillment = (page: Page) => header(page, 'fulfillment')
const orderCenter = (page: Page) => header(page, 'inspection-orders')

async function open(page: Page, route: string): Promise<void> {
  await page.evaluate((url) => uni.navigateTo({ url }), route)
}

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('root-tab-mine')).toBeVisible()
}

async function openMine(page: Page): Promise<void> {
  await openHome(page)
  await page.getByTestId('root-tab-mine').click()
  await expect(page.getByTestId('mine-page')).toBeVisible()
}

async function pressTopBack(page: Page): Promise<void> {
  await page.locator('[aria-label="返回"]').filter({ visible: true }).click()
}

// The same call App's back key makes. App has no H5 back guard, and the guard would
// drop the navigateBack nested inside onBackPress.
async function pressAppBack(page: Page): Promise<void> {
  await page.evaluate(() => {
    uni.removeInterceptor('navigateBack')
    uni.navigateBack({ from: 'backbutton', success() {}, fail() {} })
  })
}

async function currentRoutes(page: Page): Promise<string[]> {
  return page.evaluate(() => getCurrentPages().map(({ route }) => route))
}

test.describe('交易页面返回', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('inspection.accessToken', 'trade-back')
    })
    // The landing page is what matters here, and back must work on a failed load too.
    await page.route('**/api/v1/inspection/**', (route) =>
      route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found' } }),
    )
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({ json: EMPTY_ORDERS }),
    )
  })

  test('TB-101 方案确认从提交结果页进入，顶部返回回提交结果页', async ({ page }) => {
    await openHome(page)
    await open(page, SUBMITTED_ROUTE)
    await open(page, PLAN_ROUTE)
    await expect(plan(page)).toBeVisible()
    await pressTopBack(page)

    await expect(submitted(page)).toBeVisible()
    await expect(orderCenter(page)).toHaveCount(0)
  })

  test('TB-102 方案确认从提交结果页进入，App 返回键回提交结果页', async ({ page }) => {
    await openHome(page)
    await open(page, SUBMITTED_ROUTE)
    await open(page, PLAN_ROUTE)
    await expect(plan(page)).toBeVisible()
    await pressAppBack(page)

    await expect(submitted(page)).toBeVisible()
    await expect(orderCenter(page)).toHaveCount(0)
  })

  test('TB-103 方案确认从“我的”进入，顶部返回到订单中心', async ({ page }) => {
    await openMine(page)
    await open(page, PLAN_ROUTE)
    await expect(plan(page)).toBeVisible()
    await pressTopBack(page)

    await expect(orderCenter(page)).toBeVisible()
  })

  test('TB-104 方案确认从“我的”进入，App 返回键也到订单中心', async ({ page }) => {
    await openMine(page)
    await open(page, PLAN_ROUTE)
    await expect(plan(page)).toBeVisible()
    await pressAppBack(page)

    await expect(orderCenter(page)).toBeVisible()
  })

  test('TB-105 提交结果页 App 返回键到订单中心，不退回下单页面', async ({ page }) => {
    await openHome(page)
    await open(page, SUBMITTED_ROUTE)
    await expect(submitted(page)).toBeVisible()
    await pressAppBack(page)

    await expect(orderCenter(page)).toBeVisible()
  })

  test('TB-106 履约页 App 返回键到订单中心，不退回提交结果页', async ({ page }) => {
    await openHome(page)
    await open(page, SUBMITTED_ROUTE)
    await open(page, FULFILLMENT_ROUTE)
    await expect(fulfillment(page)).toBeVisible()
    await pressAppBack(page)

    await expect(orderCenter(page)).toBeVisible()
    await expect(submitted(page)).toHaveCount(0)
  })

  test('TB-107 从订单中心进入的页面，App 返回键退回同一个订单中心', async ({ page }) => {
    await openMine(page)
    await page.locator('.platform-mine__view-all').click()
    await expect(orderCenter(page)).toBeVisible()
    const routes = await currentRoutes(page)

    for (const [route, pageHeader] of [
      [SUBMITTED_ROUTE, submitted],
      [PLAN_ROUTE, plan],
      [FULFILLMENT_ROUTE, fulfillment],
    ] as const) {
      await open(page, route)
      await expect(pageHeader(page)).toBeVisible()
      await pressAppBack(page)
      await expect(orderCenter(page)).toBeVisible()
      await expect.poll(() => currentRoutes(page)).toEqual(routes)
    }
  })
})
