import { DAY_MS, formatBeijingTime, padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type {
  RentalHandoverMode,
  RentalLessee,
  RentalPlace,
  RentalReferenceUnit,
  RentalReturnMode,
} from '../domain/rental'

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

export const RENTAL_HANDOVER_LABELS: Record<RentalHandoverMode, string> = {
  DELIVERY: '商家送达',
  PICKUP: '到店自提',
}

export const RENTAL_RETURN_LABELS: Record<RentalReturnMode, string> = {
  COLLECT: '商家上门取回',
  DROP_OFF: '送回门店',
}

const UNIT_LABELS: Record<RentalReferenceUnit, string> = {
  DAY: '天',
  HALF_DAY: '半天',
  ITEM: '次',
}

// ¥1,900 / ¥1,900.50
export function formatRentalAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

// "¥380/天 起"; a device without a listed price is quoted by the merchant.
export function formatRentalReference(amountMinor: number | null, unit: RentalReferenceUnit): string {
  return amountMinor === null ? '价格以商家报价为准' : `${formatRentalAmount(amountMinor)}/${UNIT_LABELS[unit]} 起`
}

// A Beijing calendar day YYYY-MM-DD as a UTC midnight, for arithmetic only.
function dayValue(date: string): number {
  const [year, month, day] = date.split('-').map(Number)
  return Date.UTC(year, (month || 1) - 1, day || 1)
}

function fromDayValue(value: number): string {
  const instant = new Date(value)
  return `${instant.getUTCFullYear()}-${padTwo(instant.getUTCMonth() + 1)}-${padTwo(instant.getUTCDate())}`
}

export function addRentalDays(date: string, days: number): string {
  return fromDayValue(dayValue(date) + days * DAY_MS)
}

// Today in Beijing as YYYY-MM-DD.
export function beijingToday(now: Date): string {
  const parts = toBeijingParts(now)
  return `${parts.year}-${padTwo(parts.month)}-${padTwo(parts.day)}`
}

// Whole days from `from` to `to` (both YYYY-MM-DD).
export function daysBetween(from: string, to: string): number {
  return Math.round((dayValue(to) - dayValue(from)) / DAY_MS)
}

// "11/22"
export function formatMonthDay(date: string): string {
  if (!date) return ''
  const instant = new Date(dayValue(date))
  return `${padTwo(instant.getUTCMonth() + 1)}/${padTwo(instant.getUTCDate())}`
}

// "周六"
export function weekdayOf(date: string): string {
  return WEEKDAYS[new Date(dayValue(date)).getUTCDay()]
}

// "11/22–11/26 · 共 5 天"
export function formatRentalPeriod(startDate: string, endDate: string, days: number): string {
  return `${formatMonthDay(startDate)}–${formatMonthDay(endDate)} · 共 ${days} 天`
}

// "11/22 18:00" in Beijing time.
export function formatRentalInstant(iso: string): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

export interface RentalLesseeView {
  // 企业 / 个人, the small tag.
  tag: string
  // "东营港务物流（经办人 王女士）" / "王女士"
  text: string
  // "企业 · 东营港务物流" / "个人", the badge of the page header.
  badge: string
}

export function describeRentalLessee(lessee: RentalLessee): RentalLesseeView {
  if (lessee.kind === 'ORGANIZATION' && lessee.organizationName) {
    return {
      tag: '企业',
      text: lessee.contactName ? `${lessee.organizationName}（经办人 ${lessee.contactName}）` : lessee.organizationName,
      badge: `企业 · ${lessee.organizationName}`,
    }
  }
  return { tag: '个人', text: lessee.contactName || '个人', badge: '个人' }
}

// "东营市 东营港 示例港路 6 号"
export function formatRentalPlace(place: RentalPlace): string {
  return [place.cityName, place.detail].filter(Boolean).join(' ')
}
