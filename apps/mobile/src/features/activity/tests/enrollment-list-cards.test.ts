import { describe, expect, it } from 'vitest'

import { describeEnrollmentCard, describeWaitlistCard } from '../application/enrollment-view'
import type { Enrollment, EnrollmentTicket, Waitlist } from '../domain/enrollment'

// S5-3 cards on 我的报名 (board EnrollList): a waitlist, a team, a rescheduled sign-up, a finished one with results.

function ticket(name: string, overrides: Partial<EnrollmentTicket> = {}): EnrollmentTicket {
  return {
    ticketId: `t-${name}`,
    participantId: `p-${name}`,
    participantName: name,
    relation: 'CHILD',
    age: 11,
    ticketCode: 'ACT-1234-5678-9ABC',
    status: 'ACTIVE',
    checkedInAt: null,
    cancelledAt: null,
    refund: null,
    result: null,
    ...overrides,
  }
}

function enrollment(overrides: Partial<Enrollment> = {}): Enrollment {
  return {
    enrollmentId: 'e1',
    status: 'UPCOMING',
    activity: {
      activityId: 'a1',
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    // 09:00 on 15 November in Beijing.
    session: { sessionId: 's1', label: '上午场', startsAt: '2026-11-15T01:00:00Z', endsAt: null, checkInOpensAt: null },
    location: { name: '东营低空与机器人体验馆', address: '示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-03T01:00:00Z',
    cancellableUntil: '2026-11-15T01:00:00Z',
    cancellable: false,
    tickets: [ticket('王小禾'), ticket('王小川')],
    payment: { status: 'PAID', tradeOrderId: 'o1', amountMinor: 24000, unitAmountMinor: 12000, payBefore: null },
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: false,
    ...overrides,
  }
}

function waitlist(overrides: Partial<Waitlist> = {}): Waitlist {
  return {
    waitlistId: 'w1',
    status: 'WAITING',
    position: 3,
    ahead: 2,
    activity: enrollment().activity,
    // 14:00 on 15 November in Beijing.
    session: { sessionId: 's2', label: '下午场', startsAt: '2026-11-15T06:00:00Z', endsAt: null, venueName: '东营低空与机器人体验馆' },
    members: [
      { participantId: 'p1', name: '王小禾', relation: 'CHILD', age: 11 },
      { participantId: 'p2', name: '王小川', relation: 'CHILD', age: 9 },
    ],
    people: 2,
    unitAmountMinor: 12000,
    amountMinor: 24000,
    holdUntil: null,
    enrollmentId: null,
    tradeOrderId: null,
    abandonable: true,
    createdAt: '2026-11-03T01:40:00Z',
    offeredAt: null,
    endedAt: null,
    ...overrides,
  }
}

describe('waitlist cards', () => {
  it('say the place in the queue and that payment comes later', () => {
    const card = describeWaitlistCard(waitlist())
    expect(card.statusText).toBe('候补中')
    expect(card.note).toBe('排第 3 位 · 候补成功后再付款')
    expect(card.who).toBe('参与人：王小禾、王小川')
    expect(card.price).toBe('¥240')
  })

  it('say until when an offered seat is held, and what to do for a free one', () => {
    const paid = describeWaitlistCard(waitlist({ status: 'OFFERED', position: null, holdUntil: '2026-11-03T02:35:00Z' }))
    expect(paid.statusText).toBe('候补成功')
    expect(paid.note).toBe('名额保留到 10:35，请尽快付款')
    const free = describeWaitlistCard(waitlist({
      status: 'OFFERED', unitAmountMinor: null, amountMinor: null, holdUntil: '2026-11-03T02:35:00Z',
    }))
    expect(free.note).toBe('名额保留到 10:35，请尽快确认报名')
    expect(free.price).toBe('免费')
  })

  it('say how a waitlist ended', () => {
    expect(describeWaitlistCard(waitlist({ status: 'EXPIRED' })).statusText).toBe('候补已失效')
    expect(describeWaitlistCard(waitlist({ status: 'ENDED' })).statusText).toBe('候补未成功')
    expect(describeWaitlistCard(waitlist({ status: 'ABANDONED' })).note).toBe('')
  })
})

describe('sign-up cards', () => {
  it('name the leader and the head count of a team', () => {
    const card = describeEnrollmentCard(enrollment({
      kind: 'TEAM',
      team: { teamName: '东营港务物流职工子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' },
      tickets: [ticket('李明轩'), ticket('张雨桐'), ticket('赵子涵'), ticket('陈可欣')],
    }))
    expect(card.who).toBe('领队：王女士 · 4 人')
    expect(card.statusText).toBe('待参加')
  })

  it('say where a rescheduled sign-up went', () => {
    const card = describeEnrollmentCard(enrollment({
      reschedule: { previousSession: { sessionId: 's0', label: '上午场', startsAt: '2026-11-08T01:00:00Z' }, rescheduledAt: '2026-11-03T02:00:00Z' },
    }))
    expect(card.statusText).toBe('已改期')
    expect(card.note).toBe('已改到 11/15 09:00，新凭证已发放')
    expect(card.noteTone).toBe('MOVED')
  })

  it('say the results are out once the organiser recorded them', () => {
    const result = { completion: 'COMPLETED' as const, scoreText: '5/5', certificateNote: null, recordedAt: '2026-11-15T08:00:00Z' }
    const card = describeEnrollmentCard(enrollment({
      status: 'FINISHED',
      tickets: [
        ticket('王小禾', { status: 'CHECKED_IN', checkedInAt: '2026-11-15T00:42:00Z', result }),
        ticket('王小川', { status: 'CHECKED_IN', checkedInAt: '2026-11-15T00:43:00Z', result }),
      ],
    }))
    expect(card.note).toBe('2 人完成 · 结果记录已发布')
    expect(card.noteTone).toBe('DONE')
  })
})
