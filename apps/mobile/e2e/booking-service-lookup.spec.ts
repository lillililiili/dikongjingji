import { expect, test, type Page, type Route } from '@playwright/test'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const SERVICES_PATH = '/api/v1/inspection/services'
const DETAIL_PATH = `${SERVICES_PATH}/${SERVICE_ID}`
const DETAIL_URL = `/#/pages/service/inspection/detail?serviceId=${SERVICE_ID}&cityCode=370500`
const BOOKING_URL = `/#/pages/service/inspection/booking?serviceId=${SERVICE_ID}&cityCode=370500`
const UNAVAILABLE_COPY = '当前城市暂无这项服务，或服务已下架'

// Stands in for a city where this service ranks after the first page of the list.
function hideServiceFromList(page: Page): Promise<void> {
  return page.route(`**${SERVICES_PATH}?*`, (route) => route.fulfill({
    status: 200,
    json: { page: 1, size: 50, total: 60, hasMore: true, items: [] },
  }))
}

async function withoutBookableItems(route: Route): Promise<void> {
  const response = await route.fetch()
  await route.fulfill({
    response,
    json: { ...await response.json(), inspectionItems: [] },
  })
}

function collectRequests(page: Page, pathname: string): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === pathname) requests.push(url)
  })
  return requests
}

test.describe('M04 查服务', () => {
  test('BKL-101 服务排在列表第一页之后时，M04 仍能打开', async ({ page }) => {
    await hideServiceFromList(page)

    await page.goto(BOOKING_URL)

    await expect(page.locator('.booking-row--time')).toBeVisible()
    await expect(page.locator('.booking-item').first()).toBeVisible()
    await expect(page.locator('.booking-state')).toHaveCount(0)
  })

  test('BKL-102 从 M03 进入时，排名靠后的服务也能预约', async ({ page }) => {
    await hideServiceFromList(page)
    await page.goto(DETAIL_URL)
    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()

    await page.locator('.service-detail-bottom-bar__primary').click()

    await expect(page.locator('.booking-row--time')).toBeVisible()
    expect(new URL(page.url()).hash).toContain(`serviceId=${SERVICE_ID}`)
  })

  test('BKL-103 M04 只读这项服务，不再读列表', async ({ page }) => {
    const details = collectRequests(page, DETAIL_PATH)
    const lists = collectRequests(page, SERVICES_PATH)

    await page.goto(BOOKING_URL)
    await expect(page.locator('.booking-row--time')).toBeVisible()
    await page.waitForLoadState('networkidle')

    expect(details.at(-1)?.searchParams.get('cityCode')).toBe('370500')
    expect(lists).toHaveLength(0)
  })

  test('BKL-104 M04 的巡检项目和 M03 的服务内容一致', async ({ page }) => {
    await page.goto(DETAIL_URL)
    await expect(page.locator('.inspection-scope__name').first()).toBeVisible()
    const offered = await page.locator('.inspection-scope__name').allInnerTexts()

    await page.locator('.service-detail-bottom-bar__primary').click()
    await expect(page.locator('.booking-item').first()).toBeVisible()

    const items = await page.locator('.booking-item').allInnerTexts()
    expect(items.map((item) => item.trim())).toEqual(offered.map((item) => item.trim()))
  })

  test('BKL-105 服务没有可预约项时，M03 不能预约', async ({ page }) => {
    await page.route(`**${DETAIL_PATH}?*`, withoutBookableItems)

    await page.goto(DETAIL_URL)

    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
    const reserve = page.locator('.service-detail-bottom-bar__primary')
    await expect(reserve).toHaveAttribute('disabled', 'true')

    await reserve.click()

    await page.waitForTimeout(500)
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await expect(page.locator('.inspection-booking')).toHaveCount(0)
  })

  test('BKL-106 直接打开 M04 时，没有可预约项也不给空表单', async ({ page }) => {
    await page.route(`**${DETAIL_PATH}?*`, withoutBookableItems)

    await page.goto(BOOKING_URL)

    await expect(page.locator('.booking-state')).toContainText(UNAVAILABLE_COPY)
    await expect(page.locator('.booking-state__action')).toHaveText('看看其他服务')
    await expect(page.locator('.booking-row--time')).toHaveCount(0)
    await expect(page.locator('.inspection-booking__footer')).toHaveCount(0)
  })

  test('BKL-107 M03 打开后项目才被停用，M04 仍拦得住', async ({ page }) => {
    await page.goto(DETAIL_URL)
    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
    await page.route(`**${DETAIL_PATH}?*`, withoutBookableItems)

    await page.locator('.service-detail-bottom-bar__primary').click()

    await expect(page.locator('.booking-state')).toContainText(UNAVAILABLE_COPY)
    await expect(page.locator('.booking-row--time')).toHaveCount(0)
  })
})
