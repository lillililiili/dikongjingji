import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// S5-3（docs/redesign/08-后续开发计划.md §5“候补、团队报名、改期”）：结果记录（画板 EnrollResult）、
// 申请退改（EnrollRefund，取消并退款 / 改到其他场次 / 申请原因）、退改详情（EnrollRefundDetail）、
// 整月日历（CalendarPick），以及凭证页、订单页上的入口。接口用 page.route 桩数据（契约 openapi-activity.yaml 1.4.0），
// 不连后端；开发版的活动预览开关是开的。390 宽整页截图放到 .planning/activity-s53/visual，供和画板并排对照。

test.use({ timezoneId: 'Asia/Shanghai' })

// Run from the main worktree (.worktrees/app-impl) this is <repo>/.planning; elsewhere set S53_VISUAL.
const visualOutput = process.env.S53_VISUAL ?? resolve(__dirname, '../../../../../.planning/activity-s53/visual')
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

// The sample contest of the development seed, so the preview cover shows.
const ACTIVITY = 'a2000000-0000-4000-8000-000000000001'
const ENROLLMENT = 'e5300000-0000-4000-8000-000000000001'
const CHANGE = 'c5300000-0000-4000-8000-000000000001'
const MOVE_CHANGE = 'c5300000-0000-4000-8000-000000000002'
const S_AM = 's5300000-0000-4000-8000-0000000000a1'
const S_PM = 's5300000-0000-4000-8000-0000000000a2'
const S_NEXT = 's5300000-0000-4000-8000-0000000000a3'
const S_DEC = 's5300000-0000-4000-8000-0000000000a4'
const T1 = 't5300000-0000-4000-8000-000000000001'
const T2 = 't5300000-0000-4000-8000-000000000002'
const T3 = 't5300000-0000-4000-8000-000000000003'
const T4 = 't5300000-0000-4000-8000-000000000004'

// 10:12 on 3 November (board EnrollRefund / EnrollRefundDetail).
const BEFORE = new Date('2026-11-03T10:12:00+08:00')
// 08:45 on the day: signing in (board EnrollResult 已签到).
const DURING = new Date('2026-11-15T08:45:00+08:00')
// 16:30 on the day: over (已完成 / 未到场).
const AFTER = new Date('2026-11-15T16:30:00+08:00')

type Json = Record<string, unknown>

function ticket(id: string, name: string, age: number, overrides: Json = {}): Json {
  return {
    ticketId: id,
    participantId: `p-${id}`,
    participantName: name,
    relation: 'CHILD',
    age,
    ticketCode: id === T1 ? 'ACT-2611-0928-0005' : 'ACT-2611-0928-0006',
    status: 'ACTIVE',
    checkedInAt: null,
    cancelledAt: null,
    refund: null,
    result: null,
    ...overrides,
  }
}

function sessionRef(id: string, label: string | null, startsAt: string): Json {
  return { sessionId: id, label, startsAt }
}

function enrollment(overrides: Json = {}): Json {
  return {
    enrollmentId: ENROLLMENT,
    status: 'UPCOMING',
    activity: {
      activityId: ACTIVITY,
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    session: {
      sessionId: S_AM,
      label: '上午',
      startsAt: '2026-11-15T09:00:00+08:00',
      endsAt: '2026-11-15T11:30:00+08:00',
      checkInOpensAt: '2026-11-15T08:30:00+08:00',
    },
    location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-01T09:00:00+08:00',
    cancellableUntil: '2026-11-15T09:00:00+08:00',
    cancellable: true,
    tickets: [ticket(T1, '王小禾', 11), ticket(T2, '王小川', 9)],
    payment: { status: 'PAID', tradeOrderId: 'o-1', amountMinor: 24000, unitAmountMinor: 12000, payBefore: null },
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: true,
    ...overrides,
  }
}

function session(id: string, label: string | null, startsAt: string, overrides: Json = {}): Json {
  return {
    sessionId: id,
    label,
    startsAt,
    endsAt: null,
    checkInOpensAt: null,
    enrollmentClosesAt: startsAt,
    capacity: 40,
    seatsLeft: 12,
    status: 'OPEN',
    saleStopped: false,
    ...overrides,
  }
}

function detail(): Json {
  return {
    activityId: ACTIVITY,
    type: 'CONTEST',
    title: '青少年无人机编程挑战赛（东营站）',
    subtitle: null,
    coverImage: null,
    gallery: [],
    price: { mode: 'PER_PERSON', amountMinor: 12000, onlinePayment: true },
    ageRange: { minAge: 8, maxAge: 15 },
    enrollmentStatus: 'OPEN',
    organizer: { organizationId: 'org-1', name: '东营低空与机器人体验馆' },
    coOrganizer: null,
    location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: null, longitude: null },
    scheduleNote: null,
    eligibilityNote: null,
    seatNote: null,
    refundNote: null,
    enrollmentNotice: null,
    waitlistAllowed: true,
    teamEnrollmentAllowed: true,
    sessions: [
      session(S_AM, '上午', '2026-11-15T09:00:00+08:00'),
      session(S_PM, '下午', '2026-11-15T14:00:00+08:00', { status: 'FULL_WAITLIST', seatsLeft: 0 }),
      session(S_NEXT, '上午', '2026-11-22T09:00:00+08:00', { seatsLeft: 30 }),
      session(S_DEC, '上午', '2026-12-06T09:00:00+08:00', { seatsLeft: 20 }),
    ],
  }
}

function change(overrides: Json = {}): Json {
  return {
    changeId: CHANGE,
    kind: 'CANCEL',
    reason: 'TIME_CONFLICT',
    people: 2,
    fromSession: sessionRef(S_AM, '上午', '2026-11-15T09:00:00+08:00'),
    toSession: null,
    refundAmountMinor: 24000,
    refundStatus: 'PROCESSING',
    refundSucceededAt: null,
    createdAt: '2026-11-03T10:12:00+08:00',
    ...overrides,
  }
}

function changeDetail(summary: Json, tickets: Json[]): Json {
  return {
    change: summary,
    enrollmentId: ENROLLMENT,
    activityTitle: '青少年无人机编程挑战赛（东营站）',
    coverImage: null,
    organizerName: '东营低空与机器人体验馆',
    tickets,
  }
}

interface Stub {
  enrollment: Json
  changes: Record<string, Json>
  posts: Array<{ path: string; body: Json }>
  answers?: (path: string, method: string, body: Json) => Json | null
}

async function stub(page: Page, now: Date, state: Stub): Promise<void> {
  await page.clock.setFixedTime(now)
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    const path = new URL(request.url()).pathname
    const body = (request.postDataJSON() ?? {}) as Json
    let answer: unknown = null
    if (request.method() === 'POST') state.posts.push({ path, body })
    const custom = state.answers?.(path, request.method(), body)
    if (custom) answer = custom
    else if (path === `/api/v1/activities/${ACTIVITY}`) answer = detail()
    else if (path.includes('/changes/')) answer = state.changes[path.split('/').pop() as string] ?? null
    else if (path === `/api/v1/activity-enrollments/${ENROLLMENT}`) answer = state.enrollment
    await route.fulfill({
      status: answer ? 200 : 404,
      headers: CORS,
      json: answer ?? { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' },
    })
  })
}

async function shoot(page: Page, name: string): Promise<void> {
  mkdirSync(visualOutput, { recursive: true })
  // Toasts of the last action are not part of the board.
  await page.addStyleTag({ content: 'uni-toast { display: none !important; }' })
  await page.waitForTimeout(300)
  // A full-page capture leaves the sticky bottom bar where the viewport ended; grow the viewport to the
  // page first so the bar sits at the end like on a phone that scrolled down.
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: 390, height: Math.max(844, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path: resolve(visualOutput, `${name}-app.png`), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
}

async function noOverflow(page: Page): Promise<void> {
  const size = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
  expect(size.scroll).toBeLessThanOrEqual(size.client + 1)
}

test.describe('结果记录 (board EnrollResult)', () => {
  test('RES-001 signing in: the sign-in records and no more changes', async ({ page }) => {
    await stub(page, DURING, {
      enrollment: enrollment({
        cancellable: false,
        reschedulable: false,
        tickets: [
          ticket(T1, '王小禾', 11, { status: 'CHECKED_IN', checkedInAt: '2026-11-15T08:42:00+08:00' }),
          ticket(T2, '王小川', 9, { status: 'CHECKED_IN', checkedInAt: '2026-11-15T08:43:00+08:00' }),
        ],
      }),
      changes: {},
      posts: [],
    })
    await page.goto(`/#/pages/activity/result?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-result-title')).toHaveText('已签到 · 活动进行中')
    await expect(page.getByTestId('activity-result-sub')).toHaveText('2 人都已签到，活动结束后这里显示结果记录')
    await expect(page.getByTestId('activity-result-people')).toContainText('08:42 已签到')
    await expect(page.getByTestId('activity-result-notice')).toContainText('活动开始后不再支持退改')
    await expect(page.locator('.result__actions-note')).toHaveText('活动已开始，不再支持退改')
    await noOverflow(page)
    await shoot(page, 'EnrollResult-checkedin')
  })

  test('RES-002 over: the organiser’s results and the certificate note', async ({ page }) => {
    const result = (score: string, recordedAt: string) => ({
      completion: 'COMPLETED', scoreText: score, certificateNote: '完赛证书由主办方现场发放', recordedAt,
    })
    await stub(page, AFTER, {
      enrollment: enrollment({
        status: 'FINISHED',
        cancellable: false,
        reschedulable: false,
        tickets: [
          ticket(T1, '王小禾', 11, { status: 'CHECKED_IN', checkedInAt: '2026-11-15T08:42:00+08:00', result: result('5/5', '2026-11-15T16:00:00+08:00') }),
          ticket(T2, '王小川', 9, { status: 'CHECKED_IN', checkedInAt: '2026-11-15T08:43:00+08:00', result: result('4/5', '2026-11-15T15:50:00+08:00') }),
        ],
      }),
      changes: {},
      posts: [],
    })
    await page.goto(`/#/pages/activity/result?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-result-title')).toHaveText('活动已完成')
    await expect(page.getByTestId('activity-result-records')).toContainText('主办方 11/15 16:00 发布')
    await expect(page.getByTestId(`activity-result-record-${T1}`)).toContainText('5/5')
    await expect(page.getByTestId('activity-result-certificate')).toContainText('完赛证书由主办方现场发放')
    await expect(page.getByTestId('activity-result-parties')).toContainText('东营低空与机器人体验馆 · 经平台支付渠道')
    await noOverflow(page)
    await shoot(page, 'EnrollResult-done')

    // 查看凭证 goes to the ticket page, which offers the result record back.
    await page.getByTestId('activity-result-ticket').click()
    await expect(page.getByTestId('ticket-open-result')).toBeVisible()
  })

  test('RES-003 nobody came', async ({ page }) => {
    await stub(page, AFTER, {
      enrollment: enrollment({
        status: 'FINISHED',
        cancellable: false,
        reschedulable: false,
        tickets: [ticket(T1, '王小禾', 11, { status: 'EXPIRED' }), ticket(T2, '王小川', 9, { status: 'EXPIRED' })],
      }),
      changes: {},
      posts: [],
    })
    await page.goto(`/#/pages/activity/result?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-result-title')).toHaveText('未到场')
    await expect(page.getByTestId('activity-result-people')).toContainText('0 / 2 人')
    await expect(page.getByTestId('activity-result-notice')).toContainText('报名费不退')
    await shoot(page, 'EnrollResult-absent')
  })

  test('RES-004 after a reschedule: 原时间 / 新时间 on the result and the order', async ({ page }) => {
    const moved = enrollment({
      session: {
        sessionId: S_NEXT, label: '上午', startsAt: '2026-11-22T09:00:00+08:00', endsAt: '2026-11-22T11:30:00+08:00', checkInOpensAt: '2026-11-22T08:30:00+08:00',
      },
      reschedule: { previousSession: sessionRef(S_AM, '上午', '2026-11-15T09:00:00+08:00'), rescheduledAt: '2026-11-03T10:12:00+08:00' },
      changes: [change({
        changeId: MOVE_CHANGE, kind: 'RESCHEDULE', refundAmountMinor: null, refundStatus: null,
        toSession: sessionRef(S_NEXT, '上午', '2026-11-22T09:00:00+08:00'),
      })],
    })
    await stub(page, BEFORE, { enrollment: moved, changes: {}, posts: [] })
    await page.goto(`/#/pages/activity/result?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-result-move')).toContainText('11/15 上午 09:00')
    await expect(page.getByTestId('activity-result-move')).toContainText('11/22 上午 09:00')
    await shoot(page, 'EnrollResult-moved')

    await page.goto(`/#/pages/activity/order?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-order-move')).toContainText('11/15 上午 09:00')
    await expect(page.getByTestId(`activity-order-change-${MOVE_CHANGE}`)).toContainText('已改期')
  })
})

test.describe('申请退改 and 退改详情 (boards EnrollRefund, EnrollRefundDetail)', () => {
  test('REF-001 cancel two with a reason: the refund goes at once and its progress shows', async ({ page }) => {
    const state: Stub = { enrollment: enrollment(), changes: {}, posts: [] }
    state.answers = (path, method) => {
      if (method === 'POST' && path.endsWith('/cancel')) {
        state.enrollment = enrollment({
          status: 'CANCELLED',
          cancellable: false,
          reschedulable: false,
          tickets: [
            ticket(T1, '王小禾', 11, { status: 'CANCELLED', ticketCode: null, refund: { status: 'PROCESSING', amountMinor: 12000 } }),
            ticket(T2, '王小川', 9, { status: 'CANCELLED', ticketCode: null, refund: { status: 'PROCESSING', amountMinor: 12000 } }),
          ],
          changes: [change({ reason: 'UNWELL' })],
        })
        state.changes[CHANGE] = changeDetail(change({ reason: 'UNWELL' }), [
          { ticketId: T1, participantName: '王小禾', relation: 'CHILD', age: 11, issued: false, ticketCode: null, status: 'CANCELLED' },
          { ticketId: T2, participantName: '王小川', relation: 'CHILD', age: 9, issued: false, ticketCode: null, status: 'CANCELLED' },
        ])
        return state.enrollment
      }
      return null
    }
    await stub(page, BEFORE, state)
    await page.goto(`/#/pages/activity/refund?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('activity-refund-mode-refund')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('activity-refund-amount')).toHaveText('¥240')
    await expect(page.getByTestId('activity-refund-reason-value')).toHaveText('时间冲突')
    await noOverflow(page)
    await shoot(page, 'EnrollRefund')

    await page.getByTestId('activity-refund-reason').click()
    await expect(page.getByTestId('activity-refund-reason-sheet')).toBeVisible()
    await page.getByTestId('activity-refund-reason-UNWELL').click()
    await expect(page.getByTestId('activity-refund-reason-value')).toHaveText('孩子身体不适')
    await page.getByTestId('activity-refund-submit').click()
    await expect(page.locator('.uni-modal')).toContainText('原路退回 ¥240')
    await page.locator('.uni-modal__btn_primary').click()

    await expect(page.getByTestId('activity-change-status')).toHaveText('退款中')
    const cancel = state.posts.find((post) => post.path.endsWith('/cancel'))
    expect(cancel?.body).toEqual({ ticketIds: [T1, T2], reason: 'UNWELL' })
    await expect(page.getByTestId('activity-change-steps')).toContainText('退款原路退回')
    await expect(page.getByTestId('activity-change-steps')).not.toContainText('主办方确认')
    await expect(page.getByTestId('activity-change-apply')).toContainText('孩子身体不适')
    await noOverflow(page)
    await shoot(page, 'EnrollRefundDetail-refunding')
  })

  test('REF-002 a refund that arrived shows when', async ({ page }) => {
    await stub(page, BEFORE, {
      enrollment: enrollment({ status: 'CANCELLED', cancellable: false, reschedulable: false }),
      changes: {
        [CHANGE]: changeDetail(change({ refundStatus: 'SUCCEEDED', refundSucceededAt: '2026-11-04T09:10:00+08:00' }), []),
      },
      posts: [],
    })
    await page.goto(`/#/pages/activity/change?enrollmentId=${ENROLLMENT}&changeId=${CHANGE}`)
    await expect(page.getByTestId('activity-change-status')).toHaveText('已退款')
    await expect(page.getByTestId('activity-change-info')).toContainText('11/04 09:10')
    await expect(page.getByTestId('activity-change-refresh')).toHaveCount(0)
    await shoot(page, 'EnrollRefundDetail-refunded')
  })

  test('REF-003 move the whole sign-up through the month calendar', async ({ page }) => {
    const state: Stub = { enrollment: enrollment(), changes: {}, posts: [] }
    state.answers = (path, method) => {
      if (method === 'POST' && path.endsWith('/reschedule')) {
        const moved = change({
          changeId: MOVE_CHANGE, kind: 'RESCHEDULE', refundAmountMinor: null, refundStatus: null,
          toSession: sessionRef(S_DEC, '上午', '2026-12-06T09:00:00+08:00'),
        })
        state.enrollment = enrollment({
          session: {
            sessionId: S_DEC, label: '上午', startsAt: '2026-12-06T09:00:00+08:00', endsAt: null, checkInOpensAt: '2026-12-06T08:30:00+08:00',
          },
          tickets: [
            ticket(T3, '王小禾', 11, { ticketCode: 'ACT-2612-0928-0007' }),
            ticket(T4, '王小川', 9, { ticketCode: 'ACT-2612-0928-0008' }),
          ],
          reschedule: { previousSession: sessionRef(S_AM, '上午', '2026-11-15T09:00:00+08:00'), rescheduledAt: '2026-11-03T10:12:00+08:00' },
          changes: [moved],
        })
        state.changes[MOVE_CHANGE] = changeDetail(moved, [
          { ticketId: T1, participantName: '王小禾', relation: 'CHILD', age: 11, issued: false, ticketCode: null, status: 'CANCELLED' },
          { ticketId: T2, participantName: '王小川', relation: 'CHILD', age: 9, issued: false, ticketCode: null, status: 'CANCELLED' },
          { ticketId: T3, participantName: '王小禾', relation: 'CHILD', age: 11, issued: true, ticketCode: 'ACT-2612-0928-0007', status: 'ACTIVE' },
          { ticketId: T4, participantName: '王小川', relation: 'CHILD', age: 9, issued: true, ticketCode: 'ACT-2612-0928-0008', status: 'ACTIVE' },
        ])
        return state.enrollment
      }
      return null
    }
    await stub(page, BEFORE, state)
    await page.goto(`/#/pages/activity/refund?enrollmentId=${ENROLLMENT}`)
    await page.getByTestId('activity-refund-mode-move').click()
    await expect(page.getByTestId('activity-refund-whole')).toHaveText('改期按整单，所有未使用的参与人一起改')
    await expect(page.locator('.enroll-refund__person[aria-checked="true"]')).toHaveCount(2)
    await expect(page.getByTestId(`activity-refund-session-${S_PM}`)).toHaveAttribute('aria-disabled', 'true')
    await expect(page.getByTestId(`activity-refund-session-${S_NEXT}`)).toHaveAttribute('aria-pressed', 'true')
    await noOverflow(page)
    await shoot(page, 'EnrollRefund-move')

    // 更多日期 → December → the 6th → its only session is picked → 确定 hands it back.
    await page.getByTestId('activity-refund-more-dates').click()
    await expect(page.getByTestId('activity-calendar-month')).toHaveText('2026 年 11 月')
    await expect(page.getByTestId('activity-calendar-day-2026-11-15')).toHaveAttribute('data-state', 'FULL')
    await expect(page.getByTestId('activity-calendar-day-2026-11-22')).toHaveAttribute('data-state', 'OPEN')
    await page.getByTestId('activity-calendar-day-2026-11-22').click()
    await noOverflow(page)
    await shoot(page, 'CalendarPick')
    await page.getByTestId('activity-calendar-next').click()
    await expect(page.getByTestId('activity-calendar-month')).toHaveText('2026 年 12 月')
    await page.getByTestId('activity-calendar-day-2026-12-06').click()
    await expect(page.getByTestId('activity-calendar-summary')).toHaveText('已选择：12月6日 周日 上午 09:00')
    await page.getByTestId('activity-calendar-confirm').click()

    await expect(page.getByTestId(`activity-refund-session-${S_DEC}`)).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('activity-refund-mode-move')).toHaveAttribute('aria-checked', 'true')
    await page.getByTestId('activity-refund-submit').click()
    await expect(page.locator('.uni-modal')).toContainText('2 人改到 12/06 周日')
    await page.locator('.uni-modal__btn_primary').click()

    await expect(page.getByTestId('activity-change-status')).toHaveText('已改期')
    expect(state.posts.find((post) => post.path.endsWith('/reschedule'))?.body).toEqual({ targetSessionId: S_DEC, reason: 'TIME_CONFLICT' })
    await expect(page.getByTestId('activity-change-ticket')).toBeVisible()
    await expect(page.getByTestId('activity-change-code')).toHaveText('ACT-2612-0928-0007')
    await page.getByTestId(`activity-change-person-${T4}`).click()
    await expect(page.getByTestId('activity-change-code')).toHaveText('ACT-2612-0928-0008')
    await noOverflow(page)
    await shoot(page, 'EnrollRefundDetail-moved')
  })

  test('REF-004 a free sign-up: cancel from the sheet stays, moving has its own link', async ({ page }) => {
    await stub(page, BEFORE, {
      enrollment: enrollment({ payment: { status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null } }),
      changes: {},
      posts: [],
    })
    await page.goto(`/#/pages/activity/ticket?enrollmentId=${ENROLLMENT}`)
    await expect(page.getByTestId('ticket-cancel')).toHaveAttribute('data-refund-page', 'false')
    await page.getByTestId('ticket-move').click()
    await expect(page.getByTestId('activity-refund-mode-move')).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('activity-refund-mode-refund')).toContainText('取消报名')
  })

  test('REF-005 a team sign-up opens on its team order', async ({ page }) => {
    await stub(page, BEFORE, {
      enrollment: enrollment({ kind: 'TEAM', team: { teamName: '东营港务物流职工子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' } }),
      changes: {},
      posts: [],
    })
    await page.goto(`/#/pages/activity/ticket?enrollmentId=${ENROLLMENT}`)
    await expect(page).toHaveURL(/pages\/activity\/team-order\?enrollmentId=/)
  })
})
