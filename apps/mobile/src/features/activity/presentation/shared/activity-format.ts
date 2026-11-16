import type {
  ActivityAgeRange,
  ActivityCard,
  ActivityEnrollmentStatus,
  ActivityPrice,
  ActivitySession,
  ActivityType,
} from '../../domain/activity'

// Activities happen in mainland China, so every time is shown in Beijing time whatever the phone's zone is.
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

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

interface BeijingParts {
  year: number
  month: number
  day: number
  weekday: string
  hour: number
  minute: number
}

const pad = (value: number): string => String(value).padStart(2, '0')

// Shifts the instant by eight hours and reads the UTC fields, which avoids relying on Intl time zones in the App webview.
function toBeijingParts(instant: Date): BeijingParts {
  const shifted = new Date(instant.getTime() + BEIJING_OFFSET_MS)
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    weekday: WEEKDAYS[shifted.getUTCDay()],
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
  }
}

export function toBeijingDate(instant: Date): string {
  const parts = toBeijingParts(instant)
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}`
}

// "11/28 周六 09:00"
export function formatActivityDateTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${pad(parts.month)}/${pad(parts.day)} ${parts.weekday} ${pad(parts.hour)}:${pad(parts.minute)}`
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
  return Array.from({ length: days }, (_, index) => {
    const instant = new Date(now.getTime() + index * DAY_MS)
    const parts = toBeijingParts(instant)
    const date = toBeijingDate(instant)
    return {
      date,
      title: index === 0 ? '今天' : index === 1 ? '明天' : parts.weekday,
      subtitle: `${pad(parts.month)}/${pad(parts.day)}`,
      hasSessions: marked.has(date),
    }
  })
}

export function lastActivityStripDate(now: Date, days = ACTIVITY_DATE_STRIP_DAYS): string {
  return toBeijingDate(new Date(now.getTime() + (days - 1) * DAY_MS))
}
