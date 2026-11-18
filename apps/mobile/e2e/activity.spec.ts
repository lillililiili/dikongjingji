import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// 赛事研学频道（只读）：列表、筛选、日期条、分页、详情与选场次。
// 真实数据来自 dev 环境的示例活动（backend/.../db/development/activity）；分页和出错两条用接口桩，
// 因为示例数据只有 5 条、也没法让真实后端按需失败。
const visualOutput = resolve(__dirname, '../../../.planning/activity/visual')
const ACTIVITIES_PATH = '/api/v1/activities'
const CONTEST_ID = 'a2000000-0000-4000-8000-000000000001'
const CAMP_ID = 'a2000000-0000-4000-8000-000000000003'
const OPEN_DAY_ID = 'a2000000-0000-4000-8000-000000000005'
const OFFLINE_ID = 'a2000000-0000-4000-8000-000000000006'

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
}

async function openActivityChannel(page: Page): Promise<void> {
  await openHome(page)
  await page.getByTestId('home-channel-activity').click()
  await expect(page.getByTestId('activity-channel')).toBeVisible()
  await expect(page.locator('.activity-card').first()).toBeVisible()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

function isListRequest(url: URL): boolean {
  return url.pathname === ACTIVITIES_PATH
}

function stubCard(index: number) {
  const id = `b0000000-0000-4000-8000-${String(index).padStart(12, '0')}`
  return {
    activityId: id,
    type: 'CONTEST',
    title: `分页示例活动 ${index}`,
    coverImage: null,
    venueName: '示例场馆',
    scheduleNote: null,
    price: { mode: 'FREE', amountMinor: null, currency: 'CNY' },
    ageRange: { minAge: null, maxAge: null },
    enrollmentStatus: 'OPEN',
    nextSession: {
      sessionId: id,
      startsAt: '2030-01-01T01:00:00Z',
      capacity: 30,
      seatsLeft: 30 - index,
    },
  }
}

test.describe('赛事研学频道', () => {
  test('ACT-001 首页轮播“查看赛事” → 赛事研学频道，列出本城活动', async ({ page }) => {
    await openHome(page)
    const pause = page.getByTestId('home-carousel-pause')
    if ((await pause.getAttribute('aria-label')) === '暂停轮播') await pause.click()
    await page.getByTestId('home-carousel-dot-contest').click()
    await page.getByTestId('home-carousel-action-contest').click()

    await expect(page.getByTestId('home-channel-activity')).toHaveClass(
      /home-header__channel--active/,
    )
    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await expect(page.locator('.activity-card')).toHaveCount(5)
    // The order follows each card's next bookable session, which moves as deadlines pass during
    // the day, so only the set of activities is fixed.
    for (const title of [
      '无人机开放体验日',
      '机器人研学体验',
      '黄河口低空研学一日营',
      '青少年无人机编程挑战赛（东营站）',
      '多旋翼无人机驾驶员培训',
    ]) {
      await expect(page.locator('.activity-card__title', { hasText: title })).toHaveCount(1)
    }
    const starts = await page.locator('.activity-card').evaluateAll((cards) =>
      cards.map((card) => card.querySelector('.activity-card__schedule')?.textContent ?? ''))
    expect(starts.every((text) => text.trim().length > 0)).toBe(true)
    await expect(page.getByTestId('activity-list-more')).toContainText('没有更多活动了')
    await expect(page.getByTestId('home-search-input')).toContainText('搜索赛事、培训、研学')
    await expect(page.getByTestId('root-tab-home')).toHaveClass(/root-tab-bar__item--active/)
  })

  test('ACT-002 金刚位“赛事报名” → 赛事研学频道', async ({ page }) => {
    await openHome(page)
    // The entry sits on the second page of the king area, off screen until swiped to.
    await page.getByTestId('home-king-contest').dispatchEvent('click')

    await expect(page.getByTestId('activity-channel')).toBeVisible()
    await expect(page.locator('.activity-card').first()).toBeVisible()
  })

  test('ACT-003 卡片显示价格、适合年龄和服务端给的状态', async ({ page }) => {
    await openActivityChannel(page)

    const contest = page.getByTestId(`activity-card-${CONTEST_ID}`)
    await expect(contest).toHaveAttribute('data-status', 'OPEN')
    await expect(contest.locator('.activity-card__price-value')).toHaveText('¥120')
    await expect(contest.locator('.activity-card__age')).toHaveText('8–15 岁')
    await expect(contest.locator('.activity-card__status')).toHaveText('余 12/60')
    await expect(contest.locator('.activity-card__type')).toHaveText('赛事')

    const camp = page.getByTestId(`activity-card-${CAMP_ID}`)
    await expect(camp).toHaveAttribute('data-status', 'FULL_WAITLIST')
    await expect(camp.locator('.activity-card__status')).toHaveText('已满 · 可候补')

    const openDay = page.getByTestId(`activity-card-${OPEN_DAY_ID}`)
    await expect(openDay.locator('.activity-card__price-value')).toHaveText('免费')
    await expect(openDay.locator('.activity-card__price-unit')).toHaveCount(0)

    await expect(page.getByText('已下线的示例赛事')).toHaveCount(0)
  })

  test('ACT-004 类型页签、“报名中”“免费”可筛选，筛空了能一键清除', async ({ page }) => {
    await openActivityChannel(page)

    await page.getByTestId('activity-type-study_tour').click()
    await expect(page.locator('.activity-card')).toHaveCount(2)
    await expect(page.locator('.activity-card__type')).toHaveText(['研学', '研学'])

    await page.getByTestId('activity-filter-enrolling').click()
    await expect(page.locator('.activity-card')).toHaveCount(1)
    await expect(page.locator('.activity-card__title')).toHaveText(['机器人研学体验'])

    await page.getByTestId('activity-filter-free').click()
    await expect(page.getByTestId('activity-list-empty')).toContainText('没有符合条件的活动')

    await page.getByTestId('activity-clear-filters').click()
    await expect(page.locator('.activity-card')).toHaveCount(5)
    await expect(page.getByTestId('activity-type-all')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('activity-filter-free')).toHaveAttribute('aria-pressed', 'false')

    await page.getByTestId('activity-filter-free').click()
    await expect(page.locator('.activity-card__title')).toHaveText(['无人机开放体验日'])
  })

  test('ACT-005 日期条：有场次的日子带圆点，点某天只看当天的活动', async ({ page }) => {
    await openActivityChannel(page)

    const marked = page.locator('.activity-channel__date[data-has-sessions="true"]')
    await expect(marked.first()).toBeVisible()
    const unmarked = page.locator('.activity-channel__date[data-has-sessions="false"]').first()

    // A marked day lists at least one activity; an unmarked day lists none.
    await marked.first().click()
    await expect(page.locator('.activity-card').first()).toBeVisible()
    const onThatDay = await page.locator('.activity-card').count()
    expect(onThatDay).toBeGreaterThan(0)
    expect(onThatDay).toBeLessThan(5)

    await unmarked.click()
    await expect(page.getByTestId('activity-list-empty')).toBeVisible()

    await page.getByTestId('activity-date-all').click()
    await expect(page.locator('.activity-card')).toHaveCount(5)
  })

  test('ACT-006 滚到底加载下一页，同一活动不重复，最后提示没有更多', async ({ page }) => {
    const requestedPages: string[] = []
    await page.route(`**${ACTIVITIES_PATH}?**`, async (route: Route) => {
      const url = new URL(route.request().url())
      if (!isListRequest(url)) return route.continue()
      const pageNumber = url.searchParams.get('page') ?? '1'
      requestedPages.push(pageNumber)
      const items = pageNumber === '1'
        ? Array.from({ length: 10 }, (_, index) => stubCard(index + 1))
        // Card 10 comes back on purpose: the list must keep one copy.
        : [stubCard(10), stubCard(11), stubCard(12)]
      await route.fulfill({
        json: { page: Number(pageNumber), size: 10, total: 12, hasMore: pageNumber === '1', items },
      })
    })

    await openActivityChannel(page)
    await expect(page.locator('.activity-card')).toHaveCount(10)

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect(page.locator('.activity-card')).toHaveCount(12)
    await expect(page.getByTestId('activity-list-more')).toContainText('没有更多活动了')

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await page.waitForTimeout(500)
    expect(requestedPages).toEqual(['1', '2'])
  })

  test('ACT-007 列表加载失败可重试', async ({ page }) => {
    let failing = true
    await page.route(`**${ACTIVITIES_PATH}?**`, (route: Route) => {
      const url = new URL(route.request().url())
      return failing && isListRequest(url)
        ? route.abort('internetdisconnected')
        : route.continue()
    })

    await openHome(page)
    await page.getByTestId('home-channel-activity').click()
    await expect(page.getByTestId('activity-list-error')).toContainText('活动加载失败')

    failing = false
    await page.getByTestId('activity-list-retry').click()
    await expect(page.locator('.activity-card')).toHaveCount(5)
  })

  test('ACT-008 切到别的频道再回来，筛选条件还在', async ({ page }) => {
    await openActivityChannel(page)
    await page.getByTestId('activity-type-training').click()
    await expect(page.locator('.activity-card')).toHaveCount(1)

    await page.getByTestId('home-channel-recommend').click()
    await expect(page.getByTestId('home-carousel')).toBeVisible()
    await page.getByTestId('home-channel-activity').click()

    await expect(page.getByTestId('activity-type-training')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.activity-card__title')).toHaveText(['多旋翼无人机驾驶员培训'])
  })
})

test.describe('活动详情', () => {
  test('ACT-101 列表 → 详情 → 选场次 → 主按钮变为“立即报名”，返回后回到频道', async ({
    page,
  }) => {
    await openActivityChannel(page)
    await page.getByTestId(`activity-card-${CONTEST_ID}`).click()

    await expect(page.getByTestId('activity-detail-title')).toHaveText(
      '青少年无人机编程挑战赛（东营站）',
    )
    await expect(page).toHaveURL(new RegExp(`activityId=${CONTEST_ID}.*cityCode=370500`))
    await expect(page.getByTestId('activity-detail-price')).toHaveText('¥120')
    await expect(page.getByTestId('activity-detail-status')).toHaveText('报名中')
    await expect(page.getByTestId('activity-detail')).toContainText('示例青少年科技机构')
    await expect(page.getByTestId('activity-detail')).toContainText('东营区示例路 66 号')
    await expect(page.getByTestId('activity-detail')).toContainText('8–15 岁，由监护人代报名并到场')
    await expect(page.getByTestId('activity-detail')).toContainText('活动开始 48 小时前可免费取消')
    await expect(page.locator('.root-tab-bar')).toHaveCount(0)

    const primary = page.getByTestId('activity-detail-primary')
    await expect(primary).toHaveText('选择场次')
    await expect(primary).toHaveAttribute('disabled', 'true')

    const sessions = page.locator('.activity-detail__session')
    await expect(sessions).toHaveCount(3)
    await expect(sessions.nth(0)).toHaveAttribute('data-status', 'OPEN')
    await expect(sessions.nth(1)).toHaveAttribute('data-status', 'FULL_WAITLIST')

    await sessions.nth(0).click()
    await expect(sessions.nth(0)).toHaveAttribute('aria-pressed', 'true')
    await expect(primary).toHaveText('立即报名')
    await expect(primary).not.toHaveAttribute('disabled', 'true')
    await expect(page.getByTestId('activity-session-note')).toContainText('报名截止')

    await primary.click()
    await expect(page.locator('.uni-sample-toast')).toContainText('报名功能即将开放')
    await expect(page.getByTestId('activity-detail')).toBeVisible()

    await sessions.nth(1).click()
    await expect(primary).toHaveText('加入候补')
    await expect(page.getByTestId('activity-detail')).toContainText('本场已满，加入候补后有名额会通知你')

    await page.getByTestId('activity-detail-back').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
    await expect(page.getByTestId('home-channel-activity')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('ACT-102 已结束的场次选不了；全部已满的活动只能候补', async ({ page }) => {
    await page.goto(`/#/pages/activity/detail?activityId=${OPEN_DAY_ID}&cityCode=370500`)
    await expect(page.getByTestId('activity-detail-title')).toHaveText('无人机开放体验日')
    await expect(page.getByTestId('activity-detail-price')).toHaveText('免费')

    const ended = page.locator('.activity-detail__session[data-status="ENDED"]')
    await expect(ended).toHaveCount(1)
    await ended.click({ force: true })
    await expect(ended).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByTestId('activity-detail-primary')).toHaveText('选择场次')

    // A hash-only change keeps the same page instance on H5, so the document is reloaded for the second activity.
    await page.goto(`/?second=1#/pages/activity/detail?activityId=${CAMP_ID}&cityCode=370500`)
    await expect(page.getByTestId('activity-detail-title')).toHaveText('黄河口低空研学一日营')
    await expect(page.getByTestId('activity-detail-status')).toHaveText('已满 · 可候补')
    await page.locator('.activity-detail__session').first().click()
    await expect(page.getByTestId('activity-detail-primary')).toHaveText('加入候补')
    // This camp does not take team sign-ups.
    await expect(page.getByTestId('activity-detail-team')).toHaveCount(0)
  })

  test('ACT-103 团队报名、咨询入口给出提示，不跳走', async ({ page }) => {
    await page.goto(`/#/pages/activity/detail?activityId=${CONTEST_ID}&cityCode=370500`)
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()
    const url = page.url()

    await page.getByTestId('activity-detail-team').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('团队报名即将开放')
    expect(page.url()).toBe(url)
  })

  test('ACT-104 已下线、别的城市、缺参数的链接都落到“活动不存在”，并能回频道', async ({
    page,
  }) => {
    for (const query of [
      `activityId=${OFFLINE_ID}&cityCode=370500`,
      `activityId=${CONTEST_ID}&cityCode=370100`,
      'cityCode=370500',
      'activityId=not-a-uuid&cityCode=370500',
    ]) {
      await page.goto(`/?case=${encodeURIComponent(query)}#/pages/activity/detail?${query}`)
      await expect(page.getByTestId('activity-detail-missing')).toContainText('活动不存在或已下线')
      await expect(page.getByTestId('activity-detail-primary')).toHaveCount(0)
    }

    await page.getByTestId('activity-detail-to-channel').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
  })

  test('ACT-105 详情加载失败可重试', async ({ page }) => {
    let failing = true
    await page.route(`**${ACTIVITIES_PATH}/${CONTEST_ID}?**`, (route: Route) =>
      failing ? route.abort('internetdisconnected') : route.continue())

    await page.goto(`/#/pages/activity/detail?activityId=${CONTEST_ID}&cityCode=370500`)
    await expect(page.getByTestId('activity-detail-error')).toContainText('活动加载失败')

    failing = false
    await page.getByTestId('activity-detail-retry').click()
    await expect(page.getByTestId('activity-detail-title')).toHaveText(
      '青少年无人机编程挑战赛（东营站）',
    )
  })

  test('ACT-106 直接打开的详情页，返回落在赛事研学频道', async ({ page }) => {
    await page.goto(`/#/pages/activity/detail?activityId=${CONTEST_ID}&cityCode=370500`)
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()

    await page.getByTestId('activity-detail-back').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
  })
})

test('VIS-ACT 频道与详情在 320/375/430 无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openActivityChannel(page)
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `activity-channel-${width}.png`) })

    await page.getByTestId(`activity-card-${CONTEST_ID}`).click()
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()
    await page.locator('.activity-detail__session').first().click()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({
      path: resolve(visualOutput, `activity-detail-${width}.png`),
      fullPage: true,
    })
    await page.getByTestId('activity-detail-back').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
  }
})
