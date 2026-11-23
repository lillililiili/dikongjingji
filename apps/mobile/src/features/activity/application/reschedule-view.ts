import type { ActivitySession } from '../domain/activity'
import type { ChangeReason, Enrollment, EnrollmentTicket } from '../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityDay,
  formatActivityShortDateTime,
  formatActivityTime,
} from '../presentation/shared/activity-format'

// 申请退改 (board EnrollRefund, S5-3): the two ways out of a sign-up before it starts. The server decides
// what is allowed (cancellable, reschedulable, ticket status); this file only words it.
//
// - 取消报名并退款: the ticked people are cancelled at once; a paid one is refunded at the unit price
//   (提交即退, no organiser review). A free one is just cancelled.
// - 改到其他场次: the whole sign-up moves to another session of the same activity that is open and has
//   enough seats, at once; the old tickets are voided and new ones issued. Same price, nothing to pay.

export type RefundMode = 'MOVE' | 'REFUND'

export const CHANGE_REASONS: ReadonlyArray<{ code: ChangeReason; label: string }> = [
  { code: 'TIME_CONFLICT', label: '时间冲突' },
  { code: 'UNWELL', label: '孩子身体不适' },
  { code: 'PLAN_CHANGED', label: '行程有变' },
  { code: 'OTHER', label: '其他原因' },
]

export function changeReasonLabel(reason: ChangeReason | null): string {
  return CHANGE_REASONS.find((item) => item.code === reason)?.label ?? '未填写'
}

// The tickets that can be cancelled now: not used, before the start, and the sign-up is paid or free
// (an unpaid one is cancelled whole on its ticket page).
export function cancellableTickets(enrollment: Enrollment): EnrollmentTicket[] {
  if (!enrollment.cancellable) return []
  if (enrollment.payment.status !== 'PAID' && enrollment.payment.status !== 'FREE') return []
  return enrollment.tickets.filter((ticket) => ticket.status === 'ACTIVE')
}

// A reschedule moves every unused ticket of the sign-up.
export function movableTickets(enrollment: Enrollment): EnrollmentTicket[] {
  if (!enrollment.reschedulable) return []
  return enrollment.tickets.filter((ticket) => ticket.status === 'ACTIVE')
}

export function availableModes(enrollment: Enrollment): RefundMode[] {
  const modes: RefundMode[] = []
  if (movableTickets(enrollment).length > 0) modes.push('MOVE')
  if (cancellableTickets(enrollment).length > 0) modes.push('REFUND')
  return modes
}

export interface ModeOption {
  mode: RefundMode
  title: string
  subtitle: string
}

export function describeModes(enrollment: Enrollment): ModeOption[] {
  const paid = enrollment.payment.status === 'PAID'
  const deadline = formatActivityShortDateTime(enrollment.cancellableUntil)
  return availableModes(enrollment).map((mode) => mode === 'MOVE'
    ? { mode, title: '改到其他场次', subtitle: paid ? '不退款，名额转入新场次' : '名额转入新场次' }
    : {
        mode,
        title: paid ? '取消报名并退款' : '取消报名',
        subtitle: paid ? `${deadline} 前免费取消，原路退回` : `${deadline} 前可取消，名额释放给别人`,
      })
}

export interface RescheduleOption {
  sessionId: string
  // "11/22 周日"
  day: string
  // "上午 09:00 · 余 30" / "下午 14:00 · 已满"
  sub: string
  selectable: boolean
}

// The other sessions of the activity a sign-up of `people` can move to. Full or closed ones are shown
// greyed, so the customer sees why a date is missing; ended ones are left out.
export function describeRescheduleOptions(
  sessions: readonly ActivitySession[],
  currentSessionId: string,
  people: number,
): RescheduleOption[] {
  return sessions
    .filter((session) => session.sessionId !== currentSessionId && session.status !== 'ENDED')
    .map((session) => {
      const selectable = session.status === 'OPEN' && session.seatsLeft >= people
      const time = `${session.label ? `${session.label} ` : ''}${formatActivityTime(session.startsAt)}`
      let seats: string
      if (session.status === 'OPEN') seats = session.seatsLeft >= people ? `余 ${session.seatsLeft}` : `仅余 ${session.seatsLeft}`
      else if (session.status === 'CLOSED') seats = '已截止'
      else seats = '已满'
      return { sessionId: session.sessionId, day: formatActivityDay(session.startsAt), sub: `${time} · ${seats}`, selectable }
    })
}

// The note above the submit button.
export function describeRefundNote(mode: RefundMode, enrollment: Enrollment): string {
  if (mode === 'MOVE') {
    return '改期按整单，所有未使用的参与人一起改。提交后立即生效：原凭证作废，新场次一人一码重新发放，无需补差价。'
  }
  return enrollment.payment.status === 'PAID'
    ? '提交后立即取消所选参与人的报名并释放名额，报名费原路退回，到账时间以微信支付为准。'
    : '提交后立即取消所选参与人的报名并释放名额。免费报名不涉及退款。'
}

// "2 人 × ¥120 · 全额退款"; empty for a free sign-up.
export function describeRefundSide(enrollment: Enrollment, count: number): string {
  const unit = enrollment.payment.unitAmountMinor
  if (enrollment.payment.status !== 'PAID' || !unit || count === 0) return ''
  return `${count} 人 × ${formatActivityAmount(unit)} · 全额退款`
}

export interface RefundPersonRow {
  ticketId: string
  name: string
  ageText: string
  // "实付 ¥120" / "免费" for one that can go; what happened otherwise ("已退款 ¥120", "已签到").
  sideText: string
  selectable: boolean
  // A reschedule takes everyone unused: ticked and locked.
  locked: boolean
}

const REFUND_LABELS: Record<string, string> = {
  REQUESTED: '退款中',
  PROCESSING: '退款中',
  SUCCEEDED: '已退款',
  CLOSED: '退款异常',
  ABNORMAL: '退款异常',
}

export function describePeopleRows(enrollment: Enrollment, mode: RefundMode): RefundPersonRow[] {
  const open = new Set((mode === 'MOVE' ? movableTickets(enrollment) : cancellableTickets(enrollment)).map((ticket) => ticket.ticketId))
  const unit = enrollment.payment.status === 'PAID' ? enrollment.payment.unitAmountMinor : null
  return enrollment.tickets
    .filter((ticket) => ticket.status !== 'CANCELLED' || ticket.refund)
    .map((ticket) => {
      const selectable = open.has(ticket.ticketId)
      let sideText = unit ? `实付 ${formatActivityAmount(unit)}` : '免费'
      if (ticket.refund) sideText = `${REFUND_LABELS[ticket.refund.status]} ${formatActivityAmount(ticket.refund.amountMinor)}`
      else if (ticket.status === 'CHECKED_IN') sideText = '已签到'
      else if (ticket.status !== 'ACTIVE') sideText = '不能退改'
      else if (!selectable) sideText = '已过退改时间'
      return {
        ticketId: ticket.ticketId,
        name: ticket.participantName,
        ageText: ticket.relation !== 'SELF' && ticket.age !== null ? `${ticket.age} 岁` : '',
        sideText,
        selectable: selectable && mode === 'REFUND',
        locked: selectable && mode === 'MOVE',
      }
    })
}

// Opened from one person's ticket: that person; otherwise everyone who can be cancelled.
export function initialCancelSelection(enrollment: Enrollment, ticketId: string | null): string[] {
  const open = cancellableTickets(enrollment).map((ticket) => ticket.ticketId)
  if (ticketId && open.includes(ticketId)) return [ticketId]
  return open
}
