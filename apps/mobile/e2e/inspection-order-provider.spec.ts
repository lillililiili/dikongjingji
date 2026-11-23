import { expect, test, type Page } from '@playwright/test'

// S4-4（服务流程契约 2.6.0）：巡检订单卡头行是服务商名和“资质已审核”（画板 OrderList），替换原来的预约编号。
// M08 订单中心和“我的订单”合并页用同一张卡。接口全部桩住：列表里一张有服务商且审核过、一张服务商没审核、
// 一张还没有服务商（头行照旧显示预约编号）。
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

function order(id: string, provider: { name: string; qualificationReviewed: boolean } | null) {
  return {
    reservationId: `40000000-0000-4000-8000-00000000000${id}`,
    reservationNo: `YY2026112300000${id}`,
    serviceId: '30000000-0000-4000-8000-000000000001',
    serviceTitle: '物流园区季度安防巡检',
    cityCode: '370500',
    locationSummary: '东营市垦利区示例物流园区',
    preferredServiceDate: '2026-11-28',
    submittedAt: '2026-11-23T01:00:00Z',
    stage: 'AWAITING_PLAN',
    reservationStatus: 'SUBMITTED',
    currency: 'CNY',
    visitCount: 0,
    acceptedVisitCount: 0,
    ...(provider
      ? { provider: { providerId: '22222222-2222-2222-2222-222222222222', ...provider } }
      : {}),
  }
}

const ORDERS = [
  order('1', { name: '鹰眼智能巡检', qualificationReviewed: true }),
  order('2', { name: '示例测绘服务商', qualificationReviewed: false }),
  order('3', null),
]

async function stubApi(page: Page): Promise<void> {
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
          items: ORDERS,
          page: 1,
          size: 20,
          total: ORDERS.length,
          hasMore: false,
          stageCounts: {
            AWAITING_PLAN: ORDERS.length,
            AWAITING_CONFIRMATION: 0,
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
}

async function expectHeadRows(page: Page): Promise<void> {
  const reviewed = page.getByTestId(`inspection-order-${ORDERS[0]!.reservationId}`)
  await expect(reviewed.getByTestId('inspection-order-provider')).toHaveText('鹰眼智能巡检')
  await expect(reviewed.getByTestId('qualification-badge')).toHaveText('资质已审核')
  await expect(reviewed).not.toContainText(ORDERS[0]!.reservationNo)

  const unreviewed = page.getByTestId(`inspection-order-${ORDERS[1]!.reservationId}`)
  await expect(unreviewed.getByTestId('inspection-order-provider')).toHaveText('示例测绘服务商')
  await expect(unreviewed.getByTestId('qualification-badge')).toHaveCount(0)

  const withoutProvider = page.getByTestId(`inspection-order-${ORDERS[2]!.reservationId}`)
  await expect(withoutProvider.getByTestId('inspection-order-provider')).toHaveCount(0)
  await expect(withoutProvider).toContainText(ORDERS[2]!.reservationNo)
}

test.describe('巡检订单卡的服务商', () => {
  test('IOP-101 M08 订单中心：服务商名、资质已审核，没有服务商时显示预约编号', async ({ page }) => {
    await stubApi(page)
    await page.goto('/#/pages/mine/inspection-orders?stage=ALL')

    await expectHeadRows(page)
  })

  test('IOP-102 “我的订单”合并页的巡检卡同样显示', async ({ page }) => {
    await stubApi(page)
    await page.goto('/#/pages/mine/orders')

    await expectHeadRows(page)
  })

  for (const width of [320, 430]) {
    test(`IOP-103 ${width} 宽：长服务商名不把状态挤出卡片`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 })
      await stubApi(page)
      await page.goto('/#/pages/mine/inspection-orders?stage=ALL')

      const card = page.getByTestId(`inspection-order-${ORDERS[0]!.reservationId}`)
      await expect(card).toBeVisible()
      const stage = await card.locator('.inspection-order-card__stage').boundingBox()
      const box = await card.boundingBox()
      expect(stage!.x + stage!.width).toBeLessThanOrEqual(box!.x + box!.width + 0.5)
      expect(await page.evaluate(() => document.documentElement.scrollWidth
        - document.documentElement.clientWidth)).toBe(0)
    })
  }
})
