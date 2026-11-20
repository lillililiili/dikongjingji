import { expect, test, type Page, type Route } from '@playwright/test'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const MISSING_SERVICE_ID = '12345678-1234-4234-8234-123456789012'
const DETAIL_PATH = `/api/v1/inspection/services/${SERVICE_ID}`
const UNAVAILABLE_COPY = '当前城市暂无这项服务，或服务已下架'
const RETRY_COPY = '服务信息加载失败，请检查网络后重试'

function bookingUrl(query: string): string {
  return `/#/pages/service/inspection/booking${query}`
}

const BOOKING_URL = bookingUrl(`?serviceId=${SERVICE_ID}&cityCode=370500`)

// M03 reads this same URL, so a test that passes through M03 has to install this
// after M03 has its detail.
function replyToBookingRead(
  page: Page,
  reply: (route: Route) => Promise<void> | void,
): Promise<void> {
  return page.route(`**${DETAIL_PATH}?*`, reply)
}

function serviceGone(route: Route): Promise<void> {
  return route.fulfill({
    status: 404,
    json: { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 'e2e' },
  })
}

function collectBookingReads(page: Page): URL[] {
  const requests: URL[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === DETAIL_PATH) requests.push(url)
  })
  return requests
}

function failureView(page: Page) {
  return page.locator('.booking-state')
}

function recoveryAction(page: Page) {
  return page.locator('.booking-state__action')
}

async function expectUnavailable(page: Page): Promise<void> {
  await expect(failureView(page)).toContainText(UNAVAILABLE_COPY)
  await expect(recoveryAction(page)).toHaveText('看看其他服务')
  await expect(page.locator('.inspection-booking__footer')).toHaveCount(0)
}

async function expectRetryOffered(page: Page): Promise<void> {
  await expect(failureView(page)).toContainText(RETRY_COPY)
  await expect(recoveryAction(page)).toHaveText('重新加载')
  await expect(page.locator('.inspection-booking__footer')).toHaveCount(0)
}

async function openCatalogFromHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-service').click()
  await page.getByTestId('portal-category-power').click()
  await expect(page.getByTestId(`catalog-service-${SERVICE_ID}`)).toBeVisible()
}

async function reserveFromDetail(page: Page): Promise<void> {
  await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
  await page.locator('.service-detail-bottom-bar__primary:not([disabled])').click()
}

async function reserveAfterServiceIsGone(page: Page): Promise<void> {
  await expect(page.locator('.inspection-service-detail__content')).toBeVisible()
  await replyToBookingRead(page, serviceGone)
  await reserveFromDetail(page)
}

async function chooseCity(page: Page, name: string): Promise<void> {
  await page.getByTestId('catalog-city-select').click()
  await expect(page.locator('.city-sheet')).toBeVisible()
  await page.locator('.city-sheet__city', { hasText: name }).first().click()
  await expect(page.getByTestId('catalog-city-select')).toContainText(name)
}

test.describe('M04 不可用时的恢复', () => {
  test('BKR-101 服务不存在时回到当前城市的目录', async ({ page }) => {
    await page.goto(bookingUrl(`?serviceId=${MISSING_SERVICE_ID}&cityCode=370500`))
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
    await expect(page.locator('[data-testid^="catalog-service-"]').first()).toBeVisible()
  })

  test('BKR-102 城市没有这项服务时，能换城市完成预约入口', async ({ page }) => {
    await page.goto(bookingUrl(`?serviceId=${SERVICE_ID}&cityCode=370200`))
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('catalog-city-select')).toContainText('青岛市')
    await chooseCity(page, '东营市')
    await page.getByTestId(`catalog-service-${SERVICE_ID}`).click()
    await reserveFromDetail(page)
    await expect(page.locator('.booking-row--time')).toBeVisible()
    expect(new URL(page.url()).hash).toContain('cityCode=370500')
  })

  test('BKR-103 从目录进来时跳过详情，回到原来的目录', async ({ page }) => {
    await openCatalogFromHome(page)
    await page.getByTestId(`catalog-service-${SERVICE_ID}`).click()
    await reserveAfterServiceIsGone(page)
    await expectUnavailable(page)
    await page.evaluate(() => {
      (window as unknown as { recoveryMark?: boolean }).recoveryMark = true
    })

    await recoveryAction(page).click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(page.getByTestId('inspection-detail')).toHaveCount(0)
    expect(page.url()).toContain('source=CATEGORY')
    expect(await page.evaluate(() =>
      (window as unknown as { recoveryMark?: boolean }).recoveryMark,
    )).toBe(true)
    await expect.poll(() => page.evaluate(() => getCurrentPages().length)).toBe(2)
  })

  test('BKR-104 从首页精选进来时回到首页', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.evaluate((id) => {
      (window as unknown as { uni: { navigateTo(input: { url: string }): void } }).uni.navigateTo({
        url: `/pages/service/inspection/detail?serviceId=${id}`,
      })
    }, SERVICE_ID)
    await reserveAfterServiceIsGone(page)
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('home-page')).toBeVisible()
    await expect(page.getByTestId('inspection-detail')).toHaveCount(0)
  })

  test('BKR-105 只打开了详情就预约时，打开目录而不是回详情', async ({ page }) => {
    await page.goto(`/#/pages/service/inspection/detail?serviceId=${SERVICE_ID}&cityCode=370500`)
    await reserveAfterServiceIsGone(page)
    await expectUnavailable(page)

    await recoveryAction(page).click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(page.getByTestId('catalog-city-select')).toContainText('东营市')
  })

  test('BKR-106 断网恢复后重新加载，能继续填写预约', async ({ page }) => {
    const reads = collectBookingReads(page)
    let offline = true
    await replyToBookingRead(page, async (route) => {
      if (offline) await route.abort('internetdisconnected')
      else await route.continue()
    })
    await page.goto(BOOKING_URL)
    await expectRetryOffered(page)
    const readsBeforeRetry = reads.length

    offline = false
    await recoveryAction(page).click()

    await expect(page.locator('.booking-row--time')).toBeVisible()
    await expect(recoveryAction(page)).toHaveCount(0)
    await expect(page.locator('.inspection-booking__submit')).toBeVisible()
    expect(reads.length).toBeGreaterThan(readsBeforeRetry)
    expect(reads.at(-1)?.searchParams.get('cityCode')).toBe('370500')
  })

  test('BKR-107 重试时再次请求真实接口，仍失败就留在可重试状态', async ({ page }) => {
    const reads = collectBookingReads(page)
    await replyToBookingRead(page, (route) => route.fulfill({
      status: 500,
      json: { code: 'COMMON_INTERNAL_ERROR', message: '系统繁忙，请稍后重试', traceId: 'e2e' },
    }))
    await page.goto(BOOKING_URL)
    await expectRetryOffered(page)
    const readsBeforeRetry = reads.length

    await recoveryAction(page).click()

    await expect.poll(() => reads.length).toBeGreaterThan(readsBeforeRetry)
    await expectRetryOffered(page)
  })

  test('BKR-108 重试后发现服务已没有，就不再提供重试', async ({ page }) => {
    let offline = true
    await replyToBookingRead(page, async (route) => {
      if (offline) await route.abort('internetdisconnected')
      else await serviceGone(route)
    })
    await page.goto(BOOKING_URL)
    await expectRetryOffered(page)

    offline = false
    await recoveryAction(page).click()

    await expectUnavailable(page)
  })

  for (const [name, reply] of [
    ['网关错误', (route: Route) => route.fulfill({
      status: 502,
      contentType: 'text/html',
      body: '<html>Bad Gateway</html>',
    })],
    ['响应结构异常', (route: Route) => route.fulfill({ status: 200, json: {} })],
  ] as const) {
    test(`BKR-109 ${name}时只显示用户能看懂的文案`, async ({ page }) => {
      await replyToBookingRead(page, reply)

      await page.goto(BOOKING_URL)

      await expectRetryOffered(page)
    })
  }

  test('BKR-110 城市编码无效时不提供重试', async ({ page }) => {
    await page.goto(bookingUrl(`?serviceId=${SERVICE_ID}&cityCode=abc`))

    await expectUnavailable(page)
  })

  for (const [name, query, copy] of [
    ['没有服务', '?cityCode=370500', '未找到要预约的巡检服务'],
    ['没有城市', `?serviceId=${SERVICE_ID}`, '请从巡检服务页面进入并选择服务城市'],
  ] as const) {
    test(`BKR-111 路由${name}时也能离开`, async ({ page }) => {
      await page.goto(bookingUrl(query))
      await expect(failureView(page)).toContainText(copy)
      await expect(recoveryAction(page)).toHaveText('看看其他服务')

      await recoveryAction(page).click()

      await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    })
  }

  test('BKR-112 加载中不显示恢复按钮', async ({ page }) => {
    let release = (): void => undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    await replyToBookingRead(page, async (route) => {
      await held
      await route.continue()
    })

    await page.goto(BOOKING_URL)

    await expect(failureView(page)).toContainText('正在加载服务信息…')
    await expect(recoveryAction(page)).toHaveCount(0)
    release()
    await expect(page.locator('.booking-row--time')).toBeVisible()
  })

  test('BKR-113 失败时顶部返回仍然可用', async ({ page }) => {
    await page.goto(bookingUrl(`?serviceId=${SERVICE_ID}&cityCode=370200`))
    await expect(failureView(page)).not.toContainText('正在加载服务信息…')

    await page.getByTestId('inspection-booking-back').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  })

  test('BKR-114 刷新失败页后仍有恢复入口', async ({ page }) => {
    await page.goto(bookingUrl(`?serviceId=${MISSING_SERVICE_ID}&cityCode=370500`))
    await expectUnavailable(page)

    await page.reload()

    await expectUnavailable(page)
    await recoveryAction(page).click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  })
})
