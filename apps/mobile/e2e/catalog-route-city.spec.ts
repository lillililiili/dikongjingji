import { expect, test, type Page } from '@playwright/test'

const SERVICES_PATH = '/api/v1/inspection/services'
const CITIES_PATH = '/api/v1/platform/cities'
const CATALOG_ROUTE = '/#/pages/service/inspection/catalog'
const NETWORK_ERROR = '巡检服务加载失败，请检查网络后重试'

function collectRequests(page: Page, pathname: string): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === pathname) requests.push(url)
  })
  return requests
}

async function expectCatalogLoaded(page: Page, cityName: string): Promise<void> {
  await expect(
    page.locator('[data-testid^="catalog-service-"]').first(),
  ).toBeVisible()
  await expect(page.getByTestId('catalog-city-select')).toContainText(cityName)
  await expect(page.getByText(NETWORK_ERROR)).toHaveCount(0)
}

async function expectCityPrompt(page: Page): Promise<void> {
  await expect(page.locator('.catalog-state__title')).toHaveText('请先选择城市')
  await expect(page.getByText(NETWORK_ERROR)).toHaveCount(0)
}

test.describe('M02 从路由恢复城市', () => {
  test('CAT-101 深链只带城市编码时，用城市目录的名称加载列表', async ({ page }) => {
    const services = collectRequests(page, SERVICES_PATH)

    await page.goto(`${CATALOG_ROUTE}?cityCode=370500&categoryCode=POWER`)

    await expectCatalogLoaded(page, '东营市')
    expect(services.at(-1)?.searchParams.get('cityCode')).toBe('370500')
  })

  test('CAT-102 浏览器刷新后仍是原城市的列表', async ({ page }) => {
    await page.goto(`${CATALOG_ROUTE}?cityCode=370100&categoryCode=POWER`)
    await expectCatalogLoaded(page, '济南市')

    await page.reload()

    await expectCatalogLoaded(page, '济南市')
  })

  test('CAT-103 共享城市已有时不请求城市目录', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-channel-service').click()
    await expect(page.getByTestId('portal-category-power')).toBeVisible()
    const cities = collectRequests(page, CITIES_PATH)

    await page.getByTestId('portal-category-power').click()

    await expectCatalogLoaded(page, '东营市')
    expect(cities).toHaveLength(0)
  })

  test('CAT-104 路由和共享城市都没有时，引导选择城市', async ({ page }) => {
    const services = collectRequests(page, SERVICES_PATH)

    await page.goto(`${CATALOG_ROUTE}?categoryCode=POWER`)

    await expectCityPrompt(page)
    expect(services).toHaveLength(0)

    await page.locator('.catalog-state__action').click()
    await expect(page.locator('.city-sheet')).toBeVisible()
    await page.locator('.city-sheet__city', { hasText: '东营市' }).first().click()

    await expectCatalogLoaded(page, '东营市')
  })

  test('CAT-105 城市编码不在目录时不当成可用城市', async ({ page }) => {
    const services = collectRequests(page, SERVICES_PATH)

    await page.goto(`${CATALOG_ROUTE}?cityCode=999999&categoryCode=POWER`)

    await expectCityPrompt(page)
    expect(services).toHaveLength(0)
    await expect(page.getByTestId('catalog-city-select')).toContainText('选择城市')
  })

  test('CAT-106 城市目录失败时如实报错，重试后恢复', async ({ page }) => {
    let offline = true
    await page.route(`**${CITIES_PATH}`, async (route) => {
      if (offline) {
        await route.abort('internetdisconnected')
        return
      }
      await route.continue()
    })

    await page.goto(`${CATALOG_ROUTE}?cityCode=370500&categoryCode=POWER`)
    await expect(page.getByText(NETWORK_ERROR)).toBeVisible()

    offline = false
    await page.locator('.catalog-state__action').click()

    await expectCatalogLoaded(page, '东营市')
  })

  test('CAT-107 深链恢复的城市带回首页', async ({ page }) => {
    await page.goto(`${CATALOG_ROUTE}?cityCode=370100&categoryCode=POWER`)
    await expectCatalogLoaded(page, '济南市')

    await page.getByTestId('catalog-back').click()

    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.getByTestId('home-city')).toContainText('济南')
  })
})
