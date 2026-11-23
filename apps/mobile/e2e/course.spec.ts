import { randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  API,
  BUYER,
  MERCHANT,
  apiLogin,
  databaseAvailable,
  fakeWechatAvailable,
  fakeWechatPays,
  fakeWechatRefunds,
  latestRefundNo,
  offerId,
  openPaymentNo,
  psql,
  readOrderByApi,
  submitOrderByApi,
  type Session,
} from './support/trade-fixtures'

// S5-5 课程（docs/redesign/09-课程实现说明.md）：首页金刚位“培训课程” → 商城“课程”页（子类目、价格排序、线下班进活动）→
// 课程详情（先试听、报名弹层）→ 登录 → 收银台 → 支付结果 → 课程学习（播放、播完记进度）→ 申请退款；体验中心“课程试听”；
// 我的 → 我的课程（附带课程随商城订单完成开通）。真实后端 + 本机模拟微信（FAKE_WECHAT=1）。
// 示例数据：R__dev_course.sql（无人机飞行原理 线上课 ¥199，试听 2 课；编程机器人入门课随“编程机器人探索套装”附带）。
// 每条用例前后把 dev-customer 的开通记录和课程、商城订单删掉。
const FLY = 'd7c00000-0000-4000-8000-000000000001'
const ROBOT = 'd7c00000-0000-4000-8000-000000000002'
const CAMP = 'd7c00000-0000-4000-8000-000000000003'
const CERT = 'd7c00000-0000-4000-8000-000000000004'
const CAMP_ACTIVITY = 'a2000000-0000-4000-8000-000000000002'
const TRIAL_ENTRY = 'b1000000-0000-4000-8000-000000000005'
const ROBOT_KIT_OFFER = offerId('101', '1')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/course')

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

function cleanup(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT id FROM trade_order WHERE buyer_id = ${buyerId()} AND source IN ('COURSE', 'MALL');
    SELECT count(*) FROM (SELECT id FROM trade_order WHERE id IN (SELECT id FROM e2e_orders) FOR UPDATE) locked;
    DELETE FROM course_lesson_progress WHERE enrollment_id IN (SELECT id FROM course_enrollment WHERE account_id = ${buyerId()});
    DELETE FROM course_enrollment_event WHERE enrollment_id IN (SELECT id FROM course_enrollment WHERE account_id = ${buyerId()});
    DELETE FROM course_enrollment WHERE account_id = ${buyerId()};
    DELETE FROM trade_channel_notification
      WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders))
         OR out_refund_no IN (SELECT out_refund_no FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders));
    DELETE FROM trade_state_transition WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_delivery WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_line WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order WHERE id IN (SELECT id FROM e2e_orders);
    COMMIT;
  `)
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

// A whole new document every time: a hash-only goto keeps the uni page that is already there (and
// its query), and the token set by signIn() only lands on a fresh load.
let loads = 0
async function open(page: Page, hashPath: string): Promise<void> {
  loads += 1
  await page.goto(`/?e2e=${Date.now()}-${loads}#${hashPath}`)
}

/** A query value of the hash route (the page address lives after the #). */
function hashParam(page: Page, name: string): string {
  return new URL(page.url().split('#')[1] ?? '', 'http://hash.local').searchParams.get(name) ?? ''
}

async function noHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

/** The tall scrolling area of a page grown to its content for one picture, then put back. */
async function tallShot(page: Page, scroller: string, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  const height = await page.evaluate((selector) => {
    // uni's scroll-view scrolls an inner element; its height is the content's.
    const element = document.querySelector(`${selector} .uni-scroll-view-content`) ?? document.querySelector(selector)
    const outer = document.querySelector(selector)
    const chrome = document.documentElement.clientHeight - (outer ? outer.getBoundingClientRect().height : 0)
    return element ? element.scrollHeight + chrome : document.documentElement.scrollHeight
  }, scroller)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(300)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

/** The uni video wraps a native element; its end is what the page records as 学完. */
async function videoEnds(page: Page, testId: string): Promise<void> {
  await page.locator(`[data-testid="${testId}"] video`).first().evaluate((video) => {
    video.dispatchEvent(new Event('ended'))
  })
}

async function enrollmentOf(request: APIRequestContext, session: Session, courseId: string): Promise<{ enrollmentId: string | null; status: string | null }> {
  const mine = await request.get(`${API}/api/v1/courses/${courseId}/me`, { headers: session.headers })
  return mine.json()
}

test.describe('课程', () => {
  test.describe.configure({ mode: 'serial' })

  let buyer: Session

  test.beforeEach(async ({ request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    test.skip(!databaseAvailable(), '没有本地开发数据库')
    test.skip(!(await fakeWechatAvailable(request)), '没有本地模拟微信（FAKE_WECHAT=1）')
    const courses = await request.get(`${API}/api/v1/courses?cityCode=370500`)
    test.skip(!courses.ok(), '后端没有课程模块（S5-5）')
    buyer = signedIn as Session
    cleanup()
    mkdirSync(compareOutput, { recursive: true })
  })

  test.afterEach(() => {
    if (buyer) cleanup()
  })

  test('金刚位“培训课程”进商城课程页：线上课、线下班、子类目和价格排序', async ({ page }) => {
    await open(page, '/pages/home/index')
    await page.getByTestId('home-king-course').click()
    await expect(page.getByTestId('course-list')).toBeVisible()
    await expect(page.getByTestId('mall-category-course')).toHaveAttribute('aria-pressed', 'true')
    const fly = page.getByTestId(`course-card-${FLY}`)
    await expect(fly).toContainText('无人机飞行原理 线上课')
    await expect(fly).toContainText('¥199')
    await expect(fly).toContainText('12 课时')
    await expect(fly).toContainText('线上')
    await expect(page.getByTestId(`course-card-${CAMP}`)).toContainText('机器人研学体验')
    await expect(page.getByTestId(`course-card-${CAMP}`)).toContainText('开班')
    await expect(page.getByTestId(`course-card-${CERT}`)).toContainText('价格面议')
    await expect(page.getByTestId(`course-card-${CERT}`)).toContainText('联系询价')
    // 只随商品附带的课不在列表里。
    await expect(page.getByTestId(`course-card-${ROBOT}`)).toHaveCount(0)
    await noHorizontalOverflow(page)
    await page.screenshot({ path: `${compareOutput}/MallCourse-app.png` })

    await page.getByTestId('course-topic-certificate').click()
    await expect(page.getByTestId(`course-card-${CERT}`)).toBeVisible()
    await expect(page.getByTestId(`course-card-${FLY}`)).toHaveCount(0)
    await page.getByTestId('course-topic-all').click()
    await page.getByTestId('course-sort-price').click()
    await expect(page.locator('[data-testid^="course-card-"][data-mode]').last()).toHaveAttribute('data-testid', `course-card-${CERT}`)

    // 线下班点进对应的活动详情，在那里报名。
    await page.getByTestId(`course-card-${CAMP}`).locator('.course-card__name').click()
    await expect(page).toHaveURL(new RegExp(`/pages/activity/detail\\?activityId=${CAMP_ACTIVITY}`))
  })

  test('课程详情 → 试听 → 报名弹层 → 登录 → 收银台 → 支付结果 → 课程学习，播完记进度，7 天内没开始学可退款', async ({ page, request }) => {
    await open(page, `/pages/course/detail?courseId=${FLY}`)
    await expect(page.getByTestId('course-detail-price')).toHaveText('¥199')
    await expect(page.getByTestId('course-detail-try')).toContainText('先试听，再决定')
    await noHorizontalOverflow(page)
    await tallShot(page, '.course-detail__scroll', `${compareOutput}/CourseFly-app.png`)

    await page.getByTestId('course-detail-pack').click()
    await expect(page.getByText('升力从哪里来')).toBeVisible()
    await page.getByTestId('course-detail-trial').click()
    await expect(page).toHaveURL(/\/pages\/course\/trial\?courseId=/)
    await expect(page.getByTestId('course-trial-lesson-1')).toContainText('升力从哪里来')
    await expect(page.getByTestId('course-trial-lesson-2')).toContainText('四旋翼怎样保持平衡')
    await expect(page.getByTestId('course-trial-lesson-3')).toHaveCount(0)
    await page.screenshot({ path: `${compareOutput}/CourseTrial-app.png` })
    await page.getByTestId('course-trial-buy').click()

    // 没登录：去报名先登录，登录后回到详情，报名弹层还开着。
    await expect(page.getByTestId('course-buy-sheet')).toBeVisible()
    await expect(page.getByTestId('course-buy-guest')).toBeVisible()
    await page.screenshot({ path: `${compareOutput}/PdSkuCourseFly-app.png` })
    await page.getByTestId('course-buy-submit').click()
    await expect(page).toHaveURL(/\/pages\/service\/inspection\/login\?returnUrl=/)

    await signIn(page, buyer)
    await open(page, `/pages/course/detail?courseId=${FLY}&buy=1`)
    await expect(page.getByTestId('course-buy-sheet')).toBeVisible()
    await expect(page.getByTestId('course-buy-guest')).toHaveCount(0)
    await page.getByTestId('course-buy-submit').click()
    await expect(page).toHaveURL(/\/pages\/trade\/cashier\?orderId=/)
    const orderId = hashParam(page, 'orderId')
    const order = await readOrderByApi(request, buyer, orderId)
    expect(order.orderNo.startsWith('CO')).toBe(true)
    await expect(page.getByTestId('cashier-amount')).toHaveText('¥199')
    await expect(page.getByTestId('cashier-payee')).toContainText('示例培训机构')
    await expect(page.getByTestId('cashier-course')).toContainText('无人机飞行原理 线上课')
    await page.screenshot({ path: `${compareOutput}/Cashier-course-app.png` })

    // H5 调不起微信：页面发起支付后由模拟微信替买家付款，页面只认服务端查单的结果。
    await page.getByTestId('cashier-pay').click()
    await expect.poll(() => openPaymentNo(orderId)).not.toBe('')
    await fakeWechatPays(request, openPaymentNo(orderId))
    await expect.poll(async () => (await enrollmentOf(request, buyer, FLY)).status).toBe('ACTIVE')
    await open(page, `/pages/trade/pay-result?orderId=${orderId}`)
    await expect(page.getByTestId('pay-result-paid-title')).toHaveText('报名成功 · 课程已开通')
    await expect(page.getByTestId('pay-result-view-order')).toHaveText('查看课程')
    await page.getByTestId('pay-result-view-order').click()
    await expect(page).toHaveURL(/\/pages\/course\/learn\?enrollmentId=/)

    const progress = page.getByTestId('course-learn-progress')
    await expect(progress).toContainText('已学 0 / 12 课时')
    await expect(page.getByTestId('course-learn-rules')).toContainText('可申请全额退款')
    // 第 1 课是试听课：播放不影响退款；播完记一节。
    await page.getByTestId('course-learn-play').click()
    await expect(page.getByTestId('course-learn-video').first()).toBeVisible()
    await videoEnds(page, 'course-learn-video')
    await expect(progress).toContainText('已学 1 / 12 课时')
    await expect(progress).toContainText('继续学习：第 2 课')
    await expect(page.getByTestId('course-learn-lesson-1')).toHaveAttribute('data-state', 'COMPLETED')
    await noHorizontalOverflow(page)
    await tallShot(page, '.course-learn__scroll', `${compareOutput}/CourseDetail-app.png`)

    // 还没开始学付费课时：申请退款，原路退回，课程关闭。
    await page.getByTestId('course-learn-refund').click()
    await expect(page.locator('.uni-modal')).toContainText('退款 ¥199 将原路退回')
    await page.locator('.uni-modal__btn_primary').click()
    await expect(page.getByTestId('course-learn-rules')).toContainText('退款处理中')
    await fakeWechatRefunds(request, latestRefundNo(orderId))
    await expect.poll(async () => (await readOrderByApi(request, buyer, orderId)).status).toBe('REFUNDED')
    await page.reload()
    await expect(page.getByTestId('course-learn-rules')).toContainText('已原路退回')
    await expect(page.getByTestId('course-learn-play')).toHaveCount(0)
  })

  test('开始学付费课时前先确认，确认后不能在线退款', async ({ page, request }) => {
    await signIn(page, buyer)
    await open(page, `/pages/course/detail?courseId=${FLY}&buy=1`)
    await page.getByTestId('course-buy-submit').click()
    await expect(page).toHaveURL(/\/pages\/trade\/cashier\?orderId=/)
    const orderId = hashParam(page, 'orderId')
    await page.getByTestId('cashier-pay').click()
    await expect.poll(() => openPaymentNo(orderId)).not.toBe('')
    await fakeWechatPays(request, openPaymentNo(orderId))
    await expect.poll(async () => (await enrollmentOf(request, buyer, FLY)).status).toBe('ACTIVE')
    const mine = await enrollmentOf(request, buyer, FLY)

    await open(page, `/pages/course/learn?enrollmentId=${mine.enrollmentId}`)
    // 第 1 章默认展开（接着学的是第 1 课），第 3 课是第一节付费课时。
    await page.getByTestId('course-learn-lesson-3').click()
    await page.getByTestId('course-learn-play').click()
    await expect(page.locator('.uni-modal')).toContainText('开始学习付费课时后，这门课就不能在线退款了')
    await page.locator('.uni-modal__btn_primary').click()
    await expect(page.getByTestId('course-learn-video').first()).toBeVisible()
    await expect(page.getByTestId('course-learn-rules')).toContainText('已开始学习付费课时，不能在线退款')
    await expect(page.getByTestId('course-learn-refund')).toHaveCount(0)
  })

  test('体验中心“课程试听”进试听页；商城订单完成后附带课程出现在我的课程', async ({ page, request }) => {
    await open(page, `/pages/course/trial?experienceItemId=${TRIAL_ENTRY}`)
    await expect(page.getByTestId('course-trial-header-back')).toBeVisible()
    await expect(page.getByTestId('course-trial-lesson-1')).toBeVisible()

    // 买“编程机器人探索套装”到店自提，付款后商家核销取货码，订单完成，附带的编程机器人入门课开通。
    const price = Number(psql(`SELECT price_amount_minor FROM mall_offer WHERE id = '${ROBOT_KIT_OFFER}'`))
    const order = await submitOrderByApi(request, buyer, { offer: ROBOT_KIT_OFFER, mode: 'PICKUP', payableMinor: price })
    const launched = await request.post(`${API}/api/v1/trade/orders/${order.orderId}/payments`, {
      headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() },
      data: { channel: 'WECHAT_APP' },
    })
    expect(launched.ok()).toBe(true)
    await fakeWechatPays(request, openPaymentNo(order.orderId))
    await expect.poll(async () => (await readOrderByApi(request, buyer, order.orderId)).status).toBe('PAID')
    const paid = await readOrderByApi(request, buyer, order.orderId)
    const merchant = await apiLogin(request, MERCHANT) as Session
    const pickup = await request.post(`${API}/api/v1/provider/trade/orders/${order.orderId}/pickup`, {
      headers: { ...merchant.headers, 'Idempotency-Key': randomUUID(), 'If-Match': `"${paid.version}"` },
      data: { pickupCode: paid.delivery.pickupCode },
    })
    expect(pickup.ok()).toBe(true)

    await signIn(page, buyer)
    await open(page, '/pages/mine/index')
    await page.getByTestId('mine-link-my_courses').click()
    await expect(page).toHaveURL(/\/pages\/mine\/courses/)
    const card = page.getByTestId(`my-course-${ROBOT}`)
    await expect(card).toContainText('配套数字课程')
    await expect(card).toContainText('编程机器人入门课')
    await expect(card).toContainText('来源：编程机器人探索套装')
    await expect(card).toContainText('已学 0 / 16 课时')
    await expect(page.getByTestId(`my-course-trial-${FLY}`)).toContainText('试听 2 课')
    await expect(page.getByTestId('my-courses-offline')).toBeVisible()
    await noHorizontalOverflow(page)
    await page.screenshot({ path: `${compareOutput}/MyCourses-app.png` })

    await page.getByTestId('my-courses-bucket-finished').click()
    await expect(page.getByTestId('my-courses-empty')).toContainText('还没有学完的课程')
    await page.getByTestId('my-courses-bucket-all').click()
    await card.click()
    await expect(page.getByTestId('course-learn-order')).toContainText(paid.orderNo)
    await expect(page.getByTestId('course-learn-rules')).toContainText('课程退款随商品订单规则处理')
  })
})
