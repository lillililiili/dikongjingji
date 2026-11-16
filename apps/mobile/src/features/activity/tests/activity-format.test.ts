import { describe, expect, it } from 'vitest'

import type { ActivityCard, ActivitySession } from '../domain/activity'
import {
  buildActivityDateOptions,
  formatActivityAgeRange,
  formatActivityCardSchedule,
  formatActivityCardStatus,
  formatActivityDateTime,
  formatActivityPrice,
  formatActivitySessionSeats,
  formatActivityShortDateTime,
  formatActivityTime,
  lastActivityStripDate,
  toBeijingDate,
} from '../presentation/shared/activity-format'

const card: ActivityCard = {
  activityId: 'a',
  type: 'CONTEST',
  title: '挑战赛',
  coverImage: null,
  venueName: '东营低空与机器人体验馆',
  scheduleNote: null,
  price: { mode: 'PER_PERSON', amountMinor: 12000 },
  ageRange: { minAge: 8, maxAge: 15 },
  enrollmentStatus: 'OPEN',
  nextSession: { sessionId: 's', startsAt: '2026-11-28T01:00:00Z', capacity: 60, seatsLeft: 12 },
}

describe('activity times', () => {
  it('shows Beijing time whatever zone the instant is written in', () => {
    expect(formatActivityDateTime('2026-11-28T01:00:00Z')).toBe('11/28 周六 09:00')
    expect(formatActivityDateTime('2026-11-28T09:00:00+08:00')).toBe('11/28 周六 09:00')
    expect(formatActivityShortDateTime('2026-11-27T10:00:00Z')).toBe('11/27 18:00')
    expect(formatActivityTime('2026-11-28T00:30:00Z')).toBe('08:30')
  })

  it('rolls over to the next Beijing day late in the UTC day', () => {
    expect(formatActivityDateTime('2026-11-27T16:30:00Z')).toBe('11/28 周六 00:30')
    expect(toBeijingDate(new Date('2026-11-27T16:30:00Z'))).toBe('2026-11-28')
    expect(toBeijingDate(new Date('2026-12-31T16:00:00Z'))).toBe('2027-01-01')
  })
})

describe('activity price and age', () => {
  it('formats the three price modes', () => {
    expect(formatActivityPrice({ mode: 'PER_PERSON', amountMinor: 12000 })).toBe('¥120')
    expect(formatActivityPrice({ mode: 'PER_PERSON', amountMinor: 12050 })).toBe('¥120.50')
    expect(formatActivityPrice({ mode: 'PER_PERSON', amountMinor: 1280000 })).toBe('¥12,800')
    expect(formatActivityPrice({ mode: 'FREE', amountMinor: null })).toBe('免费')
    expect(formatActivityPrice({ mode: 'INQUIRY', amountMinor: null })).toBe('询价')
  })

  it('does not invent a price when a per-person amount is missing', () => {
    expect(formatActivityPrice({ mode: 'PER_PERSON', amountMinor: null })).toBe('询价')
  })

  it('formats age ranges and stays silent without limits', () => {
    expect(formatActivityAgeRange({ minAge: 8, maxAge: 15 })).toBe('8–15 岁')
    expect(formatActivityAgeRange({ minAge: 16, maxAge: null })).toBe('16 岁以上')
    expect(formatActivityAgeRange({ minAge: null, maxAge: 12 })).toBe('12 岁以下')
    expect(formatActivityAgeRange({ minAge: null, maxAge: null })).toBeNull()
  })
})

describe('activity card', () => {
  it('shows seats while open and the status otherwise', () => {
    expect(formatActivityCardStatus(card)).toBe('余 12/60')
    expect(formatActivityCardStatus({ ...card, enrollmentStatus: 'FULL_WAITLIST' })).toBe('已满 · 可候补')
    expect(formatActivityCardStatus({ ...card, enrollmentStatus: 'FULL' })).toBe('已满')
    expect(formatActivityCardStatus({ ...card, enrollmentStatus: 'CLOSED' })).toBe('报名截止')
    expect(formatActivityCardStatus({ ...card, nextSession: null })).toBe('报名中')
  })

  it('joins the organiser note, the time and the place', () => {
    expect(formatActivityCardSchedule(card)).toBe('11/28 周六 09:00 · 东营低空与机器人体验馆')
    expect(formatActivityCardSchedule({ ...card, scheduleNote: '每周六 · 含接送' }))
      .toBe('每周六 · 含接送 · 11/28 周六 09:00 · 东营低空与机器人体验馆')
    expect(formatActivityCardSchedule({ ...card, nextSession: null })).toBe('东营低空与机器人体验馆')
  })

  it('labels a session by seats or by status', () => {
    const session: ActivitySession = {
      sessionId: 's',
      label: '上午',
      startsAt: '2026-11-28T01:00:00Z',
      endsAt: null,
      checkInOpensAt: null,
      enrollmentClosesAt: '2026-11-27T10:00:00Z',
      capacity: 60,
      seatsLeft: 12,
      status: 'OPEN',
    }
    expect(formatActivitySessionSeats(session)).toBe('余 12')
    expect(formatActivitySessionSeats({ ...session, status: 'FULL_WAITLIST', seatsLeft: 0 })).toBe('已满 · 可候补')
    expect(formatActivitySessionSeats({ ...session, status: 'ENDED' })).toBe('已结束')
  })
})

describe('activity date strip', () => {
  // 2026-11-16 17:30 in Beijing.
  const now = new Date('2026-11-16T09:30:00Z')

  it('starts today in Beijing time and marks the days that have sessions', () => {
    const options = buildActivityDateOptions(now, ['2026-11-17', '2026-11-21'])

    expect(options).toHaveLength(14)
    expect(options.slice(0, 3).map((option) => [option.title, option.subtitle, option.date])).toEqual([
      ['今天', '11/16', '2026-11-16'],
      ['明天', '11/17', '2026-11-17'],
      ['周三', '11/18', '2026-11-18'],
    ])
    expect(options.filter((option) => option.hasSessions).map((option) => option.date))
      .toEqual(['2026-11-17', '2026-11-21'])
    expect(lastActivityStripDate(now)).toBe('2026-11-29')
  })

  it('uses the Beijing day even when the phone is still on the previous UTC day', () => {
    // 2026-11-16 23:30 UTC is already 11-17 07:30 in Beijing.
    const options = buildActivityDateOptions(new Date('2026-11-16T23:30:00Z'), [])
    expect(options[0]).toMatchObject({ title: '今天', date: '2026-11-17' })
  })
})
