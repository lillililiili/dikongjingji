import { readFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// 底栏“消息”：巡检服务进度通知（计划 §15.4）。行为用例用桩数据；最后一条对着本地后端真实登录。
const visualOutput = resolve(__dirname, '../../../.planning/message/visual')
const API = 'http://127.0.0.1:18420'
const PLAN_ID = '50000000-0000-4000-8000-000000000001'
const ORDER_ID = '60000000-0000-4000-8000-000000000001'
const VISIT_ID = '61000000-0000-4000-8000-000000000001'

const notice = (number: number, overrides: Record<string, unknown> = {}) => ({
  id: `70000000-0000-4000-8000-00000000000${number}`,
  category: 'SERVICE_PROGRESS',
  eventCode: 'INSPECTION_DELIVERY_PUBLISHED',
  title: '巡检报告已交付',
  body: `光伏电站巡检服务第 ${number} 次报告已交付，请核对后验收。`,
  target: { type: 'INSPECTION_DELIVERY', id: ORDER_ID, subId: VISIT_ID },
  occurredAt: '2026-11-17T03:00:00Z',
  read: false,
  ...overrides,
})

async function signIn(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('inspection.accessToken', 'message-spec')
  })
}

async function stubInbox(page: Page, items: unknown[], unreadCount: number, hasMore = false): Promise<void> {
  await page.route('**/api/v1/notifications?**', (route: Route) =>
    route.fulfill({ json: { page: 1, size: 20, total: items.length, hasMore, unreadCount, items } }))
  await page.route('**/api/v1/notifications/unread-count*', (route: Route) =>
    route.fulfill({ json: { count: unreadCount } }))
}

const messageBadge = (page: Page) => page.getByTestId('root-tab-message').locator('.root-tab-bar__badge')

test.describe('消息', () => {
  test('MSG-001 未登录只提示登录，不请求通知；登录后回到消息页', async ({ page }) => {
    const calls: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/notifications')) calls.push(request.url())
    })
    await page.goto('/#/pages/message/index')

    await expect(page.getByTestId('message-guest')).toContainText('登录后查看消息')
    await expect(messageBadge(page)).toHaveCount(0)
    expect(calls).toEqual([])

    await page.getByTestId('message-login').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*pages.*message.*index/)
  })

  test('MSG-002 列表显示未读点和底栏角标；点一条标为已读并进到对应页面', async ({ page }) => {
    await signIn(page)
    await stubInbox(page, [
      notice(1, { title: '方案已出', body: '光伏电站巡检服务的方案已发布，请查看并确认。', target: { type: 'INSPECTION_PLAN', id: PLAN_ID, subId: null } }),
      notice(2),
      notice(3, { read: true }),
    ], 2)
    const reads: string[] = []
    await page.route('**/api/v1/notifications/*/read', (route: Route) => {
      reads.push(new URL(route.request().url()).pathname)
      return route.fulfill({ status: 204, body: '' })
    })
    await page.goto('/#/pages/message/index')

    const items = page.locator('.message-center__item')
    await expect(items).toHaveCount(3)
    await expect(items.first()).toContainText('服务进度 · 方案已出')
    await expect(page.locator('.message-center__dot')).toHaveCount(2)
    await expect(messageBadge(page)).toHaveText('2')
    await expect(items.nth(2)).toHaveAttribute('data-read', 'true')

    await items.first().click()
    await expect(page).toHaveURL(new RegExp(`plan-confirmation\\?planId=${PLAN_ID}`))
    expect(reads).toEqual(['/api/v1/notifications/70000000-0000-4000-8000-000000000001/read'])
  })

  test('MSG-003 “全部已读”清掉未读点和角标，没有未读时不显示这个按钮', async ({ page }) => {
    await signIn(page)
    await stubInbox(page, [notice(1), notice(2)], 2)
    let readAll = 0
    await page.route('**/api/v1/notifications/read-all', (route: Route) => {
      readAll += 1
      return route.fulfill({ json: { updated: 2 } })
    })
    await page.goto('/#/pages/message/index')
    await expect(page.locator('.message-center__dot')).toHaveCount(2)

    await page.getByTestId('message-read-all').click()

    await expect(page.locator('.message-center__dot')).toHaveCount(0)
    await expect(messageBadge(page)).toHaveCount(0)
    await expect(page.getByTestId('message-read-all')).toHaveCount(0)
    expect(readAll).toBe(1)
  })

  test('MSG-004 登录失效和加载失败分开提示；失败可重试', async ({ page }) => {
    await signIn(page)
    let mode: 'expired' | 'broken' | 'fine' = 'expired'
    await page.route('**/api/v1/notifications/unread-count*', (route: Route) => route.fulfill({ json: { count: 0 } }))
    await page.route('**/api/v1/notifications?**', (route: Route) => {
      if (mode === 'expired') {
        return route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: '登录状态已失效', traceId: 't' } })
      }
      if (mode === 'broken') {
        return route.fulfill({ status: 500, json: { code: 'COMMON_INTERNAL_ERROR', message: '系统繁忙', traceId: 't' } })
      }
      return route.fulfill({ json: { page: 1, size: 20, total: 0, hasMore: false, unreadCount: 0, items: [] } })
    })

    await page.goto('/#/pages/message/index')
    await expect(page.getByTestId('message-expired')).toContainText('登录已过期')
    // 消息页自己说明情况，不把人直接带去登录页。
    await expect(page).toHaveURL(/pages\/message\/index/)

    mode = 'broken'
    await page.reload()
    await expect(page.getByTestId('message-error')).toBeVisible()

    mode = 'fine'
    await page.getByTestId('message-retry').click()
    await expect(page.getByTestId('message-empty')).toContainText('还没有消息')
  })

  test('MSG-005 还有下一页时可以继续加载，同一条不重复', async ({ page }) => {
    await signIn(page)
    await page.route('**/api/v1/notifications/unread-count*', (route: Route) => route.fulfill({ json: { count: 0 } }))
    await page.route('**/api/v1/notifications?**', (route: Route) => {
      const pageNumber = new URL(route.request().url()).searchParams.get('page')
      return route.fulfill({
        json: pageNumber === '1'
          ? { page: 1, size: 20, total: 3, hasMore: true, unreadCount: 0, items: [notice(1, { read: true }), notice(2, { read: true })] }
          : { page: 2, size: 20, total: 3, hasMore: false, unreadCount: 0, items: [notice(2, { read: true }), notice(3, { read: true })] },
      })
    })
    await page.goto('/#/pages/message/index')
    await expect(page.locator('.message-center__item')).toHaveCount(2)

    // 列表不满一屏时滚不动，所以“加载更多”也可以点；滚进视野时也可能已经自动加载。
    const more = page.getByTestId('message-more-action')
    await expect(async () => {
      if (await more.isVisible()) await more.click({ timeout: 1000 })
      await expect(page.locator('.message-center__item')).toHaveCount(3, { timeout: 1000 })
    }).toPass({ timeout: 10_000 })
    await expect(page.getByTestId('message-more')).toContainText('没有更多消息了')
  })

  test('MSG-006 其他根页面的底栏也显示未读数，超过 99 显示 99+', async ({ page }) => {
    await signIn(page)
    await page.route('**/api/v1/notifications/unread-count*', (route: Route) => route.fulfill({ json: { count: 120 } }))
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-page')).toBeVisible()

    await expect(messageBadge(page)).toHaveText('99+')
  })

  test('MSG-008 交易物流 / 服务进度 / 活动提醒三格各带未读数，点格筛选，点通知进对应页面；不认识的分类显示成通知', async ({ page }) => {
    await signIn(page)
    const GOODS_ORDER = '80000000-0000-4000-8000-000000000001'
    const BOOKING = '81000000-0000-4000-8000-000000000001'
    await stubInbox(page, [
      notice(1, { category: 'TRADE_LOGISTICS', eventCode: 'TRADE_ORDER_SHIPPED', title: '订单已发货',
        body: '编程机器人探索套装，示例机器人商家已发出', target: { type: 'TRADE_ORDER', id: GOODS_ORDER, subId: null } }),
      notice(2, { category: 'ACTIVITY_REMINDER', eventCode: 'VENUE_BOOKED', title: '到馆预约成功',
        body: '东营低空与机器人体验馆 机器人避障闯关 2 人，11/21 10:00 开始，到馆出示核销码',
        target: { type: 'VENUE_BOOKING', id: BOOKING, subId: null } }),
      notice(3, { category: 'ACTIVITY_REMINDER', eventCode: 'ACTIVITY_ENROLLED', title: '报名成功', read: true,
        body: '青少年无人机编程挑战赛 2 人，11/15 08:30 开始，凭证已生成',
        target: { type: 'ACTIVITY_ENROLLMENT', id: BOOKING, subId: null } }),
      notice(4),
      notice(5, { category: 'SYSTEM_NOTICE', eventCode: 'POLICY_UPDATED', title: '隐私政策更新摘要',
        body: '查看主要变化与生效日期', target: { type: 'NOTICE', id: BOOKING, subId: null } }),
    ], 4)
    await page.route('**/api/v1/notifications/*/read', (route: Route) => route.fulfill({ status: 204, body: '' }))
    await page.goto('/#/pages/message/index')

    const items = page.locator('.message-center__item[data-kind="notice"]')
    await expect(items).toHaveCount(5)
    await expect(page.getByTestId('message-cat-trade')).toContainText('交易物流')
    await expect(page.getByTestId('message-cat-progress')).toContainText('服务进度')
    await expect(page.getByTestId('message-cat-activity')).toContainText('活动提醒')
    await expect(page.getByTestId('message-cat-trade').getByTestId('message-cat-badge')).toHaveText('1')
    await expect(page.getByTestId('message-cat-progress').getByTestId('message-cat-badge')).toHaveText('1')
    await expect(page.getByTestId('message-cat-activity').getByTestId('message-cat-badge')).toHaveText('1')
    await expect(items.nth(0)).toContainText('交易物流 · 订单已发货')
    await expect(items.nth(1)).toContainText('体验预约 · 到馆预约成功')
    await expect(items.nth(2)).toContainText('活动提醒 · 报名成功')
    await expect(items.nth(4)).toContainText('通知 · 隐私政策更新摘要')

    await page.getByTestId('message-cat-activity').click()
    await expect(items).toHaveCount(2)
    await page.getByTestId('message-cat-activity').click()
    await expect(items).toHaveCount(5)

    // 不认识的分类：标为已读，不跳转。
    await items.nth(4).click()
    await expect(items.nth(4)).toHaveAttribute('data-read', 'true')
    await expect(page).toHaveURL(/pages\/message\/index/)

    await items.first().click()
    await expect(page).toHaveURL(new RegExp(`goods-order\\?orderId=${GOODS_ORDER}`))
  })

  test('MSG-007 用开发账号真实登录后，消息页读到服务端的通知（320 和 375 宽）', async ({ page, request }) => {
    const seed = readFileSync(resolve(
      __dirname,
      '../../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
    ), 'utf8')
    const password = /crypt\('([^']+)'/.exec(seed)?.[1] ?? ''
    const login = await request.post(`${API}/api/v1/auth/login`, {
      data: { loginName: 'dev-customer', password },
    })
    test.skip(!login.ok(), '本地后端没有开发账号（不是 dev 配置）')
    const { accessToken } = await login.json()
    const inbox = await (await request.get(`${API}/api/v1/notifications?page=1&size=20`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })).json()
    test.skip(inbox.total === 0, '开发账号还没有通知（先用推进脚本推进一张预约）')
    await page.addInitScript((token) => {
      localStorage.setItem('inspection.accessToken', token)
    }, accessToken)

    mkdirSync(visualOutput, { recursive: true })
    for (const width of [375, 320]) {
      await page.setViewportSize({ width, height: 760 })
      await page.goto(`/?w=${width}#/pages/message/index`)
      // 客服会话 share the list by time, so look at the first notification.
      await expect(page.locator('.message-center__item[data-kind="notice"]').first()).toBeVisible()
      // R6-5: the newest may be 交易物流 or 活动提醒 now, not only 服务进度.
      await expect(page.locator('.message-center__item[data-kind="notice"]').first())
        .toContainText(/服务进度|交易物流|活动提醒|体验预约|直播提醒|需求与报价|客服消息/)
      const dimensions = await page.evaluate(() => ({
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
      }))
      expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
      await page.screenshot({ path: resolve(visualOutput, `message-${width}.png`) })
    }
  })
})
