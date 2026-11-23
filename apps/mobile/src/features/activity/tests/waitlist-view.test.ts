import { describe, expect, it } from 'vitest'

import {
  abandonSheetText,
  describeWaitlist,
  formatWaitlistSession,
  waitlistHoldRemainingMs,
  waitlistRules,
  waitlistSessionName,
} from '../application/waitlist-view'
import type { Waitlist } from '../domain/enrollment'

// 2026-11-03 10:20 Beijing; the offer is held until 10:35.
const NOW = Date.parse('2026-11-03T02:20:00Z')

function waitlist(overrides: Partial<Waitlist> = {}): Waitlist {
  return {
    waitlistId: 'w1',
    status: 'WAITING',
    position: 3,
    ahead: 2,
    activity: {
      activityId: 'a1',
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    session: { sessionId: 's2', label: '下午', startsAt: '2026-11-15T06:00:00Z', endsAt: null, venueName: '东营低空与机器人体验馆' },
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

describe('waitlist page wording (board EnrollWaitlist)', () => {
  it('names the session like the board', () => {
    expect(waitlistSessionName(waitlist())).toBe('下午场')
    expect(waitlistSessionName(waitlist({ session: { ...waitlist().session, label: '下午场' } }))).toBe('下午场')
    expect(waitlistSessionName(waitlist({ session: { ...waitlist().session, label: null } }))).toBe('本场')
    expect(formatWaitlistSession(waitlist())).toBe('11/15 下午场 14:00')
  })

  it('shows the place in the queue while waiting', () => {
    const view = describeWaitlist(waitlist(), NOW)
    expect(view.heroTitle).toBe('候补中 · 第 3 位')
    expect(view.note).toBe('下午场已满 · 前面还有 2 位在候补')
    expect(view.payLabel).toBe('候补成功后应付')
    expect(view.payValue).toBe('¥240')
    expect(view.feeLine).toBe('2 人 × ¥120')
    expect(view.steps.map((step) => step.kind)).toEqual(['done', 'cur', 'todo', 'todo'])
    expect(view.bar).toBe('WAITING')
    expect(describeWaitlist(waitlist({ position: 1, ahead: 0 }), NOW).note).toBe('下午场已满 · 你排在第一位')
  })

  it('counts down the held seats after an offer', () => {
    const offered = waitlist({ status: 'OFFERED', position: null, ahead: null, holdUntil: '2026-11-03T02:34:58Z' })
    expect(waitlistHoldRemainingMs(offered, NOW)).toBe(14 * 60_000 + 58_000)
    const view = describeWaitlist(offered, NOW)
    expect(view.heroTitle).toBe('候补成功 · 待付款')
    expect(view.note).toBe('名额保留 14:58 · 超时自动释放')
    expect(view.steps.map((step) => step.label)).toEqual(['加入候补', '等待名额', '付款', '报名成功'])
    expect(view.bar).toBe('OFFERED')
    expect(abandonSheetText(offered)).toContain('马上释放给下一位')
    expect(waitlistHoldRemainingMs(offered, Date.parse('2026-11-03T03:00:00Z'))).toBe(0)
  })

  it('asks a free offer to confirm instead of paying', () => {
    const free = waitlist({ status: 'OFFERED', unitAmountMinor: null, amountMinor: null, holdUntil: '2026-11-03T02:35:00Z' })
    const view = describeWaitlist(free, NOW)
    expect(view.heroTitle).toBe('候补成功 · 待确认')
    expect(view.payValue).toBe('免费')
    expect(view.feeLine).toBeNull()
    expect(view.steps[2].label).toBe('确认报名')
    expect(waitlistRules(free)[2]).toBe('转正后保留 15 分钟，超时未确认释放给下一位')
  })

  it('ends with nothing charged', () => {
    const expired = describeWaitlist(waitlist({ status: 'EXPIRED' }), NOW)
    expect(expired.tone).toBe('GRAY')
    expect(expired.heroTitle).toBe('候补已失效')
    expect(expired.payValue).toBe('未产生费用')
    expect(expired.steps[2]).toEqual({ label: '已超时', kind: 'end' })
    expect(describeWaitlist(waitlist({ status: 'ENDED' }), NOW).steps[1]).toEqual({ label: '未转正', kind: 'end' })
    expect(describeWaitlist(waitlist({ status: 'ABANDONED' }), NOW).heroTitle).toBe('已放弃候补')
    expect(describeWaitlist(waitlist({ status: 'ENROLLED', enrollmentId: 'e1' }), NOW).bar).toBe('ENROLLED')
  })

  it('leaves out the together rule for one person', () => {
    expect(waitlistRules(waitlist())[1]).toBe('同单 2 人一起转正，余位不足 2 个时继续排队')
    expect(waitlistRules(waitlist({ people: 1 }))).toHaveLength(3)
  })
})
