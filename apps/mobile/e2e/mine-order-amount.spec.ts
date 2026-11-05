import { expect, test, type Page } from '@playwright/test'

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

function order(id: string, quoteAmountMinor: number) {
  return {
    reservationId: id,
    reservationNo: `RS${id}`,
    serviceId: 's1',
    serviceTitle: '输电线路无人机巡检',
    cityCode: '370500',
    locationSummary: '东营市垦利区',
    preferredServiceDate: '2026-11-06',
    submittedAt: '2026-11-01T09:00:00+08:00',
    stage: 'AWAITING_CONFIRMATION',
    reservationStatus: 'PLAN_PUBLISHED',
    currentPlanId: `plan-${id}`,
    quoteAmountMinor,
    currency: 'CNY',
  }
}

async function openMine(page: Page, width: number): Promise<void> {
  await page.setViewportSize({ width, height: 844 })
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    if (new URL(request.url()).pathname.endsWith('/inspection/reservations')) {
      await route.fulfill({
        headers: CORS,
        json: {
          items: [order('1', 1_860_000), order('2', 123_456_700)],
          page: 1,
          size: 3,
          total: 2,
          hasMore: false,
          stageCounts: {
            AWAITING_PLAN: 0,
            AWAITING_CONFIRMATION: 2,
            IN_PROGRESS: 0,
            AWAITING_ACCEPTANCE: 0,
            COMPLETED: 0,
          },
        },
      })
      return
    }
    await route.fulfill({
      status: 404,
      headers: CORS,
      json: { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' },
    })
  })
  await page.goto('/#/pages/mine/index')
  await expect(page.locator('.platform-mine__amount-text').first()).toHaveText('¥18,600.00')
}

for (const width of [320, 375, 430]) {
  test(`我的 ${width} 宽：最近订单的金额不折行，也不超出卡片`, async ({ page }) => {
    await openMine(page, width)

    const orders = page.locator('.platform-mine__order')
    for (const index of [0, 1]) {
      const card = orders.nth(index)
      const amount = card.locator('.platform-mine__amount-text')
      const lineCount = await amount.evaluate((element) => {
        const range = document.createRange()
        range.selectNodeContents(element)
        return new Set([...range.getClientRects()].map((rect) => Math.round(rect.top))).size
      })
      expect(lineCount).toBe(1)

      // The wider amount must not cost the title: the status moves down a line instead.
      const title = card.locator('.platform-mine__order-title')
      expect(await title.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)

      const amountBox = await amount.boundingBox()
      const cardBox = await card.boundingBox()
      expect(amountBox!.x + amountBox!.width).toBeLessThanOrEqual(cardBox!.x + cardBox!.width + 0.5)
    }
    await expect(orders.nth(1).locator('.platform-mine__amount-text')).toHaveText('¥1,234,567.00')
    expect(await page.evaluate(() => document.documentElement.scrollWidth
      - document.documentElement.clientWidth)).toBe(0)
  })
}
