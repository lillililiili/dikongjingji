import { expect, test, type Page, type Route } from '@playwright/test'

// 首页“推荐”频道在入口下面的四个区块：今天去体验、近期活动、本地精选、附近场馆（计划 §13）。
// 数据来自赛事研学、商城、体验三个只读接口的 dev 示例数据，没有写死的内容。
const VENUE_ID = 'b2000000-0000-4000-8000-000000000001'
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await expect(page.getByTestId('home-recommend')).toBeVisible()
}

test.describe('首页推荐区块', () => {
  test('HOME-R01 近期活动、本地精选、附近场馆来自各自的接口，点进去是对应的详情', async ({ page }) => {
    await openHome(page)

    await expect(page.locator('.home-recommend__activity')).toHaveCount(3)
    await expect(page.locator('.home-recommend__product')).toHaveCount(4)
    await expect(page.locator('.home-recommend__venue-name')).toHaveText([
      '东营低空与机器人体验馆',
      '黄河口户外飞行营地',
    ])
    // The inspection sections of the public build are not shown next to them.
    await expect(page.getByText('精选服务')).toHaveCount(0)
    await expect(page.getByText('企业解决方案')).toHaveCount(0)

    const firstActivity = await page.locator('.home-recommend__activity-title').first().textContent()
    await page.locator('.home-recommend__activity').first().click()
    await expect(page.getByTestId('activity-detail-title')).toHaveText(firstActivity ?? '')
    await page.getByTestId('activity-detail-back').click()
    await expect(page.getByTestId('home-recommend')).toBeVisible()

    await page.getByTestId(`home-product-${KIT_ID}`).click()
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await page.getByTestId('mall-product-back').click()

    await page.getByTestId(`home-venue-${VENUE_ID}`).click()
    await expect(page.getByTestId('venue-detail-name')).toHaveText('东营低空与机器人体验馆')
  })

  test('HOME-R02 “全部”“更多”切到对应频道', async ({ page }) => {
    await openHome(page)

    await page.getByTestId('home-activities-more').click()
    await expect(page.getByTestId('home-channel-activity')).toHaveClass(/home-header__channel--active/)
    await expect(page.getByTestId('activity-channel')).toBeVisible()

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-products-more').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-venues-more').click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  })

  test('HOME-R03 今天还有可约场次时显示“今天去体验”，已开始和已满的不算', async ({ page }) => {
    // 13:00 in Beijing; the stubbed sessions are at 10:00 (over), 14:00 (open) and 15:30 (full).
    await page.clock.setFixedTime(new Date('2026-11-17T05:00:00Z'))
    await page.route('**/api/v1/venues/*/sessions?**', async (route: Route) => {
      const url = new URL(route.request().url())
      expect(url.searchParams.get('date')).toBe('2026-11-17')
      const session = (hour: string, status: string, seatsLeft: number) => ({
        sessionId: `${url.searchParams.get('projectId')}-${hour}`,
        startsAt: `2026-11-17T${hour}:00+08:00`,
        endsAt: `2026-11-17T${hour}:00+08:00`,
        capacity: 8,
        seatsLeft,
        status,
      })
      await route.fulfill({
        headers: { 'access-control-allow-origin': '*' },
        json: {
          date: '2026-11-17',
          sessions: [session('10:00', 'OPEN', 5), session('14:00', 'OPEN', 6), session('15:30', 'FULL', 0)],
        },
      })
    })
    await page.route('**/api/v1/venues?**', async (route: Route) => {
      const response = await route.fetch()
      const body = await response.json()
      // The venue list says how many sessions are still open today; make sure it says some.
      body.items = body.items.map((venue: { venueId: string }) =>
        (venue.venueId === VENUE_ID ? { ...venue, openSessionsToday: 2 } : venue))
      await route.fulfill({ response, json: body })
    })

    await openHome(page)

    const today = page.getByTestId('home-today')
    await expect(today).toContainText('今天去体验')
    await expect(today).toContainText('东营低空与机器人体验馆')
    const slots = today.locator('.home-recommend__slot')
    await expect(slots).toHaveCount(3)
    await expect(today.locator('.home-recommend__slot-time')).toHaveText(['14:00 场', '14:00 场', '14:00 场'])
    await expect(today.locator('.home-recommend__slot-seats').first()).toHaveText('余 6 位')

    await slots.first().click()
    await expect(page.getByTestId('venue-detail-name')).toHaveText('东营低空与机器人体验馆')
  })

  test('HOME-R04 一个区块的接口失败，其他区块照常显示', async ({ page }) => {
    await page.route('**/api/v1/mall/products?**', (route: Route) => route.abort('internetdisconnected'))

    await openHome(page)

    await expect(page.locator('.home-recommend__activity')).toHaveCount(3)
    await expect(page.locator('.home-recommend__venue')).toHaveCount(2)
    await expect(page.getByTestId('home-products')).toHaveCount(0)
    await expect(page.getByText('首页内容加载失败')).toHaveCount(0)
  })

  test('HOME-R05 切换城市后按新城市重新取数，没有数据的区块不显示', async ({ page }) => {
    await openHome(page)
    await expect(page.locator('.home-recommend__venue')).toHaveCount(2)

    await page.getByTestId('home-city').click()
    await page.locator('.city-sheet__city', { hasText: '潍坊市' }).first().click()

    await expect(page.getByTestId('home-city')).toContainText('潍坊')
    await expect(page.getByTestId('home-venues')).toHaveCount(0)
    await expect(page.getByTestId('home-activities')).toHaveCount(0)
  })
})
