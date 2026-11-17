import { expect, test, type Page } from '@playwright/test'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const SERVICES_PATH = '/api/v1/inspection/services'
const DETAIL_PATH = `${SERVICES_PATH}/${SERVICE_ID}`
const BOOKING_ROUTE = '/pages/service/inspection/booking'

function bookingUrl(query: string): string {
  return `/#${BOOKING_ROUTE}${query}`
}

const BOOKING_URL = bookingUrl(`?serviceId=${SERVICE_ID}&cityCode=370500`)

function collectRequests(page: Page, pathname: string): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === pathname) requests.push(url)
  })
  return requests
}

async function openBooking(page: Page): Promise<void> {
  await page.goto(BOOKING_URL)
  await expect(page.locator('.booking-row--time')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function openBookingFromHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-service').click()
  await page.getByTestId('portal-category-power').click()
  await page.locator('[data-testid^="catalog-service-"]').first().click()
  await expect(page.getByTestId('inspection-detail')).toBeVisible()
  // The button stays disabled until the detail has loaded, and Playwright clicks a disabled
  // uni-button without complaint; a click that lands too early does nothing.
  await page.locator('.service-detail-bottom-bar__primary:not([disabled])').click()
  await expect(page.locator('.booking-row--time')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function reloadBooking(page: Page): Promise<void> {
  await page.reload()
  await expect(page.locator('.booking-row--time')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

// A reload wipes window, so the mark surviving proves the document stayed.
async function markDocument(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { bookingBackMark?: boolean }).bookingBackMark = true
  })
}

async function expectDocumentKept(page: Page): Promise<void> {
  expect(await page.evaluate(() =>
    (window as unknown as { bookingBackMark?: boolean }).bookingBackMark,
  )).toBe(true)
}

async function backFromBooking(page: Page): Promise<void> {
  await markDocument(page)
  await page.locator('.inspection-booking__back').click()
}

async function expectDetail(page: Page, details: URL[]): Promise<void> {
  await expect(page.locator('.inspection-booking')).toHaveCount(0)
  await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
  await expectDocumentKept(page)
  expect(details.at(-1)?.searchParams.get('cityCode')).toBe('370500')
}

async function expectCatalog(page: Page): Promise<void> {
  await expect(page.locator('.inspection-booking')).toHaveCount(0)
  await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  await expectDocumentKept(page)
}

test.describe('M04 返回', () => {
  test('BKB-101 新标签打开深链，返回到详情且不重载', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBooking(page)
    await backFromBooking(page)

    await expectDetail(page, details)
  })

  test('BKB-102 深链刷新后，返回到详情且不重载', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBooking(page)
    await reloadBooking(page)
    await backFromBooking(page)

    await expectDetail(page, details)
  })

  test('BKB-103 正常路径进入后刷新，返回到详情且不重载', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBookingFromHome(page)
    await reloadBooking(page)
    await backFromBooking(page)

    await expectDetail(page, details)
  })

  test('BKB-104 正常路径不刷新，走页面栈返回', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBookingFromHome(page)
    const beforeBack = await page.evaluate(() => history.state?.position)
    await backFromBooking(page)

    await expectDetail(page, details)
    expect(await page.evaluate(() => history.state?.position)).toBe(beforeBack - 1)
  })

  test('BKB-105 回到详情后可以继续返回目录', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBooking(page)
    await backFromBooking(page)
    await expectDetail(page, details)
    await page.locator('.service-detail-header__action').first().click()

    await expectCatalog(page)
    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
  })

  test('BKB-106 连点两次返回，停在详情', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openBooking(page)
    await markDocument(page)
    await page.locator('.inspection-booking__back').dblclick()

    await expectDetail(page, details)
  })

  test('BKB-107 路由没有服务，返回目录', async ({ page }) => {
    await page.goto(bookingUrl(''))
    await expect(page.locator('.booking-state')).toBeVisible()

    await backFromBooking(page)

    await expectCatalog(page)
  })

  test('BKB-108 服务不存在，返回目录', async ({ page }) => {
    await page.goto(bookingUrl('?serviceId=missing-service&cityCode=370500'))
    await expect(page.locator('.booking-state')).toBeVisible()
    await page.waitForLoadState('networkidle')

    await backFromBooking(page)

    await expectCatalog(page)
    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
  })

  test('BKB-109 从站外跳入，返回留在应用内', async ({ page, baseURL }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await page.goto('data:text/html,<title>outside</title>')
    await page.evaluate((url) => {
      location.href = url
    }, `${baseURL}${BOOKING_URL}`)
    await expect(page.locator('.booking-row--time')).toBeVisible()
    await page.waitForLoadState('networkidle')
    await backFromBooking(page)

    await expectDetail(page, details)
  })
})
