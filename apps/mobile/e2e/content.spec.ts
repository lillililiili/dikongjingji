import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

import { stubSupportChat } from './support/support-fixtures'
import { BUYER, apiLogin, type Session } from './support/trade-fixtures'

// 发现页的图文内容与专题（H1，计划 §27）：底栏“发现”真实点击进入，推荐 / 赛事 / 研学 / 案例 / 知识五个频道（赛事、研学
// 直接读赛事研学模块的活动列表），两列瀑布流和专题卡，图文详情的作者、正文块和“内容中提到”（点进巡检服务、商品的真实页面），
// “主页”（服务商主页、商家提示、平台没有），“咨询服务商”（会话接口用桩，不在开发库里留会话），分享只复制链接，专题页三块。
// 真实后端和开发种子；分页、失败和慢响应用桩。
const CITY = '370500'
const CASE_DOG = 'f3000000-0000-4000-8000-000000000001'
const CASE_SURVEY = 'f3000000-0000-4000-8000-000000000002'
const CASE_SOLAR = 'f3000000-0000-4000-8000-000000000003'
const KNOW_DRONE = 'f3000000-0000-4000-8000-000000000004'
const KNOW_ROBOT = 'f3000000-0000-4000-8000-000000000005'
const KNOW_CHECK = 'f3000000-0000-4000-8000-000000000006'
const TOPIC = 'f3100000-0000-4000-8000-000000000001'
const EAGLE_PROVIDER = 'f2000000-0000-4000-8000-000000000001'
const PARK_INSPECTION = '77777777-7777-7777-7777-777777777777'
const ROBOT_DOG = 'c2000000-0000-4000-8000-000000000401'
const CONTEST = 'a2000000-0000-4000-8000-000000000001'
const OPEN_DAY = 'a2000000-0000-4000-8000-000000000005'
const STUDY_CAMP = 'a2000000-0000-4000-8000-000000000003'
const STUDY_ROBOT = 'a2000000-0000-4000-8000-000000000002'
const TRAINING = 'a2000000-0000-4000-8000-000000000004'
const VENUE = 'b2000000-0000-4000-8000-000000000001'
const visualOutput = resolve(__dirname, '../../../../../.planning/content/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/content')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')

const discover = (page: Page) => page.getByTestId('content-discover')
const article = (page: Page, id: string) => page.getByTestId(`discover-article-${id}`)

async function openDiscoverFromHome(page: Page): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/home/index`)
  await page.getByTestId('root-tab-discover').click()
  await expect(discover(page)).toHaveAttribute('data-state', 'ready')
}

async function openArticle(page: Page, id: string): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/content/article?articleId=${id}&cityCode=${CITY}`)
  await expect(page.getByTestId('content-article')).toHaveAttribute('data-state', 'ready')
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function tallShot(page: Page, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  await page.evaluate(() => window.scrollTo(0, 0))
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(250)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

function feedCard(index: number) {
  return {
    articleId: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    kind: index % 2 ? 'CASE' : 'KNOWLEDGE',
    title: `分页测试内容 ${index}`,
    author: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' },
    sponsored: false,
    cityCode: CITY,
    coverImage: null,
    sample: true,
    publishedAt: '2026-11-10T02:00:00Z',
  }
}

function feedPage(route: Route, page: number, hasMore: boolean, items: unknown[]) {
  return route.fulfill({
    json: { channel: 'RECOMMEND', cityCode: CITY, page, size: 10, total: 13, hasMore, topics: [], items },
  })
}

test.describe('发现页图文内容与专题', () => {
  test('CNT-001 底栏“发现”进入推荐：专题卡、两列瀑布流、示例和广告标记、没有更多', async ({ page }) => {
    await openDiscoverFromHome(page)
    await expect(page).toHaveURL(/#\/pages\/discover\/index/)
    await expect(page.getByTestId('root-tab-discover')).toHaveClass(/root-tab-bar__item--active/)
    await expect(page.getByTestId('discover-city')).toContainText('东营')
    // 视频（1.2.0）排在推荐后面，直播（M20）在视频后面；live.spec.ts、content-video.spec.ts 各自覆盖。
    await expect(page.getByRole('tab')).toHaveText(['推荐', '视频', '直播', '赛事', '研学', '案例', '知识'])
    await expect(page.getByTestId('discover-channel-recommend')).toHaveAttribute('aria-selected', 'true')

    const topic = page.getByTestId(`discover-topic-${TOPIC}`)
    await expect(topic).toContainText('黄河口低空体验季')
    await expect(topic).toContainText('到馆体验、赛事、研学合集')
    await expect(topic).toContainText('东营城市运营中心')
    await expect(topic).toContainText('（示例）')

    const grid = page.getByTestId('discover-grid')
    await expect(grid.locator('[data-testid^="discover-article-"]')).toHaveCount(6)
    await expect(grid.locator('.discover__column')).toHaveCount(2)
    for (const id of [CASE_DOG, CASE_SURVEY, CASE_SOLAR, KNOW_DRONE, KNOW_ROBOT, KNOW_CHECK]) {
      await expect(article(page, id).getByTestId('content-sample')).toHaveText('示例')
    }
    // Merchants' knowledge is advertising; a provider's case and the platform's knowledge are not.
    await expect(article(page, KNOW_ROBOT).getByTestId('content-sponsored')).toHaveText('广告')
    await expect(article(page, KNOW_CHECK).getByTestId('content-sponsored')).toHaveText('广告')
    await expect(article(page, CASE_DOG).getByTestId('content-sponsored')).toHaveCount(0)
    await expect(article(page, KNOW_DRONE).getByTestId('content-sponsored')).toHaveCount(0)
    // No licensed pictures yet: preview builds show the prototype's photos (plan 06 R2), one per
    // card with the title under it; no counts without a source.
    await expect(grid.locator('.content-thumb__image')).toHaveCount(6)
    await expect(grid.locator('.discover__card-title')).toHaveCount(6)
    await expect(article(page, CASE_DOG)).toContainText('一台机器狗如何完成园区夜间巡逻')
    // The live bar above the grid counts its viewers (live.spec.ts); the cards carry no counts.
    await expect(grid).not.toContainText('观看')
    // 推荐里混着视频（content-video.spec.ts），第一页 10 条：滑到底读完第二页才说没有更多。
    await page.mouse.move(195, 400)
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect(page.getByTestId('discover-no-more')).toHaveText('没有更多内容了')
    await assertNoHorizontalOverflow(page)
  })

  test('CNT-002 频道切换：赛事、研学读真实活动，案例、知识读图文，点活动进活动详情', async ({ page }) => {
    await openDiscoverFromHome(page)

    await page.getByTestId('discover-channel-events').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'EVENTS')
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('discover-note')).toContainText('赛事由主办方发布')
    const events = page.locator('[data-testid^="discover-activity-"]')
    await expect(events).toHaveCount(3)
    for (const id of [CONTEST, OPEN_DAY, TRAINING]) await expect(page.getByTestId(`discover-activity-${id}`)).toBeVisible()
    await expect(page.getByTestId(`discover-activity-${CONTEST}`)).toContainText('¥120/人')
    await expect(page.getByTestId(`discover-activity-${OPEN_DAY}`)).toContainText('免费')
    await expect(page.getByTestId(`discover-activity-${TRAINING}`)).toContainText('询价')

    await page.getByTestId('discover-channel-study').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'STUDY')
    await expect(events).toHaveCount(2)
    for (const id of [STUDY_CAMP, STUDY_ROBOT]) await expect(page.getByTestId(`discover-activity-${id}`)).toBeVisible()

    await page.getByTestId('discover-channel-case').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'CASE')
    await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(3)
    await expect(page.getByTestId('discover-grid')).toHaveCount(0)
    await expect(page.getByTestId('discover-note')).toContainText('案例由服务商提供')

    await page.getByTestId('discover-channel-knowledge').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'KNOWLEDGE')
    await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(3)
    await expect(article(page, KNOW_ROBOT)).toContainText('示例机器人商家')

    await page.getByTestId('discover-channel-events').click()
    await page.getByTestId(`discover-activity-${CONTEST}`).click()
    await expect(page).toHaveURL(new RegExp(`activity/detail\\?activityId=${CONTEST}&cityCode=${CITY}`))
    await expect(page.getByTestId('activity-detail')).toContainText('青少年无人机编程挑战赛')
  })

  test('CNT-003 快速切换频道：先点的慢频道后返回，也不会盖住后点的频道', async ({ page }) => {
    await openDiscoverFromHome(page)
    let release: () => void = () => undefined
    const held = new Promise<void>((resolve) => { release = resolve })
    await page.route('**/api/v1/content/feed?*channel=KNOWLEDGE*', async (route) => {
      await held
      await route.continue()
    })

    await page.getByTestId('discover-channel-knowledge').click()
    await expect(discover(page)).toHaveAttribute('data-state', 'loading')
    await page.getByTestId('discover-channel-case').click()
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')
    await expect(discover(page)).toHaveAttribute('data-channel', 'CASE')
    const knowledgeAnswered = page.waitForResponse((response) => response.url().includes('channel=KNOWLEDGE'))
    release()
    await knowledgeAnswered
    await page.waitForTimeout(200)
    await expect(discover(page)).toHaveAttribute('data-channel', 'CASE')
    await expect(page.getByTestId('discover-channel-case')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(3)
    await expect(article(page, KNOW_DRONE)).toHaveCount(0)
  })

  test('CNT-004 分页：滑到底读下一页，下一页失败可点重试，读完说没有更多', async ({ page }) => {
    let secondPageCalls = 0
    await page.route('**/api/v1/content/feed?*channel=RECOMMEND*', (route) => {
      const url = new URL(route.request().url())
      const number = Number(url.searchParams.get('page'))
      if (number === 1) return feedPage(route, 1, true, Array.from({ length: 10 }, (_, index) => feedCard(index + 1)))
      secondPageCalls += 1
      if (secondPageCalls === 1) return route.fulfill({ status: 503, json: { code: 'COMMON_UNAVAILABLE', message: 'x', traceId: 't' } })
      return feedPage(route, 2, false, [feedCard(11), feedCard(12), feedCard(13)])
    })
    await openDiscoverFromHome(page)
    const cards = page.locator('[data-testid^="discover-article-"]')
    await expect(cards).toHaveCount(10)
    await expect(page.getByTestId('discover-no-more')).toHaveCount(0)

    await page.mouse.move(195, 400)
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect(page.getByTestId('discover-more-retry')).toBeVisible()
    await expect(cards).toHaveCount(10)

    await page.getByTestId('discover-more-retry').click()
    await expect(cards).toHaveCount(13)
    await expect(page.getByTestId('discover-no-more')).toHaveText('没有更多内容了')
    expect(secondPageCalls).toBe(2)
  })

  test('CNT-005 首屏失败显示重试，恢复后读出内容', async ({ page }) => {
    await page.route('**/api/v1/content/feed?*', (route) => route.fulfill({ status: 500, json: { code: 'X', message: 'x', traceId: 't' } }))
    await openDiscoverFromHome(page).catch(() => undefined)
    await expect(page.getByTestId('discover-error')).toContainText('内容加载失败')
    await page.unroute('**/api/v1/content/feed?*')
    await page.getByTestId('discover-retry').click()
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')
    await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(6)
  })

  test('CNT-006 图文详情：作者、正文块、内容中提到进巡检服务和商品的真实页面，主页进服务商主页', async ({ page }) => {
    await openDiscoverFromHome(page)
    await article(page, CASE_DOG).click()
    await expect(page).toHaveURL(new RegExp(`content/article\\?articleId=${CASE_DOG}&cityCode=${CITY}`))
    const detail = page.getByTestId('content-article')
    await expect(detail).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-article-title')).toHaveText('一台机器狗如何完成园区夜间巡逻')
    await expect(page.getByTestId('content-article-author')).toContainText('鹰眼智能巡检')
    await expect(page.getByTestId('content-article-meta')).toHaveText(/^\d{2}\/\d{2} 发布 · 东营$/)
    await expect(detail.getByTestId('content-sample')).toHaveText('示例')
    await expect(detail.getByTestId('content-sponsored')).toHaveCount(0)
    await expect(detail).toContainText('物流园区夜间安防')
    await expect(detail).toContainText('现场的难处')
    await expect(detail).toContainText('白天踏勘，定路线')
    await expect(detail).toContainText('围墙外侧由无人机补充巡查')
    await expect(detail).toContainText('案例由服务商提供')

    const refs = page.getByTestId('content-article-refs')
    await expect(refs.locator('[data-testid^="content-ref-"]')).toHaveCount(2)
    const service = page.getByTestId(`content-ref-${PARK_INSPECTION}`)
    await expect(service).toContainText('服务')
    await expect(service).toContainText('看服务')
    const dog = page.getByTestId(`content-ref-${ROBOT_DOG}`)
    await expect(dog).toContainText('租赁')
    await expect(dog).toContainText('巡检机器狗')
    await expect(dog).toContainText('/天')
    await expect(page.getByTestId('content-article-primary')).toHaveText('看服务')

    await service.click()
    await expect(page).toHaveURL(new RegExp(`service/inspection/detail\\?serviceId=${PARK_INSPECTION}&cityCode=${CITY}`))
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await page.goBack()
    await expect(page.getByTestId('content-article')).toHaveAttribute('data-state', 'ready')

    await page.getByTestId(`content-ref-${ROBOT_DOG}`).click()
    await expect(page).toHaveURL(new RegExp(`mall/product\\?productId=${ROBOT_DOG}&cityCode=${CITY}`))
    await expect(page.getByTestId('mall-product')).toContainText('巡检机器狗')
    await page.goBack()
    await expect(page.getByTestId('content-article')).toHaveAttribute('data-state', 'ready')

    await page.getByTestId('content-article-home').click()
    await expect(page).toHaveURL(new RegExp(`provider/detail\\?providerId=${EAGLE_PROVIDER}&cityCode=${CITY}`))
    await expect(page.getByTestId('provider-page')).toHaveAttribute('data-state', 'ready')
    await page.goBack()
    await page.getByTestId('content-article-back').click()
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')
  })

  test('CNT-007 商家内容标广告，主页给提示；平台内容没有主页和咨询', async ({ page }) => {
    await openArticle(page, KNOW_ROBOT)
    await expect(page.getByTestId('content-article-meta')).toHaveText(/^\d{2}\/\d{2} 发布 · 知识$/)
    await expect(page.getByTestId('content-article').getByTestId('content-sponsored')).toHaveText('广告')
    await expect(page.getByTestId('content-article-consult')).toHaveText('咨询商家')
    await expect(page.getByTestId('content-article-primary')).toHaveText('看商品')
    await page.getByTestId('content-article-home').click()
    await expect(page.locator('uni-toast')).toContainText('商家主页暂未开通')
    await expect(page).toHaveURL(/content\/article/)

    await openArticle(page, KNOW_DRONE)
    await expect(page.getByTestId('content-article-author')).toContainText('东营城市运营中心')
    await expect(page.getByTestId('content-article-home')).toHaveCount(0)
    await expect(page.getByTestId('content-article-consult')).toHaveCount(0)
    await expect(page.getByTestId('content-article-primary')).toHaveText('看商品')
  })

  test('CNT-008 咨询服务商进这家服务商的客服会话，分享只复制带编号和城市的链接', async ({ page, request }) => {
    const buyer = await apiLogin(request, BUYER)
    test.skip(!buyer, 'needs the development backend with dev-customer')
    await signIn(page, buyer as Session)
    await openArticle(page, CASE_DOG)

    await page.getByTestId('content-article-share').click()
    const sheet = page.getByTestId('share-sheet')
    await expect(sheet).toContainText('分享内容')
    await expect(sheet).toContainText('鹰眼智能巡检')
    await expect(sheet).toHaveAttribute('data-link', new RegExp(`#/pages/content/article\\?articleId=${CASE_DOG}&cityCode=${CITY}$`))
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.getByTestId('share-copy').click()
    await expect(page.getByTestId('share-copied')).toContainText('链接已复制')
    await page.getByTestId('share-close').click()

    const opened = await stubSupportChat(page, { kind: 'PROVIDER', organizationId: EAGLE_PROVIDER, name: '鹰眼智能巡检' })
    await page.getByTestId('content-article-consult').click()
    await expect(page).toHaveURL(new RegExp(`support/chat\\?(.*&)?(kind=PROVIDER&organizationId=${EAGLE_PROVIDER}|conversationId=)`))
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    expect(opened).toEqual([{ counterpartKind: 'PROVIDER', organizationId: EAGLE_PROVIDER }])
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  })

  test('CNT-009 专题页：本季活动、到馆体验、相关内容都读真实数据，点进各自页面', async ({ page }) => {
    await openDiscoverFromHome(page)
    await page.getByTestId(`discover-topic-${TOPIC}`).click()
    await expect(page).toHaveURL(new RegExp(`content/topic\\?topicId=${TOPIC}&cityCode=${CITY}`))
    const topic = page.getByTestId('content-topic')
    await expect(topic).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-topic-title')).toHaveText('黄河口低空体验季')
    await expect(page.getByTestId('content-topic-publisher')).toHaveText('东营城市运营中心')
    await expect(page.getByTestId('content-topic-dates')).toHaveText(/^\d{2}\/\d{2}–\d{2}\/\d{2}（示例）$/)
    await expect(topic.getByTestId('content-sample').first()).toBeVisible()

    const activities = page.getByTestId('content-topic-activities').locator('[data-testid^="content-topic-activity-"]')
    await expect(activities).toHaveCount(3)
    await expect(activities.nth(0)).toContainText('青少年无人机编程挑战赛')
    await expect(activities.nth(0)).toContainText('¥120')
    await expect(activities.nth(1)).toContainText('无人机开放体验日')
    await expect(activities.nth(1)).toContainText('免费')
    await expect(activities.nth(2)).toContainText('黄河口低空研学一日营')

    const venue = page.getByTestId(`content-topic-venue-${VENUE}`)
    await expect(venue).toContainText('东营低空与机器人体验馆')
    const sessions = page.getByTestId('content-topic-venues').locator('[data-testid^="content-topic-session-"]')
    const noSession = page.getByTestId('content-topic-no-session')
    await expect(sessions.or(noSession).first()).toBeVisible()
    if (await sessions.count()) await expect(sessions.first()).toContainText('今天')

    const related = page.getByTestId(`content-topic-article-${CASE_DOG}`)
    await expect(related).toContainText('一台机器狗如何完成园区夜间巡逻')

    await page.getByTestId(`content-topic-activity-${OPEN_DAY}`).click()
    await expect(page).toHaveURL(new RegExp(`activity/detail\\?activityId=${OPEN_DAY}&cityCode=${CITY}`))
    await expect(page.getByTestId('activity-detail')).toContainText('无人机开放体验日')
    await page.goBack()

    await page.getByTestId(`content-topic-venue-${VENUE}`).click()
    await expect(page).toHaveURL(new RegExp(`venue/detail\\?venueId=${VENUE}&cityCode=${CITY}`))
    await expect(page.getByTestId('venue-detail')).toContainText('东营低空与机器人体验馆')
    await page.goBack()

    await page.getByTestId(`content-topic-article-${CASE_DOG}`).click()
    await expect(page.getByTestId('content-article')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-article-title')).toHaveText('一台机器狗如何完成园区夜间巡逻')
    await page.goBack()

    await page.getByTestId('content-topic-share').click()
    await expect(page.getByTestId('share-sheet')).toHaveAttribute('data-link', new RegExp(`#/pages/content/topic\\?topicId=${TOPIC}&cityCode=${CITY}$`))
    await page.getByTestId('share-close').click()

    await page.getByTestId('content-topic-all-activities').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
  })

  test('CNT-010 打不开的内容和专题说明原因，回到发现', async ({ page }) => {
    await page.goto(`/?n=${Date.now()}#/pages/content/article?articleId=00000000-0000-4000-8000-00000000dead&cityCode=${CITY}`)
    await expect(page.getByTestId('content-article-unavailable')).toContainText('内容已下线，或不在当前城市展示')
    await page.getByTestId('content-article-recover').click()
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')

    // 东营的专题在别的城市打不开。
    await page.goto(`/?n=${Date.now()}#/pages/content/topic?topicId=${TOPIC}&cityCode=370100`)
    await expect(page.getByTestId('content-topic-unavailable')).toContainText('专题已下线，或不是当前城市的专题')
  })
})

test.describe('发现页视觉', () => {
  test('CNT-201 320 / 375 / 430 不溢出，390 宽与画板对照', async ({ page }) => {
    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })

    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at('#/pages/discover/index'))
      await expect(discover(page)).toHaveAttribute('data-state', 'ready')
      await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(6)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Discover'))

      if (width !== 390) {
        await page.getByTestId('discover-channel-events').click()
        await expect(page.locator('[data-testid^="discover-activity-"]')).toHaveCount(3)
        await assertNoHorizontalOverflow(page)
        await page.screenshot({ path: shot('Discover-events') })
        await page.getByTestId('discover-channel-knowledge').click()
        await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(3)
        await assertNoHorizontalOverflow(page)
        await page.screenshot({ path: shot('Discover-knowledge') })
      }

      await page.goto(at(`#/pages/content/article?articleId=${CASE_DOG}&cityCode=${CITY}`))
      await expect(page.getByTestId('content-article-refs')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('ContentArticle'))

      await page.goto(at(`#/pages/content/article?articleId=${KNOW_ROBOT}&cityCode=${CITY}`))
      await expect(page.getByTestId('content-article-refs')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('ContentKnow2'))

      await page.goto(at(`#/pages/content/topic?topicId=${TOPIC}&cityCode=${CITY}`))
      await expect(page.getByTestId('content-topic-activities').locator('[data-testid^="content-topic-activity-"]')).toHaveCount(3)
      await expect(page.getByTestId(`content-topic-venue-${VENUE}`)).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Topic'))
    }

    for (const board of ['Discover', 'ContentArticle', 'ContentKnow2', 'Topic']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${resolve(boardDir, `${board}.html`)}`)
      const frame = page.locator('#root > div').first()
      await expect(frame).toBeVisible()
      const height = Math.ceil((await frame.boundingBox())?.height ?? 844) + 60
      await page.setViewportSize({ width: 390, height })
      await page.waitForTimeout(300)
      await frame.screenshot({ path: resolve(compareOutput, `${board}-board.png`) })

      const image = (name: string) => `data:image/png;base64,${readFileSync(resolve(compareOutput, name)).toString('base64')}`
      await page.setViewportSize({ width: 820, height: 900 })
      await page.setContent(`
        <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
          <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
            <figure style="margin:0;width:390px"><figcaption>画板 ${board}</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-board.png`)}"></figure>
            <figure style="margin:0;width:390px"><figcaption>App 390 宽</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-app.png`)}"></figure>
          </div>
        </body>`)
      await page.waitForTimeout(200)
      await page.screenshot({ path: resolve(compareOutput, `${board}-compare.png`), fullPage: true })
    }
  })
})
