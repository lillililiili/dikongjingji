const pad = (value: number): string => String(value).padStart(2, '0')

function parseInstant(value: string): Date | null {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

// A plain date has no time zone, so it is read as written rather than through
// Date, which would shift it a day west of UTC.
export function formatMonthDay(value: string): string {
  const plainDate = /^\d{4}-(\d{2})-(\d{2})$/.exec(value)
  if (plainDate) return `${plainDate[1]}/${plainDate[2]}`
  const date = parseInstant(value)
  return date ? `${pad(date.getMonth() + 1)}/${pad(date.getDate())}` : value
}

export function formatClock(value: string): string {
  const date = parseInstant(value)
  return date ? `${pad(date.getHours())}:${pad(date.getMinutes())}` : value
}

export function formatMonthDayTime(value: string): string {
  return parseInstant(value)
    ? `${formatMonthDay(value)} ${formatClock(value)}`
    : value
}

export function formatDateRange(dates: string[]): string {
  if (!dates.length) return ''
  const first = formatMonthDay(dates[0])
  const last = formatMonthDay(dates[dates.length - 1])
  return first === last ? first : `${first}–${last}`
}

export function formatDistance(meters: number): string {
  return meters >= 1000
    ? `${(meters / 1000).toFixed(1).replace(/\.0$/, '')} km`
    : `${meters} m`
}

export function formatFileSize(sizeBytes: number): string {
  return sizeBytes >= 1024 * 1024
    ? `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(sizeBytes / 1024))} KB`
}
