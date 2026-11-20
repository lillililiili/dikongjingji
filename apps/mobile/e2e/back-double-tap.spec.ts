import { expect, test, type Page } from '@playwright/test'

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

const ordersBack = (page: Page) => page.getByTestId('inspection-orders-back')
const catalogBack = (page: Page) => page.getByTestId('catalog-back')
const detailBack = (page: Page) => page.getByTestId('inspection-detail-back')

async function openOrders(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await page.getByTestId('root-tab-mine').click()
  await page.locator('.platform-mine__view-all').click()
  await expect(ordersBack(page)).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function openCatalog(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await page.getByTestId('home-channel-service').click()
  await page.getByTestId('portal-category-power').click()
  await expect(page.getByTestId('inspection-catalog')).toBeVisible()
  await expect(page.locator('[data-testid^="catalog-service-"]').first()).toBeVisible()
  await page.waitForLoadState('networkidle')
}

async function openDetail(page: Page): Promise<void> {
  await openCatalog(page)
  await page.locator('[data-testid^="catalog-service-"]').first().click()
  await expect(page.getByTestId('inspection-detail')).toBeVisible()
  await page.waitForLoadState('networkidle')
}

// A reload or a move to another document wipes window, so the mark proves the app stayed.
async function markDocument(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { backDoubleTapMark?: boolean }).backDoubleTapMark = true
  })
}

async function expectSameDocument(page: Page): Promise<void> {
  await page.waitForTimeout(600)
  expect(await page.evaluate(() =>
    (window as unknown as { backDoubleTapMark?: boolean }).backDoubleTapMark,
  )).toBe(true)
}

test.describe('连点返回', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      try {
        localStorage.setItem('inspection.accessToken', 'back-double-tap')
      } catch {
        // about:blank has no storage.
      }
    })
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({ json: EMPTY_ORDERS }),
    )
  })

  // mouse.dblclick delivers both clicks before the first history.go lands, which is
  // what a phone does when both taps fall inside one long task on the main thread.
  test('BDT-101 M08 同时收到两下点击，回到“我的”不离开应用', async ({ page }) => {
    await openOrders(page)
    await markDocument(page)

    await ordersBack(page).dblclick()

    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expectSameDocument(page)
  })

  test('BDT-102 M02 同时收到两下点击，回到首页不离开应用', async ({ page }) => {
    await openCatalog(page)
    await markDocument(page)

    await catalogBack(page).dblclick()

    await expect(page.getByTestId('home-page')).toBeVisible()
    await expectSameDocument(page)
  })

  test('BDT-103 M03 同时收到两下点击，只退到目录', async ({ page }) => {
    await openDetail(page)
    await markDocument(page)

    await detailBack(page).dblclick()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expectSameDocument(page)
  })

  test('BDT-104 退到目录后再点目录的返回不受限制', async ({ page }) => {
    await openDetail(page)
    await markDocument(page)

    await detailBack(page).click()
    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await catalogBack(page).click()

    await expect(page.getByTestId('home-page')).toBeVisible()
    await expectSameDocument(page)
  })

  test('BDT-105 回到“我的”后立刻再进再退仍然生效', async ({ page }) => {
    await openOrders(page)
    await markDocument(page)

    await ordersBack(page).click()
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await page.locator('.platform-mine__view-all').click()
    await expect(ordersBack(page)).toBeVisible()
    await ordersBack(page).click()

    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expectSameDocument(page)
  })
})
