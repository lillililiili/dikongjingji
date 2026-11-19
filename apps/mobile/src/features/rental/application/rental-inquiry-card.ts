import type { RentalInquiry, RentalInquiryStatus } from '../domain/rental'
import { describeRentalLessee, formatRentalAmount, formatRentalInstant } from './rental-format'

// A rental inquiry as one card of 我的 → 需求与报价 (DemandList board, 询价单). The tones are the
// demand list's, so both kinds of card share one look.
export type RentalInquiryTone = 'PENDING' | 'QUOTING' | 'CHOSEN' | 'OFF'

export type RentalInquiryCardAction = 'QUOTE' | 'CLOSE' | 'REINQUIRE' | 'ORDER'

export interface RentalInquiryCardButton {
  action: RentalInquiryCardAction
  label: string
  primary: boolean
}

export interface RentalInquiryCardView {
  kind: '询价单'
  publisher: string
  statusText: string
  tone: RentalInquiryTone
  title: string
  meta: string
  line: string
  buttons: RentalInquiryCardButton[]
}

export const RENTAL_INQUIRY_STATUS_LABELS: Record<RentalInquiryStatus, string> = {
  PENDING: '待报价',
  QUOTED: '报价中',
  EXPIRED: '报价已过期',
  CONFIRMED: '已确认',
  DECLINED: '已关闭',
  CLOSED: '已关闭',
}

function tone(status: RentalInquiryStatus): RentalInquiryTone {
  switch (status) {
    case 'PENDING': return 'PENDING'
    case 'QUOTED': return 'QUOTING'
    case 'CONFIRMED': return 'CHOSEN'
    default: return 'OFF'
  }
}

function line(inquiry: RentalInquiry): string {
  const quote = inquiry.quote
  switch (inquiry.status) {
    case 'PENDING':
      return `等待 ${inquiry.merchant.name} 报价 · 截止 ${formatRentalInstant(inquiry.quoteDeadline)}`
    case 'QUOTED':
      if (!quote) return '已收到报价'
      return quote.scheduleAvailable
        ? `已收到报价 ${formatRentalAmount(quote.totalMinor)} · 有效期至 ${formatRentalInstant(quote.validUntil)}`
        : `已收到报价 · 商家说这段时间不能出租`
    case 'EXPIRED':
      return '报价已过有效期，可以重新询价'
    case 'CONFIRMED':
      return quote ? `已确认合同 · ${formatRentalAmount(quote.totalMinor)}` : '已确认合同'
    case 'DECLINED':
      return inquiry.declineReason ? `商家已拒绝：${inquiry.declineReason}` : '商家已拒绝'
    case 'CLOSED':
      return inquiry.closeReason === 'NO_QUOTE' ? '7 天内商家没有报价' : '你已关闭这份询价'
  }
}

/**
 * Which buttons a card has comes from the server's flags (closable) and the status; tapping the
 * card always opens the quote page (租赁报价单), which also shows a waiting or closed inquiry.
 */
export function describeRentalInquiryCard(inquiry: RentalInquiry): RentalInquiryCardView {
  const buttons: RentalInquiryCardButton[] = []
  if (inquiry.closable) buttons.push({ action: 'CLOSE', label: '关闭询价', primary: false })
  switch (inquiry.status) {
    case 'QUOTED':
      buttons.push({ action: 'QUOTE', label: '查看报价', primary: true })
      break
    case 'CONFIRMED':
      buttons.push({ action: 'QUOTE', label: '查看报价', primary: false })
      if (inquiry.order) buttons.push({ action: 'ORDER', label: '查看订单', primary: true })
      break
    case 'EXPIRED':
    case 'DECLINED':
    case 'CLOSED':
      buttons.push({ action: 'QUOTE', label: '查看报价', primary: false })
      buttons.push({ action: 'REINQUIRE', label: '重新询价', primary: inquiry.status === 'EXPIRED' })
      break
    case 'PENDING':
      buttons.push({ action: 'QUOTE', label: '查看询价', primary: false })
      break
  }
  return {
    kind: '询价单',
    publisher: describeRentalLessee(inquiry.lessee).badge,
    statusText: RENTAL_INQUIRY_STATUS_LABELS[inquiry.status],
    tone: tone(inquiry.status),
    title: `${inquiry.item.productName} 租赁询价`,
    meta: `设备租赁 · ${inquiry.place.cityName || '—'} · ${inquiry.quantity} 台 · ${inquiry.days} 天`,
    line: line(inquiry),
    buttons,
  }
}

// What closing asks first: the board's text once there is a quote, a shorter one before.
export function rentalCloseConfirm(inquiry: RentalInquiry): { title: string; text: string; keep: string; go: string } {
  return {
    title: '确认关闭询价？',
    text: inquiry.quote
      ? '关闭后这份报价失效，商家不再为你保留档期。'
      : '关闭后商家不再报价，需要时重新发起询价。',
    keep: '暂不关闭',
    go: '确认关闭',
  }
}
