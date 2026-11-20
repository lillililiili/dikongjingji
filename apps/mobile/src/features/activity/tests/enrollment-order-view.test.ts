import { describe, expect, it } from 'vitest'

import {
  canRequestRefund,
  describeCancelDeadline,
  describeEnrollmentPayState,
  describeEnrollmentRefunds,
  describeRefundBreakdown,
  describeRefundRows,
  formatEnrollmentCheckIn,
  formatEnrollmentFee,
  formatEnrollmentSession,
  initialRefundSelection,
  refundAmountText,
  refundableTickets,
} from '../application/enrollment-order-view'
import type { Enrollment, EnrollmentTicket } from '../domain/enrollment'

function ticket(status: EnrollmentTicket['status'], name: string): EnrollmentTicket {
  return {
    ticketId: `t-${name}`,
    participantId: `p-${name}`,
    participantName: name,
    relation: 'CHILD',
    age: 11,
    ticketCode: status === 'ACTIVE' ? 'ACT-1234-5678-9ABC' : null,
    status,
    checkedInAt: null,
    cancelledAt: null,
    refund: null,
  }
}

// Two children at ¥120, paid; 09:00 on 15 November in Beijing, check-in from 08:30.
function paid(tickets: EnrollmentTicket[], cancellable = true): Enrollment {
  return {
    enrollmentId: 'e1',
    status: 'UPCOMING',
    activity: {
      activityId: 'a1',
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '示例青少年科技机构',
    },
    session: {
      sessionId: 's1',
      label: '上午',
      startsAt: '2026-11-15T01:00:00Z',
      endsAt: null,
      checkInOpensAt: '2026-11-15T00:30:00Z',
    },
    location: { name: '体验馆', address: '示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-10T01:00:00Z',
    cancellableUntil: '2026-11-15T01:00:00Z',
    cancellable,
    tickets,
    payment: { status: 'PAID', tradeOrderId: 'o1', amountMinor: 24000, unitAmountMinor: 12000, payBefore: null },
  }
}

describe('enrollment order view', () => {
  it('words the session, check-in and fee like the board', () => {
    const enrollment = paid([ticket('ACTIVE', '王小禾'), ticket('ACTIVE', '王小川')])
    expect(formatEnrollmentSession(enrollment)).toBe('11/15 上午 09:00')
    expect(formatEnrollmentSession({ ...enrollment, session: { ...enrollment.session, label: null } })).toBe('11/15 09:00')
    expect(formatEnrollmentCheckIn(enrollment)).toBe('08:30 起签到')
    expect(formatEnrollmentFee(enrollment)).toBe('2 人 × ¥120')
    expect(formatEnrollmentFee({ ...enrollment, payment: { ...enrollment.payment, status: 'FREE', unitAmountMinor: null } })).toBeNull()
  })

  it('only offers tickets the server still lets go', () => {
    const enrollment = paid([ticket('ACTIVE', '王小禾'), ticket('CHECKED_IN', '王小川')])
    expect(refundableTickets(enrollment).map((item) => item.participantName)).toEqual(['王小禾'])
    expect(canRequestRefund(enrollment)).toBe(true)
    expect(canRequestRefund(paid([ticket('ACTIVE', '王小禾')], false))).toBe(false)
    const free = paid([ticket('ACTIVE', '王小禾')])
    expect(canRequestRefund({ ...free, payment: { ...free.payment, status: 'FREE' } })).toBe(false)

    const rows = describeRefundRows(enrollment)
    expect(rows.map((row) => [row.name, row.ageText, row.sideText, row.selectable])).toEqual([
      ['王小禾', '11 岁', '实付 ¥120', true],
      ['王小川', '11 岁', '已签到，不能退', false],
    ])
  })

  it('starts on the person the ticket page was showing, else everyone', () => {
    const enrollment = paid([ticket('ACTIVE', '王小禾'), ticket('ACTIVE', '王小川')])
    expect(initialRefundSelection(enrollment, 't-王小川')).toEqual(['t-王小川'])
    expect(initialRefundSelection(enrollment, null)).toEqual(['t-王小禾', 't-王小川'])
    expect(initialRefundSelection(enrollment, 't-nobody')).toEqual(['t-王小禾', 't-王小川'])
    expect(refundAmountText(enrollment, 2)).toBe('¥240')
    expect(describeRefundBreakdown(enrollment, 2)).toBe('2 人 × ¥120 · 全额退款')
    expect(describeRefundBreakdown(enrollment, 0)).toBe('')
  })

  it('says how the payment and refunds stand', () => {
    const one = { ...ticket('CANCELLED', '王小禾'), refund: { status: 'PROCESSING' as const, amountMinor: 12000 } }
    const enrollment = paid([one, ticket('ACTIVE', '王小川')])
    expect(describeEnrollmentPayState(enrollment)).toEqual({ text: '已支付 · 渠道回执已核验', tone: 'DONE' })
    expect(describeEnrollmentRefunds(enrollment)).toEqual({ text: '退款中 ¥120 · 原路退回', tone: 'PENDING' })
    expect(describeEnrollmentRefunds(paid([ticket('ACTIVE', '王小川')]))).toBeNull()
    expect(describeRefundRows(enrollment)[0].sideText).toBe('退款中 ¥120')
    expect(describeCancelDeadline(enrollment)).toBe('11/15 09:00 前可取消')
    expect(describeCancelDeadline(paid([ticket('ACTIVE', '王小川')], false))).toBe('活动已开始，不能再取消')
  })
})
