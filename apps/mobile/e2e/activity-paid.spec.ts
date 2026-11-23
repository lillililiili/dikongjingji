import { randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  API,
  BUYER,
  CITY,
  apiLogin,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  fakeWechatRefunds,
  latestRefundNo,
  openPaymentNo,
  payByApi,
  psql,
  type Session,
} from './support/trade-fixtures'
import { purgeFreeEnrollments } from './support/activity-fixtures'

// 收费活动报名（E09，计划 §20）：填写报名 → 收银台 → 支付结果 → 报名凭证 → 退一位；待付款取消；
// 金额变了、主办方没开通在线支付两种拒绝。真实后端 + 本机模拟微信（FAKE_WECHAT=1），H5 调不起微信，
// 付款由模拟微信的控制入口代替买家完成，页面只认服务端查单的结果。
// 用示例活动“机器人研学体验”（¥168/人，6–12 岁，主办方登记了收款账户）。每条用例前后都把 dev-customer 在这个活动上的
// 报名、交易订单和测试子女从数据库删掉，并把占着的名额还给场次。
const STUDY_ID = 'a2000000-0000-4000-8000-000000000002'
const SESSION_A = 'a3000000-0000-4000-8000-000000000201'
const UNIT = 16800
const CHILD_NAMES = ['付费小禾', '付费小川']
const visualOutput = resolve(__dirname, '../../../../../.planning/activity-paid/visual')

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

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/**
 * Removes what the tests made: the buyer's enrolments on the study activity (seats of tickets still
 * holding one go back to the session first), the trade orders behind them and the test children.
 * The orders are locked first so the timeout job skips them while they are deleted.
 */
function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_enrollments ON COMMIT DROP AS
      SELECT id, trade_order_id FROM activity_enrollment WHERE account_id = ${buyerId()} AND activity_id = '${STUDY_ID}';
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT id FROM trade_order WHERE id IN (SELECT trade_order_id FROM e2e_enrollments)
         OR (buyer_id = ${buyerId()} AND source = 'ACTIVITY' AND source_ref IN (SELECT id FROM e2e_enrollments));
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_orders) FOR UPDATE) locked;
    UPDATE activity_session s SET enrolled_count = s.enrolled_count - t.n
      FROM (SELECT session_id, count(*) AS n FROM activity_enrollment_ticket
            WHERE enrollment_id IN (SELECT id FROM e2e_enrollments) AND status <> 'CANCELLED' GROUP BY session_id) t
     WHERE s.id = t.session_id;
    DELETE FROM activity_enrollment_event WHERE enrollment_id IN (SELECT id FROM e2e_enrollments);
    DELETE FROM activity_enrollment_ticket WHERE enrollment_id IN (SELECT id FROM e2e_enrollments);
    DELETE FROM activity_enrollment WHERE id IN (SELECT id FROM e2e_enrollments);
    DELETE FROM trade_channel_notification
      WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders))
         OR out_refund_no IN (SELECT out_refund_no FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders));
    DELETE FROM trade_state_transition WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_delivery WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_line WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order WHERE id IN (SELECT id FROM e2e_orders);
    DELETE FROM activity_participant p WHERE owner_id = ${buyerId()}
      AND name IN (${CHILD_NAMES.map((name) => `'${name}'`).join(', ')})
      AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.participant_id = p.id);
    COMMIT;
  `)
  // Free sign-ups that are over (left by the free sign-up spec) hold no seats but fill the 已取消 tab
  // that PAY-002 pages through; they go too.
  purgeFreeEnrollments({ onlyCancelled: true })
}

async function addChild(request: APIRequestContext, session: Session, name: string, yearsOld: number): Promise<string> {
  const created = await request.post(`${API}/api/v1/activity-participants`, {
    headers: session.headers,
    data: { name, birthMonth: `${new Date().getFullYear() - yearsOld}-01`, guardianConfirmed: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).participantId
}

interface EnrollmentSnapshot {
  enrollmentId: string
  payment: { status: string; tradeOrderId: string | null; payBefore: string | null }
  tickets: Array<{ ticketId: string; status: string }>
}

async function enrollByApi(request: APIRequestContext, session: Session, participantIds: string[]): Promise<EnrollmentSnapshot> {
  const created = await request.post(`${API}/api/v1/activity-enrollments`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: {
      activityId: STUDY_ID,
      sessionId: SESSION_A,
      participantIds,
      noticeAgreed: true,
      expectedAmountMinor: UNIT * participantIds.length,
    },
  })
  expect(created.status()).toBe(201)
  return created.json()
}

async function readEnrollment(request: APIRequestContext, session: Session, enrollmentId: string): Promise<EnrollmentSnapshot> {
  return (await request.get(`${API}/api/v1/activity-enrollments/${enrollmentId}`, { headers: session.headers })).json()
}

async function seatsLeft(request: APIRequestContext): Promise<number> {
  const detail = await (await request.get(`${API}/api/v1/activities/${STUDY_ID}?cityCode=${CITY}`)).json()
  return detail.sessions.find((item: { sessionId: string }) => item.sessionId === SESSION_A).seatsLeft
}

function orderIdFromUrl(page: Page): string {
  return new URL(page.url().replace('/#/', '/')).searchParams.get('orderId') as string
}

// uni-button shows as enabled to Playwright even when disabled, so the attribute is checked.
const submitOn = (page: Page) => page.locator('[data-testid="activity-enroll-submit"]:not([disabled])')
const submitOff = (page: Page) => page.locator('[data-testid="activity-enroll-submit"][disabled]')
const person = (page: Page, name: string) => page.locator(`.activity-enroll__person[data-name="${name}"]`)

test.describe('收费活动报名', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let kidA: string
  let kidB: string

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试数据')
    buyer = signedIn as Session
    cleanup()
    kidA = await addChild(request, buyer, CHILD_NAMES[0], 10)
    kidB = await addChild(request, buyer, CHILD_NAMES[1], 8)
  })

  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('PAY-001 两个孩子报名 → 收银台 → 模拟微信付款 → 报名成功 → 凭证有二维码 → 退一位，退款到账', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const seatsBefore = await seatsLeft(request)
    await signIn(page, buyer)

    // 详情页：收费活动现在是真的“立即报名”。
    await page.goto(`/#/pages/activity/detail?activityId=${STUDY_ID}&cityCode=${CITY}`)
    await expect(page.getByTestId('activity-detail-title')).toHaveText('机器人研学体验')
    await page.getByTestId(`activity-session-${SESSION_A}`).click()
    await page.locator('[data-testid="activity-detail-primary"]:not([disabled])').click()

    // 填写报名：单价 × 人数，按钮“提交并支付”。
    await expect(page.getByTestId('activity-enroll-title')).toHaveText('机器人研学体验')
    await expect(page.getByTestId('activity-enroll-price')).toHaveText('¥168/人')
    await expect(page.getByTestId('activity-enroll-submit')).toHaveText('提交并支付')
    await expect(page.getByTestId('activity-enroll-paid-rule')).toContainText('每位按报名时的单价原路退回')
    await person(page, CHILD_NAMES[0]).click()
    await person(page, CHILD_NAMES[1]).click()
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('2 人 × ¥168')
    await expect(page.getByTestId('activity-enroll-price')).toHaveText('¥336')
    // 画板：按钮一直是橙色；没勾同意就点，提示先同意。
    await expect(page.getByTestId('activity-enroll-submit')).toHaveAttribute('data-ready', 'false')
    await page.getByTestId('activity-enroll-submit').click()
    await expect(page.locator('uni-toast')).toContainText('请先阅读并同意报名须知')
    await page.getByTestId('activity-enroll-agree').click()
    await submitOn(page).click()

    // 收银台替换了报名表。
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥336')
    await expect(page.getByTestId('cashier-due-note')).toHaveText('报名费 2 人 × ¥168')
    await expect(page.getByTestId('cashier-countdown')).toHaveText(/^名额保留 1[45]:\d\d · 超时自动释放$/)
    await expect(page.getByTestId('cashier-payee')).toHaveText('示例研学机构')
    await expect(page.getByTestId('cashier-activity')).toHaveText('机器人研学体验')
    await expect(page.getByTestId('cashier-people')).toHaveText('2 人')
    await expect(page.getByTestId('cashier-goods')).toHaveCount(0)
    await expect(page.getByTestId('cashier-order-no')).toHaveText(/^AO\d{16}$/)
    const orderId = orderIdFromUrl(page)
    // 名额在提交时就占住了。
    expect(await seatsLeft(request)).toBe(seatsBefore - 2)

    // H5 调不起微信：结果页由服务端查单，没付款就是“支付失败”，按钮去报名单。
    await page.getByTestId('cashier-pay').click()
    await expect(page.locator('uni-toast')).toContainText('请在 App 中完成微信支付')
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('pay-result-failed')).toContainText('名额仍为你保留，超时自动释放')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看报名')

    await fakeWechatPays(request, openPaymentNo(orderId))
    await page.getByTestId('pay-result-refresh').click()
    await expect(page.getByTestId('pay-result-paid')).toBeVisible()
    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('报名成功')
    await expect(page.getByTestId('pay-result-amount')).toHaveText('¥336')
    await expect(page.getByTestId('pay-result-paid-note')).toHaveText('2 个名额已确认 · 一人一码')
    await expect(page.getByTestId('pay-result-payee')).toHaveText('示例研学机构')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看凭证')
    await page.getByTestId('pay-result-view-order').click()

    // 凭证：已支付在进度的最前面，一人一码。
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名成功 · 待参加')
    await expect(page.getByTestId('ticket-code')).toHaveText(/^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/)
    await expect(page.getByTestId('ticket-qr').locator('img')).toHaveAttribute('src', /data:image\/svg\+xml/)
    await expect(page.getByTestId('ticket-steps')).toContainText('已支付')
    await expect(page.getByTestId('ticket-refund')).toHaveCount(0)

    // 订单详情（画板 OrderEnroll）：金额、收款方、订单编号都从服务端读。
    await page.getByTestId('ticket-order').click()
    await expect(page.getByTestId('activity-order-amount')).toHaveText('¥336')
    await expect(page.getByTestId('activity-order-money')).toContainText('2 人 × ¥168')
    await expect(page.getByTestId('activity-order-parties')).toContainText('示例研学机构 · 经平台支付渠道')
    await expect(page.getByTestId('activity-order-pay-state')).toHaveText('已支付 · 渠道回执已核验')
    await expect(page.getByTestId('activity-order-no')).not.toBeEmpty()
    await page.getByTestId('activity-order-back').click()

    // 退其中一位（画板 EnrollRefund）：从凭证进入只勾当前这位，写明退多少，确认后提交即退。
    await page.locator('[data-testid="ticket-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('activity-refund-amount')).toHaveText('¥168')
    await expect(page.locator('.enroll-refund__person[aria-checked="true"]')).toHaveCount(1)
    await page.getByTestId('activity-refund-submit').click()
    await expect(page.locator('.uni-modal')).toContainText('原路退回 ¥168')
    await page.locator('.uni-modal__btn_primary').click()
    // S5-3：提交后先到退改详情（退款中），返回凭证。
    await expect(page.getByTestId('activity-change-status')).toHaveText('退款中')
    await page.getByTestId('activity-change-back').click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名已取消')
    await expect(page.getByTestId('ticket-refund')).toHaveText('退款中 ¥168 · 原路退回')
    await expect(page.getByTestId('ticket-stamp')).toHaveText('已取消')
    expect(await seatsLeft(request)).toBe(seatsBefore - 1)

    // 模拟微信退款成功并回调，凭证上变成“已退款”。
    await fakeWechatRefunds(request, latestRefundNo(orderId))
    // A reload opens on the ticket still in use; the cancelled one is picked again.
    await expect.poll(async () => {
      await page.reload()
      await page.locator('.ticket__person[data-status="CANCELLED"]').click()
      return (await page.getByTestId('ticket-refund').textContent({ timeout: 5000 }))?.trim()
    }, { timeout: 30_000 }).toBe('已退款 ¥168 · 原路退回')
    await expect(page.getByTestId('ticket-note')).toContainText('¥168 已原路退回')

    // 另一位的凭证仍然有效。
    const other = page.locator('.ticket__person[data-status="ACTIVE"]')
    await other.click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('报名成功 · 待参加')
    await expect(page.getByTestId('ticket-code')).toBeVisible()

    // 我的报名：待参加，金额和退款写在卡片上。
    await page.goto('/#/pages/mine/activity-enrollments')
    const card = page.locator('.enrollments__card')
    await expect(card).toHaveCount(1)
    await expect(card).toContainText('待参加')
    await expect(card.getByTestId('enrollment-price')).toHaveText('¥336')
    await expect(card.getByTestId('enrollment-note')).toHaveText('¥168 已原路退回')
  })

  test('PAY-002 待付款：列表“待付款”可去付款，凭证没有二维码，取消后关闭并释放名额', async ({ page, request }) => {
    const seatsBefore = await seatsLeft(request)
    const created = await enrollByApi(request, buyer, [kidA, kidB])
    expect(created.payment.status).toBe('PENDING_PAYMENT')
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/activity-enrollments')
    const card = page.getByTestId(`enrollment-${created.enrollmentId}`)
    await expect(card).toContainText('待付款')
    await expect(card.getByTestId('enrollment-price')).toHaveText('¥336')
    await expect(card.getByTestId('enrollment-note')).toHaveText(/^请在 \d\d\/\d\d \d\d:\d\d 前付款$/)

    // 去付款 → 收银台，返回回到列表。
    await page.getByTestId(`enrollment-pay-${created.enrollmentId}`).click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥336')
    expect(orderIdFromUrl(page)).toBe(created.payment.tradeOrderId)
    await page.getByTestId('cashier-back').click()
    await expect(page.getByTestId('activity-enrollments')).toBeVisible()

    await page.getByTestId(`enrollment-open-${created.enrollmentId}`).click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('待付款')
    await expect(page.getByTestId('ticket-qr-empty')).toContainText('付款后生成')
    await expect(page.getByTestId('ticket-code')).toHaveCount(0)
    await expect(page.getByTestId('ticket-pay')).toHaveText(/^去付款 · 1[45]:\d\d$/)
    await expect(page.getByTestId('ticket-note')).toContainText('内付款，超时名额自动释放')

    // 待付款只能整单取消，确认框写明不会扣款。
    await page.locator('[data-testid="ticket-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('ticket-cancel-text')).toHaveText('还没有付款，取消后报名关闭、名额释放，不会扣款。')
    await expect(page.getByTestId('ticket-cancel-one')).toHaveCount(0)
    await page.getByTestId('ticket-cancel-unpaid').click()
    await expect(page.getByTestId('ticket-status-title')).toHaveText('已关闭，未付款')
    await expect(page.getByTestId('ticket-stamp')).toHaveText('已关闭')
    await expect(page.getByTestId('ticket-pay')).toHaveCount(0)
    expect(await seatsLeft(request)).toBe(seatsBefore)
    expect((await readEnrollment(request, buyer, created.enrollmentId)).payment.status).toBe('CLOSED')

    // “已取消”页签里写明是没付款关闭的。
    await page.getByTestId('ticket-back').click()
    await page.getByTestId('enrollments-tab-cancelled').click()
    const closed = page.getByTestId(`enrollment-${created.enrollmentId}`)
    // Cancelled sign-ups are ordered by session time; free ones left by other specs may come first.
    await expect(page.locator('.enrollments__card').first()).toBeVisible()
    for (let page_ = 0; page_ < 5 && (await closed.count()) === 0; page_ += 1) {
      const more = page.getByTestId('enrollments-more')
      if ((await more.count()) === 0) break
      await more.click()
      await page.waitForTimeout(500)
    }
    await expect(closed).toContainText('已关闭')
    await expect(closed.getByTestId('enrollment-note')).toHaveText('未付款，名额已释放')
  })

  test('PAY-003 付款时间到了，凭证页重新问服务端，不自己认定已关闭（接口桩）', async ({ page, request }) => {
    const created = await enrollByApi(request, buyer, [kidA])
    const real = await readEnrollment(request, buyer, created.enrollmentId)
    let reads = 0
    await page.route(`**/api/v1/activity-enrollments/${created.enrollmentId}`, async (route) => {
      reads += 1
      // Still unpaid on the server, due two seconds after the page first read it.
      const body = JSON.parse(JSON.stringify(real))
      body.payment.payBefore = new Date(Date.now() + (reads === 1 ? 2000 : -1000)).toISOString()
      await route.fulfill({ json: body })
    })
    await signIn(page, buyer)
    await page.goto(`/#/pages/activity/ticket?enrollmentId=${created.enrollmentId}`)
    await expect(page.getByTestId('ticket-status-title')).toHaveText('待付款')
    await expect.poll(() => reads, { timeout: 15_000 }).toBeGreaterThan(1)
    // The server still says unpaid: the page waits for it instead of drawing a closed ticket.
    await expect(page.getByTestId('ticket-status-title')).toHaveText('待付款')
    await expect(page.getByTestId('ticket-pay')).toHaveText('付款时间已到')
    await expect(page.getByTestId('ticket-note')).toHaveText('付款时间已到，正在确认订单状态')
  })

  test('PAY-004 报名费变了：提示后按新金额重新提交；主办方没开通在线支付时不能提交', async ({ page }) => {
    await signIn(page, buyer)
    const enrollUrl = `/#/pages/activity/enroll?activityId=${STUDY_ID}&sessionId=${SESSION_A}&cityCode=${CITY}`

    // The page first sees an old price (stub, once); the real server then refuses the amount.
    await page.route(`**/api/v1/activities/${STUDY_ID}?*`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.price.amountMinor = 15800
      await route.fulfill({ response, json: body })
    }, { times: 1 })
    await page.goto(enrollUrl)
    await person(page, CHILD_NAMES[0]).click()
    await expect(page.getByTestId('activity-enroll-price')).toHaveText('¥158')
    await page.getByTestId('activity-enroll-agree').click()
    await submitOn(page).click()
    await expect(page.locator('uni-toast')).toContainText('报名费有调整')
    await expect(page.getByTestId('activity-enroll-price')).toHaveText('¥168')
    await expect(page.getByTestId('activity-enroll')).toBeVisible()
    // The changed amount is a new request: it goes through.
    await submitOn(page).click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥168')

    // The organiser cannot take payment online: the form says so and cannot be sent.
    await page.route(`**/api/v1/activities/${STUDY_ID}?*`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.price.onlinePayment = false
      await route.fulfill({ response, json: body })
    })
    await page.goto(`/#/pages/activity/detail?activityId=${STUDY_ID}&cityCode=${CITY}`)
    await page.getByTestId('activity-session-a3000000-0000-4000-8000-000000000203').click()
    await page.locator('[data-testid="activity-detail-primary"]:not([disabled])').click()
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('主办方暂未开通在线支付')
    await person(page, CHILD_NAMES[1]).click()
    await page.getByTestId('activity-enroll-agree').click()
    await expect(page.getByTestId('activity-enroll-submit')).toHaveText('暂不能报名')
    await expect(submitOff(page)).toHaveCount(1)
    await page.unroute(`**/api/v1/activities/${STUDY_ID}?*`)

    // The server refuses on its own (the account was removed after the page read it).
    await page.route('**/api/v1/activity-enrollments', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({
        status: 409,
        json: { code: 'ACTIVITY_PAYMENT_UNAVAILABLE', message: '主办方暂未开通在线支付', traceId: 'e2e' },
      })
    })
    await page.goto(`/?r=1${enrollUrl.slice(1)}`)
    await person(page, CHILD_NAMES[1]).click()
    await page.getByTestId('activity-enroll-agree').click()
    await submitOn(page).click()
    await expect(page.locator('uni-toast')).toContainText('主办方暂未开通在线支付')
    await expect(page.getByTestId('activity-enroll')).toBeVisible()
  })

  test('PAY-005 游客点收费活动的立即报名先去登录', async ({ page }) => {
    await page.goto(`/#/pages/activity/detail?activityId=${STUDY_ID}&cityCode=${CITY}`)
    await page.getByTestId(`activity-session-${SESSION_A}`).click()
    await page.locator('[data-testid="activity-detail-primary"]:not([disabled])').click()
    await expect(page.locator('.inspection-login__form')).toBeVisible()
  })

  test('PAY-006 报名表、收银台、支付结果、凭证（待付款 / 已付款）、我的报名在 320 / 375 / 430 宽不横向溢出，并留截图', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const unpaid = await enrollByApi(request, buyer, [kidA])
    const paid = await enrollByApi(request, buyer, [kidB])
    await payByApi(request, buyer, paid.payment.tradeOrderId as string)
    await signIn(page, buyer)
    mkdirSync(visualOutput, { recursive: true })

    for (const width of [375, 320, 430]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}${hash.slice(1)}`

      await page.goto(at(`/#/pages/activity/enroll?activityId=${STUDY_ID}&sessionId=a3000000-0000-4000-8000-000000000203&cityCode=${CITY}`))
      await person(page, CHILD_NAMES[0]).click()
      await person(page, CHILD_NAMES[1]).click()
      await expect(page.getByTestId('activity-enroll-price')).toHaveText('¥336')
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `enroll-${width}.png`), fullPage: true })

      await page.goto(at(`/#/pages/trade/cashier?orderId=${unpaid.payment.tradeOrderId}`))
      await expect(page.getByTestId('cashier-due-note')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `cashier-${width}.png`), fullPage: true })

      await page.goto(at(`/#/pages/trade/pay-result?orderId=${paid.payment.tradeOrderId}`))
      await expect(page.getByTestId('pay-result-paid-title')).toHaveText('报名成功')
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `pay-result-${width}.png`), fullPage: true })

      await page.goto(at(`/#/pages/activity/ticket?enrollmentId=${unpaid.enrollmentId}`))
      await expect(page.getByTestId('ticket-qr-empty')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `ticket-unpaid-${width}.png`), fullPage: true })

      await page.goto(at(`/#/pages/activity/ticket?enrollmentId=${paid.enrollmentId}`))
      // Only the hash changed: the same page would stay mounted, so it is read again.
      await page.reload()
      await expect(page.getByTestId('ticket-code')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `ticket-paid-${width}.png`), fullPage: true })

      await page.goto(at('/#/pages/mine/activity-enrollments'))
      await expect(page.locator('.enrollments__card')).toHaveCount(2)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: resolve(visualOutput, `enrollments-${width}.png`), fullPage: true })
    }
  })
})
