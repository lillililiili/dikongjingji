/**
 * 金额：接口里一律是分（整数），页面上按元显示和输入。换算只在这里做，不用浮点乘除后直接取整。
 */

/** 分 → “¥1,299.00”；null 显示为“—”。 */
export function formatYuan(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return '—'
  const negative = minor < 0
  const abs = Math.abs(minor)
  const yuan = Math.floor(abs / 100).toLocaleString('en-US')
  const fen = String(abs % 100).padStart(2, '0')
  return `${negative ? '-' : ''}¥${yuan}.${fen}`
}

/** 分 → 输入框里的元（数字，可能带两位小数）。 */
export function minorToYuan(minor: number | null | undefined): number | null {
  if (minor === null || minor === undefined) return null
  return minor / 100
}

/**
 * 输入框里的元 → 分。最多两位小数；空、负数、超过两位小数都返回 null（由表单规则先挡住）。
 * 按字符串拆整数和小数部分，避免 19.9 * 100 = 1989.9999 这类误差。
 */
export function yuanToMinor(yuan: number | string | null | undefined): number | null {
  if (yuan === null || yuan === undefined) return null
  const text = String(yuan).trim()
  if (text === '') return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(text)
  if (!match) return null
  const whole = Number(match[1])
  const fraction = Number((match[2] ?? '').padEnd(2, '0'))
  return whole * 100 + fraction
}
