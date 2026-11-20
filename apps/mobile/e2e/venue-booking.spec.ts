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

// 到馆预约（E10，计划 §21）：场馆详情 → 确认到馆预约 → 收银台 → 支付结果 → 核销码；改期换新码；已付款取消退款；
// 待付款取消关闭；我的 → 到馆预约三个页签。真实后端 + 本机模拟微信（FAKE_WECHAT=1）：H5 调不起微信，付款由模拟微信的
// 控制入口代替买家完成，页面只认服务端查单的结果。
// 用示例场馆“东营低空与机器人体验馆”的“机器人避障闯关”（¥39/人，6 岁以上，运营机构登记了收款账户）。每条用例前后把
// dev-customer 的到馆预约、它们的交易订单和测试子女从数据库删掉，并把占着的名额还给场次。
const VENUE_ID = 'b2000000-0000-4000-8000-000000000001'
const ROBOT_PROJECT_ID = 'b4000000-0000-4000-8000-000000000101'
const UNIT = 3900
const KID_OK = '到馆小禾'
const KID_YOUNG = '到馆小满'
const visualOutput = resolve(__dirname, '../../../../../.planning/venue-booking/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/venue')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

/**
 * Removes what the tests made: every venue booking of the buyer (bookings still holding seats give
 * them back to their current session first), the trade orders behind them and the test children.
 * The orders are locked first so the timeout job skips them while they are deleted.
 */
function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_bookings ON COMMIT DROP AS
      SELECT id, trade_order_id, session_id, people, status FROM venue_booking WHERE account_id = ${buyerId()};
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT id FROM trade_order WHERE id IN (SELECT trade_order_id FROM e2e_bookings)
         OR (buyer_id = ${buyerId()} AND source = 'VENUE');
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_orders) FOR UPDATE) locked;
    SELECT count(*) FROM (SELECT id FROM venue_booking WHERE id IN (SELECT id FROM e2e_bookings) FOR UPDATE) locked;
    UPDATE venue_session s SET booked_count = s.booked_count - t.n
      FROM (SELECT b.session_id, sum(b.people) AS n FROM venue_booking b
            WHERE b.id IN (SELECT id FROM e2e_bookings) AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')
            GROUP BY b.session_id) t
     WHERE s.id = t.session_id;
    DELETE FROM venue_booking_event WHERE booking_id IN (SELECT id FROM e2e_bookings);
    DELETE FROM venue_booking_participant WHERE booking_id IN (SELECT id FROM e2e_bookings);
    DELETE FROM venue_booking WHERE id IN (SELECT id FROM e2e_bookings);
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
      AND name IN ('${KID_OK}', '${KID_YOUNG}')
      AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.participant_id = p.id);
    COMMIT;
  `)
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

async function addChild(request: APIRequestContext, session: Session, name: string, yearsOld: number): Promise<string> {
  const created = await request.post(`${API}/api/v1/activity-participants`, {
    headers: session.headers,
    data: { name, birthMonth: `${new Date().getFullYear() - yearsOld}-01`, guardianConfirmed: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).participantId
}

interface SessionSlot {
  sessionId: string
  startsAt: string
  seatsLeft: number
  status: string
  date: string
}

// The next seven Beijing days, as the day tabs show them.
function beijingDays(): string[] {
  return Array.from({ length: 7 }, (_, index) =>
    new Date(Date.now() + 8 * 3_600_000 + index * 86_400_000).toISOString().slice(0, 10))
}

async function sessionsOn(request: APIRequestContext, date: string): Promise<SessionSlot[]> {
  const response = await request.get(
    `${API}/api/v1/venues/${VENUE_ID}/sessions?cityCode=${CITY}&projectId=${ROBOT_PROJECT_ID}&date=${date}`)
  expect(response.ok()).toBe(true)
  return (await response.json()).sessions.map((session: Omit<SessionSlot, 'date'>) => ({ ...session, date }))
}

// Open sessions of the robot project within the week with room for `people`, earliest first.
async function openSessions(request: APIRequestContext, people = 1): Promise<SessionSlot[]> {
  const found: SessionSlot[] = []
  for (const date of beijingDays()) {
    found.push(...(await sessionsOn(request, date))
      .filter((session) => session.status === 'OPEN' && session.seatsLeft >= people))
  }
  expect(found.length).toBeGreaterThan(1)
  return found
}

async function seatsLeft(request: APIRequestContext, slot: SessionSlot): Promise<number> {
  return (await sessionsOn(request, slot.date)).find((item) => item.sessionId === slot.sessionId)?.seatsLeft ?? -1
}

interface BookingSnapshot {
  bookingId: string
  status: string
  code: string | null
  session: { sessionId: string }
  payment: { status: string; tradeOrderId: string | null }
  refund: { status: string; amountMinor: number } | null
  version: number
}

async function bookByApi(request: APIRequestContext, session: Session, slot: SessionSlot, participantIds: string[]): Promise<BookingSnapshot> {
  const created = await request.post(`${API}/api/v1/venue-bookings`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { sessionId: slot.sessionId, participantIds, expectedAmountMinor: UNIT * participantIds.length, noticeAgreed: true },
  })
  expect(created.status()).toBe(201)
  return created.json()
}

async function readBooking(request: APIRequestContext, session: Session, bookingId: string): Promise<BookingSnapshot> {
  return (await request.get(`${API}/api/v1/venue-bookings/${bookingId}`, { headers: session.headers })).json()
}

async function paidBookingByApi(request: APIRequestContext, session: Session, slot: SessionSlot, participantIds: string[]): Promise<BookingSnapshot> {
  const created = await bookByApi(request, session, slot, participantIds)
  await payByApi(request, session, created.payment.tradeOrderId as string)
  await expect.poll(async () => (await readBooking(request, session, created.bookingId)).status).toBe('CONFIRMED')
  return readBooking(request, session, created.bookingId)
}

/**
 * A whole-page picture with the sticky header and bar where they belong: the viewport grows to the
 * page's height for the shot (a full-page shot keeps them where the scroll position left them).
 */
async function tallShot(page: Page, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  await page.evaluate(() => window.scrollTo(0, 0))
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

function orderIdFromUrl(page: Page): string {
  return new URL(page.url().replace('/#/', '/')).searchParams.get('orderId') as string
}

const bookingUrl = (bookingId: string) => `/#/pages/venue/booking?bookingId=${bookingId}`
const bookUrl = (slot: SessionSlot) =>
  `/#/pages/venue/book?venueId=${VENUE_ID}&cityCode=${CITY}&projectId=${ROBOT_PROJECT_ID}&sessionId=${slot.sessionId}&date=${slot.date}`
// uni-button shows as enabled to Playwright even when disabled, so the attribute is checked.
const submitOn = (page: Page) => page.locator('[data-testid="venue-book-submit"]:not([disabled])')
const submitOff = (page: Page) => page.locator('[data-testid="venue-book-submit"][disabled]')
const person = (page: Page, name: string) => page.locator(`.venue-book__person[data-name="${name}"]`)

test.describe('到馆预约', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session
  let kidOk: string

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库容器，无法清理测试数据')
    buyer = signedIn as Session
    cleanup()
    kidOk = await addChild(request, buyer, KID_OK, 10)
    await addChild(request, buyer, KID_YOUNG, 4)
  })

  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('VB-001 详情选场次 → 确认预约（不满年龄的孩子不能选）→ 收银台 → 模拟微信付款 → 查看核销码', async ({ page, request }) => {
    test.setTimeout(120_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const [slot] = await openSessions(request)
    const seatsBefore = await seatsLeft(request, slot)
    await signIn(page, buyer)

    // 场馆详情：选这一天、这一场，“预约到馆”进确认页。
    await page.goto(`/#/pages/venue/detail?venueId=${VENUE_ID}&cityCode=${CITY}`)
    await expect(page.getByTestId('venue-detail-name')).toHaveText('东营低空与机器人体验馆')
    await page.getByTestId(`venue-day-${slot.date}`).click()
    await page.getByTestId(`venue-session-${slot.sessionId}`).click()
    await page.locator('[data-testid="venue-detail-book"]:not([disabled])').click()

    // 确认到馆预约：项目、场次、场馆、余位、参与人（年龄不够的置灰并写明原因）。
    await expect(page.getByTestId('venue-book-project')).toHaveText('机器人避障闯关')
    await expect(page.getByTestId('venue-book-slot')).toHaveText(/^(今天|明天)? ?\d\d\/\d\d 周. \d\d:\d\d–\d\d:\d\d$/)
    await expect(page.getByTestId('venue-book-venue')).toHaveText('东营低空与机器人体验馆')
    await expect(page.getByTestId('venue-book-seats')).toHaveText(`本场余 ${seatsBefore} 位`)
    await expect(page.getByTestId('venue-book-submit')).toHaveText('提交并支付')
    const young = person(page, KID_YOUNG)
    await expect(young).toHaveAttribute('data-reason', 'UNDER_MIN_AGE')
    await expect(young).toContainText('不满项目最低年龄')
    // aria-disabled: Playwright would wait for it to be enabled, so the tap is forced.
    await young.click({ force: true })
    await expect(young).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByTestId('venue-book-count')).toHaveText('请选择参与人')

    await person(page, KID_OK).click()
    await expect(person(page, KID_OK)).toContainText('符合年龄')
    await expect(page.getByTestId('venue-book-fee')).toHaveText('¥39 × 1 人')
    await expect(page.getByTestId('venue-book-total')).toHaveText('¥39')
    await expect(page.getByTestId('venue-book-payee')).toHaveText('东营低空与机器人体验馆 · 经平台支付渠道')
    await expect(page.getByTestId('venue-book')).toContainText('取消后整单原路全额退款，名额同时释放')
    await expect(page.getByTestId('venue-book')).not.toContainText('提醒')
    // 画板：按钮一直是橙色；没勾同意就点，提示先同意。
    await expect(page.getByTestId('venue-book-submit')).toHaveAttribute('data-ready', 'false')
    await page.getByTestId('venue-book-submit').click()
    await expect(page.locator('uni-toast')).toContainText('请先阅读并同意到馆须知与退改说明')
    await page.getByTestId('venue-book-agree').click()
    await submitOn(page).click()

    // 收银台替换了确认页：收款方是场馆，行是预约内容、人数（画板 CashierVenue：金额下面不再写“到馆体验费”）。
    await expect(page.getByTestId('cashier')).toBeVisible()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥39')
    await expect(page.getByTestId('cashier-due-note')).toHaveCount(0)
    await expect(page.getByTestId('cashier-countdown')).toHaveText(/^剩余支付时间 1[45]:\d\d · 超时释放场次名额$/)
    await expect(page.getByTestId('cashier-payee')).toHaveText('东营低空与机器人体验馆')
    await expect(page.getByTestId('cashier-venue-item')).toContainText('机器人避障闯关')
    await expect(page.getByTestId('cashier-people')).toHaveText('1 人')
    await expect(page.getByTestId('cashier-goods')).toHaveCount(0)
    await expect(page.getByTestId('cashier-order-no')).toHaveText(/^VO\d{16}$/)
    const orderId = orderIdFromUrl(page)
    // 名额在提交时就占住了。
    expect(await seatsLeft(request, slot)).toBe(seatsBefore - 1)

    // H5 调不起微信：结果页由服务端查单，没付款就是“支付失败”。
    await page.getByTestId('cashier-pay').click()
    await expect(page.locator('uni-toast')).toContainText('请在 App 中完成微信支付')
    await expect(page.getByTestId('pay-result-failed')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('pay-result-failed')).toContainText('预约仍为待支付，超时释放场次名额')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看预约')

    await fakeWechatPays(request, openPaymentNo(orderId))
    await page.getByTestId('pay-result-refresh').click()
    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('预约成功 · 已支付')
    await expect(page.getByTestId('pay-result-paid-note')).toHaveText('核销码已生成，到馆时出示')
    await expect(page.getByTestId('pay-result-payee')).toHaveText('东营低空与机器人体验馆')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看核销码')
    await page.getByTestId('pay-result-view-order').click()

    // 核销码：一单一码，二维码，订单号和收款方。
    await expect(page.getByTestId('venue-booking-title')).toHaveText('预约成功 · 待到馆')
    await expect(page.getByTestId('venue-booking-code')).toHaveText(/^VEN-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/)
    await expect(page.getByTestId('venue-booking-qr').locator('img')).toHaveAttribute('src', /data:image\/svg\+xml/)
    await expect(page.getByTestId('venue-booking-hint')).toHaveText('到馆出示二维码核销 · 一单一码，含 1 人')
    await expect(page.getByTestId('venue-booking-people')).toHaveText(`${KID_OK} · 1 人`)
    await expect(page.getByTestId('venue-booking-steps')).toContainText('已支付')
    await expect(page.getByTestId('venue-booking-order-no')).toHaveText(/^VO\d{16}$/)
    await expect(page.getByTestId('venue-booking-amount')).toHaveText('¥39')
    await expect(page.getByTestId('venue-booking-payee')).toHaveText('东营低空与机器人体验馆')
    await expect(page.getByTestId('venue-booking-refund')).toHaveCount(0)

    // 返回：确认页和收银台都被替换掉了，回到场馆详情。
    await page.getByTestId('venue-booking-back').click()
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
  })

  test('VB-002 改期到同一项目的另一场：换新码，两个场次的名额一出一进；版本冲突时重读', async ({ page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const [first, second] = await openSessions(request)
    const booking = await paidBookingByApi(request, buyer, first, [kidOk])
    const firstSeats = await seatsLeft(request, first)
    const secondSeats = await seatsLeft(request, second)
    await signIn(page, buyer)

    await page.goto(bookingUrl(booking.bookingId))
    const oldCode = booking.code as string
    await expect(page.getByTestId('venue-booking-code')).toHaveText(oldCode)

    // A conflict first: someone changed the booking on another device; the page reads it again.
    await page.route('**/api/v1/venue-bookings/*/reschedule', (route) => route.fulfill({
      status: 412,
      json: { code: 'VENUE_VERSION_CONFLICT', message: '预约已变化', traceId: 'e2e' },
    }), { times: 1 })
    await page.locator('[data-testid="venue-booking-reschedule"]:not([disabled])').click()
    const sheet = page.getByTestId('venue-booking-move-sheet')
    await expect(sheet).toContainText('当前场次')
    await page.getByTestId(`venue-move-day-${first.date}`).click()
    await expect(page.getByTestId(`venue-move-slot-${first.sessionId}`)).toContainText('当前场次')
    await expect(page.getByTestId(`venue-move-slot-${first.sessionId}`)).toHaveAttribute('data-selectable', 'false')
    await page.getByTestId(`venue-move-day-${second.date}`).click()
    await page.getByTestId(`venue-move-slot-${second.sessionId}`).click()
    await page.locator('[data-testid="venue-booking-move-confirm"]:not([disabled])').click()
    await expect(page.locator('uni-toast')).toContainText('预约有变化，已刷新')
    await expect(sheet).toHaveCount(0)
    await expect(page.getByTestId('venue-booking-code')).toHaveText(oldCode)

    // The real reschedule.
    await page.locator('[data-testid="venue-booking-reschedule"]:not([disabled])').click()
    await page.getByTestId(`venue-move-day-${second.date}`).click()
    await page.getByTestId(`venue-move-slot-${second.sessionId}`).click()
    await page.locator('[data-testid="venue-booking-move-confirm"]:not([disabled])').click()
    await expect(page.getByTestId('venue-booking-title')).toHaveText('改期成功 · 待到馆')
    await expect(page.getByTestId('venue-booking-sub')).toHaveText('旧码已作废，请出示下面的新码')
    await expect(page.getByTestId('venue-booking-code')).not.toHaveText(oldCode)
    await expect(page.getByTestId('venue-booking-code')).toHaveText(/^VEN-/)
    const moved = await readBooking(request, buyer, booking.bookingId)
    expect(moved.session.sessionId).toBe(second.sessionId)
    expect(moved.code).not.toBe(oldCode)
    expect(await seatsLeft(request, first)).toBe(firstSeats + 1)
    expect(await seatsLeft(request, second)).toBe(secondSeats - 1)
  })

  test('VB-003 已付款取消：确认框写明原路退回金额，取消后退款中，模拟微信退款后已退款，名额释放', async ({ page, request }) => {
    test.setTimeout(90_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const [slot] = await openSessions(request)
    const seatsBefore = await seatsLeft(request, slot)
    const booking = await paidBookingByApi(request, buyer, slot, [kidOk])
    expect(await seatsLeft(request, slot)).toBe(seatsBefore - 1)
    await signIn(page, buyer)

    await page.goto(bookingUrl(booking.bookingId))
    await page.locator('[data-testid="venue-booking-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('venue-booking-cancel-refund')).toHaveText('¥39')
    await expect(page.getByTestId('venue-booking-cancel-text')).toHaveText('取消后名额释放，核销码立即失效，整单原路全额退款。')
    await page.getByTestId('venue-booking-cancel-confirm').click()

    await expect(page.getByTestId('venue-booking-title')).toHaveText('已取消 · 退款中')
    await expect(page.getByTestId('venue-booking-refund')).toHaveText('退款中 ¥39 · 原路退回')
    await expect(page.getByTestId('venue-booking-qr-empty')).toContainText('核销码已失效')
    await expect(page.getByTestId('venue-booking-stamp')).toHaveText('已失效')
    expect(await seatsLeft(request, slot)).toBe(seatsBefore)

    await fakeWechatRefunds(request, latestRefundNo(booking.payment.tradeOrderId as string))
    await expect.poll(async () => {
      await page.reload()
      return (await page.getByTestId('venue-booking-title').textContent({ timeout: 5000 }))?.trim()
    }, { timeout: 30_000 }).toBe('已取消 · 已退款')
    await expect(page.getByTestId('venue-booking-refund')).toHaveText('已退款 ¥39 · 原路退回')
    await expect(page.getByTestId('venue-booking-sub')).toHaveText('¥39 已原路退回')

    // 已取消的预约在“已取消”页签。
    await page.getByTestId('venue-booking-list').click()
    await expect(page.getByTestId('venue-bookings')).toBeVisible()
    await page.getByTestId('venue-bookings-tab-cancelled').click()
    const card = page.getByTestId(`venue-booking-card-${booking.bookingId}`)
    await expect(card).toContainText('已取消')
    await expect(card.getByTestId('venue-booking-card-money')).toHaveText('东营低空与机器人体验馆 · 已退款 ¥39')
  })

  test('VB-004 待付款：我的 → 到馆预约可去付款，核销码页没有二维码，取消后关闭并释放名额', async ({ page, request }) => {
    const [slot] = await openSessions(request)
    const seatsBefore = await seatsLeft(request, slot)
    const created = await bookByApi(request, buyer, slot, [kidOk])
    expect(created.status).toBe('PENDING_PAYMENT')
    await signIn(page, buyer)

    // “我的”里的入口。
    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-venue_bookings').click()
    const card = page.getByTestId(`venue-booking-card-${created.bookingId}`)
    await expect(card).toContainText('待付款')
    await expect(card.getByTestId('venue-booking-card-money')).toHaveText(/^东营低空与机器人体验馆 · ¥39 待付款 · 1[45]:\d\d$/)

    // 去付款 → 收银台，返回回到列表。
    await page.getByTestId(`venue-booking-pay-${created.bookingId}`).click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥39')
    expect(orderIdFromUrl(page)).toBe(created.payment.tradeOrderId)
    await page.getByTestId('cashier-back').click()
    await expect(page.getByTestId('venue-bookings')).toBeVisible()

    await page.getByTestId(`venue-booking-open-${created.bookingId}`).click()
    await expect(page.getByTestId('venue-booking-title')).toHaveText('待付款')
    await expect(page.getByTestId('venue-booking-qr-empty')).toContainText('付款后生成')
    await expect(page.getByTestId('venue-booking-code')).toHaveCount(0)
    await expect(page.getByTestId('venue-booking-pay')).toHaveText(/^去付款 · 1[45]:\d\d$/)
    await expect(page.getByTestId('venue-booking-sub')).toHaveText(/^请在 1[45]:\d\d 内付款，超时名额自动释放$/)

    await page.locator('[data-testid="venue-booking-cancel"]:not([disabled])').click()
    await expect(page.getByTestId('venue-booking-cancel-text')).toHaveText('还没有付款，取消后预约关闭、名额释放，不会扣款。')
    await expect(page.getByTestId('venue-booking-cancel-refund')).toHaveCount(0)
    await page.getByTestId('venue-booking-cancel-confirm').click()
    await expect(page.getByTestId('venue-booking-title')).toHaveText('已关闭，未付款')
    await expect(page.getByTestId('venue-booking-stamp')).toHaveText('已关闭')
    await expect(page.getByTestId('venue-booking-pay')).toHaveCount(0)
    expect(await seatsLeft(request, slot)).toBe(seatsBefore)
    expect((await readBooking(request, buyer, created.bookingId)).status).toBe('CLOSED')

    // 返回列表：待到馆里没有了，已取消里写“未付款，名额已释放”。
    await page.getByTestId('venue-booking-back').click()
    await expect(page.getByTestId(`venue-booking-card-${created.bookingId}`)).toHaveCount(0)
    await page.getByTestId('venue-bookings-tab-cancelled').click()
    const closed = page.getByTestId(`venue-booking-card-${created.bookingId}`)
    await expect(closed).toContainText('已关闭')
    await expect(closed.getByTestId('venue-booking-card-money')).toHaveText('东营低空与机器人体验馆 · 未付款，名额已释放')
  })

  test('VB-005 我的 → 到馆预约：待到馆有出示核销码，已完成列出已核销的（接口桩）', async ({ page, request }) => {
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const [slot] = await openSessions(request)
    const booking = await paidBookingByApi(request, buyer, slot, [kidOk])
    const real = await (await request.get(`${API}/api/v1/venue-bookings/${booking.bookingId}`, { headers: buyer.headers })).json()
    // Checking in needs the session to be about to start; the finished bucket is stubbed with the same booking checked in.
    await page.route('**/api/v1/venue-bookings?bucket=FINISHED*', (route) => route.fulfill({
      json: {
        page: 1, size: 10, total: 1, hasMore: false,
        items: [{ ...real, status: 'CHECKED_IN', checkedInAt: real.session.startsAt, cancellable: false, reschedulable: false }],
      },
    }))
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/venue-bookings')
    const card = page.getByTestId(`venue-booking-card-${booking.bookingId}`)
    await expect(card).toContainText('待到馆')
    await expect(card).toContainText('机器人避障闯关 · 1 人')
    await expect(card.getByTestId('venue-booking-card-money')).toHaveText('东营低空与机器人体验馆 · ¥39 已支付')
    await page.getByTestId(`venue-booking-code-${booking.bookingId}`).click()
    await expect(page.getByTestId('venue-booking-code')).toHaveText(booking.code as string)
    await page.getByTestId('venue-booking-back').click()

    await page.getByTestId('venue-bookings-tab-finished').click()
    await expect(card).toContainText('已核销')
    await expect(page.getByTestId(`venue-booking-detail-${booking.bookingId}`)).toHaveText('查看详情')
    await page.getByTestId('venue-bookings-tab-cancelled').click()
    await expect(page.getByTestId('venue-bookings-empty')).toContainText('没有取消过的预约')
  })

  test('VB-006 体验费变了：提示后按新金额提交；场馆没开通在线支付不能提交；免费项目按钮是“提交预约”（接口桩）', async ({ page, request }) => {
    const [slot] = await openSessions(request)
    await signIn(page, buyer)

    // The page first sees an old price (stub, once); the real server then refuses the amount.
    await page.route(`**/api/v1/venues/${VENUE_ID}?*`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.projects[0].priceAmountMinor = 3800
      await route.fulfill({ response, json: body })
    }, { times: 1 })
    await page.goto(bookUrl(slot))
    await person(page, KID_OK).click()
    await expect(page.getByTestId('venue-book-total')).toHaveText('¥38')
    await page.getByTestId('venue-book-agree').click()
    await submitOn(page).click()
    await expect(page.locator('uni-toast')).toContainText('体验费有调整')
    await expect(page.getByTestId('venue-book-total')).toHaveText('¥39')
    await expect(page.getByTestId('venue-book')).toBeVisible()
    // The changed amount is a new request: it goes through.
    await submitOn(page).click()
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥39')

    // The operator cannot take payment online: the bar says so and the form cannot be sent.
    await page.route(`**/api/v1/venues/${VENUE_ID}?*`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.projects[0].onlinePayment = false
      await route.fulfill({ response, json: body })
    })
    const [, other] = await openSessions(request)
    await page.goto(`/?r=1${bookUrl(other).slice(1)}`)
    await expect(page.getByTestId('venue-book-count')).toHaveText('场馆暂未开通在线支付')
    await person(page, KID_OK).click()
    await page.getByTestId('venue-book-agree').click()
    await expect(page.getByTestId('venue-book-count')).toHaveText('场馆暂未开通在线支付')
    await expect(submitOff(page)).toHaveCount(1)
    await page.unroute(`**/api/v1/venues/${VENUE_ID}?*`)

    // The server refuses on its own (the account was removed after the page read it).
    await page.route('**/api/v1/venue-bookings', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({ status: 409, json: { code: 'VENUE_PAYMENT_UNAVAILABLE', message: '场馆暂未开通在线支付', traceId: 'e2e' } })
    })
    await page.goto(`/?r=2${bookUrl(other).slice(1)}`)
    await person(page, KID_OK).click()
    await page.getByTestId('venue-book-agree').click()
    await submitOn(page).click()
    await expect(page.locator('uni-toast')).toContainText('场馆暂未开通在线支付')
    await expect(page.getByTestId('venue-book')).toBeVisible()
    await page.unroute('**/api/v1/venue-bookings')

    // A free project: no fee line, no payee, "提交预约".
    await page.route(`**/api/v1/venues/${VENUE_ID}?*`, async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.projects[0].priceAmountMinor = 0
      body.projects[0].onlinePayment = false
      await route.fulfill({ response, json: body })
    })
    await page.goto(`/?r=3${bookUrl(other).slice(1)}`)
    await person(page, KID_OK).click()
    await expect(page.getByTestId('venue-book-submit')).toHaveText('提交预约')
    await expect(page.getByTestId('venue-book-total')).toHaveText('免费')
    await expect(page.getByTestId('venue-book-payee')).toHaveCount(0)
  })

  test('VB-007 ＋ 添加参与人去参与人页，加完回来列表刷新', async ({ page, request }) => {
    const [slot] = await openSessions(request)
    await signIn(page, buyer)
    await page.goto(bookUrl(slot))
    await expect(person(page, KID_OK)).toBeVisible()
    await page.getByTestId('venue-book-add-person').click()
    await expect(page.getByTestId('participants-add')).toBeVisible()
    // Added elsewhere while the page is away (the add form itself is covered by the activity specs).
    const added = await addChild(request, buyer, '到馆小川', 9)
    await page.getByTestId('participants-back').click()
    await expect(page.getByTestId(`venue-book-person-${added}`)).toContainText('符合年龄')
    psql(`DELETE FROM activity_participant WHERE id = '${added}';`)
  })

  test('VB-008 确认页、收银台、支付结果、核销码（待付款 / 待到馆）、我的到馆预约在 320 / 375 / 430 宽不横向溢出，并留截图', async ({ page, request }) => {
    test.setTimeout(150_000)
    test.skip(!(await fakeWechatAvailable(request)), '本机模拟微信没有运行（FAKE_WECHAT=1）')
    const [first, second] = await openSessions(request)
    const unpaid = await bookByApi(request, buyer, first, [kidOk])
    const paid = await paidBookingByApi(request, buyer, second, [kidOk])
    await signIn(page, buyer)
    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })

    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}${hash.slice(1)}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      // A session nobody here has booked, so both children show their own state.
      const [free] = (await openSessions(request)).filter((slot) =>
        slot.sessionId !== first.sessionId && slot.sessionId !== second.sessionId)
      await page.goto(at(bookUrl(free)))
      await person(page, KID_OK).click()
      await page.getByTestId('venue-book-agree').click()
      await expect(page.getByTestId('venue-book-total')).toHaveText('¥39')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('VenueBook'))

      await page.goto(at(bookingUrl(paid.bookingId)))
      await expect(page.getByTestId('venue-booking-code')).toBeVisible()
      await expect(page.getByTestId('venue-booking-order-no')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('VenueCode'))
      if (width === 390) continue

      await page.goto(at(bookingUrl(unpaid.bookingId)))
      await page.reload()
      await expect(page.getByTestId('venue-booking-qr-empty')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('code-unpaid'))

      await page.goto(at(`/#/pages/trade/cashier?orderId=${unpaid.payment.tradeOrderId}`))
      await expect(page.getByTestId('cashier-countdown')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('cashier'))

      await page.goto(at(`/#/pages/trade/pay-result?orderId=${paid.payment.tradeOrderId}`))
      await expect(page.getByTestId('pay-result-paid-title')).toHaveText('预约成功 · 已支付')
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('pay-result'))

      await page.goto(at('/#/pages/mine/venue-bookings'))
      await expect(page.locator('.venue-bookings__card')).toHaveCount(2)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('bookings'))

      // The reschedule and cancel sheets of a paid booking.
      await page.goto(at(bookingUrl(paid.bookingId)))
      await page.reload()
      await page.locator('[data-testid="venue-booking-reschedule"]:not([disabled])').click()
      await page.getByTestId(`venue-move-day-${second.date}`).click()
      await expect(page.locator('.venue-code__move-slot').first()).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('reschedule-sheet') })
    }
  })
})
