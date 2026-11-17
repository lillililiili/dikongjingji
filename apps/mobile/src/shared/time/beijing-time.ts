// Activities and venues are in mainland China, so their times are shown in Beijing time whatever the
// phone's zone is. The instant is shifted by eight hours and read through the UTC getters, which
// avoids relying on Intl time zones in the App webview.
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

export const DAY_MS = 24 * 60 * 60 * 1000

export interface BeijingParts {
  year: number
  month: number
  day: number
  weekday: string
  hour: number
  minute: number
}

export const padTwo = (value: number): string => String(value).padStart(2, '0')

export function toBeijingParts(instant: Date): BeijingParts {
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

// YYYY-MM-DD
export function toBeijingDate(instant: Date): string {
  const parts = toBeijingParts(instant)
  return `${parts.year}-${padTwo(parts.month)}-${padTwo(parts.day)}`
}

// HH:mm
export function formatBeijingTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

export interface BeijingDayOption {
  // YYYY-MM-DD in Beijing time.
  date: string
  title: string
  subtitle: string
}

// Today, tomorrow, then weekdays: the day tabs of the date strips.
export function buildBeijingDayOptions(now: Date, days: number): BeijingDayOption[] {
  return Array.from({ length: days }, (_, index) => {
    const instant = new Date(now.getTime() + index * DAY_MS)
    const parts = toBeijingParts(instant)
    return {
      date: toBeijingDate(instant),
      title: index === 0 ? '今天' : index === 1 ? '明天' : parts.weekday,
      subtitle: `${padTwo(parts.month)}/${padTwo(parts.day)}`,
    }
  })
}
