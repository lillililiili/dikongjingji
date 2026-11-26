import { expect, test, type Page, type Route } from '@playwright/test'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const MISSING_SERVICE_ID = '12345678-1234-4234-8234-123456789012'
const DETAIL_PATH = `/api/v1/inspection/services/${SERVICE_ID}`
const UNAVAILABLE_COPY = '当前城市暂无这项服务，或服务已下架'
const RETRY_COPY = '服务详情加载失败，请检查网络后重试'

function detailUrl(serviceId: string, cityCode: string): string {
  return `/#/pages/service/inspection/detail?serviceId=${serviceId}&cityCode=${cityCode}`
}

function collectDetailRequests(page: Page): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === DETAIL_PATH) requests.push(url)
  })
  return requests
}

async function openDetail(page: Page, serviceId: string, cityCode: string): Promise<void> {
  await page.goto(detailUrl(serviceId, cityCode))
  await expect(page.getByTestId('inspection-detail')).toBeVisible()
}

function failureCopy(page: Page) {
  return page.locator('.inspection-service-detail__empty-copy')
}

function recoveryAction(page: Page) {
  return page.locator('.inspection-service-detail__empty-action')
}

async function expectUnavailable(page: Page): Promise<void> {
  await expect(failureCopy(page)).toHaveText(UNAVAILABLE_COPY)
  await expect(recoveryAction(page)).toHaveText('看看其他服务')
  await expect(page.locator('.service-detail-bottom-bar__primary')).toHaveAttribute('disabled', 'true')
}

async function expectRetryOffered(page: Page): Promise<void> {
  await expect(failureCopy(page)).toHaveText(RETRY_COPY)
  await expect(recoveryAction(page)).toHaveText('重新加载')
}

async function chooseCity(page: Page, name: string): Promise<void> {
  await page.getByTestId('catalog-city-select').click()
  await expect(page.locator('.city-sheet')).toBeVisible()
  await page.locator('.city-sheet__city', { hasText: name }).first().click()
  await expect(page.getByTestId('catalog-city-select')).toContainText(name)
}

test.describe('M03 不可用时的恢复', () => {
  test('SDR-101 城市没有这项服务时，能换城市找到服务', async ({ page }) => {
    await openDetail(page, SERVICE_ID, '370200')
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(page.getByTestId('catalog-city-select')).toContainText('青岛市')
    await chooseCity(page, '东营市')
    await page.locator('[data-testid^="catalog-service-"]').first().click()
    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
  })

  test('SDR-102 服务不存在时回到当前城市的目录', async ({ page }) => {
    await openDetail(page, MISSING_SERVICE_ID, '370500')
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
    await expect(page.locator('[data-testid^="catalog-service-"]').first()).toBeVisible()
  })

  test('SDR-103 从目录点进已下架的服务，回到原来的目录', async ({ page }) => {
    await page.route(`**${DETAIL_PATH}?*`, (route) => route.fulfill({
      status: 404,
      json: { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 'e2e' },
    }))
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-channel-service').click()
    await page.getByTestId('portal-category-power').click()
    await page.getByTestId(`catalog-service-${SERVICE_ID}`).click()
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    expect(page.url()).toContain('source=CATEGORY')
  })

  test('SDR-104 断网恢复后重新加载，详情和预约都可用', async ({ page }) => {
    let offline = true
    await page.route(`**${DETAIL_PATH}?*`, async (route) => {
      if (offline) await route.abort('internetdisconnected')
      else await route.continue()
    })
    await openDetail(page, SERVICE_ID, '370500')
    await expectRetryOffered(page)

    offline = false
    await recoveryAction(page).click()

    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
    await expect(recoveryAction(page)).toHaveCount(0)
    await page.locator('.service-detail-bottom-bar__primary:not([disabled])').click()
    await expect(page.locator('.booking-row--time')).toBeVisible()
    expect(new URL(page.url()).hash).toContain('cityCode=370500')
  })

  test('SDR-105 重试时再次请求真实接口，仍失败就留在可重试状态', async ({ page }) => {
    const requests = collectDetailRequests(page)
    await page.route(`**${DETAIL_PATH}?*`, (route) => route.fulfill({
      status: 500,
      json: { code: 'COMMON_INTERNAL_ERROR', message: '系统繁忙，请稍后重试', traceId: 'e2e' },
    }))
    await openDetail(page, SERVICE_ID, '370500')
    await expectRetryOffered(page)
    const requestsBeforeRetry = requests.length

    await recoveryAction(page).click()

    await expect.poll(() => requests.length).toBeGreaterThan(requestsBeforeRetry)
    await expectRetryOffered(page)
  })

  for (const [name, reply] of [
    ['网关错误', (route: Route) => route.fulfill({
      status: 502,
      contentType: 'text/html',
      body: '<html>Bad Gateway</html>',
    })],
    ['响应结构异常', (route: Route) => route.fulfill({ status: 200, json: {} })],
  ] as const) {
    test(`SDR-106 ${name}时只显示用户能看懂的文案`, async ({ page }) => {
      await page.route(`**${DETAIL_PATH}?*`, reply)

      await openDetail(page, SERVICE_ID, '370500')

      await expectRetryOffered(page)
    })
  }

  test('SDR-107 加载中不显示恢复按钮', async ({ page }) => {
    let release = (): void => undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    await page.route(`**${DETAIL_PATH}?*`, async (route) => {
      await held
      await route.continue()
    })

    await openDetail(page, SERVICE_ID, '370500')

    await expect(page.locator('.inspection-service-detail__empty-title')).toHaveText('正在加载服务详情')
    await expect(recoveryAction(page)).toHaveCount(0)
    release()
    await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
  })

  test('SDR-108 失败时顶部返回仍然可用', async ({ page }) => {
    await openDetail(page, SERVICE_ID, '370200')
    await expect(failureCopy(page)).toBeVisible()
    await expect(failureCopy(page)).not.toHaveText('正在读取真实服务数据…')

    await page.getByTestId('inspection-detail-back').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  })
})

// 验收清单已知问题 4：没有评分时，评价那一行不能以“·”开头。
// 2026-11-20 原型对齐：0 条评价不显示，这一行从服务次数开始。
test('SDR-109 没有评分也没有评价时，这一行只有服务次数', async ({ page }) => {
  await page.route(`**${DETAIL_PATH}?**`, async (route) => {
    const response = await route.fetch()
    const body = await response.json()
    body.reviews = { ...body.reviews, average: null, total: 0, positiveRate: null }
    await route.fulfill({ response, json: body })
  })
  await openDetail(page, SERVICE_ID, '370500')

  const metrics = page.locator('.service-detail-hero__metrics')
  await expect(metrics).toContainText(/已完成\s*[\d,]+\s*单/)
  const text = (await metrics.innerText()).replace(/\s+/g, ' ').trim()
  expect(text).not.toContain('条评价')
  expect(text).not.toContain('·')
  await expect(metrics.locator('.service-detail-hero__rating')).toHaveCount(0)
})
