import { describe, expect, it } from 'vitest'

import {
  findNextVisit,
  findPendingAcceptanceVisit,
  isVisitFinished,
  resolveDeliveryVisit,
  type ServiceVisitLike,
} from '../domain/service-flow/service-visit'
import { formatPlanPrice } from '../domain/service-flow/plan'
import {
  formatClock,
  formatDateRange,
  formatDistance,
  formatFileSize,
  formatMonthDay,
  formatMonthDayTime,
} from '../presentation/shared/service-flow-format'

const local = (month: number, day: number, hour: number, minute: number): string =>
  new Date(2026, month - 1, day, hour, minute).toISOString()

describe('service flow formatting', () => {
  it('reads a plain date as written, whatever the device time zone', () => {
    expect(formatMonthDay('2026-11-02')).toBe('11/02')
    expect(formatMonthDay('2027-01-18')).toBe('01/18')
  })

  it('shows instants in device time', () => {
    expect(formatMonthDay(local(11, 3, 10, 0))).toBe('11/03')
    expect(formatClock(local(11, 3, 16, 5))).toBe('16:05')
    expect(formatMonthDayTime(local(11, 3, 9, 30))).toBe('11/03 09:30')
  })

  it('keeps an unreadable value rather than showing Invalid Date', () => {
    expect(formatMonthDay('待定')).toBe('待定')
    expect(formatClock('待定')).toBe('待定')
    expect(formatMonthDayTime('待定')).toBe('待定')
  })

  it('collapses a range of one date', () => {
    expect(formatDateRange(['2026-11-02', '2026-11-16', '2027-01-18'])).toBe('11/02–01/18')
    expect(formatDateRange(['2026-11-02'])).toBe('11/02')
    expect(formatDateRange([])).toBe('')
  })

  it('formats distances and file sizes for a phone-width row', () => {
    expect(formatDistance(12_000)).toBe('12 km')
    expect(formatDistance(18_600)).toBe('18.6 km')
    expect(formatDistance(800)).toBe('800 m')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatFileSize(100)).toBe('1 KB')
  })

  it('drops the fen from whole-yuan prices only', () => {
    expect(formatPlanPrice(1_860_000)).toBe('¥18,600')
    expect(formatPlanPrice(558_050)).toBe('¥5,580.50')
    expect(formatPlanPrice(-1)).toBe('¥--')
  })
})

describe('service visit rules', () => {
  const visits: ServiceVisitLike[] = [
    { id: 'v3', visitNo: 3, status: 'SCHEDULED' },
    { id: 'v1', visitNo: 1, status: 'ACCEPTED' },
    { id: 'v2', visitNo: 2, status: 'DELIVERED' },
    { id: 'v4', visitNo: 4, status: 'PENDING_SCHEDULE' },
  ]

  it('counts a visit as finished once it is delivered', () => {
    expect(visits.filter(isVisitFinished).map((visit) => visit.visitNo).sort()).toEqual([1, 2])
  })

  it('picks the earliest visit that still needs acceptance or a start', () => {
    expect(findPendingAcceptanceVisit(visits)?.id).toBe('v2')
    expect(findNextVisit(visits)?.id).toBe('v3')
    expect(findPendingAcceptanceVisit([visits[0]])).toBeNull()
    expect(findNextVisit([visits[1]])).toBeNull()
  })

  it('opens the visit in the link, and only that one', () => {
    expect(resolveDeliveryVisit(visits, 'v1')?.id).toBe('v1')
    expect(resolveDeliveryVisit(visits, 'unknown')).toBeNull()
  })

  it('falls back for links without a visit', () => {
    expect(resolveDeliveryVisit(visits, '')?.id).toBe('v2')
    expect(resolveDeliveryVisit([
      { id: 'a', visitNo: 1, status: 'ACCEPTED' },
      { id: 'b', visitNo: 2, status: 'ACCEPTED' },
      { id: 'c', visitNo: 3, status: 'SCHEDULED' },
    ], '')?.id).toBe('b')
    expect(resolveDeliveryVisit([{ id: 'a', visitNo: 1, status: 'SCHEDULED' }], '')).toBeNull()
  })
})
