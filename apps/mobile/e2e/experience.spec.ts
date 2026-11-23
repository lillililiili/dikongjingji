import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// 体验频道与场馆（只读）：线上体验列表、场馆列表、场馆详情与场次、馆内设备。
// 数据来自 dev 环境的示例场馆（backend/.../db/development/experience），场次按灌入当天起 7 天生成；
// 超过一周没重新灌数据时，按场次断言的用例会找不到可约场次，届时按种子文件开头的说明刷新日期。
const visualOutput = resolve(__dirname, '../../../.planning/experience/visual')
const VENUE_ID = 'b2000000-0000-4000-8000-000000000001'
const OFFLINE_VENUE_ID = 'b2000000-0000-4000-8000-000000000003'
const ROBOT_PROJECT_ID = 'b4000000-0000-4000-8000-000000000101'
const CODING_PROJECT_ID = 'b4000000-0000-4000-8000-000000000103'
const SIMULATION_ID = 'b1000000-0000-4000-8000-000000000001'
const RECORDED_DEMO_ID = 'b1000000-0000-4000-8000-000000000003'

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
}

async function openExperienceChannel(page: Page): Promise<void> {
  await openHome(page)
  await page.getByTestId('home-channel-experience').click()
  await expect(page.getByTestId('experience-channel')).toBeVisible()
  await expect(page.locator('.experience-card').first()).toBeVisible()
}

async function openVenue(page: Page, venueId = VENUE_ID): Promise<void> {
  await page.goto(`/?venue=${venueId}#/pages/venue/detail?venueId=${venueId}&cityCode=370500`)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

// Walks the day tabs until the sessions area satisfies the check; the seed guarantees one within a week.
async function selectDayWhere(
  page: Page,
  matches: () => Promise<boolean>,
  description: string,
): Promise<void> {
  const days = page.locator('.venue-detail__day')
  const count = await days.count()
  for (let index = 0; index < count; index += 1) {
    await days.nth(index).click()
    await expect(page.getByTestId('venue-sessions-loading')).toHaveCount(0)
    if (await matches()) return
  }
  throw new Error(`no day within the strip has ${description}`)
}

test.describe('体验频道', () => {
  test('EXP-001 首页大卡“去体验” → 体验频道，列出线上体验并可按形式筛选', async ({ page }) => {
    await openHome(page)
    await page.getByTestId('home-card-experience').click()

    await expect(page.getByTestId('home-channel-experience')).toHaveClass(
      /home-header__channel--active/,
    )
    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('experience-mode-online')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.experience-card__title')).toHaveText([
      '机器人避障模拟',
      '无人机起降与悬停',
      '3 分钟看懂编程机器人',
      '图形化编程软件试用',
      '无人机飞行原理（试听）',
    ])
    await expect(page.getByText('未发布的示例仿真')).toHaveCount(0)
    await expect(page.getByTestId('home-search-input')).toContainText('搜索体验项目或场馆')

    await page.getByTestId('experience-type-simulation').click()
    await expect(page.locator('.experience-card')).toHaveCount(2)
    await expect(page.locator('.experience-card__tag').first()).toHaveText('虚拟仿真 · 不连接真实设备')

    await page.getByTestId('experience-type-course_trial').click()
    await expect(page.locator('.experience-card__title')).toHaveText(['无人机飞行原理（试听）'])

    await page.getByTestId('experience-type-all').click()
    await expect(page.locator('.experience-card')).toHaveCount(5)
  })

  test('EXP-002 首页轮播“探索体验” → 体验频道', async ({ page }) => {
    await openHome(page)
    const pause = page.getByTestId('home-carousel-pause')
    if ((await pause.getAttribute('aria-label')) === '暂停轮播') await pause.click()
    await page.getByTestId('home-carousel-dot-experience').click()
    await page.getByTestId('home-carousel-action-experience').click()

    await expect(page.getByTestId('experience-channel')).toBeVisible()
    await expect(page.getByTestId('root-tab-home')).toHaveClass(/root-tab-bar__item--active/)
  })

  // S5-4 起虚拟仿真、软件试用、课程试听有自己的页面（e2e/experience-sim.spec.ts）；录播演示还没做。
  test('EXP-003 还没做的体验形式点开只给提示，不跳走；虚拟仿真进任务页', async ({ page }) => {
    await openExperienceChannel(page)
    const url = page.url()

    await page.getByTestId(`experience-item-${RECORDED_DEMO_ID}`).click()
    await expect(page.locator('.uni-sample-toast')).toContainText('录播演示即将开放')
    expect(page.url()).toBe(url)

    await page.getByTestId(`experience-item-${SIMULATION_ID}`).click()
    await expect(page.getByTestId('simulation-page')).toBeVisible()
  })

  test('EXP-004 线下体验列出本城场馆，已下线的不出现', async ({ page }) => {
    await openExperienceChannel(page)
    await page.getByTestId('experience-mode-offline').click()

    await expect(page.locator('.venue-card__name')).toHaveText([
      '东营低空与机器人体验馆',
      '黄河口户外飞行营地',
    ])
    await expect(page.getByTestId(`venue-card-${VENUE_ID}`)).toContainText('09:30–18:00 · 周一闭馆')
    // Board ExpCenter: two lines, opening hours and (while sessions are left today) the bookable
    // count; the address line is gone.
    await expect(page.getByTestId(`venue-card-${VENUE_ID}`)).not.toContainText('东营区示例路 66 号')
    await expect(page.getByText('已下线的示例场馆')).toHaveCount(0)

    // The choice survives a trip to another channel.
    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-channel-experience').click()
    await expect(page.getByTestId('experience-mode-offline')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.venue-card')).toHaveCount(2)
  })

  test('EXP-005 频道里的“趣玩挑战”进到趣玩，返回回到体验频道', async ({ page }) => {
    await openExperienceChannel(page)
    await page.getByTestId('experience-games').click()
    await expect(page.getByTestId('play-games')).toBeVisible()

    await page.getByTestId('play-back').click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  })

  test('EXP-006 线上体验加载失败可重试', async ({ page }) => {
    let failing = true
    await page.route('**/api/v1/experiences?**', (route: Route) =>
      failing ? route.abort('internetdisconnected') : route.continue())

    await openHome(page)
    await page.getByTestId('home-channel-experience').click()
    await expect(page.getByTestId('experience-list-error')).toContainText('体验项目加载失败')

    failing = false
    await page.getByTestId('experience-list-retry').click()
    await expect(page.locator('.experience-card')).toHaveCount(5)
  })
})

test.describe('场馆详情', () => {
  test('VEN-101 场馆卡 → 详情 → 选项目和场次 → “预约到馆”进确认页（游客先去登录）', async ({ page }) => {
    await openExperienceChannel(page)
    await page.getByTestId('experience-mode-offline').click()
    await page.getByTestId(`venue-card-${VENUE_ID}`).click()

    await expect(page.getByTestId('venue-detail-name')).toHaveText('东营低空与机器人体验馆')
    await expect(page).toHaveURL(new RegExp(`venueId=${VENUE_ID}.*cityCode=370500`))
    await expect(page.getByTestId('venue-detail')).toContainText('09:30–18:00 · 周一闭馆')
    await expect(page.locator('.venue-detail__project-name')).toHaveText([
      '机器人避障闯关',
      '无人机模拟飞行',
      '编程体验课',
    ])
    const robot = page.getByTestId(`venue-project-${ROBOT_PROJECT_ID}`)
    await expect(robot).toHaveAttribute('aria-pressed', 'true')
    // The selected project's duration, limit and price are in the card under the pills.
    const selected = page.getByTestId('venue-project-detail')
    await expect(selected).toContainText('机器人避障闯关')
    await expect(selected).toContainText('30 分钟 · 6 岁以上 · 每场 8 人')
    await expect(selected).toContainText('未满 8 岁需 1 名成人陪同')
    await expect(selected).toContainText('¥39')
    await expect(page.getByTestId('venue-detail')).toContainText('开场 10 分钟后停止入场')
    await expect(page.locator('.root-tab-bar')).toHaveCount(0)

    const book = page.getByTestId('venue-detail-book')
    // 画板：没选场次时按钮仍是橙色，点了提示先选场次。
    await expect(book).toHaveAttribute('data-ready', 'false')
    await expect(page.getByTestId('venue-detail-bar-line')).toHaveText('请选择场次')
    await book.click()
    await expect(page.locator('uni-toast')).toContainText('请先选择一个场次')

    // Tomorrow always has bookable sessions; today may already be over.
    await page.locator('.venue-detail__day').nth(1).click()
    const open = page.locator('.venue-detail__slot[data-status="OPEN"]').first()
    await expect(open).toBeVisible()
    await open.click()
    await expect(open).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('venue-detail-bar-line')).toContainText('明天')
    await expect(page.getByTestId('venue-detail-bar-line')).toContainText('· 1 人')
    await expect(book).toHaveAttribute('data-ready', 'true')

    // E10: no longer a "coming soon" prompt. A guest is sent to sign in first, with the confirm page
    // (venue, project, session and its day in the URL) as where to come back to.
    await book.click()
    await expect(page.locator('.inspection-login__form')).toBeVisible()
    const returnUrl = decodeURIComponent(new URL(page.url().replace('/#/', '/')).searchParams.get('returnUrl') ?? '')
    expect(returnUrl).toMatch(new RegExp(`^/pages/venue/book\\?venueId=${VENUE_ID}&cityCode=370500&projectId=${ROBOT_PROJECT_ID}&sessionId=[0-9a-f-]{36}&date=\\d{4}-\\d{2}-\\d{2}$`))

    await page.goBack()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
    await page.getByTestId('venue-detail-back').click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  })

  test('VEN-102 已满的场次选不了；换项目或日期后已选场次清空', async ({ page }) => {
    await openVenue(page)
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()

    await selectDayWhere(
      page,
      async () => (await page.locator('.venue-detail__slot[data-status="FULL"]').count()) > 0,
      'a full session',
    )
    const full = page.locator('.venue-detail__slot[data-status="FULL"]').first()
    await expect(full).toContainText('已满')
    await full.click({ force: true })
    await expect(full).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByTestId('venue-detail-book')).toHaveAttribute('data-ready', 'false')

    const open = page.locator('.venue-detail__slot[data-status="OPEN"]').first()
    await open.click()
    await expect(page.getByTestId('venue-detail-book')).toHaveAttribute('data-ready', 'true')

    await page.locator('.venue-detail__project').nth(1).click()
    await expect(page.getByTestId('venue-sessions-loading')).toHaveCount(0)
    await expect(page.getByTestId('venue-detail-bar-line')).toHaveText('请选择场次')
    await expect(page.getByTestId('venue-detail-book')).toHaveAttribute('data-ready', 'false')
  })

  test('VEN-103 某天没有场次时说明是哪一天、哪个项目没有', async ({ page }) => {
    await openVenue(page)
    await page.getByTestId(`venue-project-${CODING_PROJECT_ID}`).click()
    await expect(page.getByTestId('venue-sessions-loading')).toHaveCount(0)

    await selectDayWhere(
      page,
      async () => (await page.getByTestId('venue-sessions-empty').count()) > 0,
      'no session for the coding class',
    )
    await expect(page.getByTestId('venue-sessions-empty')).toContainText('没有这个项目的场次')
  })

  test('VEN-104 馆内设备可按分类看，标明能不能上手', async ({ page }) => {
    await openVenue(page)
    await page.getByTestId('venue-detail-devices').click()

    await expect(page.getByTestId('venue-devices')).toBeVisible()
    await expect(page.locator('.venue-devices__card')).toHaveCount(6)
    // 画板：页头副标题写场馆和件数，列表底部不写“没有更多”。
    await expect(page.getByTestId('venue-devices-head')).toContainText('东营低空与机器人体验馆 · 6 件')
    await expect(page.locator('.venue-devices__more')).toHaveCount(0)

    await page.getByTestId('venue-devices-tab-robot').click()
    await expect(page.locator('.venue-devices__name')).toHaveText(['编程机器人探索套装', '巡检机器狗'])
    await expect(page.locator('.venue-devices__trial')).toHaveText([
      '工作人员陪同试用',
      '只展示，不试用',
    ])

    // S4-4（体验 1.5.0）：设备关联商品在东营的价格和在售家数（画板 VenueDevices），数字来自商城开发数据。
    await expect(page.locator('.venue-devices__offer')).toHaveText([
      /¥1,299\s*起\s*3 家在售/,
      /¥380\s*\/天\s*租赁 · 1 家/,
    ])
    await page.getByTestId('venue-devices-tab-all').click()
    await expect(page.locator('.venue-devices__card')).toHaveCount(6)
    await expect(page.locator('.venue-devices__offer')).toHaveText([
      /¥1,299\s*起\s*3 家在售/,
      /¥4,999\s*起\s*2 家在售/,
      /¥380\s*\/天\s*租赁 · 1 家/,
      /¥386\s*1 家在售/,
      /¥460\s*1 家在售/,
      /询价\s*按项目报价/,
    ])
    await expect(page.locator('.venue-devices__notice')).toHaveText('到馆可看真机，想买或想租到商品页选商家')
    // 点设备进商品页选商家，返回回到设备列表。
    await page.locator('.venue-devices__card').first().click()
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await page.getByTestId('mall-product-back').click()
    await expect(page.getByTestId('venue-devices')).toBeVisible()
    await page.getByTestId('venue-devices-tab-robot').click()

    // The button at the bottom leads back to the venue, where a visit is booked.
    await page.getByTestId('venue-devices-book').click()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
    await page.getByTestId('venue-detail-devices').click()
    await expect(page.locator('.venue-devices__card').first()).toBeVisible()

    await page.getByTestId('venue-devices-back').click()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
  })

  test('VEN-105 已下线、别的城市、缺参数的链接都落到“场馆不存在”，并能回频道', async ({
    page,
  }) => {
    for (const query of [
      `venueId=${OFFLINE_VENUE_ID}&cityCode=370500`,
      `venueId=${VENUE_ID}&cityCode=370100`,
      'cityCode=370500',
      'venueId=not-a-uuid&cityCode=370500',
    ]) {
      await page.goto(`/?case=${encodeURIComponent(query)}#/pages/venue/detail?${query}`)
      await expect(page.getByTestId('venue-detail-missing')).toContainText('场馆不存在或已下线')
      await expect(page.getByTestId('venue-detail-book')).toHaveCount(0)
    }

    await page.getByTestId('venue-detail-to-channel').click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  })

  test('VEN-106 场馆和场次加载失败都可重试', async ({ page }) => {
    let failingVenue = true
    let failingSessions = true
    await page.route(`**/api/v1/venues/${VENUE_ID}?**`, (route: Route) =>
      failingVenue ? route.abort('internetdisconnected') : route.continue())
    await page.route(`**/api/v1/venues/${VENUE_ID}/sessions?**`, (route: Route) =>
      failingSessions ? route.abort('internetdisconnected') : route.continue())

    await openVenue(page)
    await expect(page.getByTestId('venue-detail-error')).toContainText('场馆加载失败')

    failingVenue = false
    await page.getByTestId('venue-detail-retry').click()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
    await expect(page.getByTestId('venue-sessions-error')).toContainText('场次加载失败')

    failingSessions = false
    await page.getByTestId('venue-sessions-retry').click()
    await expect(page.getByTestId('venue-sessions-error')).toHaveCount(0)
    await expect(page.locator('.venue-detail__slot').first()).toBeVisible()
  })
})

test('VIS-EXP 体验频道、场馆详情、馆内设备在 320/375/430 无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openExperienceChannel(page)
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `experience-online-${width}.png`) })

    await page.getByTestId('experience-mode-offline').click()
    await expect(page.locator('.venue-card').first()).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `experience-offline-${width}.png`) })

    await page.getByTestId(`venue-card-${VENUE_ID}`).click()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
    await page.locator('.venue-detail__day').nth(1).click()
    await expect(page.locator('.venue-detail__slot').first()).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `venue-detail-${width}.png`), fullPage: true })

    await page.getByTestId('venue-detail-devices').click()
    await expect(page.locator('.venue-devices__card').first()).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `venue-devices-${width}.png`) })

    await page.getByTestId('venue-devices-back').click()
    await page.getByTestId('venue-detail-back').click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
    // Leave the channel on the online list for the next width.
    await page.getByTestId('experience-mode-online').click()
  }
})
