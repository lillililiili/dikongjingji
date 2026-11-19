import type { MallInquiry } from '../domain/inquiry'
import { formatMallAmount } from '../presentation/shared/mall-format'
import { describeMallInquiryBuyer } from './inquiry-form'
import {
  MALL_INQUIRY_STATUS_LABELS,
  closeReasonText,
  formatInquiryInstant,
  requirementHeadline,
} from './inquiry-view'

// A goods inquiry as one card of 我的 → 需求与报价 (DemandList board, 询价单). The tones are the
// demand list's, so every kind of card shares one look.
export type MallInquiryTone = 'PENDING' | 'QUOTING' | 'CHOSEN' | 'OFF'

export type MallInquiryCardAction = 'QUOTES' | 'CLOSE' | 'REINQUIRE' | 'ORDER'

export interface MallInquiryCardButton {
  action: MallInquiryCardAction
  label: string
  primary: boolean
}

export interface MallInquiryCardView {
  kind: '询价单'
  publisher: string
  statusText: string
  tone: MallInquiryTone
  title: string
  meta: string
  line: string
  buttons: MallInquiryCardButton[]
}

function tone(inquiry: MallInquiry): MallInquiryTone {
  switch (inquiry.status) {
    case 'OPEN': return 'PENDING'
    case 'QUOTED': return 'QUOTING'
    case 'CHOSEN': return 'CHOSEN'
    case 'CLOSED': return 'OFF'
  }
}

function line(inquiry: MallInquiry): string {
  switch (inquiry.status) {
    case 'OPEN':
      return inquiry.quotingOpen
        ? `等待商家报价 · 截止 ${formatInquiryInstant(inquiry.quoteDeadline)}`
        : '截止前没有商家报价'
    case 'QUOTED':
      return inquiry.quotingOpen
        ? `已收到 ${inquiry.quoteCount} 份报价 · 截止 ${formatInquiryInstant(inquiry.quoteDeadline)}`
        : `已收到 ${inquiry.quoteCount} 份报价 · 报价已截止`
    case 'CHOSEN':
      return inquiry.chosen
        ? `已选定 ${inquiry.chosen.merchant.name} · ${formatMallAmount(inquiry.chosen.totalMinor)}`
        : '已选定报价'
    case 'CLOSED':
      return closeReasonText(inquiry)
  }
}

/**
 * Which buttons a card has comes from the server's flags (closable) and the status; tapping the
 * card always opens the quotes page (报价比选), which also shows a waiting or closed inquiry and
 * holds 转为订单.
 */
export function describeMallInquiryCard(inquiry: MallInquiry): MallInquiryCardView {
  const buttons: MallInquiryCardButton[] = []
  if (inquiry.closable) buttons.push({ action: 'CLOSE', label: '关闭询价', primary: false })
  switch (inquiry.status) {
    case 'OPEN':
      buttons.push({ action: 'QUOTES', label: '查看询价', primary: false })
      break
    case 'QUOTED':
      buttons.push({ action: 'QUOTES', label: '比选报价', primary: true })
      break
    case 'CHOSEN':
      if (inquiry.chosen?.orderId) {
        buttons.push({ action: 'QUOTES', label: '比选报价', primary: false })
        buttons.push({ action: 'ORDER', label: '查看订单', primary: true })
      } else {
        buttons.push({ action: 'QUOTES', label: '比选报价', primary: true })
      }
      break
    case 'CLOSED':
      buttons.push({ action: 'QUOTES', label: '查看询价', primary: false })
      buttons.push({ action: 'REINQUIRE', label: '重新询价', primary: true })
      break
  }
  return {
    kind: '询价单',
    publisher: describeMallInquiryBuyer(inquiry.buyer).badge,
    statusText: MALL_INQUIRY_STATUS_LABELS[inquiry.status],
    tone: tone(inquiry),
    title: inquiry.product.name,
    meta: [requirementHeadline(inquiry.requirement), `${inquiry.quantity} 台`].filter(Boolean).join(' · '),
    line: line(inquiry),
    buttons,
  }
}
