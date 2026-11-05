import { expect, test, type Page } from '@playwright/test'

const ORDER_CENTER_URL = '/#/pages/mine/inspection-orders?stage=ALL'
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
const ACCEPTANCE_ORDER = {
  reservationId: '40000000-0000-4000-8000-000000000001',
  reservationNo: 'YY202607310001',
  serviceId: '30000000-0000-4000-8000-000000000001',
  serviceTitle: '输电线路无人机巡检',
  cityCode: '370500',
  locationSummary: '东营市示范线路',
  preferredServiceDate: '2026-08-02',
  submittedAt: '2026-07-31T08:00:00Z',
  stage: 'AWAITING_ACCEPTANCE',
  reservationStatus: 'SUBMITTED',
  serviceOrderId: '60000000-0000-4000-8000-000000000001',
  currency: 'CNY',
}

const SUBMITTED_ROUTE = `/pages/service/inspection/booking-submitted?reservationId=${ACCEPTANCE_ORDER.reservationId}`

declare const uni: {
  navigateTo(options: { url: string }): void
  navigateBack(options: { from: string; success(): void; fail(): void }): void
  removeInterceptor(name: string): void
}
declare function getCurrentPages(): Array<{ route: string }>

const orderCenter = (page: Page) => page.locator('.inspection-orders__header')
const backButton = (page: Page) => page.locator('[aria-label="返回我的"]')

async function waitForOrderCenter(page: Page): Promise<void> {
  await expect(orderCenter(page)).toBeVisible()
  await expect(page.getByText('正在加载订单…')).toHaveCount(0)
  await page.waitForLoadState('networkidle')
}

async function openOrderCenter(page: Page): Promise<void> {
  await page.goto(ORDER_CENTER_URL)
  await waitForOrderCenter(page)
}

async function openOrderCenterFromMine(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await page.getByTestId('root-tab-mine').click()
  await page.locator('.platform-mine__view-all').click()
  await waitForOrderCenter(page)
}

// Booking replaces M04 with the submitted page, so the pages the user came through
// stay behind it. Home stands in for them here.
async function openOrderCenterThroughBooking(page: Page): Promise<void> {
  await page.route('**/api/v1/inspection/reservations/**', (route) =>
    route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found' } }),
  )
  await page.goto('/#/pages/home/index')
  await page.evaluate((url) => uni.navigateTo({ url }), SUBMITTED_ROUTE)
  await page.locator('[aria-label="返回"]').click()
  await waitForOrderCenter(page)
}

async function reloadOrderCenter(page: Page): Promise<void> {
  await page.reload()
  await waitForOrderCenter(page)
}

// A reload wipes window, so the mark surviving proves the document stayed.
async function markDocument(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { orderCenterBackMark?: boolean }).orderCenterBackMark = true
  })
}

async function backFromOrderCenter(page: Page): Promise<void> {
  await markDocument(page)
  await backButton(page).click()
}

async function expectMine(page: Page): Promise<void> {
  await expect(orderCenter(page)).toHaveCount(0)
  await expect(page.getByTestId('mine-page')).toBeVisible()
  await expect(page.getByTestId('root-tab-mine')).toHaveClass(/root-tab-bar__item--active/)
  expect(await page.evaluate(() =>
    (window as unknown as { orderCenterBackMark?: boolean }).orderCenterBackMark,
  )).toBe(true)
}

test.describe('M08 返回', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('inspection.accessToken', 'order-center-back')
    })
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({ json: EMPTY_ORDERS }),
    )
  })

  test('OCB-101 新标签打开深链，返回到“我的”且不重载', async ({ page }) => {
    await openOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-102 深链刷新后，返回到“我的”且不重载', async ({ page }) => {
    await openOrderCenter(page)
    await reloadOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-103 正常路径进入后刷新，返回到“我的”且不重载', async ({ page }) => {
    await openOrderCenterFromMine(page)
    await reloadOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-104 正常路径不刷新，走页面栈返回', async ({ page }) => {
    await openOrderCenterFromMine(page)
    const beforeBack = await page.evaluate(() => history.state?.position)
    await backFromOrderCenter(page)

    await expectMine(page)
    expect(await page.evaluate(() => history.state?.position)).toBe(beforeBack - 1)
  })

  test('OCB-105 M07 没有上一页时回到 M08，再返回到“我的”', async ({ page }) => {
    await page.goto('/#/pages/service/inspection/delivery')
    await page.locator('[aria-label="返回"]').click()
    await waitForOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-106 我的 → 最近订单 → M07 → 返回 M08 → 返回到“我的”', async ({ page }) => {
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({ json: { ...EMPTY_ORDERS, items: [ACCEPTANCE_ORDER], total: 1 } }),
    )
    await page.route('**/api/v1/inspection/service-orders/**', (route) =>
      route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found' } }),
    )
    await page.goto('/#/pages/home/index')
    await page.getByTestId('root-tab-mine').click()
    await page.locator('.platform-mine__order').click()
    await page.locator('[aria-label="返回"]').click()
    await waitForOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-107 连点两次返回，停在“我的”', async ({ page }) => {
    await openOrderCenter(page)
    await markDocument(page)
    await backButton(page).dblclick()

    await expectMine(page)
  })

  test('OCB-108 回到“我的”后再进 M08，走页面栈返回', async ({ page }) => {
    await openOrderCenter(page)
    await backFromOrderCenter(page)
    await expectMine(page)
    await page.locator('.platform-mine__view-all').click()
    await waitForOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-109 从站外跳入，返回留在应用内', async ({ page, baseURL }) => {
    await page.goto('data:text/html,<title>outside</title>')
    await page.evaluate((url) => {
      location.href = url
    }, `${baseURL}${ORDER_CENTER_URL}`)
    await waitForOrderCenter(page)
    await backFromOrderCenter(page)

    await expectMine(page)
  })

  test('OCB-110 下完单进入，返回到“我的”，不退回下单页面', async ({ page }) => {
    await openOrderCenterThroughBooking(page)
    await backFromOrderCenter(page)

    await expectMine(page)
    expect(await page.evaluate(() => getCurrentPages().map(({ route }) => route)))
      .toEqual(['pages/mine/index'])
  })

  test('OCB-111 下完单进入，App 返回键同样到“我的”', async ({ page }) => {
    await openOrderCenterThroughBooking(page)
    await markDocument(page)
    await page.evaluate(() => {
      // App has no H5 back guard, and the guard would drop the nested navigateBack.
      uni.removeInterceptor('navigateBack')
      uni.navigateBack({ from: 'backbutton', success() {}, fail() {} })
    })

    await expectMine(page)
  })
})
