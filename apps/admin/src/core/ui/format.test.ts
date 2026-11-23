import { describe, expect, it } from 'vitest'

import { formatDate, formatMoney, minorToYuanText, yuanToMinor } from './format'

describe('金额', () => {
  it('分显示成元，千分位、两位小数', () => {
    expect(formatMoney(0)).toBe('¥0.00')
    expect(formatMoney(5)).toBe('¥0.05')
    expect(formatMoney(123456789)).toBe('¥1,234,567.89')
    expect(formatMoney(-4100)).toBe('-¥41.00')
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(1.5)).toBe('—')
  })

  it('输入的元换成分，不经过浮点数；格式不对返回 null', () => {
    expect(yuanToMinor('12')).toBe(1200)
    expect(yuanToMinor(' 12.5 ')).toBe(1250)
    expect(yuanToMinor('0.07')).toBe(7)
    expect(yuanToMinor('19.99')).toBe(1999)
    for (const bad of ['', '-1', '1.234', 'abc', '1e3', '.5', '12.']) {
      expect(yuanToMinor(bad), bad).toBeNull()
    }
    expect(minorToYuanText(1999)).toBe('19.99')
    expect(minorToYuanText(5)).toBe('0.05')
  })

  it('日期按北京时间', () => {
    expect(formatDate('2026-11-22T17:30:00Z')).toBe('2026-11-23')
    expect(formatDate(null)).toBe('—')
  })
})
