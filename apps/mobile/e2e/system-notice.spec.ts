import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { PLANNING, boardUrl } from './support/board-compare'
import { expectCurrentAgreement } from './support/agreement'
import { purgeFreeEnrollments } from './support/activity-fixtures'
import { API, BUYER, apiLogin, databaseAvailable, psql, type Session } from './support/trade-fixtures'

// 系统通知、通知详情、通知开关和推送（S5-9，docs/redesign/08-后续开发计划.md）：真实后端（dev 配置，本机模拟推送）。
// “消息”的系统通知格和开发种子“隐私政策更新摘要”、通知详情的链接、协议页“有更新”、设置页三个开关（存到服务端），
// 以及“开关只管推送、站内信照记”：活动提醒关掉后报名成功照样进“消息”，但模拟推送里没有；打开后有。
// 跑完恢复 dev-customer 的开关、撤掉测试设备、删掉这次的模拟推送记录和测试报名；系统通知那一条删掉，下次打开“消息”重新补记成未读。
const NOTICE_ID = 'c5000000-0000-4000-8000-000000000001'
const OPEN_DAY_ID = 'a2000000-0000-4000-8000-000000000005'
const OPEN_DAY_SESSION = 'a3000000-0000-4000-8000-000000000502'
const PUSH_ID = `e2e-system-notice-${Date.now()}`
const visualOutput = resolve(PLANNING, 'system-notice/visual')
const compareOutput = resolve(PLANNING, 'style-compare/system-notice')

const buyerId = `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

let buyer: Session
let since = ''

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
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

/**
 * The seed notice is dated at its publication (11/1) and the inbox is newest first, so after other
 * specs have left their 交易物流 / 活动提醒 / 服务进度 rows in dev-customer's inbox it can sit a few
 * pages down. Load pages the way a person would (“上拉或点此加载更多”) until its row is there.
 */
async function revealSystemNotice(page: Page) {
  const row = page.locator('[data-category="SYSTEM_NOTICE"]').first()
  await expect(page.locator('.message-center__item').first()).toBeVisible()
  await expect(async () => {
    if (await row.count() > 0) return
    const more = page.getByTestId('message-more-action')
    if (await more.isVisible()) await more.click({ timeout: 2000 })
    await expect(row).toHaveCount(1, { timeout: 2000 })
  }).toPass({ timeout: 90_000 })
  await row.scrollIntoViewIfNeeded()
  return row
}

async function setSwitches(request: APIRequestContext, change: Record<string, boolean>): Promise<void> {
  const saved = await request.put(`${API}/api/v1/notifications/preferences`, { headers: buyer.headers, data: change })
  expect(saved.ok()).toBe(true)
}

// The seed notice's copy in dev-customer's inbox; deleting it lets the next inbox read copy it again, unread.
function forgetDeliveredNotice(): void {
  psql(`DELETE FROM notification_message WHERE recipient_id = ${buyerId}
    AND category = 'SYSTEM_NOTICE' AND target_id = '${NOTICE_ID}';`)
}

function cleanUp(): void {
  forgetDeliveredNotice()
  psql(`
    DELETE FROM notification_push_fake_delivery WHERE device_id IN
      (SELECT id FROM notification_push_device WHERE push_client_id = '${PUSH_ID}');
    DELETE FROM notification_push_device WHERE push_client_id = '${PUSH_ID}';
    DELETE FROM notification_message WHERE recipient_id = ${buyerId}
      AND category = 'ACTIVITY_REMINDER' AND created_at >= '${since}'::timestamptz;
  `)
  purgeFreeEnrollments({ onlyCancelled: true })
}

async function selfId(request: APIRequestContext): Promise<string> {
  const people = await (await request.get(`${API}/api/v1/activity-participants`, { headers: buyer.headers })).json()
  return people.items.find((person: { relation: string }) => person.relation === 'SELF').participantId
}

async function enrollSelf(request: APIRequestContext): Promise<string> {
  const created = await request.post(`${API}/api/v1/activity-enrollments`, {
    headers: { ...buyer.headers, 'Idempotency-Key': crypto.randomUUID() },
    data: { activityId: OPEN_DAY_ID, sessionId: OPEN_DAY_SESSION, participantIds: [await selfId(request)], noticeAgreed: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).enrollmentId
}

async function cancelEnrollment(request: APIRequestContext, enrollmentId: string): Promise<void> {
  const cancelled = await request.post(`${API}/api/v1/activity-enrollments/${enrollmentId}/cancel`, {
    headers: buyer.headers,
    data: {},
  })
  expect(cancelled.ok()).toBe(true)
}

async function fakePushes(request: APIRequestContext): Promise<Array<{ category: string; targetId: string; deviceId: string }>> {
  const response = await request.get(`${API}/api/v1/dev/notifications/push-deliveries?limit=50`, { headers: buyer.headers })
  expect(response.ok()).toBe(true)
  return (await response.json()).items
}

test.describe('系统通知与通知设置', () => {
  test.describe.configure({ mode: 'serial' })

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const session = await apiLogin(request, BUYER)
    test.skip(!session, '本地后端没有开发账号（不是 dev 配置）')
    buyer = session as Session
    since = psql('SELECT now();')
    test.skip(psql(`SELECT count(*) FROM notification_system_notice WHERE id = '${NOTICE_ID}' AND status = 'PUBLISHED';`) !== '1',
      '后端还没有 S5-9 的开发种子（notification V006 与 R__dev_notification_system_notice）')
    forgetDeliveredNotice()
    await setSwitches(request, { orderServicePush: true, activityPush: true, discoverDot: true })
  })

  test.afterAll(async ({ request }) => {
    if (!buyer || !databaseAvailable()) return
    await setSwitches(request, { orderServicePush: true, activityPush: true, discoverDot: true })
    cleanUp()
  })

  test('SN-001 消息页按画板排五个格；系统通知行进通知详情，并标为已读', async ({ page }) => {
    test.setTimeout(150_000)
    await signIn(page, buyer)
    const inboxCalls: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/notifications?') || request.url().includes('/unread-count')) inboxCalls.push(request.url())
    })
    await page.goto('/#/pages/message/index')

    await expect(page.locator('.message-center__cat-label'))
      .toHaveText(['交易物流', '服务进度', '活动提醒', '系统通知', '联系客服'])
    const row = await revealSystemNotice(page)
    await expect(row).toContainText('系统通知 · 隐私政策更新摘要')
    await expect(row).toContainText('查看主要变化与生效日期')
    await expect(row).toContainText('11/1 09:00')
    await expect(row).toHaveAttribute('data-read', 'false')
    // 预览构建才向服务端要系统通知。
    expect(inboxCalls.some((url) => url.includes('systemNotices=true'))).toBe(true)

    // 系统通知格只筛出系统通知，再点一次回到全部。
    await page.getByTestId('message-cat-system').click()
    await expect(page.locator('.message-center__item[data-kind="notice"]:not([data-category="SYSTEM_NOTICE"])')).toHaveCount(0)
    await expect(page.locator('[data-category="SYSTEM_NOTICE"]')).toHaveCount(1)

    await row.click()
    await expect(page).toHaveURL(new RegExp(`pages/message/notice\\?id=${NOTICE_ID}`))
    await expect(page.getByTestId('notice-title')).toHaveText('隐私政策更新摘要')
    await expect(page.getByTestId('notice-page')).toContainText('系统通知 · 11/01 09:00')
    await expect(page.getByTestId('notice-fact')).toHaveText([/版本\s*v1\.1/, /生效日期\s*2026-11-08/])
    await expect(page.locator('.notice__change-title')).toHaveText(['参与人信息', '权限用途', '查看与导出'])
    await expect(page.getByTestId('notice-action')).toHaveText('查看完整隐私政策')
    await assertNoHorizontalOverflow(page)
    await expect.poll(() => psql(`SELECT count(*) FROM notification_message WHERE recipient_id = ${buyerId}
      AND target_id = '${NOTICE_ID}' AND read_at IS NOT NULL;`)).toBe('1')
  })

  test('SN-002 从通知查看隐私政策的当前发布状态，并回到更新摘要', async ({ page }) => {
    await signIn(page, buyer)
    await page.goto(`/#/pages/message/notice?id=${NOTICE_ID}`)
    await expect(page.getByTestId('notice-title')).toHaveText('隐私政策更新摘要')

    await page.getByTestId('notice-item-0').click()
    await expect(page).toHaveURL(/pages\/activity\/participants/)
    await page.goBack()

    await page.getByTestId('notice-item-1').click()
    await expect(page.locator('uni-toast')).toContainText('权限管理即将开通')
    await expect(page).toHaveURL(/pages\/message\/notice/)

    await page.getByTestId('notice-action').click()
    await expect(page).toHaveURL(/pages\/mine\/agreement\?doc=PRIVACY_POLICY/)
    await expectCurrentAgreement(page, 'PRIVACY_POLICY', '隐私政策')
    await page.getByTestId('agreement-update').click()
    await expect(page).toHaveURL(new RegExp(`pages/message/notice\\?id=${NOTICE_ID}`))
  })

  test('SN-003 协议页不登录也显示“有更新”；没有更新的协议不显示', async ({ page }) => {
    await page.goto('/#/pages/mine/agreement?doc=PRIVACY_POLICY')
    await expect(page.getByTestId('agreement-update')).toContainText('有更新')
    await expect(page.getByTestId('agreement-update')).toContainText('查看本次更新摘要')

    await page.getByTestId('agreement-all').click()
    await page.getByTestId('agreement-pick-user_agreement').click()
    await expectCurrentAgreement(page, 'USER_AGREEMENT', '用户协议')
    await expect(page.getByTestId('agreement-update')).toHaveCount(0)

    await page.goto(`/#/pages/message/notice?id=${NOTICE_ID}`)
    await expect(page.getByTestId('notice-title')).toHaveText('隐私政策更新摘要')
    // Same page with another id: a hash-only change keeps the open notice (H5 reads the query on load).
    await page.goto('/?n=missing#/pages/message/notice?id=c5000000-0000-4000-8000-0000000000ff')
    await expect(page.getByTestId('notice-missing')).toBeVisible()
  })

  test('SN-004 设置页的三个通知开关存到服务端，重进还在；站内消息始终开启', async ({ page, request }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/mine/settings')
    const group = page.getByTestId('settings-notifications')
    await expect(group).toContainText('订单与服务进度')
    await expect(group).toContainText('站内消息')
    await expect(group).toContainText('始终开启')
    for (const id of ['settings-switch-order-push', 'settings-switch-activity', 'settings-switch-discover-dot']) {
      await expect(page.getByTestId(id)).toHaveAttribute('aria-checked', 'true')
    }

    await page.getByTestId('settings-switch-activity').click()
    await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', 'false')
    await expect.poll(async () => (await (await request.get(`${API}/api/v1/notifications/preferences`,
      { headers: buyer.headers })).json()).activityPush).toBe(false)

    await page.reload()
    await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByTestId('settings-switch-order-push')).toHaveAttribute('aria-checked', 'true')
    await page.getByTestId('settings-switch-activity').click()
    await expect.poll(async () => (await (await request.get(`${API}/api/v1/notifications/preferences`,
      { headers: buyer.headers })).json()).activityPush).toBe(true)
  })

  test('SN-005 开关只管推送：活动提醒关着时报名成功照样进“消息”但不推送；打开后推送到登记的设备', async ({ request }) => {
    const upcoming = await (await request.get(`${API}/api/v1/activity-enrollments?status=UPCOMING&size=50`,
      { headers: buyer.headers })).json()
    test.skip(upcoming.items.some((item: { activity?: { activityId?: string } }) => item.activity?.activityId === OPEN_DAY_ID),
      'dev-customer 已经报了体验日：先取消，或在一次性库上跑')
    const registered = await request.put(`${API}/api/v1/notifications/push-devices`, {
      headers: buyer.headers,
      data: { platform: 'ANDROID', pushClientId: PUSH_ID, cityCode: '370500' },
    })
    expect(registered.ok()).toBe(true)
    const { deviceId } = await registered.json()

    await setSwitches(request, { activityPush: false })
    const silent = await enrollSelf(request)
    await expect.poll(() => psql(`SELECT count(*) FROM notification_message WHERE recipient_id = ${buyerId}
      AND category = 'ACTIVITY_REMINDER' AND target_id = '${silent}';`)).toBe('1')
    expect((await fakePushes(request)).filter((push) => push.targetId === silent)).toEqual([])
    await cancelEnrollment(request, silent)

    await setSwitches(request, { activityPush: true })
    const loud = await enrollSelf(request)
    await expect.poll(async () => (await fakePushes(request))
      .filter((push) => push.targetId === loud && push.deviceId === deviceId)
      .map((push) => push.category)).toEqual(['ACTIVITY_REMINDER'])
    await cancelEnrollment(request, loud)

    // 退出登录前撤销：之后这个账号的推送不再发到这台设备。
    const revoked = await request.post(`${API}/api/v1/notifications/push-devices/unregister`, {
      headers: buyer.headers, data: { platform: 'ANDROID', pushClientId: PUSH_ID },
    })
    expect(revoked.status()).toBe(204)
    expect(psql(`SELECT status FROM notification_push_device WHERE push_client_id = '${PUSH_ID}';`)).toBe('REVOKED')
  })

  test('SN-006 320/375/430 不溢出；390 宽和画板 Messages、NoticeDetail、Settings、Agreement 并排', async ({ page }) => {
    test.setTimeout(300_000)
    await signIn(page, buyer)
    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })
    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at('#/pages/message/index'))
      await expect(page.getByTestId('message-cat-system')).toBeVisible()
      await expect(await revealSystemNotice(page)).toBeVisible()
      await assertNoHorizontalOverflow(page)
      // Back to the top for the picture: the squares and the newest rows, as on the board.
      await page.evaluate(() => window.scrollTo(0, 0))
      await page.screenshot({ path: shot('Messages') })

      await page.goto(at(`#/pages/message/notice?id=${NOTICE_ID}`))
      await expect(page.getByTestId('notice-title')).toHaveText('隐私政策更新摘要')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('NoticeDetail'))

      await page.goto(at('#/pages/mine/settings'))
      await expect(page.getByTestId('settings-notifications')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Settings'))

      await page.goto(at('#/pages/mine/agreement?doc=PRIVACY_POLICY'))
      await expect(page.getByTestId('agreement-update')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Agreement'))
    }

    for (const board of ['Messages', 'NoticeDetail', 'Settings', 'Agreement']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(boardUrl(board))
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
