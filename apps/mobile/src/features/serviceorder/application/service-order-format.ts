import { formatBeijingTime, padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type { ServiceOrderCustomer, ServiceOrderLine, ServiceOrderType } from '../domain/service-order'

export const SERVICE_ORDER_TYPE_LABELS: Record<ServiceOrderType, string> = {
  AERIAL: '航拍',
  SURVEY: '测绘',
  MAINTENANCE: '设备维护',
  TECH_SUPPORT: '技术支持',
}

// ¥1,900 / ¥1,900.50
export function formatServiceAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

// "11/22 18:00" in Beijing time; '' for no time.
export function formatServiceInstant(iso: string | null | undefined): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

// "11/22": the Beijing day of an instant.
export function formatServiceDay(iso: string | null | undefined): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

// "11/26" from a calendar day YYYY-MM-DD.
export function formatServiceDate(date: string | null | undefined): string {
  if (!date) return ''
  const [, month, day] = date.split('-')
  return month && day ? `${month}/${day}` : ''
}

// "18:00" in Beijing time.
export function formatServiceClock(iso: string | null | undefined): string {
  return iso ? formatBeijingTime(iso) : ''
}

export interface ServiceOrderCustomerView {
  // 企业 / 个人, the small blue tag.
  tag: string
  // "东营港务物流（经办人 王女士）" / "王女士"
  text: string
  // "企业 · 东营港务物流" / "个人"
  badge: string
}

export function describeServiceOrderCustomer(customer: ServiceOrderCustomer): ServiceOrderCustomerView {
  if (customer.kind === 'ORGANIZATION' && customer.organizationName) {
    return {
      tag: '企业',
      text: customer.contactName
        ? `${customer.organizationName}（经办人 ${customer.contactName}）`
        : customer.organizationName,
      badge: `企业 · ${customer.organizationName}`,
    }
  }
  return { tag: '个人', text: customer.contactName || '个人', badge: '个人' }
}

export interface ServiceOrderLineView {
  key: string
  name: string
  // "6 次 × ¥1,800"; '' for a line not counted by the unit.
  quantity: string
  amount: string
}

// One line of the quote as the PlanConfirm and PlanCompare boards draw it.
export function describeServiceOrderLine(line: ServiceOrderLine, index: number): ServiceOrderLineView {
  const counted = line.quantity !== null && line.unitAmountMinor !== null
  return {
    key: `${index}-${line.name}`,
    name: line.name,
    quantity: counted
      ? `${line.quantity}${line.unit ? ` ${line.unit}` : ''} × ${formatServiceAmount(line.unitAmountMinor as number)}`
      : '',
    amount: formatServiceAmount(line.amountMinor),
  }
}

// "2.4 MB" / "860 KB"
export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace(/\.0$/, '')} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

export type ServiceOrderFileKind = 'DOC' | 'IMAGE' | 'VIDEO' | 'ARCHIVE' | 'SHEET'

// The icon of a delivered file, by its type (DeliveryOne board: report, table, photos).
export function serviceOrderFileKind(contentType: string, name: string): ServiceOrderFileKind {
  const type = contentType.toLowerCase()
  const lower = name.toLowerCase()
  if (type.startsWith('image/')) return 'IMAGE'
  if (type.startsWith('video/')) return 'VIDEO'
  if (type.includes('sheet') || type.includes('excel') || lower.endsWith('.xlsx')) return 'SHEET'
  if (type.includes('zip') && !lower.endsWith('.docx') && !lower.endsWith('.pptx')) return 'ARCHIVE'
  return 'DOC'
}

// The board paths of the file icons (DeliveryOne).
export const SERVICE_ORDER_FILE_ICONS: Record<ServiceOrderFileKind, string> = {
  DOC: 'M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6',
  SHEET: 'M4 5h16v14H4zM4 10h16M4 15h16M10 5v14',
  IMAGE: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 9.5h.01',
  VIDEO: 'M4 6h12v12H4zM16 10l4-2v8l-4-2',
  ARCHIVE: 'M6 3h12v18H6zM11 3v2h2V3M11 7h2M11 9h2M11 11h2v3h-2z',
}
