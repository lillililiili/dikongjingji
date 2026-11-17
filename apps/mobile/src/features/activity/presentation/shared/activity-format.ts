import type {
  ActivityAgeRange,
  ActivityCard,
  ActivityEnrollmentStatus,
  ActivityPrice,
  ActivitySession,
  ActivityType,
} from '../../domain/activity'

import {
  DAY_MS,
  buildBeijingDayOptions,
  padTwo as pad,
  toBeijingDate,
  toBeijingParts,
} from '../../../../shared/time/beijing-time'

export { toBeijingDate }

export const ACTIVITY_TYPE_LABELS: Record<ActivityType, string> = {
  CONTEST: '赛事',
  TRAINING: '培训',
  STUDY_TOUR: '研学',
  OPEN_DAY: '体验日',
}

export const ACTIVITY_STATUS_LABELS: Record<ActivityEnrollmentStatus, string> = {
  OPEN: '报名中',
  FULL_WAITLIST: '已满 · 可候补',
  FULL: '已满',
  CLOSED: '报名截止',
  ENDED: '已结束',
}

// "11/28 周六 09:00"
export function formatActivityDateTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${pad(parts.month)}/${pad(parts.day)} ${parts.weekday} ${pad(parts.hour)}:${pad(parts.minute)}`
}

// "11/28 周六"
export function formatActivityDay(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${pad(parts.month)}/${pad(parts.day)} ${parts.weekday}`
}

// "11/27 18:00"
export function formatActivityShortDateTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${pad(parts.month)}/${pad(parts.day)} ${pad(parts.hour)}:${pad(parts.minute)}`
}

export function formatActivityTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${pad(parts.hour)}:${pad(parts.minute)}`
}

export function formatActivityPrice(price: ActivityPrice): string {
  if (price.mode === 'FREE') return '免费'
  if (price.mode === 'INQUIRY' || price.amountMinor === null) return '询价'
  const yuan = price.amountMinor / 100
  // Whole yuan stays short; anything else keeps both decimals.
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

export function isPerPersonPrice(price: ActivityPrice): boolean {
  return price.mode === 'PER_PERSON' && price.amountMinor !== null
}

export function formatActivityAgeRange(range: ActivityAgeRange): string | null {
  if (range.minAge !== null && range.maxAge !== null) return `${range.minAge}–${range.maxAge} 岁`
  if (range.minAge !== null) return `${range.minAge} 岁以上`
  if (range.maxAge !== null) return `${range.maxAge} 岁以下`
  return null
}

// What a list card says about seats: the count while enrolment is open, the status otherwise.
export function formatActivityCardStatus(card: ActivityCard): string {
  if (card.enrollmentStatus === 'OPEN' && card.nextSession) {
    return `余 ${card.nextSession.seatsLeft}/${card.nextSession.capacity}`
  }
  return ACTIVITY_STATUS_LABELS[card.enrollmentStatus]
}

// "11/28 周六 09:00 · 东营低空与机器人体验馆", with the organiser's schedule wording first when there is one.
export function formatActivityCardSchedule(card: ActivityCard): string {
  const when = card.nextSession ? formatActivityDateTime(card.nextSession.startsAt) : null
  return [card.scheduleNote, when, card.venueName]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
}

export function formatActivitySessionSeats(session: ActivitySession): string {
  return session.status === 'OPEN'
    ? `余 ${session.seatsLeft}`
    : ACTIVITY_STATUS_LABELS[session.status]
}

export interface ActivityDateOption {
  // YYYY-MM-DD in Beijing time.
  date: string
  title: string
  subtitle: string
  hasSessions: boolean
}

export const ACTIVITY_DATE_STRIP_DAYS = 14

export function buildActivityDateOptions(
  now: Date,
  datesWithSessions: readonly string[],
  days = ACTIVITY_DATE_STRIP_DAYS,
): ActivityDateOption[] {
  const marked = new Set(datesWithSessions)
  return buildBeijingDayOptions(now, days)
    .map((option) => ({ ...option, hasSessions: marked.has(option.date) }))
}

export function lastActivityStripDate(now: Date, days = ACTIVITY_DATE_STRIP_DAYS): string {
  return toBeijingDate(new Date(now.getTime() + (days - 1) * DAY_MS))
}
