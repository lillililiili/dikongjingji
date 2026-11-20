import type { Enrollment, EnrollmentTicket } from '../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityShortDateTime,
  formatActivityTime,
} from '../presentation/shared/activity-format'
import { TICKET_REFUND_LABELS, cancelRefundMinor } from './enrollment-view'

// What the order page (board OrderEnroll) and the refund page (board EnrollRefund) of a paid
// sign-up say about server facts (R5 #4 / #5, 2026-11-20). Refunding stays what the cancel endpoint
// already does: the chosen tickets are cancelled at once and each paid back at its unit price.
// Nothing here decides who may be refunded beyond what the server sends (cancellable, ticket status).

// "11/15 上午 09:00", or "11/15 09:00" without a session label.
export function formatEnrollmentSession(enrollment: Enrollment): string {
  const [day, time] = formatActivityShortDateTime(enrollment.session.startsAt).split(' ')
  return enrollment.session.label ? `${day} ${enrollment.session.label} ${time}` : `${day} ${time}`
}

// "08:30 起签到", or null when the organiser did not set a check-in time.
export function formatEnrollmentCheckIn(enrollment: Enrollment): string | null {
  return enrollment.session.checkInOpensAt
    ? `${formatActivityTime(enrollment.session.checkInOpensAt)} 起签到`
    : null
}

// "2 人 × ¥120" for a paid sign-up, null for a free one.
export function formatEnrollmentFee(enrollment: Enrollment): string | null {
  const unit = enrollment.payment.unitAmountMinor
  if (enrollment.payment.status === 'FREE' || !unit) return null
  return `${enrollment.tickets.length} 人 × ${formatActivityAmount(unit)}`
}

// The tickets that can be refunded now: paid, not used, and still before the cancel deadline.
export function refundableTickets(enrollment: Enrollment): EnrollmentTicket[] {
  if (enrollment.payment.status !== 'PAID' || !enrollment.cancellable) return []
  return enrollment.tickets.filter((ticket) => ticket.status === 'ACTIVE')
}

export function canRequestRefund(enrollment: Enrollment): boolean {
  return refundableTickets(enrollment).length > 0
}

export interface RefundRow {
  ticketId: string
  name: string
  ageText: string
  // "实付 ¥120" for a ticket that can be refunded; what happened otherwise ("已退款 ¥120").
  sideText: string
  selectable: boolean
}

export function describeRefundRows(enrollment: Enrollment): RefundRow[] {
  const open = new Set(refundableTickets(enrollment).map((ticket) => ticket.ticketId))
  const unit = enrollment.payment.unitAmountMinor
  return enrollment.tickets.map((ticket) => {
    const selectable = open.has(ticket.ticketId)
    let sideText = unit ? `实付 ${formatActivityAmount(unit)}` : ''
    if (ticket.refund) {
      sideText = `${TICKET_REFUND_LABELS[ticket.refund.status]} ${formatActivityAmount(ticket.refund.amountMinor)}`
    } else if (ticket.status === 'CHECKED_IN') {
      sideText = '已签到，不能退'
    } else if (ticket.status !== 'ACTIVE') {
      sideText = '不能退'
    } else if (!selectable) {
      sideText = '已过取消时间'
    }
    return {
      ticketId: ticket.ticketId,
      name: ticket.participantName,
      ageText: ticket.relation === 'CHILD' && ticket.age !== null ? `${ticket.age} 岁` : '',
      sideText,
      selectable,
    }
  })
}

// Opened from one person's ticket: that person; otherwise everyone who can be refunded.
export function initialRefundSelection(enrollment: Enrollment, ticketId: string | null): string[] {
  const open = refundableTickets(enrollment).map((ticket) => ticket.ticketId)
  if (ticketId && open.includes(ticketId)) return [ticketId]
  return open
}

// "2 人 × ¥120 · 全额退款" next to the refund amount.
export function describeRefundBreakdown(enrollment: Enrollment, count: number): string {
  const unit = enrollment.payment.unitAmountMinor
  if (!unit || count === 0) return ''
  return `${count} 人 × ${formatActivityAmount(unit)} · 全额退款`
}

export function refundAmountText(enrollment: Enrollment, count: number): string {
  const amount = cancelRefundMinor(enrollment, count)
  return amount === null ? '' : formatActivityAmount(amount)
}

// "11/13 09:00 前可取消" while the sign-up can still be cancelled.
export function describeCancelDeadline(enrollment: Enrollment): string {
  if (enrollment.status === 'CANCELLED') return '报名已取消'
  if (enrollment.status === 'FINISHED') return '活动已结束'
  return enrollment.cancellable
    ? `${formatActivityShortDateTime(enrollment.cancellableUntil)} 前可取消`
    : '活动已开始，不能再取消'
}

// The 支付状态 row of 金额与支付.
export function describeEnrollmentPayState(enrollment: Enrollment): { text: string; tone: 'DONE' | 'PENDING' | 'MUTED' } {
  switch (enrollment.payment.status) {
    case 'PAID':
      // The trade module only marks an order paid from a verified callback or its own query.
      return { text: '已支付 · 渠道回执已核验', tone: 'DONE' }
    case 'PENDING_PAYMENT':
      return { text: '待付款', tone: 'PENDING' }
    case 'CLOSED':
      return { text: '未付款，已关闭', tone: 'MUTED' }
    case 'FREE':
      return { text: '免费报名，无需支付', tone: 'MUTED' }
  }
}

// "退款中 ¥120" / "已退款 ¥240" / "退款异常 ¥120": the refunds so far, or null.
export function describeEnrollmentRefunds(enrollment: Enrollment): { text: string; tone: 'DONE' | 'PENDING' | 'ALERT' } | null {
  const refunds = enrollment.tickets.flatMap((ticket) => (ticket.refund ? [ticket.refund] : []))
  if (refunds.length === 0) return null
  const total = formatActivityAmount(refunds.reduce((sum, refund) => sum + refund.amountMinor, 0))
  if (refunds.some((refund) => refund.status === 'CLOSED' || refund.status === 'ABNORMAL')) {
    return { text: `退款异常 ${total} · 平台正在人工处理`, tone: 'ALERT' }
  }
  if (refunds.some((refund) => refund.status !== 'SUCCEEDED')) {
    return { text: `退款中 ${total} · 原路退回`, tone: 'PENDING' }
  }
  return { text: `已退款 ${total} · 原路退回`, tone: 'DONE' }
}
