/** 运营端的时间一律按北京时间显示（和 App 一致，不跟电脑时区走）。 */
const DATE_TIME = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const parts = Object.fromEntries(DATE_TIME.formatToParts(date).map((part) => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 字符数按 Unicode 码点算（和后端 codePointCount 一致，一个汉字算一个）。 */
export function charCount(value: string): number {
  return Array.from(value).length
}

/** 金额（分）显示成元：¥1,234.50。金额只用整数分，不用浮点数参与计算。 */
export function formatMoney(minor: number | null | undefined): string {
  if (minor === null || minor === undefined || !Number.isInteger(minor)) return '—'
  const negative = minor < 0
  const absolute = Math.abs(minor)
  const yuan = Math.floor(absolute / 100).toLocaleString('en-US')
  const fen = String(absolute % 100).padStart(2, '0')
  return `${negative ? '-' : ''}¥${yuan}.${fen}`
}

/**
 * 输入框里的元（“12”“12.5”“12.50”）换成分；格式不对（负数、超过两位小数、不是数字）返回 null。
 * 按字符串拆整数和小数部分，不经过浮点数。
 */
export function yuanToMinor(text: string): number | null {
  const value = text.trim()
  const match = /^(\d{1,10})(?:\.(\d{1,2}))?$/.exec(value)
  if (!match) return null
  const fen = (match[2] ?? '').padEnd(2, '0')
  return Number(match[1]) * 100 + Number(fen)
}

/** 分换成输入框里的元（“12.50”），给改价时预填。 */
export function minorToYuanText(minor: number): string {
  return `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`
}

/** 北京时间的日期（2026-11-23）。 */
export function formatDate(value: string | null | undefined): string {
  return formatDateTime(value).slice(0, 10)
}
