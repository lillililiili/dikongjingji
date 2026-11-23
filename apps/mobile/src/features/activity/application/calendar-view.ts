import type { ActivitySession } from '../domain/activity'
import { padTwo, toBeijingDate, toBeijingParts } from '../../../shared/time/beijing-time'
import { formatActivityTime } from '../presentation/shared/activity-format'

// 选择日期 (board CalendarPick, S5-3): a whole month of one activity's sessions, Sunday first, in Beijing
// time. A day can be picked when it has a session someone can still sign up for (or move to); a day whose
// sessions are all full or closed is greyed with 已满 / 已截止; days before today and days without a
// session are plain and cannot be picked. Months run from this month to the month of the last session.

export interface YearMonth {
  year: number
  // 1–12
  month: number
}

export type CalendarDayState = 'OPEN' | 'FULL' | 'NONE' | 'PAST'

export interface CalendarDay {
  // YYYY-MM-DD
  date: string
  day: number
  state: CalendarDayState
  today: boolean
  // "今天" / "已满" / "已截止" / "".
  sub: string
}

export interface CalendarCell {
  key: string
  day: CalendarDay | null
}

export const WEEKDAY_TITLES = ['日', '一', '二', '三', '四', '五', '六'] as const

export function dateOfSession(session: ActivitySession): string {
  return toBeijingDate(new Date(session.startsAt))
}

// Whether a session can be picked for `people` (1 when unknown) and is not the one being left.
export function isSessionPickable(session: ActivitySession, people: number, exceptSessionId: string | null): boolean {
  if (session.sessionId === exceptSessionId) return false
  if (session.status === 'OPEN') return session.seatsLeft >= Math.max(people, 1)
  // A full one still takes a waitlist from the sign-up page.
  return session.status === 'FULL_WAITLIST' && exceptSessionId === null
}

export function monthOf(date: string): YearMonth {
  const [year, month] = date.split('-').map(Number)
  return { year, month }
}

export function addMonths(value: YearMonth, delta: number): YearMonth {
  const index = value.year * 12 + (value.month - 1) + delta
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

export function compareMonths(a: YearMonth, b: YearMonth): number {
  return (a.year - b.year) * 12 + (a.month - b.month)
}

export function formatMonthTitle(value: YearMonth): string {
  return `${value.year} 年 ${value.month} 月`
}

// The months the calendar can show: this month up to the month of the last session still ahead.
export function calendarMonthRange(sessions: readonly ActivitySession[], now: Date): { first: YearMonth; last: YearMonth } {
  const first = monthOf(toBeijingDate(now))
  const ahead = sessions.filter((session) => Date.parse(session.startsAt) > now.getTime()).map(dateOfSession).sort()
  const last = ahead.length > 0 ? monthOf(ahead[ahead.length - 1]) : first
  return { first, last: compareMonths(last, first) < 0 ? first : last }
}

export function buildCalendarMonth(
  month: YearMonth,
  sessions: readonly ActivitySession[],
  now: Date,
  people: number,
  exceptSessionId: string | null,
): CalendarCell[] {
  const today = toBeijingDate(now)
  const byDate = new Map<string, ActivitySession[]>()
  for (const session of sessions) {
    if (session.sessionId === exceptSessionId) continue
    const date = dateOfSession(session)
    byDate.set(date, [...(byDate.get(date) ?? []), session])
  }
  // The weekday of a calendar date does not depend on the time zone.
  const leading = new Date(Date.UTC(month.year, month.month - 1, 1)).getUTCDay()
  const daysInMonth = new Date(Date.UTC(month.year, month.month, 0)).getUTCDate()
  const cells: CalendarCell[] = []
  for (let index = 0; index < leading; index++) cells.push({ key: `blank-${index}`, day: null })
  for (let day = 1; day <= daysInMonth; day++) {
    const date = `${month.year}-${padTwo(month.month)}-${padTwo(day)}`
    const daySessions = byDate.get(date) ?? []
    let state: CalendarDayState
    let sub = ''
    if (date < today) {
      state = 'PAST'
    } else if (daySessions.some((session) => isSessionPickable(session, people, exceptSessionId))) {
      state = 'OPEN'
    } else if (daySessions.length > 0) {
      state = 'FULL'
      sub = daySessions.every((session) => session.status === 'CLOSED' || session.status === 'ENDED') ? '已截止' : '已满'
    } else {
      state = 'NONE'
    }
    if (date === today && state !== 'FULL') sub = '今天'
    cells.push({ key: date, day: { date, day, state, today: date === today, sub } })
  }
  return cells
}

export interface DaySessionOption {
  sessionId: string
  // "上午 09:00"
  title: string
  // "余 30" / "已满"
  sub: string
  pickable: boolean
}

export function describeDaySessions(
  date: string,
  sessions: readonly ActivitySession[],
  people: number,
  exceptSessionId: string | null,
): DaySessionOption[] {
  return sessions
    .filter((session) => session.sessionId !== exceptSessionId && dateOfSession(session) === date)
    .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))
    .map((session) => {
      const pickable = isSessionPickable(session, people, exceptSessionId)
      let sub: string
      if (session.status === 'OPEN') sub = `余 ${session.seatsLeft}`
      else if (session.status === 'FULL_WAITLIST') sub = exceptSessionId === null ? '已满 · 可候补' : '已满'
      else if (session.status === 'FULL') sub = '已满'
      else sub = '已截止'
      return {
        sessionId: session.sessionId,
        title: `${session.label ? `${session.label} ` : ''}${formatActivityTime(session.startsAt)}`,
        sub,
        pickable,
      }
    })
}

// "11月22日 周日"
export function formatPickedDay(date: string): string {
  const parts = toBeijingParts(new Date(`${date}T04:00:00Z`))
  return `${parts.month}月${parts.day}日 ${parts.weekday}`
}
