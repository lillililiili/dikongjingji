import type { ActivityAgeRange } from '../domain/activity'
import type {
  Enrollment,
  EnrollmentStatus,
  EnrollmentTicket,
  Participant,
  ParticipantEligibilityReason,
} from '../domain/enrollment'
import {
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

export type TicketTone = 'LIVE' | 'USED' | 'EXPIRED' | 'CANCELLED'

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
      return {
        tone: 'CANCELLED',
        title: '报名已取消',
        subtitle: '名额已释放，需要时可以重新报名',
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

export interface EnrollmentCardView {
  statusText: string
  statusTone: 'UPCOMING' | 'DONE' | 'MUTED'
  who: string
  when: string
  note: string
}

// One card of 我的报名.
export function describeEnrollmentCard(enrollment: Enrollment): EnrollmentCardView {
  const names = enrollment.tickets.map((ticket) => ticket.participantName).join('、')
  const when = [formatActivityShortDateTime(enrollment.session.startsAt), enrollment.session.label]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
  const who = `参与人：${names}`
  if (enrollment.status === 'CANCELLED') {
    return { statusText: '已取消', statusTone: 'MUTED', who, when, note: '' }
  }
  const active = enrollment.tickets.filter((ticket) => ticket.status !== 'CANCELLED')
  const activeWho = `参与人：${active.map((ticket) => ticket.participantName).join('、')}`
  const checkedIn = active.filter((ticket) => ticket.status === 'CHECKED_IN').length
  if (enrollment.status === 'FINISHED') {
    return checkedIn > 0
      ? { statusText: '已完成', statusTone: 'DONE', who: activeWho, when, note: `${checkedIn} 人已签到` }
      : { statusText: '未到场', statusTone: 'MUTED', who: activeWho, when, note: '没有签到记录' }
  }
  return {
    statusText: '待参加',
    statusTone: 'UPCOMING',
    who: activeWho,
    when,
    note: enrollment.cancellable
      ? `${formatActivityShortDateTime(enrollment.cancellableUntil)} 前可取消`
      : (checkedIn > 0 ? `${checkedIn} 人已签到` : ''),
  }
}

// The ticket a page opens on: one that can still be used, else the first.
export function pickInitialTicket(enrollment: Enrollment): EnrollmentTicket | null {
  return enrollment.tickets.find((ticket) => ticket.status === 'ACTIVE')
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
 * key, so the server answers with the first result; changing the session or the people makes a
 * new request and gets a new key.
 */
export function createEnrollmentKeyHolder(createKey: () => string) {
  let signature = ''
  let key = ''
  return {
    keyFor(sessionId: string, participantIds: string[]): string {
      const next = `${sessionId}|${[...participantIds].sort().join(',')}`
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}
