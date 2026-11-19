import type { ActivityAgeRange } from '../domain/activity'
import type {
  Enrollment,
  EnrollmentStatus,
  EnrollmentTicket,
  Participant,
  ParticipantEligibilityReason,
  TicketRefund,
  TicketRefundStatus,
} from '../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityShortDateTime,
  formatActivityTime,
} from '../presentation/shared/activity-format'

// What the enrolment pages say about server facts. Nothing here decides a status: the server
// sends eligibility, ticket status and enrolment status, and this only words them.

export const ENROLLMENT_TABS: ReadonlyArray<{ status: EnrollmentStatus; label: string }> = [
  { status: 'UPCOMING', label: '待参加' },
  { status: 'FINISHED', label: '已完成' },
  { status: 'CANCELLED', label: '已取消' },
]

// "2015-01" → "2015 年 1 月"
export function formatBirthMonth(birthMonth: string): string {
  const [year, month] = birthMonth.split('-')
  return `${year} 年 ${Number(month)} 月`
}

// The birth months a child can have today: under 18, not in the future.
export function childBirthMonthRange(now: Date): { start: string; end: string } {
  const year = now.getFullYear()
  const month = now.getMonth() + 1
  const pad = (value: number) => String(value).padStart(2, '0')
  // Someone born 18 years ago this month has already turned 18 by the server's month rule.
  const startMonth = month === 12 ? 1 : month + 1
  const startYear = month === 12 ? year - 17 : year - 18
  return { start: `${startYear}-${pad(startMonth)}`, end: `${year}-${pad(month)}` }
}

export interface ParticipantState {
  selectable: boolean
  text: string
}

// The right-hand note of one person on the sign-up form.
export function describeParticipantState(
  participant: Participant,
  ageRange: ActivityAgeRange,
): ParticipantState {
  const reason: ParticipantEligibilityReason = participant.eligibility?.reason ?? 'ELIGIBLE'
  switch (reason) {
    case 'ALREADY_ENROLLED':
      return { selectable: false, text: '已报名本场' }
    case 'UNDER_MIN_AGE':
      return { selectable: false, text: `未满 ${ageRange.minAge} 岁` }
    case 'OVER_MAX_AGE':
      // The account holder is treated as an adult; on a children's activity they only come along.
      return participant.relation === 'SELF'
        ? { selectable: false, text: '陪同不用报名' }
        : { selectable: false, text: `超过 ${ageRange.maxAge} 岁` }
    case 'ELIGIBLE':
      return {
        selectable: true,
        text: ageRange.minAge !== null || ageRange.maxAge !== null ? '符合年龄' : '可报名',
      }
  }
}

// "11 岁" for a child on the day of the session, nothing for the account holder.
export function describeParticipantAge(participant: Participant): string {
  const age = participant.eligibility?.ageAtSession ?? participant.age
  return participant.relation === 'CHILD' && age !== null ? `${age} 岁` : ''
}

// The consent line follows who is being signed up: a guardian speaks for a child.
export function enrollmentNoticeLead(selected: Participant[]): string {
  return selected.some((participant) => participant.relation === 'CHILD')
    ? '我是参与人监护人，已阅读并同意'
    : '我已阅读并同意'
}

// PENDING: a paid sign-up before payment (no code yet). CANCELLED also covers one closed unpaid.
export type TicketTone = 'PENDING' | 'LIVE' | 'USED' | 'EXPIRED' | 'CANCELLED'

export interface TicketView {
  tone: TicketTone
  title: string
  subtitle: string
  signText: string
  hint: string
  // The round mark over a code that can no longer be used.
  stamp: string | null
  // Which of the three steps (报名成功 / 签到 / 完成) are done, and how the middle one reads.
  checkInStep: { label: string; state: 'TODO' | 'DONE' | 'ENDED' }
}

export function describeTicket(ticket: EnrollmentTicket, enrollment: Enrollment): TicketView {
  switch (ticket.status) {
    case 'PENDING_PAYMENT':
      return {
        tone: 'PENDING',
        title: '待付款',
        subtitle: '付款后凭证生效，超时名额自动释放',
        signText: '付款后可签到',
        hint: '付款后生成签到二维码 · 一人一码',
        stamp: null,
        checkInStep: { label: '签到', state: 'TODO' },
      }
    case 'CHECKED_IN': {
      const time = ticket.checkedInAt ? formatActivityShortDateTime(ticket.checkedInAt) : ''
      return {
        tone: 'USED',
        title: '已签到',
        subtitle: '签到成功，祝你参加愉快',
        signText: ticket.checkedInAt ? `已签到 · ${formatActivityTime(ticket.checkedInAt)}` : '已签到',
        hint: `已于 ${time} 核销，不能重复使用`,
        stamp: '已核销',
        checkInStep: { label: '签到', state: 'DONE' },
      }
    }
    case 'EXPIRED':
      return {
        tone: 'EXPIRED',
        title: '已过期',
        subtitle: '活动已结束，没有签到记录',
        signText: '未签到',
        hint: '活动已结束，二维码已失效',
        stamp: '已过期',
        checkInStep: { label: '未签到', state: 'ENDED' },
      }
    case 'CANCELLED':
      if (enrollment.payment.status === 'CLOSED') {
        return {
          tone: 'CANCELLED',
          title: '已关闭，未付款',
          subtitle: '没有在时限内付款或已取消，名额已释放',
          signText: '已关闭',
          hint: '没有付款，未生成签到二维码',
          stamp: '已关闭',
          checkInStep: { label: '签到', state: 'TODO' },
        }
      }
      return {
        tone: 'CANCELLED',
        title: '报名已取消',
        subtitle: ticket.refund ? REFUND_SUBTITLES[ticket.refund.status] : '名额已释放，需要时可以重新报名',
        signText: '已取消',
        hint: '报名已取消，二维码已作废',
        stamp: '已取消',
        checkInStep: { label: '已取消', state: 'ENDED' },
      }
    case 'ACTIVE':
      return {
        tone: 'LIVE',
        title: '报名成功 · 待参加',
        subtitle: '到场出示二维码签到',
        signText: enrollment.session.checkInOpensAt
          ? `待签到 · ${formatActivityTime(enrollment.session.checkInOpensAt)} 起`
          : '待签到',
        hint: '到场出示二维码签到 · 一人一码',
        stamp: null,
        checkInStep: { label: '签到', state: 'TODO' },
      }
  }
}

const REFUND_SUBTITLES: Record<TicketRefundStatus, string> = {
  REQUESTED: '名额已释放，报名费正在原路退回',
  PROCESSING: '名额已释放，报名费正在原路退回',
  SUCCEEDED: '名额已释放，报名费已原路退回',
  CLOSED: '退款没有成功，平台正在人工处理',
  ABNORMAL: '退款没有成功，平台正在人工处理',
}

// The short words of a refund: 退款中 / 已退款 / 退款异常.
export const TICKET_REFUND_LABELS: Record<TicketRefundStatus, string> = {
  REQUESTED: '退款中',
  PROCESSING: '退款中',
  SUCCEEDED: '已退款',
  CLOSED: '退款异常',
  ABNORMAL: '退款异常',
}

export type RefundTone = 'PENDING' | 'DONE' | 'ALERT'

export function describeTicketRefund(refund: TicketRefund): { text: string; tone: RefundTone } {
  const tone: RefundTone = refund.status === 'SUCCEEDED'
    ? 'DONE'
    : refund.status === 'CLOSED' || refund.status === 'ABNORMAL' ? 'ALERT' : 'PENDING'
  return { text: `${TICKET_REFUND_LABELS[refund.status]} ${formatActivityAmount(refund.amountMinor)}`, tone }
}

export interface TicketStep {
  label: string
  state: 'TODO' | 'DONE' | 'ENDED'
}

/**
 * The progress row under the ticket. Free: 报名成功 / 签到 / 完成. Paid adds 已支付 in front, like the
 * board EnrollTicket. `doneWidth` is how far the orange line runs, as a share of the row.
 */
export function describeTicketSteps(
  ticket: EnrollmentTicket,
  enrollment: Enrollment,
): { steps: TicketStep[]; doneWidth: string } {
  const view = describeTicket(ticket, enrollment)
  const finished = enrollment.status === 'FINISHED' && ticket.status === 'CHECKED_IN'
  const tail: TicketStep[] = [view.checkInStep, { label: '完成', state: finished ? 'DONE' : 'TODO' }]
  let steps: TicketStep[]
  switch (enrollment.payment.status) {
    case 'FREE':
      steps = [{ label: '报名成功', state: 'DONE' }, ...tail]
      break
    case 'PAID':
      steps = [{ label: '已支付', state: 'DONE' }, { label: '报名成功', state: 'DONE' }, ...tail]
      break
    case 'PENDING_PAYMENT':
      steps = [{ label: '待付款', state: 'TODO' }, { label: '报名成功', state: 'TODO' }, ...tail]
      break
    case 'CLOSED':
      steps = [{ label: '未付款', state: 'ENDED' }, { label: '报名成功', state: 'TODO' }, ...tail]
      break
  }
  // The line joins the centres of the first and the last finished step.
  let lastDone = -1
  while (lastDone + 1 < steps.length && steps[lastDone + 1].state === 'DONE') lastDone += 1
  const column = 100 / steps.length
  return { steps, doneWidth: lastDone > 0 ? `${(lastDone * column).toFixed(1)}%` : '0' }
}

// What the person gets back when these tickets are cancelled; null when nothing is paid back.
export function cancelRefundMinor(enrollment: Enrollment, ticketCount: number): number | null {
  const unit = enrollment.payment.unitAmountMinor
  return enrollment.payment.status === 'PAID' && unit ? unit * ticketCount : null
}

// The line of the cancel confirmation.
export function describeCancelConfirm(enrollment: Enrollment, ticketCount: number): string {
  if (enrollment.payment.status === 'PENDING_PAYMENT') {
    return '还没有付款，取消后报名关闭、名额释放，不会扣款。'
  }
  const refund = cancelRefundMinor(enrollment, ticketCount)
  if (refund === null) return '取消后名额会让给别人，需要时可以重新报名。'
  const unit = formatActivityAmount(enrollment.payment.unitAmountMinor as number)
  return ticketCount > 1
    ? `取消后名额会让给别人，每人原路退回 ${unit}，共 ${formatActivityAmount(refund)}。`
    : `取消后名额会让给别人，原路退回 ${unit}。`
}

// "14:45" left to pay, or "1:05:09". Never negative.
export function formatPayCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.floor(remainingMs / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const two = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${two(minutes)}:${two(seconds)}` : `${two(minutes)}:${two(seconds)}`
}

export function paymentRemainingMs(enrollment: Enrollment, now: number): number {
  if (enrollment.payment.status !== 'PENDING_PAYMENT' || !enrollment.payment.payBefore) return 0
  return new Date(enrollment.payment.payBefore).getTime() - now
}

// The amount on a card of 我的报名: the total paid (or to pay), or 免费.
export function enrollmentPriceText(enrollment: Enrollment): string {
  return enrollment.payment.amountMinor ? formatActivityAmount(enrollment.payment.amountMinor) : '免费'
}

export interface EnrollmentCardView {
  statusText: string
  statusTone: 'ACTION' | 'UPCOMING' | 'DONE' | 'MUTED'
  who: string
  when: string
  note: string
  noteTone: 'PLAIN' | 'PENDING' | 'DONE' | 'ALERT'
}

// "¥240 已原路退回", "退款中 ¥120", "退款异常": the refunds of a paid card, or nothing.
function describeCardRefunds(enrollment: Enrollment): { note: string; noteTone: EnrollmentCardView['noteTone'] } | null {
  const refunds = enrollment.tickets.flatMap((ticket) => (ticket.refund ? [ticket.refund] : []))
  if (refunds.length === 0) return null
  const total = refunds.reduce((sum, refund) => sum + refund.amountMinor, 0)
  if (refunds.some((refund) => refund.status === 'ABNORMAL' || refund.status === 'CLOSED')) {
    return { note: '退款异常，平台正在人工处理', noteTone: 'ALERT' }
  }
  if (refunds.some((refund) => refund.status !== 'SUCCEEDED')) {
    return { note: `退款中 ${formatActivityAmount(total)} · 原路退回`, noteTone: 'PENDING' }
  }
  return { note: `${formatActivityAmount(total)} 已原路退回`, noteTone: 'DONE' }
}

// One card of 我的报名.
export function describeEnrollmentCard(enrollment: Enrollment): EnrollmentCardView {
  const names = enrollment.tickets.map((ticket) => ticket.participantName).join('、')
  const when = [formatActivityShortDateTime(enrollment.session.startsAt), enrollment.session.label]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
  const who = `参与人：${names}`
  const refunds = describeCardRefunds(enrollment)
  if (enrollment.payment.status === 'CLOSED') {
    return { statusText: '已关闭', statusTone: 'MUTED', who, when, note: '未付款，名额已释放', noteTone: 'PLAIN' }
  }
  if (enrollment.status === 'CANCELLED') {
    return { statusText: '已取消', statusTone: 'MUTED', who, when, note: refunds?.note ?? '', noteTone: refunds?.noteTone ?? 'PLAIN' }
  }
  if (enrollment.payment.status === 'PENDING_PAYMENT') {
    return {
      statusText: '待付款',
      statusTone: 'ACTION',
      who,
      when,
      note: enrollment.payment.payBefore
        ? `请在 ${formatActivityShortDateTime(enrollment.payment.payBefore)} 前付款`
        : '付款后报名生效',
      noteTone: 'PENDING',
    }
  }
  const active = enrollment.tickets.filter((ticket) => ticket.status !== 'CANCELLED')
  const activeWho = `参与人：${active.map((ticket) => ticket.participantName).join('、')}`
  const checkedIn = active.filter((ticket) => ticket.status === 'CHECKED_IN').length
  if (enrollment.status === 'FINISHED') {
    return checkedIn > 0
      ? { statusText: '已完成', statusTone: 'DONE', who: activeWho, when, note: `${checkedIn} 人已签到`, noteTone: 'PLAIN' }
      : { statusText: '未到场', statusTone: 'MUTED', who: activeWho, when, note: '没有签到记录', noteTone: 'PLAIN' }
  }
  // Someone taken off a paid sign-up: the refund matters more than the deadline.
  if (refunds) return { statusText: '待参加', statusTone: 'UPCOMING', who: activeWho, when, ...refunds }
  return {
    statusText: '待参加',
    statusTone: 'UPCOMING',
    who: activeWho,
    when,
    note: enrollment.cancellable
      ? `${formatActivityShortDateTime(enrollment.cancellableUntil)} 前可取消`
      : (checkedIn > 0 ? `${checkedIn} 人已签到` : ''),
    noteTone: 'PLAIN',
  }
}

// The ticket a page opens on: one that can still be used, else the first.
export function pickInitialTicket(enrollment: Enrollment): EnrollmentTicket | null {
  return enrollment.tickets.find((ticket) => ticket.status === 'ACTIVE')
    ?? enrollment.tickets.find((ticket) => ticket.status === 'PENDING_PAYMENT')
    ?? enrollment.tickets.find((ticket) => ticket.status === 'CHECKED_IN')
    ?? enrollment.tickets[0]
    ?? null
}

// A random UUID (version 4 layout) for the Idempotency-Key header.
export function createEnrollmentKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

/**
 * One idempotency key per distinct submission. Tapping submit again after a timeout reuses the
 * key, so the server answers with the first result; changing the session, the people or the amount
 * the page shows (a paid activity whose price changed) makes a new request and gets a new key.
 */
export function createEnrollmentKeyHolder(createKey: () => string) {
  let signature = ''
  let key = ''
  return {
    keyFor(sessionId: string, participantIds: string[], expectedAmountMinor: number | null = null): string {
      const next = `${sessionId}|${[...participantIds].sort().join(',')}|${expectedAmountMinor ?? ''}`
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}
