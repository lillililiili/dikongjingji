import { describe, expect, it } from 'vitest'

import {
  availableModes,
  changeReasonLabel,
  describeModes,
  describePeopleRows,
  describeRefundNote,
  describeRefundSide,
  describeRescheduleOptions,
  initialCancelSelection,
} from '../application/reschedule-view'
import { s53Enrollment, s53Session, s53Ticket } from './result-change-fixtures'

describe('申请退改 modes', () => {
  it('offers moving first and cancelling with a refund for a paid sign-up', () => {
    expect(describeModes(s53Enrollment()).map((item) => [item.mode, item.title])).toEqual([
      ['MOVE', '改到其他场次'],
      ['REFUND', '取消报名并退款'],
    ])
  })

  it('words a free sign-up as a plain cancellation', () => {
    const free = s53Enrollment({ payment: { status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null } })
    expect(describeModes(free).find((item) => item.mode === 'REFUND')?.title).toBe('取消报名')
    expect(describeRefundSide(free, 2)).toBe('')
    expect(describeRefundNote('REFUND', free)).toContain('免费报名不涉及退款')
  })

  it('leaves moving out when the server says it cannot move, and both out once it started', () => {
    expect(availableModes(s53Enrollment({ reschedulable: false }))).toEqual(['REFUND'])
    expect(availableModes(s53Enrollment({ reschedulable: false, cancellable: false }))).toEqual([])
  })

  it('locks every unused person when moving and lets them be picked one by one when cancelling', () => {
    const enrollment = s53Enrollment({
      tickets: [s53Ticket('王小禾'), s53Ticket('王小川', { status: 'CANCELLED', ticketCode: null, refund: { status: 'SUCCEEDED', amountMinor: 12000 } })],
    })
    const move = describePeopleRows(enrollment, 'MOVE')
    expect(move.map((row) => [row.name, row.locked, row.selectable, row.sideText])).toEqual([
      ['王小禾', true, false, '实付 ¥120'],
      ['王小川', false, false, '已退款 ¥120'],
    ])
    const refund = describePeopleRows(enrollment, 'REFUND')
    expect(refund[0]).toMatchObject({ selectable: true, locked: false })
    expect(initialCancelSelection(enrollment, null)).toEqual(['t-王小禾'])
    expect(initialCancelSelection(s53Enrollment(), 't-王小川')).toEqual(['t-王小川'])
  })

  it('adds up the refund per person', () => {
    expect(describeRefundSide(s53Enrollment(), 2)).toBe('2 人 × ¥120 · 全额退款')
  })

  it('names the reasons and the empty one', () => {
    expect(changeReasonLabel('UNWELL')).toBe('孩子身体不适')
    expect(changeReasonLabel(null)).toBe('未填写')
  })
})

describe('reschedule options', () => {
  const sessions = [
    s53Session('s-am', '2026-11-15T01:00:00Z', { label: '上午' }),
    s53Session('s-pm', '2026-11-15T06:00:00Z', { label: '下午', status: 'FULL_WAITLIST', seatsLeft: 0 }),
    s53Session('s-next', '2026-11-22T01:00:00Z', { label: '上午', seatsLeft: 30 }),
    s53Session('s-one', '2026-11-29T01:00:00Z', { seatsLeft: 1 }),
    s53Session('s-old', '2026-11-01T01:00:00Z', { status: 'ENDED' }),
  ]

  it('leaves out the current and ended sessions and greys the ones that cannot take everyone', () => {
    expect(describeRescheduleOptions(sessions, 's-am', 2)).toEqual([
      { sessionId: 's-pm', day: '11/15 周日', sub: '下午 14:00 · 已满', selectable: false },
      { sessionId: 's-next', day: '11/22 周日', sub: '上午 09:00 · 余 30', selectable: true },
      { sessionId: 's-one', day: '11/29 周日', sub: '09:00 · 仅余 1', selectable: false },
    ])
  })
})
