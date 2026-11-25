import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

import { PLANNING } from './support/board-compare'

// S5-3 候补与团队报名（docs/redesign/08-后续开发计划.md §5；boards EnrollWaitlist, TeamSignup, TeamOrder）.
// The API is stubbed like inspection-payment.spec.ts (no backend needed); the dev build has the
// activity preview on. Screenshots for the board comparison go to .planning/activity-s53/visual (390 wide).

test.use({ timezoneId: 'Asia/Shanghai' })

const visualOutput = resolve(PLANNING, 'activity-s53/visual')
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}
// 2026-11-03 10:20 Beijing (board time line: joined 09:40, offered 10:20).
const NOW = new Date('2026-11-03T10:20:00+08:00')
const ACTIVITY = 'a2000000-0000-4000-8000-000000000001'
const AM = 'a3000000-0000-4000-8000-000000000101'
const PM = 'a3000000-0000-4000-8000-000000000102'
const WAITLIST = 'b1000000-0000-4000-8000-000000000001'
const ENROLLMENT = 'b2000000-0000-4000-8000-000000000001'
const TRADE = 'b3000000-0000-4000-8000-000000000001'

type Answer = { status?: number; body: unknown } | null
type Handler = (path: string, method: string, body: unknown) => Answer

async function stub(page: Page, handler: Handler, now: Date = NOW): Promise<Array<{ path: string; method: string; body: unknown }>> {
  const calls: Array<{ path: string; method: string; body: unknown }> = []
  await page.clock.setFixedTime(now)
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    const path = new URL(request.url()).pathname
    let body: unknown = null
    try { body = request.postDataJSON() } catch { body = null }
    calls.push({ path, method: request.method(), body })
    const result = handler(path, request.method(), body)
    await route.fulfill({
      status: result?.status ?? (result ? 200 : 404),
      headers: CORS,
      json: result?.body ?? { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' },
    })
  })
  return calls
}

async function shoot(page: Page, name: string): Promise<void> {
  mkdirSync(visualOutput, { recursive: true })
  await page.waitForTimeout(300)
  // Grow the window to the whole page so the sticky bottom bar lands at the bottom like on the board.
  const viewport = page.viewportSize() ?? { width: 390, height: 844 }
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path: resolve(visualOutput, `${name}-app.png`), fullPage: true })
  await page.setViewportSize(viewport)
}

async function noHorizontalOverflow(page: Page): Promise<void> {
  const size = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
  expect(size.scroll).toBeLessThanOrEqual(size.client + 1)
}

const activitySummary = {
  activityId: ACTIVITY,
  type: 'CONTEST',
  title: '青少年无人机编程挑战赛（东营站）',
  coverImage: null,
  cityCode: '370500',
  organizerName: '东营低空与机器人体验馆',
}

function waitlist(overrides: Record<string, unknown> = {}) {
  return {
    waitlistId: WAITLIST,
    status: 'WAITING',
    position: 3,
    ahead: 2,
    activity: activitySummary,
    session: { sessionId: PM, label: '下午', startsAt: '2026-11-15T14:00:00+08:00', endsAt: '2026-11-15T16:30:00+08:00', venueName: '东营低空与机器人体验馆' },
    members: [
      { participantId: 'p-he', name: '王小禾', relation: 'CHILD', age: 11 },
      { participantId: 'p-chuan', name: '王小川', relation: 'CHILD', age: 9 },
    ],
    people: 2,
    unitAmountMinor: 12000,
    amountMinor: 24000,
    holdUntil: null,
    enrollmentId: null,
    tradeOrderId: null,
    abandonable: true,
    createdAt: '2026-11-03T09:40:00+08:00',
    offeredAt: null,
    endedAt: null,
    ...overrides,
  }
}

function session(sessionId: string, label: string, startsAt: string, status: string, seatsLeft: number) {
  return {
    sessionId,
    label,
    startsAt,
    endsAt: null,
    checkInOpensAt: null,
    enrollmentClosesAt: '2026-11-14T18:00:00+08:00',
    capacity: 40,
    seatsLeft,
    status,
    saleStopped: false,
  }
}

const detail = {
  activityId: ACTIVITY,
  type: 'CONTEST',
  title: '青少年无人机编程挑战赛（东营站）',
  subtitle: null,
  coverImage: null,
  price: { mode: 'PER_PERSON', amountMinor: 12000, currency: 'CNY', onlinePayment: true },
  ageRange: { minAge: 8, maxAge: 15 },
  enrollmentStatus: 'OPEN',
  organizer: { organizationId: 'o1', name: '东营低空与机器人体验馆' },
  coOrganizer: null,
  location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: null, longitude: null },
  scheduleNote: null,
  eligibilityNote: null,
  seatNote: null,
  refundNote: null,
  enrollmentNotice: null,
  gallery: [],
  waitlistAllowed: true,
  teamEnrollmentAllowed: true,
  sessions: [
    session(AM, '上午', '2026-11-15T09:00:00+08:00', 'OPEN', 12),
    session(PM, '下午', '2026-11-15T14:00:00+08:00', 'FULL_WAITLIST', 0),
  ],
}

const participants = [
  { participantId: 'p-self', relation: 'SELF', name: '王女士', birthMonth: null, age: null, photoUseAuthorized: false, eligibility: null },
  { participantId: 'p-he', relation: 'CHILD', name: '王小禾', birthMonth: '2015-03', age: 11, photoUseAuthorized: false, eligibility: null },
  { participantId: 'p-chuan', relation: 'CHILD', name: '王小川', birthMonth: '2017-05', age: 9, photoUseAuthorized: false, eligibility: null },
]

test.describe('候补（board EnrollWaitlist）', () => {
  test('WL-001 排队中：第几位、前面几位；放弃候补要确认，之后是“已放弃”', async ({ page }) => {
    let current = waitlist()
    const calls = await stub(page, (path, method) => {
      if (path.endsWith(`/activity-waitlists/${WAITLIST}/abandon`) && method === 'POST') {
        current = waitlist({ status: 'ABANDONED', position: null, ahead: null, abandonable: false, endedAt: '2026-11-03T10:21:00+08:00' })
        return { body: current }
      }
      if (path.endsWith(`/activity-waitlists/${WAITLIST}`)) return { body: current }
      return null
    })
    await page.goto(`/#/pages/activity/waitlist?waitlistId=${WAITLIST}`)
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('候补中 · 第 3 位')
    await expect(page.getByTestId('activity-waitlist-note')).toHaveText('下午场已满 · 前面还有 2 位在候补')
    await expect(page.getByTestId('activity-waitlist-session')).toHaveText('11/15 下午场 14:00')
    await expect(page.getByTestId('activity-waitlist-pay')).toHaveText('¥240')
    await expect(page.getByTestId('activity-waitlist-member')).toHaveCount(2)
    await noHorizontalOverflow(page)
    await shoot(page, 'EnrollWaitlist-waiting')

    await page.getByTestId('activity-waitlist-abandon').click()
    await expect(page.getByTestId('activity-waitlist-sheet')).toContainText('放弃后排队位置不再保留')
    await expect(page.getByTestId('activity-waitlist-sheet')).toContainText('王小禾、王小川')
    await page.getByTestId('activity-waitlist-abandon-confirm').click()
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('已放弃候补')
    await expect(page.getByTestId('activity-waitlist-hero')).toHaveAttribute('data-tone', 'GRAY')
    await expect(page.getByTestId('activity-waitlist-bar')).toHaveAttribute('data-bar', 'ENDED')
    expect(calls.filter((call) => call.path.endsWith('/abandon'))).toHaveLength(1)
    await shoot(page, 'EnrollWaitlist-abandoned')
  })

  test('WL-002 转正待付款：倒计时；“去付款”先确认转正（带应付金额），再去收银台', async ({ page }) => {
    const offered = waitlist({ status: 'OFFERED', position: null, ahead: null, holdUntil: '2026-11-03T10:34:58+08:00', offeredAt: '2026-11-03T10:20:00+08:00' })
    const calls = await stub(page, (path, method) => {
      if (path.endsWith('/confirm') && method === 'POST') {
        return { body: { ...offered, enrollmentId: ENROLLMENT, tradeOrderId: TRADE } }
      }
      if (path.endsWith(`/activity-waitlists/${WAITLIST}`)) return { body: offered }
      return null
    })
    await page.goto(`/#/pages/activity/waitlist?waitlistId=${WAITLIST}`)
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('候补成功 · 待付款')
    await expect(page.getByTestId('activity-waitlist-note')).toHaveText('名额保留 14:58 · 超时自动释放')
    await expect(page.getByTestId('activity-waitlist-take')).toHaveText('去付款')
    await noHorizontalOverflow(page)
    await shoot(page, 'EnrollWaitlist-offered')

    await page.getByTestId('activity-waitlist-take').click()
    await expect(page).toHaveURL(new RegExp(`/pages/trade/cashier\\?orderId=${TRADE}`))
    const confirm = calls.find((call) => call.path.endsWith('/confirm'))
    expect(confirm?.body).toEqual({ expectedAmountMinor: 24000 })
  })

  test('WL-003 已失效、未成功两种结束：未收费，按钮去看别的活动或重新候补', async ({ page }) => {
    let current = waitlist({ status: 'EXPIRED', position: null, ahead: null, offeredAt: '2026-11-03T10:00:00+08:00', endedAt: '2026-11-03T10:15:00+08:00' })
    await stub(page, (path) => (path.endsWith(`/activity-waitlists/${WAITLIST}`) ? { body: current } : null))
    await page.goto(`/#/pages/activity/waitlist?waitlistId=${WAITLIST}`)
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('候补已失效')
    await expect(page.getByTestId('activity-waitlist-pay')).toHaveText('未产生费用')
    await expect(page.getByTestId('activity-waitlist-right')).toHaveText('重新候补')
    await shoot(page, 'EnrollWaitlist-expired')

    current = waitlist({ status: 'ENDED', position: null, ahead: null, endedAt: '2026-11-15T14:00:00+08:00' })
    await page.goto(`/?ended=1#/pages/activity/waitlist?waitlistId=${WAITLIST}`)
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('候补未成功')
    await expect(page.getByTestId('activity-waitlist-left')).toHaveText('查看活动详情')
    await shoot(page, 'EnrollWaitlist-ended')
  })

  test('WL-004 详情页选已满场次“加入候补” → 候补模式的报名表 → 候补详情', async ({ page }) => {
    const calls = await stub(page, (path, method) => {
      if (path === `/api/v1/activities/${ACTIVITY}`) return { body: detail }
      if (path === '/api/v1/activity-participants') {
        return { body: { items: participants.map((person) => ({ ...person, eligibility: { eligible: person.relation === 'CHILD', reason: person.relation === 'CHILD' ? 'ELIGIBLE' : 'OVER_MAX_AGE', ageAtSession: person.age } })) } }
      }
      if (path === '/api/v1/activity-waitlists' && method === 'POST') return { status: 201, body: waitlist() }
      if (path.endsWith(`/activity-waitlists/${WAITLIST}`)) return { body: waitlist() }
      return null
    })
    await page.goto(`/#/pages/activity/detail?activityId=${ACTIVITY}&cityCode=370500`)
    await page.locator(`.activity-detail__session[data-status="FULL_WAITLIST"]`).click()
    await expect(page.getByTestId('activity-detail-primary')).toHaveText('加入候补')
    await page.getByTestId('activity-detail-primary').click()
    await expect(page).toHaveURL(/pages\/activity\/enroll\?.*mode=waitlist/)
    await expect(page.getByTestId('activity-enroll-head')).toContainText('加入候补')
    await page.getByTestId('activity-enroll-person-p-he').click()
    await page.getByTestId('activity-enroll-person-p-chuan').click()
    await expect(page.getByTestId('activity-enroll-count')).toHaveText('候补成功后应付 · 2 人 × ¥120')
    await expect(page.getByTestId('activity-enroll-submit')).toHaveText('加入候补')
    await page.getByTestId('activity-enroll-agree').click()
    await page.getByTestId('activity-enroll-submit').click()
    await expect(page).toHaveURL(new RegExp(`pages/activity/waitlist\\?waitlistId=${WAITLIST}`))
    await expect(page.getByTestId('activity-waitlist-status')).toHaveText('候补中 · 第 3 位')
    const join = calls.find((call) => call.path === '/api/v1/activity-waitlists' && call.method === 'POST')
    expect(join?.body).toEqual({ activityId: ACTIVITY, sessionId: PM, participantIds: ['p-he', 'p-chuan'], noticeAgreed: true })
  })
})

// The roster the server answers for (board TeamSignup): 王小禾 already signed up, 王小满 too young.
function checkAnswer(body: unknown) {
  const members = (body as { members: Array<{ participantId?: string; name?: string; birthMonth?: string }> }).members
  const rows = members.map((member) => {
    const name = member.name ?? participants.find((person) => person.participantId === member.participantId)?.name ?? '?'
    const reason = name === '王小禾' ? 'ALREADY_ENROLLED' : name === '王小满' ? 'UNDER_MIN_AGE' : 'ELIGIBLE'
    const ages: Record<string, number> = { 李明轩: 12, 张雨桐: 10, 赵子涵: 13, 陈可欣: 11, 王小禾: 11, 王小满: 6, 刘思远: 10 }
    return {
      participantId: member.participantId ?? null,
      name,
      birthMonth: member.birthMonth ?? null,
      ageAtSession: ages[name] ?? 10,
      eligible: reason === 'ELIGIBLE',
      reason,
    }
  })
  return { members: rows, eligibleCount: rows.filter((row) => row.eligible).length }
}

const PASTED = '姓名 出生年月\n李明轩 2014-05\n张雨桐 2016-03\n赵子涵 2013-08\n陈可欣 2015-02\n王小禾 2015-03\n王小满 2020-01\n'

test.describe('团队报名（board TeamSignup）', () => {
  test('TEAM-001 从详情进团队报名；粘贴名单逐行核对，不符合的不计费、可移除；提交并支付去收银台', async ({ page }) => {
    const calls = await stub(page, (path, method, body) => {
      if (path === `/api/v1/activities/${ACTIVITY}`) return { body: detail }
      if (path === '/api/v1/activity-participants') return { body: { items: participants } }
      if (path === '/api/v1/activity-team-enrollments/check') return { body: checkAnswer(body) }
      if (path === '/api/v1/activity-team-enrollments' && method === 'POST') {
        return { status: 201, body: teamEnrollment({ payment: { status: 'PENDING_PAYMENT', tradeOrderId: TRADE, amountMinor: 48000, unitAmountMinor: 12000, payBefore: '2026-11-03T10:35:00+08:00' } }) }
      }
      return null
    })
    await page.goto(`/#/pages/activity/detail?activityId=${ACTIVITY}&cityCode=370500`)
    await page.getByTestId('activity-detail-team').click()
    await expect(page).toHaveURL(new RegExp(`pages/activity/team-signup\\?activityId=${ACTIVITY}&sessionId=${AM}`))
    await expect(page.getByTestId(`activity-team-session-${AM}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId(`activity-team-session-${PM}`)).toContainText('已满')
    await expect(page.getByTestId('activity-team-empty')).toBeVisible()

    await page.getByTestId('activity-team-import').click()
    await page.getByTestId('activity-team-paste').locator('textarea').fill(PASTED)
    await page.getByTestId('activity-team-import-confirm').click()
    await expect(page.getByTestId('activity-team-member')).toHaveCount(6)
    await expect(page.getByTestId('activity-team-roster-line')).toHaveText('共 6 人 · 可报名 4 人')
    await expect(page.getByTestId('activity-team-issue')).toHaveText('2 人不符合条件，不计入人数和费用')
    await expect(page.locator('[data-testid="activity-team-member"][data-name="王小满"]')).toContainText('未满 8 岁')
    await expect(page.getByTestId('activity-team-count')).toHaveText('4 人 × ¥120')
    await expect(page.getByTestId('activity-team-total')).toHaveText('¥480')

    await page.getByTestId('activity-team-name').locator('input').fill('东营港务物流职工子女队')
    await expect(page.getByTestId('activity-team-leader').locator('input')).toHaveValue('王女士')
    await page.getByTestId('activity-team-phone').locator('input').fill('13800002026')
    await page.getByTestId('activity-team-agree').click()
    await expect(page.getByTestId('activity-team-submit')).toHaveAttribute('data-ready', 'true')
    await noHorizontalOverflow(page)
    // The import toast goes away before the board shot.
    await expect(page.locator('uni-toast .uni-sample-toast')).toBeHidden({ timeout: 6000 })
    await shoot(page, 'TeamSignup-default')

    await page.locator('[data-testid="activity-team-member"][data-name="王小满"]').getByTestId('activity-team-remove').click()
    await expect(page.getByTestId('activity-team-roster-line')).toHaveText('共 5 人 · 可报名 4 人')

    await page.getByTestId('activity-team-submit').click()
    await expect(page).toHaveURL(new RegExp(`/pages/trade/cashier\\?orderId=${TRADE}`))
    const submit = calls.find((call) => call.path === '/api/v1/activity-team-enrollments' && call.method === 'POST')
    expect(submit?.body).toEqual({
      activityId: ACTIVITY,
      sessionId: AM,
      teamName: '东营港务物流职工子女队',
      leaderName: '王女士',
      leaderPhone: '13800002026',
      members: [
        { name: '李明轩', birthMonth: '2014-05' },
        { name: '张雨桐', birthMonth: '2016-03' },
        { name: '赵子涵', birthMonth: '2013-08' },
        { name: '陈可欣', birthMonth: '2015-02' },
      ],
      noticeAgreed: true,
      guardianConsent: true,
      expectedAmountMinor: 48000,
    })
  })

  test('TEAM-002 添加队员可从子女里选；人数不够、没填手机时说缺什么', async ({ page }) => {
    await stub(page, (path, _method, body) => {
      if (path === `/api/v1/activities/${ACTIVITY}`) return { body: detail }
      if (path === '/api/v1/activity-participants') return { body: { items: participants } }
      if (path === '/api/v1/activity-team-enrollments/check') return { body: checkAnswer(body) }
      return null
    })
    await page.goto(`/#/pages/activity/team-signup?activityId=${ACTIVITY}&sessionId=${AM}&cityCode=370500`)
    await page.getByTestId('activity-team-add').click()
    await page.getByTestId('activity-team-pick').filter({ hasText: '王小川' }).click()
    await expect(page.locator('[data-testid="activity-team-member"][data-name="王小川"]')).toContainText('符合报名条件')
    await page.getByTestId('activity-team-submit').click()
    await expect(page.locator('uni-toast')).toContainText('请输入团队名称')
    await page.getByTestId('activity-team-name').locator('input').fill('小队')
    await page.getByTestId('activity-team-submit').click()
    await expect(page.locator('uni-toast')).toContainText('请输入 11 位领队手机号')
    await page.getByTestId('activity-team-phone').locator('input').fill('13800002026')
    await page.getByTestId('activity-team-submit').click()
    await expect(page.locator('uni-toast')).toContainText('团队报名至少 2 人')
  })
})

function memberTicket(name: string, age: number, code: string, status: string, checkedInAt: string | null = null) {
  return {
    ticketId: `t-${code}`,
    participantId: `p-${name}`,
    participantName: name,
    relation: 'MEMBER',
    age,
    ticketCode: status === 'PENDING_PAYMENT' ? null : code,
    status,
    checkedInAt,
    cancelledAt: null,
    refund: null,
    result: null,
  }
}

function teamEnrollment(overrides: Record<string, unknown> = {}) {
  return {
    enrollmentId: ENROLLMENT,
    status: 'UPCOMING',
    activity: activitySummary,
    session: { sessionId: AM, label: '上午', startsAt: '2026-11-15T09:00:00+08:00', endsAt: '2026-11-15T11:30:00+08:00', checkInOpensAt: '2026-11-15T08:30:00+08:00' },
    location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-03T10:10:00+08:00',
    cancellableUntil: '2026-11-15T09:00:00+08:00',
    cancellable: true,
    tickets: [
      memberTicket('李明轩', 12, 'ACT-2611-0928-0011', 'ACTIVE'),
      memberTicket('张雨桐', 10, 'ACT-2611-0928-0012', 'ACTIVE'),
      memberTicket('赵子涵', 13, 'ACT-2611-0928-0013', 'ACTIVE'),
      memberTicket('陈可欣', 11, 'ACT-2611-0928-0014', 'ACTIVE'),
    ],
    payment: { status: 'PAID', tradeOrderId: TRADE, amountMinor: 48000, unitAmountMinor: 12000, payBefore: null },
    kind: 'TEAM',
    team: { teamName: '东营港务物流职工子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' },
    reschedule: null,
    changes: [],
    reschedulable: true,
    ...overrides,
  }
}

test.describe('团队报名订单（board TeamOrder）', () => {
  test('TEAM-101 待参加：团队与领队（手机打码）、一人一码可切换出示、按人退改', async ({ page }) => {
    await stub(page, (path) => (path.endsWith(`/activity-enrollments/${ENROLLMENT}`) ? { body: teamEnrollment() } : null))
    await page.goto(`/#/pages/activity/team-order?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-team-order-status')).toHaveText('报名成功 · 待参加')
    await expect(page.getByTestId('activity-team-order-leader')).toContainText('王女士138****2026')
    await expect(page.getByTestId('activity-team-order-summary')).toHaveText('4 人 · 一人一码')
    await expect(page.getByTestId('activity-team-order-qr')).toContainText('李明轩 的签到码')
    await page.locator('[data-testid="activity-team-order-member"][data-name="张雨桐"]').getByTestId('activity-team-order-show').click()
    await expect(page.getByTestId('activity-team-order-qr')).toContainText('张雨桐 的签到码')
    await expect(page.getByTestId('activity-team-order-qr')).toContainText('ACT-2611-0928-0012')
    await page.locator('[data-testid="activity-team-order-member"][data-name="李明轩"]').getByTestId('activity-team-order-show').click()
    await noHorizontalOverflow(page)
    await shoot(page, 'TeamOrder-upcoming')

    await page.locator('[data-testid="activity-team-order-member"][data-name="赵子涵"]').getByTestId('activity-team-order-refund').click()
    await expect(page).toHaveURL(new RegExp(`pages/activity/refund\\?enrollmentId=${ENROLLMENT}&ticketId=t-ACT-2611-0928-0013`))
  })

  test('TEAM-102 活动当天：签到进度，已核销的码盖章；不能再退改', async ({ page }) => {
    const onDay = teamEnrollment({
      cancellable: false,
      reschedulable: false,
      tickets: [
        memberTicket('李明轩', 12, 'ACT-2611-0928-0011', 'CHECKED_IN', '2026-11-15T08:41:00+08:00'),
        memberTicket('张雨桐', 10, 'ACT-2611-0928-0012', 'CHECKED_IN', '2026-11-15T08:43:00+08:00'),
        memberTicket('赵子涵', 13, 'ACT-2611-0928-0013', 'ACTIVE'),
        memberTicket('陈可欣', 11, 'ACT-2611-0928-0014', 'ACTIVE'),
      ],
    })
    await stub(page, (path) => (path.endsWith(`/activity-enrollments/${ENROLLMENT}`) ? { body: onDay } : null), new Date('2026-11-15T08:45:00+08:00'))
    await page.goto(`/#/pages/activity/team-order?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-team-order-status')).toHaveText('签到中 · 2/4 已签到')
    await expect(page.getByTestId('activity-team-order-summary')).toHaveText('2/4 已签到')
    await expect(page.getByTestId('activity-team-order-qr')).toContainText('已核销')
    await expect(page.getByTestId('activity-team-order-refund')).toHaveCount(0)
    await expect(page.getByTestId('activity-team-order-action')).toHaveText('查看活动')
    await shoot(page, 'TeamOrder-onday')
  })
})
