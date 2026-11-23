import { existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

import { expect, test, type Page, type Route } from '@playwright/test'

// S5-3 on 我的报名 (board EnrollList): a waitlist card on top of 待参加, a team card, a rescheduled
// sign-up, finished sign-ups with results, and a cancelled one whose refund can be followed.
// The API is stubbed (no backend needed); the dev build has the activity preview on.
// Screenshots for the board comparison go to .planning/activity-s53/visual (390 wide).

test.use({ timezoneId: 'Asia/Shanghai' })

function planningDir(): string {
  let dir = __dirname
  while (dirname(dir) !== dir) {
    if (existsSync(resolve(dir, '.planning/prototype'))) return resolve(dir, '.planning')
    dir = dirname(dir)
  }
  return resolve(__dirname, '../../../../../.planning')
}
const visualOutput = resolve(planningDir(), 'activity-s53/visual')
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}
const NOW = new Date('2026-11-03T10:20:00+08:00')
const ACTIVITY = 'a2000000-0000-4000-8000-000000000001'

const activity = {
  activityId: ACTIVITY,
  type: 'CONTEST',
  title: '青少年无人机编程挑战赛（东营站）',
  coverImage: null,
  cityCode: '370500',
  organizerName: '东营低空与机器人体验馆',
}

function ticket(id: string, name: string, extra: Record<string, unknown> = {}) {
  return {
    ticketId: id, participantId: `p-${id}`, participantName: name, relation: 'CHILD', age: 11,
    ticketCode: 'ACT-2611-0928-0011', status: 'ACTIVE', checkedInAt: null, cancelledAt: null, refund: null, result: null,
    ...extra,
  }
}

function enrollment(id: string, startsAt: string, extra: Record<string, unknown> = {}) {
  return {
    enrollmentId: id,
    status: 'UPCOMING',
    activity,
    session: { sessionId: `s-${id}`, label: '上午', startsAt, endsAt: null, checkInOpensAt: null },
    location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-01T09:00:00+08:00',
    cancellableUntil: startsAt,
    cancellable: true,
    tickets: [ticket(`${id}-1`, '王小禾'), ticket(`${id}-2`, '王小川')],
    payment: { status: 'PAID', tradeOrderId: `o-${id}`, amountMinor: 24000, unitAmountMinor: 12000, payBefore: null },
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: true,
    ...extra,
  }
}

const waitlist = {
  waitlistId: 'w-1',
  status: 'WAITING',
  position: 3,
  ahead: 2,
  activity,
  session: { sessionId: 's-pm', label: '下午', startsAt: '2026-11-15T14:00:00+08:00', endsAt: null, venueName: '东营低空与机器人体验馆' },
  members: [
    { participantId: 'p1', name: '王小禾', relation: 'CHILD', age: 11 },
    { participantId: 'p2', name: '王小川', relation: 'CHILD', age: 9 },
  ],
  people: 2, unitAmountMinor: 12000, amountMinor: 24000, holdUntil: null, enrollmentId: null, tradeOrderId: null,
  abandonable: true, createdAt: '2026-11-03T09:40:00+08:00', offeredAt: null, endedAt: null,
}

const team = enrollment('team', '2026-11-15T09:00:00+08:00', {
  kind: 'TEAM',
  team: { teamName: '东营港务物流职工子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' },
  tickets: ['李明轩', '张雨桐', '赵子涵', '陈可欣'].map((name, index) => ticket(`team-${index}`, name)),
  payment: { status: 'PAID', tradeOrderId: 'o-team', amountMinor: 48000, unitAmountMinor: 12000, payBefore: null },
})

const moved = enrollment('moved', '2026-11-22T09:00:00+08:00', {
  reschedule: {
    previousSession: { sessionId: 's-old', label: '上午', startsAt: '2026-11-15T09:00:00+08:00' },
    rescheduledAt: '2026-11-03T10:12:00+08:00',
  },
  changes: [{
    changeId: 'c-moved', kind: 'RESCHEDULE', reason: 'TIME_CONFLICT', people: 2,
    fromSession: { sessionId: 's-old', label: '上午', startsAt: '2026-11-15T09:00:00+08:00' },
    toSession: { sessionId: 's-moved', label: '上午', startsAt: '2026-11-22T09:00:00+08:00' },
    refundAmountMinor: null, refundStatus: null, refundSucceededAt: null, createdAt: '2026-11-03T10:12:00+08:00',
  }],
})

const result = { completion: 'COMPLETED', scoreText: '完成任务 5/5', certificateNote: null, recordedAt: '2026-10-25T16:00:00+08:00' }
const finished = enrollment('done', '2026-10-25T09:00:00+08:00', {
  status: 'FINISHED',
  cancellable: false,
  reschedulable: false,
  tickets: [
    ticket('done-1', '王小禾', { status: 'CHECKED_IN', checkedInAt: '2026-10-25T08:42:00+08:00', result }),
    ticket('done-2', '王小川', { status: 'CHECKED_IN', checkedInAt: '2026-10-25T08:43:00+08:00', result }),
  ],
})

const refunded = enrollment('back', '2026-09-20T09:00:00+08:00', {
  status: 'CANCELLED',
  cancellable: false,
  reschedulable: false,
  tickets: [
    ticket('back-1', '王小禾', { status: 'CANCELLED', ticketCode: null, cancelledAt: '2026-09-18T10:00:00+08:00', refund: { status: 'SUCCEEDED', amountMinor: 12000 } }),
    ticket('back-2', '王小川', { status: 'CANCELLED', ticketCode: null, cancelledAt: '2026-09-18T10:00:00+08:00', refund: { status: 'SUCCEEDED', amountMinor: 12000 } }),
  ],
  changes: [{
    changeId: 'c-back', kind: 'CANCEL', reason: 'TIME_CONFLICT', people: 2,
    fromSession: { sessionId: 's-back', label: '上午', startsAt: '2026-09-20T09:00:00+08:00' }, toSession: null,
    refundAmountMinor: 24000, refundStatus: 'SUCCEEDED', refundSucceededAt: '2026-09-19T09:10:00+08:00',
    createdAt: '2026-09-18T10:00:00+08:00',
  }],
})

function page(items: unknown[]) {
  return { page: 1, size: 10, total: items.length, hasMore: false, items }
}

async function stub(target: Page): Promise<void> {
  await target.clock.setFixedTime(NOW)
  await target.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await target.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    const url = new URL(request.url())
    let body: unknown = { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' }
    let status = 404
    if (url.pathname === '/api/v1/activity-waitlists') {
      status = 200
      body = page([waitlist])
    } else if (url.pathname === '/api/v1/activity-enrollments') {
      status = 200
      const tab = url.searchParams.get('status')
      body = page(tab === 'FINISHED' ? [finished] : tab === 'CANCELLED' ? [refunded] : [team, moved])
    }
    await route.fulfill({ status, headers: CORS, json: body })
  })
}

async function shoot(target: Page, name: string): Promise<void> {
  mkdirSync(visualOutput, { recursive: true })
  await target.waitForTimeout(300)
  await target.screenshot({ path: resolve(visualOutput, `${name}-app.png`), fullPage: true })
}

test.describe('我的报名（board EnrollList）', () => {
  test('LIST-001 待参加：候补卡在最上面，团队卡和改期的卡各有去处', async ({ page: target }) => {
    await stub(target)
    await target.goto('/#/pages/mine/activity-enrollments?status=UPCOMING')

    const waiting = target.getByTestId('waitlist-w-1')
    await expect(waiting).toContainText('候补中')
    await expect(waiting).toContainText('排第 3 位 · 候补成功后再付款')
    await expect(waiting).toContainText('¥240')
    const cards = target.locator('.enrollments__card')
    await expect(cards.first()).toHaveAttribute('data-testid', 'waitlist-w-1')

    const teamCard = target.getByTestId('enrollment-team')
    await expect(teamCard).toContainText('团队')
    await expect(teamCard).toContainText('东营港务物流职工子女队')
    await expect(teamCard).toContainText('领队：王女士 · 4 人')
    await expect(target.getByTestId('enrollment-team-team')).toHaveText('团队名单')

    const movedCard = target.getByTestId('enrollment-moved')
    await expect(movedCard).toContainText('已改期')
    await expect(movedCard).toContainText('已改到 11/22 09:00，新凭证已发放')
    await expect(target.getByTestId('enrollment-refund-moved')).toHaveText('申请退改')
    await shoot(target, 'EnrollList-todo')

    await target.getByTestId('waitlist-view-w-1').click()
    await expect(target).toHaveURL(/#\/pages\/activity\/waitlist\?waitlistId=w-1/)
    await target.goBack()
    await target.getByTestId('enrollment-team-team').click()
    await expect(target).toHaveURL(/#\/pages\/activity\/team-order\?enrollmentId=team/)
  })

  test('LIST-002 已结束看结果，已取消看退款进度', async ({ page: target }) => {
    await stub(target)
    await target.goto('/#/pages/mine/activity-enrollments?status=FINISHED')
    const done = target.getByTestId('enrollment-done')
    await expect(done).toContainText('2 人完成 · 结果记录已发布')
    await expect(target.getByTestId('enrollment-result-done')).toHaveText('查看结果')
    await expect(target.getByTestId('waitlist-w-1')).toHaveCount(0)
    await shoot(target, 'EnrollList-done')
    await target.getByTestId('enrollment-result-done').click()
    await expect(target).toHaveURL(/#\/pages\/activity\/result\?enrollmentId=done/)

    await target.goto('/?tab=back#/pages/mine/activity-enrollments?status=CANCELLED')
    await expect(target.getByTestId('enrollment-back')).toContainText('¥240 已原路退回')
    await target.getByTestId('enrollment-progress-back').click()
    await expect(target).toHaveURL(/#\/pages\/activity\/change\?enrollmentId=back&changeId=c-back/)
  })
})
