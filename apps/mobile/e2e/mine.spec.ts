import { expect, test } from '@playwright/test'

// “我的”页按 2026-11-14 原型（画板 Mine、MineGuest）的两种状态；订单五阶段为跨业务口径（计划 06 R4）。
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
    // MineGuest: no gear, no headset, no recent orders; the order card asks to sign in.
    await expect(page.getByTestId('mine-settings')).toHaveCount(0)
    await expect(page.getByTestId('mine-customer-service')).toHaveCount(0)
    await expect(page.getByTestId('mine-page')).not.toContainText('近期巡检订单')
    await expect(page.locator('.platform-mine__status-label'))
      .toHaveText(['待付款', '待发货/履约', '待参加', '待验收', '退款/售后'])
    await expect(page.getByTestId('mine-orders-login')).toContainText('登录后查看你的订单和服务进度')
    await expect(page.getByTestId('mine-link-venue_bookings')).toHaveText('体验预约')

    await page.getByTestId('mine-login').click()
    // 登录后回到“我的”。
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*pages.*mine.*index/)
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
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        json: {
          displayName: '王女士',
          organizationName: '东营港务物流',
          organizationType: 'CUSTOMER',
          roles: ['CUSTOMER'],
        },
      }),
    )
    await page.goto('/#/pages/mine/index')
    await expect(page.locator('.platform-mine__order-title')).toHaveText('输电线路无人机巡检')

    await expect(page.getByTestId('mine-login')).toHaveCount(0)
    // The name and organization come from the account; no level is shown because there is none.
    await expect(page.locator('.platform-mine__name')).toHaveText('王女士')
    await expect(page.locator('.platform-mine__certification')).toContainText('东营港务物流')
    await expect(page.locator('.platform-mine__level')).toHaveCount(0)
    // Only 待验收 has a number here: the other stages' APIs reject the stub token, so they show none.
    await expect(page.locator('.platform-mine__status-count')).toHaveText(['99+'])
    // The report waiting for acceptance is a current-item card with 去验收.
    await expect(page.getByTestId('mine-todo-acceptance')).toContainText('服务订单 · 待验收')
    await expect(page.getByTestId('mine-todo-acceptance')).toContainText('去验收')
    await expect(page.getByTestId('mine-orders-login')).toHaveCount(0)

    await page.locator('.platform-mine__status-item').nth(3).click()
    await expect(page).toHaveURL(/pages\/mine\/inspection-orders\?stage=AWAITING_ACCEPTANCE/)
  })

  test('MINE-003 资料读不到时只显示“已登录”，不跳去登录页', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('inspection.accessToken', 'mine-spec')
    })
    await page.route('**/api/v1/inspection/reservations?*', (route) =>
      route.fulfill({
        json: {
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
        },
      }),
    )
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: '登录状态已失效', traceId: 't' } }),
    )
    await page.goto('/#/pages/mine/index')

    await expect(page.locator('.platform-mine__name')).toHaveText('已登录')
    await expect(page.locator('.platform-mine__certification')).toHaveCount(0)
    await page.waitForTimeout(600)
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expect(page).toHaveURL(/pages\/mine\/index/)
  })
})

test.describe('我的：当前事项与数值', () => {
  test('MINE-004 待参加报名带核销码，订单角标和菜单数值都来自现有接口', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('inspection.accessToken', 'mine-spec')
    })
    const empty = { items: [], page: 1, size: 3, total: 0, hasMore: false, stageCounts: {
      AWAITING_PLAN: 0, AWAITING_CONFIRMATION: 0, IN_PROGRESS: 0, AWAITING_ACCEPTANCE: 0, COMPLETED: 0 } }
    await page.route('**/api/v1/inspection/reservations?*', (route) => route.fulfill({ json: empty }))
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({ json: { displayName: '王女士', organizationName: null, organizationType: 'CUSTOMER', roles: ['CUSTOMER'] } }))
    const enrollment = {
      enrollmentId: 'e1',
      status: 'UPCOMING',
      activity: { activityId: 'a1', type: 'CONTEST', title: '无人机编程挑战赛', coverImage: null, cityCode: '370500', organizerName: '示例' },
      session: { sessionId: 's1', label: null, startsAt: '2026-11-15T01:00:00Z', endsAt: null, checkInOpensAt: '2026-11-15T00:30:00Z' },
      location: { name: '东营体验馆', address: '', latitude: null, longitude: null },
      createdAt: '2026-11-10T00:00:00Z',
      cancellableUntil: '2026-11-15T00:00:00Z',
      cancellable: true,
      tickets: [1, 2].map((n) => ({
        ticketId: `t${n}`, participantId: `p${n}`, participantName: '示例', relation: 'SELF', age: null,
        ticketCode: `C${n}`, status: 'ACTIVE', checkedInAt: null, cancelledAt: null, refund: null,
      })),
      payment: { status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null },
    }
    await page.route('**/api/v1/activity-enrollments?*', (route) =>
      route.fulfill({ json: { page: 1, size: 10, total: 1, hasMore: false, items: [enrollment] } }))
    await page.route('**/api/v1/trade/orders?*', (route) => {
      const tab = new URL(route.request().url()).searchParams.get('tab')
      return route.fulfill({ json: { page: 1, size: 1, total: tab === 'TO_PAY' ? 2 : 0, hasMore: false, items: [] } })
    })
    await page.route('**/api/v1/activity-participants*', (route) => route.fulfill({ json: { items: [
      { participantId: 'p1', relation: 'SELF', name: '王女士', birthMonth: null, age: null, photoUseAuthorized: false, eligibility: null },
      { participantId: 'p2', relation: 'CHILD', name: '小王', birthMonth: '2015-01', age: 11, photoUseAuthorized: false, eligibility: null },
    ] } }))
    await page.route('**/api/v1/demands?*', (route) => {
      const chosen = new URL(route.request().url()).searchParams.get('status') === 'CHOSEN'
      return route.fulfill({ json: { page: 1, size: 1, total: chosen ? 1 : 3, hasMore: false, items: [] } })
    })
    await page.goto('/#/pages/mine/index')

    const card = page.getByTestId('mine-todo-enrollment')
    await expect(card).toContainText('报名成功 · 待参加')
    await expect(card).toContainText('无人机编程挑战赛 · 2 人')
    await expect(card).toContainText('11/15 08:30 起签到 · 东营体验馆')
    // 待付款 2 (goods), 待参加 1 (enrolments); stages without a number show no badge.
    await expect(page.locator('.platform-mine__status-count')).toHaveText(['2', '1'])
    await expect(page.getByTestId('mine-link-value-activity_participants')).toHaveText('2 人')
    await expect(page.getByTestId('mine-link-value-demands')).toHaveText('1 份已选定')

    await page.getByTestId('mine-todo-ticket').click()
    await expect(page).toHaveURL(/pages\/activity\/ticket\?enrollmentId=e1/)
  })
})
