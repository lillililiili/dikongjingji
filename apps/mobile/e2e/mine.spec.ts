import { expect, test } from '@playwright/test'

// “我的”页按 2026-11-14 原型改版后的两种状态（计划 §13.2）。
const ORDER = {
  reservationId: '40000000-0000-4000-8000-000000000001',
  reservationNo: 'YY202607310001',
  serviceId: '30000000-0000-4000-8000-000000000001',
  serviceTitle: '输电线路无人机巡检',
  cityCode: '370500',
  locationSummary: '东营市示范线路',
  preferredServiceDate: '2026-08-02',
  submittedAt: '2026-07-31T08:00:00Z',
  stage: 'AWAITING_ACCEPTANCE',
  reservationStatus: 'SUBMITTED',
  serviceOrderId: '60000000-0000-4000-8000-000000000001',
  currency: 'CNY',
}

test.describe('我的', () => {
  test('MINE-001 未登录只给登录入口，不显示等级和认证', async ({ page }) => {
    await page.goto('/#/pages/mine/index')
    await expect(page.getByTestId('mine-page')).toBeVisible()

    await expect(page.locator('.platform-mine__name')).toHaveText('未登录')
    await expect(page.locator('.platform-mine__level')).toHaveCount(0)
    await expect(page.locator('.platform-mine__certification')).toHaveCount(0)
    await expect(page.locator('.platform-mine__status-count')).toHaveCount(0)

    await page.getByTestId('mine-login').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*inspection-orders/)
  })

  test('MINE-002 已登录不显示登录入口，角标只出现在有订单的阶段', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('inspection.accessToken', 'mine-spec')
    })
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({
        json: {
          items: [ORDER],
          page: 1,
          size: 20,
          total: 1,
          hasMore: false,
          stageCounts: {
            AWAITING_PLAN: 0,
            AWAITING_CONFIRMATION: 2,
            IN_PROGRESS: 0,
            AWAITING_ACCEPTANCE: 120,
            COMPLETED: 0,
          },
        },
      }),
    )
    await page.goto('/#/pages/mine/index')
    await expect(page.locator('.platform-mine__order-title')).toHaveText('输电线路无人机巡检')

    await expect(page.getByTestId('mine-login')).toHaveCount(0)
    await expect(page.locator('.platform-mine__status-count')).toHaveText(['2', '99+'])

    await page.locator('.platform-mine__status-item').nth(3).click()
    await expect(page).toHaveURL(/pages\/mine\/inspection-orders\?stage=AWAITING_ACCEPTANCE/)
  })
})
