import type { RentalInquiry, RentalQuote } from '../domain/rental'
import {
  RENTAL_HANDOVER_LABELS,
  RENTAL_RETURN_LABELS,
  describeRentalLessee,
  formatMonthDay,
  formatRentalAmount,
  formatRentalInstant,
  formatRentalPeriod,
  formatRentalPlace,
  formatRentalReference,
} from './rental-format'

/**
 * Which frame of the RentalQuote board the inquiry is in. The server's flags (confirmable,
 * closable) decide what may be done; the frame only decides what is said.
 */
export type RentalQuoteFrame =
  // Sent, no quote yet.
  | 'WAITING'
  // A quote that can be confirmed.
  | 'OPEN'
  // A quote, but the merchant says the period is not available.
  | 'UNAVAILABLE'
  // A quote past its validity.
  | 'EXPIRED'
  | 'CONFIRMED'
  | 'DECLINED'
  | 'CLOSED'

export function rentalQuoteFrame(inquiry: RentalInquiry): RentalQuoteFrame {
  switch (inquiry.status) {
    case 'PENDING': return 'WAITING'
    case 'EXPIRED': return 'EXPIRED'
    case 'CONFIRMED': return 'CONFIRMED'
    case 'DECLINED': return 'DECLINED'
    case 'CLOSED': return 'CLOSED'
    case 'QUOTED':
      if (inquiry.quote?.expired) return 'EXPIRED'
      return inquiry.quote && !inquiry.quote.scheduleAvailable ? 'UNAVAILABLE' : 'OPEN'
  }
}

export type RentalChipTone = 'ACTION' | 'DONE' | 'MUTED'

// The chip next to 商家报价.
export function rentalQuoteChip(frame: RentalQuoteFrame): { label: string; tone: RentalChipTone } {
  switch (frame) {
    case 'OPEN': return { label: '待确认', tone: 'ACTION' }
    case 'UNAVAILABLE': return { label: '档期不可用', tone: 'MUTED' }
    case 'EXPIRED': return { label: '已过期', tone: 'MUTED' }
    case 'CONFIRMED': return { label: '已确认', tone: 'DONE' }
    case 'DECLINED': return { label: '已拒绝', tone: 'MUTED' }
    case 'CLOSED': return { label: '已关闭', tone: 'MUTED' }
    case 'WAITING': return { label: '待报价', tone: 'ACTION' }
  }
}

export interface RentalQuoteLine {
  key: string
  name: string
  detail: string
  amount: string
  // A fee of 0 is included: "含" in grey.
  included: boolean
}

// 报价明细: the rent, then every fee line as the merchant wrote it.
export function rentalQuoteLines(quote: RentalQuote): RentalQuoteLine[] {
  return [
    {
      key: 'rent',
      name: '租金',
      detail: `${quote.days} 天 × ${formatRentalAmount(quote.dailyRentMinor)} × ${quote.quantity} 台`,
      amount: formatRentalAmount(quote.rentMinor),
      included: false,
    },
    ...quote.fees.map((fee) => ({
      key: `fee-${fee.name}`,
      name: fee.name,
      detail: fee.note ?? '',
      amount: fee.amountMinor > 0 ? formatRentalAmount(fee.amountMinor) : '含',
      included: fee.amountMinor === 0,
    })),
  ]
}

// The orange line under 商家报价: what the quote is based on.
export function rentalQuoteBasis(inquiry: RentalInquiry, quote: RentalQuote): string {
  const reference = inquiry.item.referenceAmountMinor === null
    ? ''
    : `，参考价 ${formatRentalReference(inquiry.item.referenceAmountMinor, inquiry.item.referenceUnit)}`
  return `按你的询价：${quote.quantity} 台、${quote.days} 天，日租金 ${formatRentalAmount(quote.dailyRentMinor)}${reference}`
}

export interface RentalRow {
  key: string
  label: string
  value: string
}

// 租期与档期 (or the inquiry itself while there is no quote).
export function rentalScheduleRows(inquiry: RentalInquiry): RentalRow[] {
  const place = formatRentalPlace(inquiry.place)
  const rows: RentalRow[] = [
    { key: 'period', label: '租期', value: formatRentalPeriod(inquiry.startDate, inquiry.endDate, inquiry.days) },
    { key: 'quantity', label: '数量', value: `${inquiry.quantity} 台 · 设备编号交接时确定` },
    {
      key: 'handover',
      label: '交接',
      value: `${RENTAL_HANDOVER_LABELS[inquiry.handoverMode]} · ${formatMonthDay(inquiry.startDate)} · ${place}`,
    },
    {
      key: 'return',
      label: '归还',
      value: `${RENTAL_RETURN_LABELS[inquiry.returnMode]} · ${formatMonthDay(inquiry.endDate)} 前`,
    },
  ]
  if (inquiry.quote) {
    rows.push({
      key: 'schedule',
      label: '档期',
      value: inquiry.quote.scheduleAvailable
        ? `商家已确认这 ${inquiry.quote.days} 天可以出租`
        : '商家说这段时间不能出租',
    })
  }
  return rows
}

// 询价内容 while waiting for a quote: contact and note too.
export function rentalInquiryRows(inquiry: RentalInquiry): RentalRow[] {
  const rows = rentalScheduleRows(inquiry)
  rows.push({ key: 'contact', label: '联系人', value: inquiry.place.recipientName })
  if (inquiry.note) rows.push({ key: 'note', label: '说明', value: inquiry.note })
  return rows
}

export interface RentalTermRow {
  key: string
  // "押金" when the line starts with "押金：…", else empty.
  label: string
  text: string
}

// The contract terms are free text; a line "押金：…" is shown as label and text like the board.
export function rentalTermRows(terms: string): RentalTermRow[] {
  return terms
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const match = /^([^：:]{1,6})[：:]\s*(.+)$/.exec(line)
      return match
        ? { key: `term-${index}`, label: match[1], text: match[2] }
        : { key: `term-${index}`, label: '', text: line }
    })
}

// 付款节点: the one payment, and why it is or is not due.
export function rentalPayNode(frame: RentalQuoteFrame): { state: string; active: boolean } {
  switch (frame) {
    case 'OPEN': return { state: '合同确认后一次付清', active: true }
    case 'CONFIRMED': return { state: '已确认合同，在租赁订单中付款', active: true }
    case 'UNAVAILABLE': return { state: '档期不可用，不能确认', active: false }
    case 'EXPIRED': return { state: '报价已过期，无需支付', active: false }
    case 'DECLINED': return { state: '商家已拒绝，无需支付', active: false }
    case 'CLOSED': return { state: '询价已关闭，无需支付', active: false }
    case 'WAITING': return { state: '商家报价后确认', active: false }
  }
}

// The status box above the bottom buttons; null when the confirm button speaks for itself.
export function rentalQuoteNotice(inquiry: RentalInquiry, frame: RentalQuoteFrame): { title: string; text: string } | null {
  switch (frame) {
    case 'WAITING':
      return {
        title: '等待商家报价',
        text: `商家报价后在这里确认；${formatRentalInstant(inquiry.quoteDeadline)} 前没有报价，询价自动关闭`,
      }
    case 'UNAVAILABLE':
      return { title: '这段时间不能出租', text: '商家说这段时间排不开，可以联系商家或换个时间重新询价' }
    case 'EXPIRED':
      return { title: '报价已过期', text: '有效期已结束，请重新询价或联系商家更新报价' }
    case 'DECLINED':
      return { title: '商家已拒绝', text: inquiry.declineReason ? `原因：${inquiry.declineReason}` : '可以换个时间或换一家商家重新询价' }
    case 'CLOSED':
      return inquiry.closeReason === 'NO_QUOTE'
        ? { title: '询价已关闭', text: '7 天内商家没有报价，询价已自动关闭' }
        : { title: '询价已关闭', text: '商家报价已失效，需要时重新发起询价' }
    case 'CONFIRMED':
      return { title: '已确认合同', text: '租赁订单已生成，付款、交接和归还在订单里进行' }
    case 'OPEN':
      return null
  }
}

// The line under 商家报价: when it was quoted and until when it holds.
export function rentalQuoteValidity(quote: RentalQuote): string {
  return quote.expired
    ? `报价已于 ${formatRentalInstant(quote.validUntil)} 过期`
    : `报价有效期至 ${formatRentalInstant(quote.validUntil)}`
}

export function rentalHeaderBadge(inquiry: RentalInquiry): string {
  return describeRentalLessee(inquiry.lessee).badge
}

export interface RentalConfirmFailureInput {
  status: number
  code: string | null
  message: string
}

export interface RentalConfirmFailure {
  message: string
  // Read the inquiry again before anything else.
  reload: boolean
  unauthorized?: boolean
}

export function describeRentalConfirmFailure(failure: RentalConfirmFailureInput | null): RentalConfirmFailure {
  if (!failure) return { message: '没有确认成功，请稍后重试', reload: false }
  if (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED') {
    return { message: '', reload: false, unauthorized: true }
  }
  switch (failure.code) {
    case 'RENTAL_QUOTE_CHANGED':
      return { message: '商家已更新报价，已刷新，请核对最新报价后再确认', reload: true }
    case 'RENTAL_VERSION_CONFLICT':
      return { message: '询价单有变化，已刷新，请核对后再确认', reload: true }
    case 'RENTAL_QUOTE_EXPIRED':
      return { message: '报价已过期，不能再确认', reload: true }
    case 'RENTAL_SCHEDULE_UNAVAILABLE':
      return { message: '商家说这段时间不能出租，不能确认', reload: true }
    case 'RENTAL_START_DATE_PASSED':
      return { message: '起租日已过，请重新询价', reload: true }
    case 'RENTAL_PAYMENT_UNAVAILABLE':
      return { message: '出租方暂未开通在线收款，暂时不能确认', reload: false }
    case 'RENTAL_STATE_INVALID':
      return { message: '询价单状态已变化，已刷新', reload: true }
    case 'RENTAL_TERMS_NOT_AGREED':
      return { message: '请先勾选“我已核对租期、费用与合同条款”', reload: false }
    default:
      break
  }
  if (failure.status === 412) return { message: '询价单有变化，已刷新，请核对后再确认', reload: true }
  if (failure.status === 404) return { message: '这份询价单已不存在', reload: true }
  if (failure.status === 0) return { message: '网络连接失败，请稍后重试', reload: false }
  return { message: failure.message || '没有确认成功，请稍后重试', reload: false }
}
