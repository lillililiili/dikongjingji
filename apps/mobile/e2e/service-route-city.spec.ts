import { expect, test, type Page } from '@playwright/test'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const SERVICES_PATH = '/api/v1/inspection/services'
const DETAIL_PATH = `${SERVICES_PATH}/${SERVICE_ID}`
const SLOTS_PATH = `${DETAIL_PATH}/available-time-slots`
const CITIES_PATH = '/api/v1/platform/cities'
const DETAIL_ROUTE = '/pages/service/inspection/detail'
const BOOKING_ROUTE = '/pages/service/inspection/booking'
const CATALOG_ROUTE = '/pages/service/inspection/catalog'

function detailUrl(cityCode: string): string {
  return `/#${DETAIL_ROUTE}?serviceId=${SERVICE_ID}&cityCode=${cityCode}`
}

function bookingUrl(cityCode: string): string {
  return `/#${BOOKING_ROUTE}?serviceId=${SERVICE_ID}&cityCode=${cityCode}`
}

function collectRequests(page: Page, pathname: string): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === pathname) requests.push(url)
  })
  return requests
}

async function openDetail(page: Page, cityCode: string): Promise<void> {
  await page.goto(detailUrl(cityCode))
  await expect(page.getByTestId('inspection-detail')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function openBooking(page: Page, cityCode: string): Promise<void> {
  await page.goto(bookingUrl(cityCode))
  await expect(page.locator('.booking-row--time')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function backFromDetail(page: Page): Promise<void> {
  await page.locator('.service-detail-header__action').first().click()
  await expect(page.getByTestId('inspection-catalog')).toBeVisible()
}

async function reserveFromDetail(page: Page): Promise<void> {
  await page.locator('.service-detail-bottom-bar__primary').click()
  await expect(page.locator('.booking-row--time')).toBeVisible()
}

async function openCatalogFromHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-service').click()
  await page.getByTestId('portal-category-power').click()
  await expect(
    page.locator('[data-testid^="catalog-service-"]').first(),
  ).toBeVisible()
}

async function chooseCity(page: Page, name: string): Promise<void> {
  await page.getByTestId('catalog-city-select').click()
  await expect(page.locator('.city-sheet')).toBeVisible()
  await page.locator('.city-sheet__city', { hasText: name }).first().click()
  await expect(page.getByTestId('catalog-city-select')).toContainText(name)
}

// M04 reads time slots through the signed-in client. These cases only check
// which city it asks for, so the session and the response can be stand-ins.
async function stubTimeSlots(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('inspection.accessToken', 'route-city-e2e')
  })
  await page.route(`**${SLOTS_PATH}?*`, (route) => route.fulfill({ json: [] }))
}

test.describe('M03、M04 从路由恢复城市', () => {
  test('SRV-101 M03 深链返回目录，城市栏是城市名', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)

    await openDetail(page, '370500')
    await backFromDetail(page)

    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
    expect(details.at(-1)?.searchParams.get('cityCode')).toBe('370500')
  })

  test('SRV-102 M04 深链后进入目录，城市栏是城市名', async ({ page }) => {
    await openBooking(page, '370500')

    await page.evaluate((route) => {
      location.hash = `#${route}?categoryCode=POWER`
    }, CATALOG_ROUTE)

    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
  })

  test('SRV-103 用户切城后前进回 M03，沿用切换后的城市', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)
    const services = collectRequests(page, SERVICES_PATH)

    await openCatalogFromHome(page)
    await page.locator('[data-testid^="catalog-service-"]').first().click()
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await backFromDetail(page)
    await chooseCity(page, '济南市')

    await page.goForward()
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(details.at(-1)?.searchParams.get('cityCode')).toBe('370100')

    await backFromDetail(page)
    await page.getByTestId('catalog-back').click()
    await page.getByTestId('portal-category-power').click()
    await expect(page.getByTestId('catalog-city-select')).toContainText('济南市')
    await page.waitForLoadState('networkidle')
    expect(services.at(-1)?.searchParams.get('cityCode')).toBe('370100')
  })

  test('SRV-104 共享城市与 M04 路由不同，服务和时段都用共享城市', async ({ page }) => {
    await stubTimeSlots(page)
    const services = collectRequests(page, SERVICES_PATH)
    const slots = collectRequests(page, SLOTS_PATH)

    await openCatalogFromHome(page)
    await chooseCity(page, '济南市')
    await page.evaluate((hash) => {
      location.hash = hash
    }, bookingUrl('370500').slice(1))
    await expect(page.locator('.booking-row--time')).toBeVisible()
    await page.waitForLoadState('networkidle')
    await page.locator('.booking-row--time').click()

    await expect.poll(() => slots.length).toBeGreaterThan(0)
    expect(services.at(-1)?.searchParams.get('cityCode')).toBe('370100')
    expect(slots.at(-1)?.searchParams.get('cityCode')).toBe('370100')
  })

  test('SRV-105 城市编码不在目录时不写入共享城市', async ({ page }) => {
    await openDetail(page, '999999')
    await backFromDetail(page)

    await expect(page.getByTestId('catalog-city-select')).toContainText('选择城市')
    await expect(page.getByTestId('catalog-city-select')).not.toContainText('999999')
  })

  test('SRV-106 城市目录失败时，详情、预约和时段仍用路由城市', async ({ page }) => {
    await stubTimeSlots(page)
    await page.route(`**${CITIES_PATH}`, (route) =>
      route.abort('internetdisconnected'))
    const slots = collectRequests(page, SLOTS_PATH)

    await openDetail(page, '370500')
    await reserveFromDetail(page)
    await page.waitForLoadState('networkidle')
    expect(new URL(page.url()).hash).toContain('cityCode=370500')

    await page.locator('.booking-row--time').click()

    await expect.poll(() => slots.length).toBeGreaterThan(0)
    expect(slots.at(-1)?.searchParams.get('cityCode')).toBe('370500')
  })

  test('SRV-107 正常路径不请求城市目录', async ({ page }) => {
    await openCatalogFromHome(page)
    const cities = collectRequests(page, CITIES_PATH)

    await page.locator('[data-testid^="catalog-service-"]').first().click()
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await reserveFromDetail(page)
    await page.waitForLoadState('networkidle')

    expect(cities).toHaveLength(0)
    expect(new URL(page.url()).hash).toContain('cityCode=370500')
  })
})
