import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { API, BUYER, MERCHANT, apiLogin, databaseAvailable, psql, type Session } from './support/trade-fixtures'

// The login page carries the target as an encoded returnUrl inside the hash (see experience.spec).
function loginReturnUrl(page: Page): string {
  const hash = page.url().split('#')[1] ?? ''
  const query = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : ''
  return decodeURIComponent(new URLSearchParams(query).get('returnUrl') ?? '')
}

// 发现页视频（contracts/openapi-content.yaml 1.2.0，计划 docs/redesign/07-视频与直播实现计划.md §2、§4）：
// 推荐瀑布流里的视频卡（时长角标），“视频”频道，点卡片进竖屏视频流并从这一条开始，上下切换，点赞、关注要登录，
// 评论（发出、关键词拦截、作者回复标“作者”、举报、受权隐藏），关注流，视频详情（内容中提到进商品页）。
// 真实后端和开发种子；开发环境没有视频存储，预览构建播放 App 内由画板图片合成的示例视频。
// 测试写下的赞、关注、评论、举报（和它记进客服反馈的那一条）在前后都清掉。
const CITY = '370500'
const ROBOT = 'f3200000-0000-4000-8000-000000000001'
const DOG = 'f3200000-0000-4000-8000-000000000002'
const AERIAL = 'f3200000-0000-4000-8000-000000000003'
const CONTEST = 'f3200000-0000-4000-8000-000000000004'
const SURVEY = 'f3200000-0000-4000-8000-000000000005'
const CAMP = 'f3200000-0000-4000-8000-000000000006'
const VIDEOS = [ROBOT, DOG, AERIAL, CONTEST, SURVEY, CAMP]
const EAGLE_PROVIDER = 'f2000000-0000-4000-8000-000000000001'
const ROBOT_KIT = 'c2000000-0000-4000-8000-000000000101'
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/content-video')
const visualOutput = resolve(__dirname, '../../../../../.planning/content/visual')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')

const discover = (page: Page) => page.getByTestId('content-discover')
const feed = (page: Page) => page.getByTestId('content-video-feed')
const videoCard = (page: Page, id: string) => page.getByTestId(`discover-video-${id}`)

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function openDiscover(page: Page): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/home/index`)
  await page.getByTestId('root-tab-discover').click()
  await expect(discover(page)).toHaveAttribute('data-state', 'ready')
}

async function openFeed(page: Page, start: string | null = ROBOT, extra = ''): Promise<void> {
  const route = `/pages/content/video-feed?cityCode=${CITY}${start ? `&videoId=${start}` : ''}${extra}`
  await page.goto(`/?n=${Date.now()}#${route}`)
  await expect(feed(page)).toHaveAttribute('data-state', /ready|empty|login|missing|error/)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

/** 清掉开发账号在示例视频上的赞、关注、评论和举报（举报记进客服反馈的那一条也清）。 */
function purgeVideoActivity(): void {
  if (!databaseAvailable()) return
  const ids = VIDEOS.map((id) => `'${id}'`).join(', ')
  psql(`
    DELETE FROM support_feedback
      WHERE feedback_type = 'CONTENT' AND body LIKE '举报视频评论%'
        AND account_id IN (SELECT id FROM identity_user WHERE login_name IN ('${BUYER}', '${MERCHANT}'));
    DELETE FROM content_video_comment
      WHERE video_id IN (${ids})
        AND account_id IN (SELECT id FROM identity_user WHERE login_name IN ('${BUYER}', '${MERCHANT}'));
    DELETE FROM content_video_like
      WHERE video_id IN (${ids})
        AND account_id IN (SELECT id FROM identity_user WHERE login_name IN ('${BUYER}', '${MERCHANT}'));
    DELETE FROM content_follow
      WHERE account_id IN (SELECT id FROM identity_user WHERE login_name IN ('${BUYER}', '${MERCHANT}'));
  `)
}

async function commentByApi(request: APIRequestContext, session: Session, videoId: string, text: string): Promise<string> {
  const response = await request.post(`${API}/api/v1/content/videos/${videoId}/comments`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { text },
  })
  expect(response.status()).toBe(201)
  return (await response.json()).commentId as string
}

test.describe('发现页视频', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session | null = null

  test.beforeAll(async ({ request }) => {
    buyer = await apiLogin(request, BUYER)
    purgeVideoActivity()
  })

  test.afterAll(() => {
    purgeVideoActivity()
  })

  test('CVD-001 推荐瀑布流里混着视频卡：封面、“视频”角标、时长、示例和广告；图文卡片不变', async ({ page }) => {
    await openDiscover(page)
    await expect(page.getByRole('tab')).toHaveText(['推荐', '视频', '赛事', '研学', '案例', '知识'])
    const grid = page.getByTestId('discover-grid')
    // 第一页 10 条：6 篇图文 + 4 条视频（按发布时间交错）；航测、研学营在第二页。
    await expect(grid.locator('[data-testid^="discover-article-"]')).toHaveCount(6)
    await expect(grid.locator('[data-testid^="discover-video-f32"]')).toHaveCount(4)
    for (const id of [ROBOT, CONTEST, AERIAL, DOG]) await expect(videoCard(page, id)).toBeVisible()
    await expect(videoCard(page, ROBOT)).toContainText('3 分钟看懂编程机器人')
    await expect(videoCard(page, ROBOT)).toContainText('视频')
    await expect(videoCard(page, ROBOT).getByTestId('discover-video-duration')).toHaveText('3:12')
    await expect(videoCard(page, AERIAL).getByTestId('discover-video-duration')).toHaveText('0:58')
    await expect(videoCard(page, ROBOT).getByTestId('content-sample')).toHaveText('示例')
    // 商家发的是广告；服务商、平台发的不是。
    await expect(videoCard(page, ROBOT).getByTestId('content-sponsored')).toHaveText('广告')
    await expect(videoCard(page, DOG).getByTestId('content-sponsored')).toHaveCount(0)
    await expect(videoCard(page, CONTEST).getByTestId('content-sponsored')).toHaveCount(0)
    // 预览构建用示例视频的第一帧作封面。
    await expect(videoCard(page, ROBOT).locator('uni-image.video-card__image')).toHaveCount(1)
    await expect(discover(page)).not.toContainText('观看')
    await assertNoHorizontalOverflow(page)

    await page.mouse.move(195, 400)
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect(videoCard(page, SURVEY)).toBeVisible()
    await expect(videoCard(page, CAMP)).toBeVisible()
    await expect(page.getByTestId('discover-no-more')).toHaveText('没有更多内容了')
  })

  test('CVD-002 “视频”频道只有视频，按运营排序；点卡片进竖屏流，从这一条开始，上下切换', async ({ page }) => {
    await openDiscover(page)
    await page.getByTestId('discover-channel-video').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'VIDEO')
    await expect(discover(page)).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('discover-note')).toContainText('点任意视频进入竖屏播放')
    const cards = page.locator('[data-testid^="discover-video-f32"]')
    await expect(cards).toHaveCount(6)
    await expect(page.locator('[data-testid^="discover-article-"]')).toHaveCount(0)
    // 两列轮流放：左列 编程机器人、航拍、航测，右列 机器狗、挑战赛、研学营。
    const columns = page.locator('.discover__column')
    await expect(columns.nth(0).locator('[data-testid^="discover-video-f32"]')).toHaveCount(3)
    await expect(columns.nth(0).locator('[data-testid^="discover-video-f32"]').first()).toHaveAttribute('data-testid', `discover-video-${ROBOT}`)
    await expect(columns.nth(1).locator('[data-testid^="discover-video-f32"]').first()).toHaveAttribute('data-testid', `discover-video-${DOG}`)

    await videoCard(page, DOG).click()
    await expect(page).toHaveURL(new RegExp(`#/pages/content/video-feed\\?cityCode=${CITY}&videoId=${DOG}`))
    await expect(feed(page)).toHaveAttribute('data-state', 'ready')
    await expect(feed(page)).toHaveAttribute('data-video', DOG)
    await expect(page.getByTestId('video-feed-author-name')).toContainText('@鹰眼智能巡检')
    await expect(page.getByTestId('video-feed-author-name')).toContainText('服务商')
    await expect(page.getByTestId('video-feed-caption')).toContainText('机器狗沿着预设路线巡逻')
    await expect(page.getByTestId('video-feed-caption')).toContainText('#园区巡检')
    // 从机器狗开始：它前面没有了。
    await expect(page.getByTestId('video-feed-prev')).toHaveAttribute('disabled', 'true')
    // 挂的入口：园区综合巡检服务，读巡检模块的真实名称。
    await expect(page.getByTestId('video-feed-entry')).toContainText('关联服务')
    await expect(page.getByTestId('video-feed-entry')).toContainText('园区综合巡检服务')
    // 预览构建播放示例视频，默认静音。
    await expect(page.getByTestId('video-feed-player').locator('video')).toHaveAttribute('src', /dog-patrol\.mp4/)
    await expect(page.getByTestId('video-feed-mute')).toHaveAttribute('aria-pressed', 'true')

    await page.getByTestId('video-feed-next').click()
    await expect(feed(page)).toHaveAttribute('data-video', AERIAL)
    await expect(page.getByTestId('video-feed-entry')).toContainText('4K 航拍无人机 标准版')
    await expect(page.getByTestId('video-feed-entry')).toContainText('¥')
    await page.getByTestId('video-feed-next').click()
    await expect(feed(page)).toHaveAttribute('data-video', CONTEST)
    await expect(page.getByTestId('video-feed-author-name')).toContainText('官方')
    // 平台发的没有关注按钮。
    await expect(page.getByTestId('video-feed-follow')).toHaveCount(0)
    await page.getByTestId('video-feed-prev').click()
    await expect(feed(page)).toHaveAttribute('data-video', AERIAL)

    // 点暂停、点静音。
    await page.getByTestId('video-feed-toggle').click()
    await expect(page.getByTestId('video-feed-paused')).toBeVisible()
    await page.getByTestId('video-feed-mute').click()
    await expect(page.getByTestId('video-feed-mute')).toHaveAttribute('aria-pressed', 'false')

    await page.getByTestId('video-feed-back').click()
    await expect(discover(page)).toHaveAttribute('data-channel', 'VIDEO')
  })

  test('CVD-003 没登录点赞、关注、发评论：去登录，登录后回到同一条视频', async ({ page }) => {
    test.skip(!buyer, '本地后端没有开发账号（不是 dev 配置）')
    await openFeed(page, DOG)
    await expect(feed(page)).toHaveAttribute('data-video', DOG)

    await page.getByTestId('video-feed-like').click()
    await expect(page).toHaveURL(/#\/pages\/service\/inspection\/login\?returnUrl=/)
    expect(loginReturnUrl(page)).toContain(`/pages/content/video-feed?cityCode=${CITY}&videoId=${DOG}`)

    // 评论列表不登录也能看，发评论要登录。
    await openFeed(page, DOG)
    await page.getByTestId('video-feed-comments').click()
    await expect(page.getByTestId('video-comments')).toHaveAttribute('data-state', 'ready')
    await page.getByTestId('video-comment-input').locator('input').fill('好看')
    // uni H5 passes input events on every 100 ms: wait until 发送 is enabled before the click.
    await expect(page.getByTestId('video-comment-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('video-comment-send').click()
    await expect(page).toHaveURL(/#\/pages\/service\/inspection\/login\?returnUrl=/)

    // 关注流要登录。
    await openFeed(page, null, '&feed=FOLLOWING')
    await expect(feed(page)).toHaveAttribute('data-state', 'login')
    await expect(page.getByTestId('video-feed-state')).toContainText('登录后看关注的作者')
  })

  test('CVD-004 登录后点赞和关注：马上变，刷新后还在；关注流只有关注的机构；取消后回到原样', async ({ page }) => {
    test.skip(!buyer, '本地后端没有开发账号（不是 dev 配置）')
    await signIn(page, buyer as Session)
    await openFeed(page, DOG)
    const count = page.getByTestId('video-feed-like-count')
    const before = Number(await count.textContent())

    await page.getByTestId('video-feed-like').click()
    await expect(page.getByTestId('video-feed-like')).toHaveAttribute('aria-pressed', 'true')
    await expect(count).toHaveText(String(before + 1))
    await page.getByTestId('video-feed-follow').click()
    await expect(page.getByTestId('video-feed-follow')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('uni-toast')).toContainText('已关注')

    await openFeed(page, DOG)
    await expect(page.getByTestId('video-feed-like')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('video-feed-follow')).toHaveAttribute('aria-pressed', 'true')

    // 关注流：只有鹰眼智能巡检的视频（机器狗）。
    await page.getByTestId('video-feed-tab-following').click()
    await expect(feed(page)).toHaveAttribute('data-feed', 'FOLLOWING')
    await expect(feed(page)).toHaveAttribute('data-video', DOG)
    await expect(page.getByTestId('video-feed-position')).toContainText('1 / 1')

    await page.getByTestId('video-feed-like').click()
    await expect(page.getByTestId('video-feed-like')).toHaveAttribute('aria-pressed', 'false')
    await expect(count).toHaveText(String(before))
    await page.getByTestId('video-feed-follow').click()
    await expect(page.getByTestId('video-feed-follow')).toHaveAttribute('aria-pressed', 'false')
    await openFeed(page, null, '&feed=FOLLOWING')
    await expect(feed(page)).toHaveAttribute('data-state', 'empty')
    await expect(page.getByTestId('video-feed-state')).toContainText('还没有关注的作者')
    await page.getByTestId('video-feed-to-recommend').click()
    await expect(feed(page)).toHaveAttribute('data-feed', 'RECOMMEND')
    await expect(feed(page)).toHaveAttribute('data-state', 'ready')
  })

  test('CVD-005 评论：发出去在最上面、计数加一；带联系方式被拦下；商家回复标“作者”；举报记下；受权隐藏后看不到', async ({ page, request }) => {
    test.skip(!buyer, '本地后端没有开发账号（不是 dev 配置）')
    const merchant = await apiLogin(request, MERCHANT)
    test.skip(!merchant, '没有开发商家操作员')
    const reply = await commentByApi(request, merchant as Session, ROBOT, '有的，点视频下方的入口可以直接看详情')

    await signIn(page, buyer as Session)
    await openFeed(page, ROBOT)
    await expect(feed(page)).toHaveAttribute('data-video', ROBOT)
    const sideCount = page.getByTestId('video-feed-comment-count')
    const before = Number(await sideCount.textContent())
    await page.getByTestId('video-feed-comments').click()
    const sheet = page.getByTestId('video-comments')
    await expect(sheet).toHaveAttribute('data-state', 'ready')
    // 商家操作员的评论显示机构名并标“作者”。
    const authorReply = page.getByTestId(`video-comment-${reply}`)
    await expect(authorReply).toContainText('示例机器人商家')
    await expect(authorReply.getByTestId('video-comment-author')).toHaveText('作者')

    const text = `看完就想带孩子去试试 ${Date.now() % 10000}`
    await page.getByTestId('video-comment-input').locator('input').fill(text)
    // uni H5 passes input events on every 100 ms: wait until 发送 is enabled before the click.
    await expect(page.getByTestId('video-comment-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('video-comment-send').click()
    const mine = sheet.locator('[data-testid^="video-comment-"]').filter({ hasText: text })
    await expect(mine).toHaveCount(1)
    await expect(mine).toContainText('我')
    await expect(mine).toContainText('刚刚')
    await expect(page.getByTestId('video-comments-count')).toHaveText(`${before + 1} 条评论`)

    // 关键词过滤：写了手机号，不发，说明原因。
    await page.getByTestId('video-comment-input').locator('input').fill('详聊加微信 13800001234')
    // uni H5 passes input events on every 100 ms: wait until 发送 is enabled before the click.
    await expect(page.getByTestId('video-comment-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('video-comment-send').click()
    await expect(page.getByTestId('video-comment-error')).toContainText('不允许发布')
    await expect(sheet.locator('[data-testid^="video-comment-"]').filter({ hasText: '13800001234' })).toHaveCount(0)

    // 举报商家那条：选原因，记进客服反馈。
    await page.getByTestId(`video-comment-report-${reply}`).click()
    await page.getByText('广告引流').click()
    await expect(page.locator('uni-toast')).toContainText('已收到举报')
    if (databaseAvailable()) {
      expect(psql(`SELECT count(*) FROM content_comment_report WHERE comment_id = '${reply}';`)).toBe('1')
      expect(psql(`SELECT count(*) FROM support_feedback WHERE feedback_type = 'CONTENT' AND body LIKE '%${reply}%';`)).toBe('1')
    }

    // 商家隐藏这条（受权接口，没有页面）；重新打开就看不到了。
    const hidden = await request.post(`${API}/api/v1/content/comments/${reply}/hide`, { headers: (merchant as Session).headers })
    expect(hidden.status()).toBe(200)
    await page.getByTestId('video-comments-close').click()
    await page.getByTestId('video-feed-comments').click()
    await expect(sheet).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId(`video-comment-${reply}`)).toHaveCount(0)
    await expect(sheet.locator('[data-testid^="video-comment-"]').filter({ hasText: text })).toHaveCount(1)
    // 客户不能隐藏。
    const refused = await request.post(`${API}/api/v1/content/comments/${reply}/hide`, { headers: (buyer as Session).headers })
    expect(refused.status()).toBe(403)
  })

  test('CVD-006 视频详情：标题、作者“进店”、说明、内容中提到；看商品进商品页；没登录点赞去登录', async ({ page }) => {
    await openFeed(page, ROBOT)
    await page.getByTestId('video-feed-caption').click()
    await expect(page).toHaveURL(new RegExp(`#/pages/content/video\\?videoId=${ROBOT}&cityCode=${CITY}`))
    const detail = page.getByTestId('content-video')
    await expect(detail).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-video-title')).toHaveText('3 分钟看懂编程机器人')
    await expect(page.getByTestId('content-video-author')).toContainText('示例机器人商家')
    await expect(page.getByTestId('content-video-author').getByTestId('content-sponsored')).toHaveText('广告')
    // The second half is the current city once one is chosen (this test opens the page directly).
    await expect(page.getByTestId('content-video-author')).toContainText(/发布 · (东营|视频)/)
    await expect(page.getByTestId('content-video-home')).toHaveText('进店')
    await expect(page.getByTestId('content-video-caption')).toContainText('超声波加循线传感器')
    const refs = page.getByTestId('content-video-refs')
    await expect(refs.locator('[data-testid^="content-ref-"]')).toHaveCount(2)
    await expect(page.getByTestId(`content-ref-${ROBOT_KIT}`)).toContainText('编程机器人探索套装')
    await expect(refs).toContainText('内容由商家发布，价格和在售情况以商品页为准。')
    await expect(page.getByTestId('content-video-player').locator('video')).toHaveAttribute('src', /robot-coding\.mp4/)
    await expect(page.getByTestId('content-video-primary')).toHaveText('看商品')
    await expect(page.getByTestId('content-video-secondary')).toHaveText('看场馆')
    await assertNoHorizontalOverflow(page)

    // 商家没有主页：进店说明。
    await page.getByTestId('content-video-home').click()
    await expect(page.locator('uni-toast')).toContainText('商家主页暂未开通')

    await page.getByTestId('content-video-primary').click()
    await expect(page).toHaveURL(new RegExp(`#/pages/mall/product\\?productId=${ROBOT_KIT}&cityCode=${CITY}`))
    await page.goBack()
    await expect(detail).toHaveAttribute('data-state', 'ready')
    await page.getByTestId('content-video-like').click()
    await expect(page).toHaveURL(/#\/pages\/service\/inspection\/login\?returnUrl=/)
    expect(loginReturnUrl(page)).toContain(`/pages/content/video?videoId=${ROBOT}&cityCode=${CITY}`)
  })

  test('CVD-007 服务商的视频进服务商主页；下线或别的城市的视频说清楚，可以从头看', async ({ page }) => {
    await page.goto(`/?n=${Date.now()}#/pages/content/video?videoId=${DOG}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-video')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-video-home')).toHaveText('主页')
    await page.getByTestId('content-video-home').click()
    await expect(page).toHaveURL(new RegExp(`#/pages/provider/detail\\?providerId=${EAGLE_PROVIDER}`))

    await page.goto(`/?n=${Date.now()}#/pages/content/video?videoId=${DOG}&cityCode=370100`)
    await expect(page.getByTestId('content-video-unavailable')).toContainText('不在当前城市展示')
    // 图文详情不收视频的 ID。
    await page.goto(`/?n=${Date.now()}#/pages/content/article?articleId=${DOG}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-article-unavailable')).toBeVisible()

    await openFeed(page, '00000000-0000-4000-8000-000000000999')
    await expect(feed(page)).toHaveAttribute('data-state', 'missing')
    await page.getByTestId('video-feed-from-top').click()
    await expect(feed(page)).toHaveAttribute('data-state', 'ready')
    // 从头：运营排序第一的编程机器人。
    await expect(feed(page)).toHaveAttribute('data-video', ROBOT)
  })

  test('CVD-008 视频流翻到底：分段读完，最后一条“下一条”不能点', async ({ page }) => {
    await openFeed(page, ROBOT)
    await expect(page.getByTestId('video-feed-position')).toContainText('第 1 条')
    for (const id of [DOG, AERIAL, CONTEST, SURVEY, CAMP]) {
      await page.getByTestId('video-feed-next').click()
      await expect(feed(page)).toHaveAttribute('data-video', id)
    }
    await expect(page.getByTestId('video-feed-position')).toContainText('6 / 6')
    await expect(page.getByTestId('video-feed-next')).toHaveAttribute('disabled', 'true')
    // 相关内容不列视频：商品详情的“相关内容”仍只有图文。
    const related = await page.request.get(`${API}/api/v1/content/related?refType=PRODUCT&refId=${ROBOT_KIT}&cityCode=${CITY}`)
    const items = (await related.json()).items as Array<{ articleId: string; kind: string }>
    expect(items.every((item) => item.kind !== 'VIDEO')).toBe(true)
    expect(items.map((item) => item.articleId)).not.toContain(ROBOT)
  })

  test('CVD-009 390 宽和画板并排：发现·视频频道、竖屏视频流、视频详情；320 / 430 不溢出', async ({ page }) => {
    mkdirSync(compareOutput, { recursive: true })
    mkdirSync(visualOutput, { recursive: true })
    for (const width of [320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(`/?w=${width}&n=${Date.now()}#/pages/discover/index`)
      await expect(discover(page)).toHaveAttribute('data-state', 'ready')
      await page.getByTestId('discover-channel-video').click()
      await expect(discover(page)).toHaveAttribute('data-channel', 'VIDEO')
      await expect(page.locator('[data-testid^="discover-video-f32"]')).toHaveCount(6)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('Discover-video'), fullPage: true })

      await openFeed(page, ROBOT)
      await expect(page.getByTestId('video-feed-entry')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('VideoFeed') })
      await page.getByTestId('video-feed-comments').click()
      await expect(page.getByTestId('video-comments')).toHaveAttribute('data-state', 'ready')
      await page.screenshot({ path: shot('VideoFeed-comments') })

      await page.goto(`/?w=${width}&n=${Date.now()}#/pages/content/video?videoId=${ROBOT}&cityCode=${CITY}`)
      await expect(page.getByTestId('content-video-refs')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('ContentVideo'), fullPage: true })
    }

    for (const [board, app] of [['Discover', 'Discover-video'], ['VideoFeed', 'VideoFeed'], ['ContentVideo', 'ContentVideo']]) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${resolve(boardDir, `${board}.html`)}`)
      const frame = page.locator('#root > div').first()
      await expect(frame).toBeVisible()
      if (board === 'Discover') await page.getByRole('tab', { name: '视频' }).click()
      await page.waitForTimeout(300)
      await frame.screenshot({ path: resolve(compareOutput, `${board}-board.png`) })

      const image = (name: string) => `data:image/png;base64,${readFileSync(resolve(compareOutput, name)).toString('base64')}`
      await page.setViewportSize({ width: 820, height: 900 })
      await page.setContent(`
        <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
          <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
            <figure style="margin:0;width:390px"><figcaption>画板 ${board}</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-board.png`)}"></figure>
            <figure style="margin:0;width:390px"><figcaption>App</figcaption>
              <img style="width:390px;display:block" src="${image(`${app}-app.png`)}"></figure>
          </div>
        </body>`)
      await page.screenshot({ path: resolve(compareOutput, `${board}-compare.png`), fullPage: true })
    }
  })
})
