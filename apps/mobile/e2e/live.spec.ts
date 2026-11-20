import { randomUUID } from 'node:crypto'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { API, BUYER, CITY, MERCHANT, apiLogin, type Session } from './support/trade-fixtures'

// 平台内直播的观看端（M20，计划 docs/redesign/07-视频与直播实现计划.md §3）：底栏“发现”真实点击进入，推荐里的直播条和
// “直播”频道（正在直播、直播预告、往期回看）、直播预告页（提醒我、进直播间、去合作平台回看）、直播间（画面、弹幕、点赞、
// 关注主播、讲解清单进平台内详情页、上下切换直播间）。真实后端和开发种子（R__dev_live.sql）；开播、断流、结束用 dev 配置
// 自带的模拟直播服务的开发控制接口（和 infra/scripts/dev-live-host.cjs 一样），新建的直播间由 dev-merchant 经受权接口建，
// 不动种子里的直播间状态。后端要用 dev 配置启动（live.stream.provider=fake）。
const DOG = 'f4000000-0000-4000-8000-000000000001'
const DRONE = 'f4000000-0000-4000-8000-000000000002'
const ROBOT_INTRO = 'f4000000-0000-4000-8000-000000000003'
const PATROL_QA = 'f4000000-0000-4000-8000-000000000004'
const SOLAR_REPLAY = 'f4000000-0000-4000-8000-000000000005'
const PARK_INSPECTION = '77777777-7777-7777-7777-777777777777'
const ROBOT_DOG = 'c2000000-0000-4000-8000-000000000401'
const ROBOT_KIT = 'c2000000-0000-4000-8000-000000000101'

const discover = (page: Page) => page.getByTestId('content-discover')
const room = (page: Page) => page.getByTestId('live-room')
const notice = (page: Page) => page.getByTestId('live-notice')

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

async function openRoom(page: Page, roomId: string): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/live/room?roomId=${roomId}&cityCode=${CITY}`)
  await expect(room(page)).toHaveAttribute('data-state', 'ready')
}

async function openNotice(page: Page, roomId: string): Promise<void> {
  await page.goto(`/?n=${Date.now()}#/pages/live/notice?roomId=${roomId}&cityCode=${CITY}`)
  await expect(notice(page)).toHaveAttribute('data-state', 'ready')
}

async function sessions(request: APIRequestContext): Promise<{ buyer: Session; host: Session }> {
  const buyer = await apiLogin(request, BUYER)
  const host = await apiLogin(request, MERCHANT)
  test.skip(!buyer || !host, '本地后端没有开发账号（不是 dev 配置）')
  return { buyer: buyer as Session, host: host as Session }
}

/** dev-merchant 建一场 10 分钟后开播的预告（东营可见），讲解编程机器人探索套装。 */
async function createRoom(request: APIRequestContext, host: Session, title: string): Promise<string> {
  const created = await request.post(`${API}/api/v1/live-host/rooms`, {
    headers: { ...host.headers, 'Idempotency-Key': randomUUID() },
    data: {
      title,
      cityCode: CITY,
      scheduledAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      speakerName: '李老师',
      speakerTitle: '课程讲师',
      topics: ['拖积木写第一段程序'],
    },
  })
  expect(created.status()).toBe(201)
  const roomId = (await created.json()).room.roomId as string
  const items = await request.put(`${API}/api/v1/live-host/rooms/${roomId}/items`, {
    headers: host.headers,
    data: { items: [{ type: 'PRODUCT', refId: ROBOT_KIT, title: '编程机器人探索套装' }], currentSeq: 1 },
  })
  expect(items.status()).toBe(200)
  return roomId
}

/** 模拟直播服务的回调：STARTED / INTERRUPTED / ENDED（只在 dev 配置下有）。 */
async function stream(request: APIRequestContext, host: Session, roomId: string, type: string): Promise<void> {
  const sent = await request.post(`${API}/api/v1/dev/live/rooms/${roomId}/stream-events`, {
    headers: host.headers,
    data: { type },
  })
  expect(sent.status(), await sent.text()).toBe(200)
}

test.describe('平台内直播', () => {
  test('LIVE-001 推荐里的直播条：第一场直播中，点进直播间；“直播”频道名带红点', async ({ page }) => {
    await openDiscover(page)
    await expect(page.getByRole('tab')).toContainText(['推荐', '直播'])

    const bar = page.getByTestId('live-bar')
    await expect(bar).toHaveAttribute('data-room', DOG)
    await expect(bar).toContainText('直播中')
    await expect(bar).toContainText(/1\.\d万 观看/)
    await expect(bar).toContainText('机器狗园区夜巡演示')
    await expect(bar).toContainText('鹰眼智能巡检 · 正在讲解 园区安防巡检')
    await expect(bar).toContainText('进直播间')
    await expect(page.getByTestId('discover-live-dot')).toBeVisible()

    await bar.click()
    await expect(page).toHaveURL(new RegExp(`pages/live/room\\?roomId=${DOG}&cityCode=${CITY}`))
    await expect(room(page)).toHaveAttribute('data-status', 'LIVE')
  })

  test('LIVE-002 “直播”频道：正在直播两场、直播预告带提醒我、往期回看；点进对应页面', async ({ page }) => {
    await openDiscover(page)
    await page.getByTestId('discover-channel-live').click()
    const channel = page.getByTestId('live-channel')
    await expect(channel).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('discover-note')).toContainText('直播间不收款')
    await expect(page.getByTestId('discover-live-dot')).toHaveCount(0)

    await expect(channel).toContainText('正在直播')
    await expect(channel).toContainText('2 场')
    await expect(page.getByTestId(`live-card-${DOG}`)).toContainText('机器狗园区夜巡演示')
    await expect(page.getByTestId(`live-card-${DRONE}`)).toContainText('编程无人机开箱与首飞')
    await expect(page.getByTestId(`live-notice-${ROBOT_INTRO}`)).toContainText('编程机器人入门')
    await expect(page.getByTestId(`live-notice-${ROBOT_INTRO}`)).toContainText('示例机器人商家 · App 内直播')
    await expect(page.getByTestId(`live-remind-${ROBOT_INTRO}`)).toHaveText('提醒我')
    await expect(page.getByTestId(`live-notice-${PATROL_QA}`)).toBeVisible()
    await expect(page.getByTestId(`live-replay-${SOLAR_REPLAY}`)).toContainText('光伏电站热斑巡检现场连线')
    await expect(page.getByTestId(`live-replay-${SOLAR_REPLAY}`)).toContainText('52 分钟')

    // 没登录点提醒我：先登录，回来落在这场的预告页。
    await page.getByTestId(`live-remind-${ROBOT_INTRO}`).click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*pages.*live.*notice/)

    await openDiscover(page)
    await page.getByTestId('discover-channel-live').click()
    await page.getByTestId(`live-replay-${SOLAR_REPLAY}`).click()
    await expect(notice(page)).toHaveAttribute('data-status', 'ENDED')
    await expect(page.getByTestId('live-notice-tag')).toHaveText('已结束 · 可回看')
    await expect(page.getByTestId('live-notice-action')).toHaveText('去合作平台回看')
    await page.getByTestId('live-notice-action').click()
    await expect(page.getByTestId('live-notice-external')).toContainText('离开本 App，打开合作平台')
    await expect(page.getByTestId('live-notice-external')).toContainText('示例直播平台')
  })

  test('LIVE-003 直播间：示例画面、种子弹幕、讲解清单进平台内详情页，上下切换', async ({ page }) => {
    await openRoom(page, DOG)
    const video = page.locator('uni-video[data-testid="live-room-video"]')
    await expect(video).toHaveCount(1)
    await expect(page.locator('video')).toHaveAttribute('src', /\/static\/videos\/preview\/live-night-patrol\.mp4$/)
    await expect(room(page)).toContainText('鹰眼智能巡检')
    await expect(page.getByTestId('live-room-counts')).toContainText(/万 观看 · .*赞/)
    await expect(page.getByTestId('live-room-elapsed')).toContainText('已播')
    await expect(room(page)).toContainText('示例画面')
    const chat = page.getByTestId('live-room-chat')
    await expect(chat).toContainText('主播：点右侧“讲解”进服务详情发需求，1 个工作日内报价')
    await expect(chat.locator('.room__line')).toHaveCount(5)

    await expect(page.getByTestId('live-room-pin')).toContainText('讲解中 · 服务')
    await page.getByTestId('live-room-bag').click()
    const sheet = page.getByTestId('live-room-bag-sheet')
    await expect(sheet).toContainText('本场讲解 · 2 项')
    await expect(sheet).toContainText('交易在平台内完成，直播间不收款')
    await expect(page.getByTestId('live-room-item-2')).toContainText('巡检机器狗')
    await page.getByTestId('live-room-item-1').click()
    await expect(page).toHaveURL(new RegExp(`pages/service/inspection/detail\\?serviceId=${PARK_INSPECTION}&cityCode=${CITY}`))

    await openRoom(page, DOG)
    await page.getByTestId('live-room-bag').click()
    await page.getByTestId('live-room-item-2').click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/product\\?productId=${ROBOT_DOG}&cityCode=${CITY}`))

    // 开播早的在前：机器狗夜巡是第一场，往下是编程无人机。
    await openRoom(page, DOG)
    await expect(page.getByTestId('live-room-prev')).toHaveAttribute('disabled', 'true')
    await page.getByTestId('live-room-next').click()
    await expect(page).toHaveURL(new RegExp(`pages/live/room\\?roomId=${DRONE}`))
    await expect(room(page)).toContainText('示例无人机厂家')
    await expect(page.locator('video')).toHaveAttribute('src', /live-drone-unboxing\.mp4$/)
  })

  test('LIVE-004 没登录发弹幕、点赞、关注都先去登录', async ({ page }) => {
    await openRoom(page, DOG)
    await page.getByTestId('live-room-follow').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*pages.*live.*room/)
    await openRoom(page, DOG)
    await page.getByTestId('live-room-like').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login/)
  })

  test('LIVE-005 登录后：发弹幕显示为“我”、关键词拦截、点赞合并计数、关注主播', async ({ page, request }) => {
    const { buyer } = await sessions(request)
    await signIn(page, buyer)
    // 写在编程无人机这一场：机器狗夜巡的种子弹幕留给 LIVE-003 看。
    const state = await request.get(`${API}/api/v1/live/rooms/${DRONE}/me`, { headers: buyer.headers })
    const wasFollowing = (await state.json()).followingHost === true
    await openRoom(page, DRONE)
    await expect(page.getByTestId('live-room-follow')).toHaveText(wasFollowing ? '已关注' : '关注')

    const text = `室内能飞多高 ${Date.now() % 10000}`
    await page.getByTestId('live-room-input').locator('input').fill(text)
    // 输入事件每 100ms 才到一次：等发送按钮可用再点。
    await expect(page.getByTestId('live-room-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('live-room-send').click()
    await expect(page.getByTestId('live-room-chat')).toContainText(`我：${text}`)
    // 同一账号 3 秒内再发：提示太快。
    await page.getByTestId('live-room-input').locator('input').fill('再来一条')
    // 输入事件每 100ms 才到一次：等发送按钮可用再点。
    await expect(page.getByTestId('live-room-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('live-room-send').click()
    await expect(page.locator('uni-toast')).toContainText('发得太快了')
    await page.waitForTimeout(3200)
    await page.getByTestId('live-room-input').locator('input').fill('想要资料加 微 信')
    // 输入事件每 100ms 才到一次：等发送按钮可用再点。
    await expect(page.getByTestId('live-room-send')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('live-room-send').click()
    await expect(page.locator('uni-toast')).toContainText('不允许发送')

    const before = await request.get(`${API}/api/v1/live/rooms/${DRONE}?cityCode=${CITY}`)
    const likesBefore = (await before.json()).likeCount as number
    for (let tap = 0; tap < 3; tap += 1) await page.getByTestId('live-room-like').click()
    await expect(page.getByTestId('live-room-my-likes')).toHaveText('+3')
    // 三下合成一次请求（约 1.5 秒后发出）。
    await expect.poll(async () => (await (await request.get(`${API}/api/v1/live/rooms/${DRONE}?cityCode=${CITY}`)).json()).likeCount)
      .toBeGreaterThanOrEqual(likesBefore + 3)

    const follow = page.getByTestId('live-room-follow')
    await follow.click()
    await expect(follow).toHaveText(wasFollowing ? '关注' : '已关注')
    await follow.click()
    await expect(follow).toHaveText(wasFollowing ? '已关注' : '关注')
  })

  test('LIVE-006 设提醒 → 模拟开播 → “消息”里有直播提醒，点开进直播间；断流、结束', async ({ page, request }) => {
    const { buyer, host } = await sessions(request)
    const roomId = await createRoom(request, host, `入门直播 ${Date.now() % 100000}`)
    await signIn(page, buyer)

    await openNotice(page, roomId)
    await expect(page.getByTestId('live-notice-tag')).toHaveText('直播预告')
    // 10 分钟后开播：一般是“今天开播”，跨北京时间午夜时是“明天开播”。
    await expect(page.getByTestId('live-notice-chip')).toHaveText(/^(今天|明天)开播$/)
    await expect(notice(page)).toContainText('主讲 李老师')
    await expect(notice(page)).toContainText('拖积木写第一段程序')
    await expect(page.getByTestId('live-notice-item-1')).toContainText('编程机器人探索套装')
    await page.getByTestId('live-notice-remind').click()
    await expect(page.getByTestId('live-notice-remind')).toHaveText(/已设提醒/)
    await expect(page.getByTestId('live-notice-hint')).toContainText('开播时会在消息里通知你')

    await stream(request, host, roomId, 'STARTED')
    // 站内信在开播的事务提交后写；按直播间找到这一条。
    let noticeId = ''
    await expect.poll(async () => {
      const inbox = await (await request.get(`${API}/api/v1/notifications?page=1&size=20`, { headers: buyer.headers })).json()
      const found = (inbox.items as Array<{ id: string; category: string; target: { id: string } }>)
        .find((item) => item.category === 'LIVE_REMINDER' && item.target.id === roomId)
      noticeId = found?.id ?? ''
      return noticeId
    }).not.toBe('')
    await page.goto(`/?n=${Date.now()}#/pages/message/index`)
    const row = page.getByTestId(`message-${noticeId}`)
    await expect(row).toContainText('直播开始了')
    await expect(row).toContainText('直播提醒')
    await expect(row).toContainText('示例机器人商家的「')
    await row.click()
    await expect(page).toHaveURL(new RegExp(`pages/live/room\\?roomId=${roomId}`))
    await expect(room(page)).toHaveAttribute('data-status', 'LIVE')
    await expect(page.getByTestId('live-room-pin')).toContainText('编程机器人探索套装')

    // 开播后预告页的主按钮变成进直播间。
    await openNotice(page, roomId)
    await expect(page.getByTestId('live-notice-action')).toHaveText('进直播间')

    await stream(request, host, roomId, 'INTERRUPTED')
    await openRoom(page, roomId)
    await expect(page.getByTestId('live-room-interrupted')).toContainText('主播暂时离开')
    await stream(request, host, roomId, 'ENDED')
    await openRoom(page, roomId)
    await expect(page.getByTestId('live-room-over')).toContainText('直播已结束')
    await page.getByTestId('live-room-notice').click()
    await expect(notice(page)).toHaveAttribute('data-status', 'ENDED')
    await expect(page.getByTestId('live-notice-tag')).toHaveText('已结束')
    await expect(page.getByTestId('live-notice-action')).toHaveCount(0)
  })
})
