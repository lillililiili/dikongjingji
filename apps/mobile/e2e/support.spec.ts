import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import {
  PLATFORM_DESK,
  PROVIDER_DESK,
  buyerConversationsWithMessages,
  conversationIdFromUrl,
  deskReply,
  purgeBuyerSupport,
} from './support/support-fixtures'
import {
  API,
  BUYER,
  CITY,
  MERCHANT,
  apiLogin,
  databaseAvailable,
  offerId,
  psql,
  type Session,
} from './support/trade-fixtures'

// 客服会话、设置与公共页（G1，计划 §26）：商品“咨询”进商家会话、带商品卡片、发卡片和文字、商家用受权接口回复、
// 轮询看到回复、“消息”里的会话未读和客服消息、点开即已读；帮助页进平台客服，dev-support 回复；专业服务详情进
// 服务商会话，dev-provider 回复；提交反馈；设置 → 退出登录 → 游客“我的”；协议与规则、关于；分享复制链接；
// 商品详情不预选不能在线收款的商家。真实后端；跑完删掉 dev-customer 这次建的会话、消息、客服消息和反馈。
const BUY_PRODUCT = 'c2000000-0000-4000-8000-000000000101'
const DOG_PRODUCT = 'c2000000-0000-4000-8000-000000000401'
const DOG_OFFER = offerId('401', '1')
const UNPAID_DOG_OFFER = offerId('401', '6')
const AERIAL_PARK = 'f2100000-0000-4000-8000-000000000001'
const visualOutput = resolve(__dirname, '../../../../../.planning/support/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/support')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')
// Replies arrive through polling every 10 seconds.
const POLL_WAIT = 25_000

let buyer: Session
let merchant: Session
let provider: Session
let support: Session
let since = ''

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    if (!sessionStorage.getItem('e2e-signed-out')) localStorage.setItem('inspection.accessToken', token)
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
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

async function typeAndSend(page: Page, text: string): Promise<void> {
  await page.getByTestId('support-chat-input').locator('input').fill(text)
  await expect(page.locator('[data-testid="support-chat-send"]:not([disabled])')).toHaveCount(1)
  await page.getByTestId('support-chat-send').click()
}

async function unreadOf(conversationId: string): Promise<{ unread: number; notices: string }> {
  const row = psql(`SELECT customer_unread FROM support_conversation WHERE id = '${conversationId}';`)
  const notices = psql(`SELECT coalesce(string_agg(CASE WHEN read_at IS NULL THEN 'U' ELSE 'R' END, '' ORDER BY created_at), '')
    FROM notification_message WHERE category = 'CUSTOMER_SERVICE' AND target_id = '${conversationId}';`)
  return { unread: Number(row), notices }
}

test.describe('客服会话、设置与公共页', () => {
  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const sessions = await Promise.all([BUYER, MERCHANT, 'dev-provider', 'dev-support'].map((name) => apiLogin(request, name)))
    test.skip(sessions.some((session) => !session), '本地后端没有开发账号（不是 dev 配置）')
    ;[buyer, merchant, provider, support] = sessions as Session[]
    since = psql('SELECT now();')
    // Empty conversations are harmless leftovers of clicking 咨询; ones with messages belong to
    // someone's manual check and are not touched.
    purgeBuyerSupport(since)
    test.skip(buyerConversationsWithMessages() > 0, 'dev-customer 已经有带消息的会话：先删掉它们，或在一次性库上跑')
  })

  test.afterAll(() => {
    if (since && databaseAvailable()) purgeBuyerSupport(since)
  })

  test('SUP-001 商品“咨询”进商家会话：发商品卡片和文字，商家回复后轮询看到；“消息”里有未读和客服消息，点开即已读', async ({ page, request }) => {
    test.setTimeout(120_000)
    await signIn(page, buyer)
    await page.goto(`/#/pages/mall/product?productId=${BUY_PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await page.getByTestId('mall-product-consult').click()

    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    await expect(page).toHaveURL(/pages\/support\/chat\?conversationId=/)
    const conversationId = conversationIdFromUrl(page)
    await expect(page.getByTestId('support-chat-title')).toHaveText('示例机器人商家')
    // No phone number of the merchant anywhere on the page.
    await expect(page.getByTestId('support-chat')).not.toContainText(/1[3-9]\d{9}/)
    const pending = page.getByTestId('support-chat-pending-card')
    await expect(pending).toContainText('编程机器人探索套装')
    await expect(pending).toContainText('商品')

    // The card goes out only on 发送.
    expect(psql(`SELECT count(*) FROM support_message WHERE conversation_id = '${conversationId}';`)).toBe('0')
    await page.getByTestId('support-chat-send-card').click()
    await expect(page.getByTestId('support-card-1')).toContainText('编程机器人探索套装')
    await expect(pending).toHaveCount(0)
    await typeAndSend(page, '物流到哪了？')
    await expect(page.getByTestId('support-message-2')).toHaveText('物流到哪了？')
    await expect(page.getByTestId('support-message-2')).toHaveAttribute('data-mine', 'true')

    // The merchant answers from its desk; the open page picks it up by polling and reads it.
    const merchantList = await (await request.get(`${API}${PROVIDER_DESK}`, { headers: merchant.headers })).json()
    expect(merchantList.items.find((item: { conversationId: string }) => item.conversationId === conversationId).unreadCount).toBe(2)
    await deskReply(request, merchant, PROVIDER_DESK, conversationId, '今天 10:24 已从东营仓发出')
    await expect(page.getByTestId('support-message-3')).toContainText('今天 10:24 已从东营仓发出', { timeout: POLL_WAIT })
    await expect(page.getByTestId('support-message-3')).toHaveAttribute('data-mine', 'false')
    await expect.poll(async () => (await unreadOf(conversationId)).unread).toBe(0)
    await expect.poll(async () => (await unreadOf(conversationId)).notices).toBe('R')

    // The card opens the product it is about.
    await page.getByTestId('support-card-1').click()
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')

    // Away from the chat, a second reply waits in 消息.
    await page.goto('/?n=messages#/pages/message/index')
    await deskReply(request, merchant, PROVIDER_DESK, conversationId, '运单号在订单详情里')
    await page.goto('/?n=messages2#/pages/message/index')
    const row = page.getByTestId(`conversation-${conversationId}`)
    await expect(row).toContainText('示例机器人商家')
    await expect(row).toContainText('运单号在订单详情里')
    await expect(row.getByTestId('conversation-unread')).toHaveText('1')
    const notice = page.locator('[data-testid^="message-"][data-read="false"]', { hasText: '商家回复了你' })
    await expect(notice).toHaveCount(1)
    await expect(notice).toContainText('客服消息')
    await expect(page.getByTestId('root-tab-message').locator('.root-tab-bar__badge')).toBeVisible()

    await notice.click()
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('support-message-4')).toContainText('运单号在订单详情里')
    await expect.poll(async () => unreadOf(conversationId)).toEqual({ unread: 0, notices: 'RR' })

    await page.goto('/?n=messages3#/pages/message/index')
    await expect(page.getByTestId(`conversation-${conversationId}`)).toHaveAttribute('data-unread', '0')
  })

  test('SUP-002 帮助页“联系平台客服”：平台客服回复；页头“帮助”回到帮助页', async ({ page, request }) => {
    test.setTimeout(90_000)
    await signIn(page, buyer)
    await page.goto('/#/pages/mine/help')
    await expect(page.getByTestId('help-page')).toBeVisible()
    await expect(page.getByTestId('help-question-pay-channel').getByTestId('help-answer')).toContainText('目前只支持微信支付')
    // A category opens with its first question open.
    await page.getByTestId('help-category-rental').click()
    await expect(page.getByTestId('help-question-rental-deposit').getByTestId('help-answer')).toContainText('押金不在线收取')

    await page.getByTestId('help-contact-platform').click()
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('support-chat-title')).toHaveText('平台客服')
    await expect(page.getByTestId('support-chat')).toContainText('平台只协助沟通，不裁决')
    await expect(page.getByTestId('support-chat-pending-card')).toHaveCount(0)
    const conversationId = conversationIdFromUrl(page)
    await typeAndSend(page, '我要投诉')
    await expect(page.getByTestId('support-message-1')).toHaveText('我要投诉')

    const desk = await (await request.get(`${API}${PLATFORM_DESK}`, { headers: support.headers })).json()
    expect(desk.items.some((item: { conversationId: string }) => item.conversationId === conversationId)).toBe(true)
    await deskReply(request, support, PLATFORM_DESK, conversationId, '请写明投诉对象和经过')
    await expect(page.getByTestId('support-message-2')).toContainText('请写明投诉对象和经过', { timeout: POLL_WAIT })

    await page.getByTestId('support-chat-peer-action').click()
    await expect(page.getByTestId('help-page')).toBeVisible()
  })

  test('SUP-003 专业服务详情“客服”进服务商会话：发服务卡片，服务商回复', async ({ page, request }) => {
    test.setTimeout(90_000)
    await signIn(page, buyer)
    await page.goto(`/#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
    await page.getByTestId('pro-detail-customer-service').click()

    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('support-chat-title')).toHaveText('云翼科技')
    await expect(page.getByTestId('support-chat-peer-action')).toHaveText('主页')
    const conversationId = conversationIdFromUrl(page)
    await page.getByTestId('support-chat-send-card').click()
    await expect(page.getByTestId('support-card-1')).toContainText('园区与工程航拍')
    await expect(page.getByTestId('support-card-1')).toContainText('专业服务')

    await deskReply(request, provider, PROVIDER_DESK, conversationId, '可以，周三上午有档期')
    await expect(page.getByTestId('support-message-2')).toContainText('可以，周三上午有档期', { timeout: POLL_WAIT })
    // Another provider's desk does not see it.
    const merchantDesk = await request.get(`${API}${PROVIDER_DESK}/${conversationId}/messages`, { headers: merchant.headers })
    expect(merchantDesk.status()).toBe(404)

    await page.getByTestId('support-chat-peer-action').click()
    await expect(page.getByTestId('provider-name')).toHaveText('云翼科技')
  })

  test('SUP-004 提交反馈：选类型、写描述，提交后只说“已收到你的反馈”', async ({ page }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/mine/help')
    await page.getByTestId('help-feedback').click()
    const sheet = page.getByTestId('help-feedback-sheet')
    await expect(sheet).toBeVisible()
    await expect(sheet).not.toContainText('截图')
    await expect(page.locator('[data-testid="help-feedback-submit"][disabled]')).toHaveCount(1)
    await page.getByTestId('help-feedback-type-display').click()
    await page.getByTestId('help-feedback-text').locator('textarea').fill('订单页金额被挡住了')
    await expect(page.getByTestId('help-feedback-count')).toHaveText('9/200')
    await expect(page.locator('[data-testid="help-feedback-submit"]:not([disabled])')).toHaveCount(1)
    await page.getByTestId('help-feedback-submit').click()

    await expect(page.getByTestId('help-feedback-sent')).toHaveText('已收到你的反馈')
    await expect(sheet).toHaveCount(0)
    expect(psql(`SELECT feedback_type || ':' || body FROM support_feedback WHERE account_id = (SELECT id FROM identity_user WHERE login_name = '${BUYER}')
      AND created_at >= '${since}'::timestamptz ORDER BY created_at DESC LIMIT 1;`)).toBe('DISPLAY:订单页金额被挡住了')
  })

  test('SUP-005 设置：账号、地址、协议与规则、关于；退出登录后是游客“我的”，帮助和关于不用登录', async ({ page }) => {
    await signIn(page, buyer)
    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-settings').click()
    await expect(page.getByTestId('settings-page')).toBeVisible()
    await expect(page.getByTestId('settings-display-name')).toHaveText('王女士（示例）')
    await expect(page.getByTestId('settings-organization')).not.toHaveText('—')
    await expect(page.getByTestId('settings-page')).not.toContainText(/1[3-9]\d{9}|手机号|注销|推送/)

    await page.getByTestId('settings-agreement-user_agreement').click()
    await expect(page.getByTestId('agreement-title')).toHaveText('用户协议')
    await expect(page.getByTestId('agreement-placeholder').first()).toHaveText('[协议正文待确认]')
    await page.getByTestId('agreement-back').click()
    await page.getByTestId('settings-about').click()
    await expect(page.getByTestId('about-version')).toHaveText(/^版本 \d+\.\d+\.\d+$/)
    await expect(page.getByTestId('about-filing')).toHaveText('[备案号待确认]')
    await page.getByTestId('about-back').click()
    await page.getByTestId('settings-addresses').click()
    await expect(page).toHaveURL(/pages\/mine\/addresses/)
    await page.goBack()

    await page.getByTestId('settings-logout').click()
    await page.locator('.uni-modal__btn_primary').click()
    await page.evaluate(() => sessionStorage.setItem('e2e-signed-out', '1'))
    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expect(page.locator('.platform-mine__name')).toHaveText('未登录')
    expect(await page.evaluate(() => localStorage.getItem('inspection.accessToken'))).toBeNull()

    // Guest (board MineGuest): help and about open without signing in; account rows go to login.
    await page.getByTestId('mine-link-help').click()
    await expect(page.getByTestId('help-page')).toBeVisible()
    await page.getByTestId('help-back').click()
    await page.getByTestId('mine-link-about').click()
    await expect(page.getByTestId('about-page')).toBeVisible()
    await page.getByTestId('about-back').click()
    await page.getByTestId('mine-link-demands').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login/)
    await page.goBack()
    await page.getByTestId('mine-settings').click()
    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=.*mine.*index/)
  })

  test('SUP-006 协议与规则：全部协议里换文档；报名和确认订单的协议链接打开同一页', async ({ page }) => {
    await page.goto('/#/pages/mine/agreement?doc=TRADE_AGREEMENT')
    await expect(page.getByTestId('agreement-title')).toHaveText('平台交易协议')
    await expect(page.getByTestId('agreement-page')).toContainText('示例')
    await page.getByTestId('agreement-all').click()
    await expect(page.getByTestId('agreement-sheet').locator('.agreement__sheet-name'))
      .toHaveText(['隐私政策', '用户协议', '儿童个人信息保护规则', '平台交易协议', '报名须知与照片使用说明'])
    await page.getByTestId('agreement-pick-enrollment_notice').click()
    await expect(page.getByTestId('agreement-title')).toHaveText('报名须知与照片使用说明')
    await expect(page.getByTestId('agreement-sheet')).toHaveCount(0)
    // An unknown document falls back to the privacy policy.
    await page.goto('/?n=x#/pages/mine/agreement?doc=NOPE')
    await expect(page.getByTestId('agreement-title')).toHaveText('隐私政策')
  })

  test('SUP-007 分享只复制链接：链接是这一页的地址，只带稳定 ID 和城市', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto(`/#/pages/mall/product?productId=${BUY_PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await page.getByTestId('mall-product-share').click()
    const sheet = page.getByTestId('share-sheet')
    await expect(sheet).toContainText('分享商品')
    await expect(sheet).toContainText('编程机器人探索套装')
    const origin = new URL(page.url()).origin
    const expected = `${origin}/#/pages/mall/product?productId=${BUY_PRODUCT}&cityCode=${CITY}`
    await expect(sheet).toHaveAttribute('data-link', expected)
    await page.getByTestId('share-copy').click()
    await expect(page.getByTestId('share-copied')).toHaveText('链接已复制')
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected)
    await page.getByTestId('share-close').click()
    await expect(sheet).toHaveCount(0)

    await page.goto(`/?n=pro#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail')).toHaveAttribute('data-state', 'ready')
    await page.getByTestId('pro-detail-share').click()
    await expect(page.getByTestId('share-sheet'))
      .toHaveAttribute('data-link', `${origin}/#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
  })

  test('SUP-008 商品详情不预选不能在线收款的商家：灰显“暂未开通在线支付”，点了也不选', async ({ page }) => {
    await page.goto(`/#/pages/mall/product?productId=${DOG_PRODUCT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('巡检机器狗')
    const unpaid = page.getByTestId(`mall-offer-${UNPAID_DOG_OFFER}`)
    await expect(unpaid).toHaveAttribute('data-online-payment', 'false')
    await expect(page.getByTestId(`mall-offer-unpaid-${UNPAID_DOG_OFFER}`)).toHaveText('暂未开通在线支付')
    await expect(page.getByTestId(`mall-offer-${DOG_OFFER}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('mall-product-seller')).toContainText('示例机器人商家')
    await unpaid.click()
    await expect(page.locator('.uni-sample-toast')).toContainText('暂未开通在线支付')
    await expect(page.getByTestId(`mall-offer-${DOG_OFFER}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(unpaid).toHaveAttribute('aria-pressed', 'false')
  })

  test('SUP-201 320 / 375 / 430 不溢出；390 宽与画板 Chat、Settings、Help 对照', async ({ page, request }) => {
    test.setTimeout(180_000)
    // A conversation with a card, both sides talking.
    await signIn(page, buyer)
    await page.goto(`/#/pages/mall/product?productId=${BUY_PRODUCT}&cityCode=${CITY}`)
    await page.getByTestId('mall-product-consult').click()
    await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
    const conversationId = conversationIdFromUrl(page)
    if (await page.getByTestId('support-chat-send-card').count()) await page.getByTestId('support-chat-send-card').click()
    await typeAndSend(page, '教室套装包含什么？')
    await expect(page.locator('[data-testid="support-outgoing"]')).toHaveCount(0)
    await deskReply(request, merchant, PROVIDER_DESK, conversationId, '6 台机器人和教师端，教室上课可以统一管理。')

    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })
    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at(`#/pages/support/chat?conversationId=${conversationId}&cardType=PRODUCT&cardId=${BUY_PRODUCT}&cardTitle=${encodeURIComponent('编程机器人探索套装')}`))
      await expect(page.getByTestId('support-chat')).toHaveAttribute('data-state', 'ready')
      await expect(page.getByTestId('support-chat-log')).toContainText('教师端')
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('Chat') })

      await page.goto(at('#/pages/mine/settings'))
      await expect(page.getByTestId('settings-display-name')).toHaveText('王女士（示例）')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Settings'))

      await page.goto(at('#/pages/mine/help'))
      await expect(page.getByTestId('help-page')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Help'))
      if (width === 390) continue

      await page.getByTestId('help-feedback').click()
      await page.getByTestId('help-feedback-type-function').click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('feedback-sheet') })

      await page.goto(at('#/pages/message/index'))
      await expect(page.getByTestId(`conversation-${conversationId}`)).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('messages'))

      await page.goto(at('#/pages/mine/agreement?doc=PRIVACY_POLICY'))
      await expect(page.getByTestId('agreement-title')).toHaveText('隐私政策')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('agreement'))

      await page.goto(at('#/pages/mine/about'))
      await expect(page.getByTestId('about-page')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('about'))

      await page.goto(at('#/pages/mine/index'))
      await expect(page.getByTestId('mine-link-help')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('mine'))

      await page.goto(at(`#/pages/mall/product?productId=${BUY_PRODUCT}&cityCode=${CITY}`))
      await expect(page.getByTestId('mall-product-name')).toBeVisible()
      await page.getByTestId('mall-product-share').click()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('share-sheet') })
    }

    // The boards at 390 next to the App at 390. The Chat board opens in its merchant frame.
    const chatCopy = resolve(compareOutput, 'Chat-merchant-board.html')
    writeFileSync(chatCopy, readFileSync(resolve(boardDir, 'Chat.html'), 'utf8')
      .replace('<head>', `<head><base href="file://${boardDir}/">`)
      .replace('{"frame":"平台客服"}', '{"frame":"商家"}'))
    const boards: Array<[string, string]> = [
      ['Chat', chatCopy],
      ['Settings', resolve(boardDir, 'Settings.html')],
      ['Help', resolve(boardDir, 'Help.html')],
    ]
    for (const [board, file] of boards) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${file}`)
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
