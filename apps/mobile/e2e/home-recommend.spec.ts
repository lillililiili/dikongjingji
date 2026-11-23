import { expect, test, type Page, type Route } from '@playwright/test'

// 首页“推荐”频道在入口下面的四个区块：今天去体验、近期活动、本地精选、附近场馆（计划 §13）。
// 数据由服务端一个接口给出（GET /api/v1/home/recommend），内容是 dev 示例数据，没有写死的内容。
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
    // S4-4（体验 1.5.0）：名称下面是项目摘要（画板 HomeFull）——体验馆是运营填的，营地没填，用前三个项目名；
    // 有摘要就不再写地址。
    await expect(page.getByTestId(`home-venue-summary-${VENUE_ID}`)).toHaveText('模拟飞行 · 机器人编程 · 厂家展区')
    await expect(page.getByTestId('home-venue-summary-b2000000-0000-4000-8000-000000000002'))
      .toHaveText('真机试飞（教练陪同） · 航拍体验 · 营地自然课堂')
    await expect(page.getByTestId(`home-venue-${VENUE_ID}`)).not.toContainText('东营区示例路 66 号')
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

  test('HOME-R03 服务端给出今天的可约场次时显示“今天去体验”', async ({ page }) => {
    // 哪些场次算“今天还能约”由服务端判断（后端 ExperienceHomeFeedTest）；这里只换掉 today 这一块。
    await page.route('**/api/v1/home/recommend?**', async (route: Route) => {
      const response = await route.fetch()
      const body = await response.json()
      const venue = body.venues.find((item: { venueId: string }) => item.venueId === VENUE_ID)
      const slot = (number: number, projectName: string, hour: string, seatsLeft: number) => ({
        projectId: `b4000000-0000-4000-8000-00000000010${number}`,
        projectName,
        startsAt: `2026-11-17T${hour}:00+08:00`,
        seatsLeft,
      })
      body.today = {
        venue,
        slots: [slot(1, '机器人避障闯关', '14:00', 6), slot(2, '无人机模拟飞行', '15:30', 2)],
      }
      await route.fulfill({ response, json: body })
    })

    await openHome(page)

    const today = page.getByTestId('home-today')
    await expect(today).toContainText('今天去体验')
    await expect(today).toContainText('东营低空与机器人体验馆')
    const slots = today.locator('.home-recommend__slot')
    await expect(slots).toHaveCount(2)
    await expect(today.locator('.home-recommend__slot-time')).toHaveText(['14:00 场', '15:30 场'])
    await expect(today.locator('.home-recommend__slot-seats')).toHaveText(['余 6 位', '余 2 位'])

    await slots.first().click()
    await expect(page.getByTestId('venue-detail-name')).toHaveText('东营低空与机器人体验馆')
  })

  test('HOME-R04 四块内容一次请求取回；请求失败时不显示区块，入口照常可用', async ({ page }) => {
    const calls: string[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname.startsWith('/api/v1/')) calls.push(url.pathname)
    })
    await openHome(page)
    await expect(page.locator('.home-recommend__venue')).toHaveCount(2)
    // 首页不再分别去问活动、商品、场馆三个列表。
    expect(calls.filter((path) => path === '/api/v1/home/recommend')).toHaveLength(1)
    expect(calls).not.toContain('/api/v1/activities')
    expect(calls).not.toContain('/api/v1/mall/products')
    expect(calls).not.toContain('/api/v1/venues')

    await page.route('**/api/v1/home/recommend?**', (route: Route) => route.abort('internetdisconnected'))
    await page.reload()
    await expect(page.getByTestId('home-page')).toBeVisible()
    await expect(page.getByTestId('home-card-service')).toBeVisible()
    await expect(page.getByTestId('home-activities')).toHaveCount(0)
    await expect(page.getByTestId('home-products')).toHaveCount(0)
    await expect(page.getByTestId('home-venues')).toHaveCount(0)
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
