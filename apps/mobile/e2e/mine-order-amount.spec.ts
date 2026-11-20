import { expect, test, type Page } from '@playwright/test'

// “我的”在窄屏和宽屏：订单五阶段、待验收卡片、我的参与都不折行、不超出页面（画板 Mine）。
// 原来的“近期巡检订单”卡片随原型对齐（计划 06 R4）删掉，这里改测替代它的卡片。
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

function order(id: string) {
  return {
    reservationId: id,
    reservationNo: `RS${id}`,
    serviceId: 's1',
    serviceTitle: '物流园区季度安防巡检服务（含夜间周界与堆场巡查）',
    cityCode: '370500',
    locationSummary: '东营市垦利区示例物流园区北侧堆场',
    preferredServiceDate: '2026-11-06',
    submittedAt: '2026-11-01T09:00:00+08:00',
    stage: 'AWAITING_ACCEPTANCE',
    reservationStatus: 'PLAN_PUBLISHED',
    serviceOrderId: `so-${id}`,
    pendingAcceptanceVisitId: `v-${id}`,
    pendingAcceptanceVisitNo: 1,
    visitCount: 4,
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
          items: [order('1')],
          page: 1,
          size: 3,
          total: 1,
          hasMore: false,
          stageCounts: {
            AWAITING_PLAN: 0,
            AWAITING_CONFIRMATION: 0,
            IN_PROGRESS: 0,
            AWAITING_ACCEPTANCE: 1,
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
  await expect(page.getByTestId('mine-todo-acceptance')).toBeVisible()
}

async function lineCount(page: Page, selector: string): Promise<number[]> {
  return page.locator(selector).evaluateAll((elements) => elements.map((element) => {
    const range = document.createRange()
    range.selectNodeContents(element)
    return new Set([...range.getClientRects()].map((rect) => Math.round(rect.top))).size
  }))
}

for (const width of [320, 375, 430]) {
  test(`我的 ${width} 宽：订单阶段和待验收卡片不折行，也不超出页面`, async ({ page }) => {
    await openMine(page, width)

    expect(await lineCount(page, '.platform-mine__status-label')).toEqual([1, 1, 1, 1, 1])
    expect((await lineCount(page, '.platform-mine__join-title')).every((lines) => lines === 1)).toBe(true)

    // A long title is cut with an ellipsis instead of pushing 去验收 out of the card.
    const card = page.getByTestId('mine-todo-acceptance')
    await expect(card.locator('.platform-mine__order-title')).toHaveText('物流园区季度安防巡检服务（含夜间周界与堆场巡查）（第 1 次）')
    const action = await card.locator('.platform-mine__todo-action').boundingBox()
    const box = await card.boundingBox()
    expect(action!.x + action!.width).toBeLessThanOrEqual(box!.x + box!.width + 0.5)
    expect(await lineCount(page, '.platform-mine__todo-action')).toEqual([1])

    expect(await page.evaluate(() => document.documentElement.scrollWidth
      - document.documentElement.clientWidth)).toBe(0)
  })
}
