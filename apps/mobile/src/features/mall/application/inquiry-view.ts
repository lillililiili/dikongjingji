import { padTwo, toBeijingParts, formatBeijingTime } from '../../../shared/time/beijing-time'

import type { CityDirectory } from '../../../shared/city/domain/city-directory'

import type {
  MallInquiry,
  MallInquiryChosen,
  MallInquiryInvitation,
  MallInquiryQuote,
  MallInquirySort,
  MallInquiryStatus,
} from '../domain/inquiry'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../presentation/shared/mall-format'
import { formatExpectedDate } from './inquiry-form'

export const MALL_INQUIRY_STATUS_LABELS: Record<MallInquiryStatus, string> = {
  OPEN: '待报价',
  QUOTED: '报价中',
  CHOSEN: '已选定',
  CLOSED: '已关闭',
}

export const MALL_INQUIRY_SORT_LABELS: Record<MallInquirySort, string> = {
  ALL: '综合',
  PRICE: '总价',
  LEAD_TIME: '交期',
}

// "11/05 18:00" in Beijing time.
export function formatInquiryInstant(iso: string): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

// "11/12" in Beijing time.
export function formatInquiryDay(iso: string): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

// The first line of the requirement, for a one-line summary.
export function requirementHeadline(requirement: string): string {
  return requirement.split('\n').map((line) => line.trim()).find(Boolean) ?? ''
}

export function closeReasonText(inquiry: Pick<MallInquiry, 'closeReason'>): string {
  switch (inquiry.closeReason) {
    case 'NO_QUOTES': return '72 小时内没有商家报价，询价已关闭'
    case 'QUOTES_LAPSED': return '报价都已失效，询价已关闭'
    default: return '你已关闭这份询价'
  }
}

export function isQuoteExpired(chosen: Pick<MallInquiryChosen, 'validUntil'>, now: Date): boolean {
  return Boolean(chosen.validUntil) && Date.parse(chosen.validUntil) <= now.getTime()
}

// The header card of the quotes page (QuoteGoods board).
export interface InquirySummaryView {
  title: string
  // "测绘版 · RTK · 五镜头倾斜相机 · 2 台"
  spec: string
  // "交付东营市 · 期望 12 月 15 日前"
  delivery: string
  statusText: string
  tone: 'PENDING' | 'QUOTING' | 'CHOSEN' | 'OFF'
  // The line next to the clock: count and deadline, the chosen quote or why it closed.
  line: string
  // The quote count to show in bold, or null when the line carries none.
  count: number | null
}

const TONES: Record<MallInquiryStatus, InquirySummaryView['tone']> = {
  OPEN: 'PENDING',
  QUOTED: 'QUOTING',
  CHOSEN: 'CHOSEN',
  CLOSED: 'OFF',
}

export function describeInquirySummary(inquiry: MallInquiry, cityName: string | null): InquirySummaryView {
  const delivery = [
    cityName ? `交付${cityName}` : null,
    inquiry.expectedDate ? `期望 ${formatExpectedDate(inquiry.expectedDate)}` : '交期不限',
  ].filter(Boolean).join(' · ')
  let line: string
  let count: number | null = null
  switch (inquiry.status) {
    case 'OPEN':
    case 'QUOTED':
      count = inquiry.quoteCount
      line = inquiry.quotingOpen
        ? `已收到 ${inquiry.quoteCount} 份报价，截止 ${formatInquiryInstant(inquiry.quoteDeadline)}`
        : `已收到 ${inquiry.quoteCount} 份报价，报价已截止，可在有效期内选择`
      break
    case 'CHOSEN':
      line = inquiry.chosen
        ? `已选定 ${inquiry.chosen.merchant.name} · ${formatMallAmount(inquiry.chosen.totalMinor)}`
        : '已选定报价'
      break
    case 'CLOSED':
      line = closeReasonText(inquiry)
      break
  }
  return {
    title: inquiry.product.name,
    spec: [requirementHeadline(inquiry.requirement), `${inquiry.quantity} 台`].filter(Boolean).join(' · '),
    delivery,
    statusText: MALL_INQUIRY_STATUS_LABELS[inquiry.status],
    tone: TONES[inquiry.status],
    line,
    count,
  }
}

// One card per merchant the inquiry went to: its latest quote, or why there is none.
export type InquiryMerchantCard =
  | {
      kind: 'QUOTE'
      key: string
      quote: MallInquiryQuote
      name: string
      kindLabel: string
      // "报价最低", "交期最短", "已选定", "已过期", "已失效" or null.
      badge: { text: string; tone: 'PRICE' | 'LEAD' | 'CHOSEN' | 'OFF' } | null
      versionText: string
      unit: string
      lead: string
      validUntil: string
      includes: string | null
      totalLabel: string
      total: string
      chosen: boolean
      choosable: boolean
      // The button's text: 选择此报价 / 已选择 / 已过期 / 已失效.
      buttonText: string
    }
  | {
      kind: 'WAITING' | 'DECLINED'
      key: string
      name: string
      kindLabel: string
      badge: { text: string; tone: 'WAIT' | 'OFF' }
      line: string
    }

function quoteButton(quote: MallInquiryQuote): string {
  if (quote.status === 'CHOSEN') return '已选择'
  if (quote.choosable) return '选择此报价'
  if (quote.status === 'EXPIRED') return '已过期'
  return '已失效'
}

function quoteBadge(
  quote: MallInquiryQuote,
  cheapest: string | null,
  fastest: string | null,
): Extract<InquiryMerchantCard, { kind: 'QUOTE' }>['badge'] {
  if (quote.status === 'CHOSEN') return { text: '已选定', tone: 'CHOSEN' }
  if (quote.status === 'EXPIRED') return { text: '已过期', tone: 'OFF' }
  if (quote.status === 'LAPSED' || quote.status === 'WITHDRAWN') return { text: '已失效', tone: 'OFF' }
  if (quote.quoteId === cheapest) return { text: '报价最低', tone: 'PRICE' }
  if (quote.quoteId === fastest) return { text: '交期最短', tone: 'LEAD' }
  return null
}

// The best of the quotes that can still be chosen; only marked when there is something to compare.
function best(quotes: MallInquiryQuote[], value: (quote: MallInquiryQuote) => number): string | null {
  const open = quotes.filter((quote) => quote.status === 'ACTIVE')
  if (open.length < 2) return null
  const lowest = Math.min(...open.map(value))
  const winners = open.filter((quote) => value(quote) === lowest)
  return winners.length === 1 ? winners[0].quoteId : null
}

/**
 * The quotes in the server's order (综合 / 总价 / 交期), then the merchants that have not quoted
 * and the ones that declined. A withdrawn quote is never sent; its merchant shows as declined.
 */
export function describeInquiryMerchants(inquiry: MallInquiry, quotes: MallInquiryQuote[]): InquiryMerchantCard[] {
  const cheapest = best(quotes, (quote) => quote.totalMinor)
  const fastest = best(quotes, (quote) => quote.leadDays)
  const quoted = new Set(quotes.map((quote) => quote.offerId))
  const cards: InquiryMerchantCard[] = quotes.map((quote) => ({
    kind: 'QUOTE',
    key: quote.quoteId,
    quote,
    name: quote.merchant.name,
    kindLabel: MALL_MERCHANT_KIND_LABELS[quote.merchant.kind],
    badge: quoteBadge(quote, cheapest, fastest),
    versionText: `第 ${quote.version} 版报价`,
    unit: formatMallAmount(quote.unitAmountMinor),
    lead: `${quote.leadDays} 天`,
    validUntil: formatInquiryDay(quote.validUntil),
    includes: quote.includes,
    totalLabel: `合计（${quote.quantity} 台）`,
    total: formatMallAmount(quote.totalMinor),
    chosen: quote.status === 'CHOSEN',
    choosable: quote.choosable,
    buttonText: quoteButton(quote),
  }))
  const rest = inquiry.merchants.filter((merchant) => !quoted.has(merchant.offerId))
  const waiting = rest.filter((merchant) => merchant.status !== 'DECLINED')
  const declined = rest.filter((merchant) => merchant.status === 'DECLINED')
  for (const merchant of waiting) cards.push(waitingCard(inquiry, merchant))
  for (const merchant of declined) cards.push(declinedCard(merchant))
  return cards
}

function waitingCard(inquiry: MallInquiry, merchant: MallInquiryInvitation): InquiryMerchantCard {
  const open = inquiry.quotingOpen
  return {
    kind: 'WAITING',
    key: merchant.offerId,
    name: merchant.merchant.name,
    kindLabel: MALL_MERCHANT_KIND_LABELS[merchant.merchant.kind],
    badge: open ? { text: '待报价', tone: 'WAIT' } : { text: '未报价', tone: 'OFF' },
    line: open ? `商家还没有报价，截止 ${formatInquiryInstant(inquiry.quoteDeadline)}` : '截止前商家没有报价',
  }
}

function declinedCard(merchant: MallInquiryInvitation): InquiryMerchantCard {
  return {
    kind: 'DECLINED',
    key: merchant.offerId,
    name: merchant.merchant.name,
    kindLabel: MALL_MERCHANT_KIND_LABELS[merchant.merchant.kind],
    badge: { text: '已拒绝', tone: 'OFF' },
    line: merchant.declineReason ? `商家已拒绝：${merchant.declineReason}` : '商家已拒绝',
  }
}

/**
 * The bottom bar of the quotes page. `orderOpen` says whether the chosen quote's latest order is
 * still alive (anything but closed): then the way on is that order, not a second one.
 */
export type InquiryBar =
  | { kind: 'PICK' }
  | { kind: 'ORDER'; merchant: string; total: string }
  | { kind: 'VIEW_ORDER'; merchant: string; total: string; orderId: string }
  | { kind: 'EXPIRED'; merchant: string; total: string }
  | { kind: 'REINQUIRE'; text: string }

export function describeInquiryBar(
  inquiry: MallInquiry,
  quotes: MallInquiryQuote[],
  orderOpen: boolean,
  now: Date,
): InquiryBar {
  const chosen = inquiry.chosen
  if (inquiry.status === 'CHOSEN' && chosen) {
    const merchant = chosen.merchant.name
    const total = formatMallAmount(chosen.totalMinor)
    if (chosen.orderId && orderOpen) return { kind: 'VIEW_ORDER', merchant, total, orderId: chosen.orderId }
    if (isQuoteExpired(chosen, now)) return { kind: 'EXPIRED', merchant, total }
    return { kind: 'ORDER', merchant, total }
  }
  if (inquiry.status === 'CLOSED') return { kind: 'REINQUIRE', text: closeReasonText(inquiry) }
  // Nothing chosen yet: waiting for quotes, or quotes to choose from.
  if (inquiry.quotingOpen || quotes.some((quote) => quote.choosable)) return { kind: 'PICK' }
  return { kind: 'REINQUIRE', text: '没有可以选择的报价' }
}

export interface ChoiceConfirmView {
  title: string
  text: string
  rows: Array<{ key: string; label: string; value: string }>
}

export function describeChoiceConfirm(quote: MallInquiryQuote): ChoiceConfirmView {
  return {
    title: '选择这份报价？',
    text: `选定后其余报价失效，按这份报价下单付款，销售方为${quote.merchant.name}。`,
    rows: [
      { key: 'model', label: '型号', value: quote.model },
      { key: 'unit', label: '单价', value: `${formatMallAmount(quote.unitAmountMinor)} / 台` },
      { key: 'total', label: '合计', value: `${formatMallAmount(quote.totalMinor)}（${quote.quantity} 台）` },
      { key: 'lead', label: '交期', value: `${quote.leadDays} 天` },
      { key: 'valid', label: '有效期', value: `至 ${formatInquiryInstant(quote.validUntil)}` },
    ],
  }
}

export function mallInquiryCloseConfirm(inquiry: Pick<MallInquiry, 'quoteCount'>): { title: string; text: string; keep: string; go: string } {
  return {
    title: '确认关闭询价？',
    text: inquiry.quoteCount > 0
      ? '关闭后已收到的报价全部失效，商家不再报价。'
      : '关闭后商家不再报价，需要时重新发起询价。',
    keep: '暂不关闭',
    go: '确认关闭',
  }
}

export type ChoiceFailure =
  | { kind: 'UNAUTHORIZED' }
  // The page was out of date: read the inquiry again and say why.
  | { kind: 'RELOAD'; message: string }
  | { kind: 'RETRY'; message: string }

export function describeChoiceFailure(failure: { status: number; code: string | null; message: string } | null): ChoiceFailure {
  if (!failure) return { kind: 'RETRY', message: '没有选定成功，请稍后重试' }
  if (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED') return { kind: 'UNAUTHORIZED' }
  switch (failure.code) {
    case 'MALL_INQUIRY_QUOTE_CHANGED':
      return { kind: 'RELOAD', message: '商家刚更新了报价，已刷新为最新报价，请再看一下' }
    case 'MALL_INQUIRY_VERSION_CONFLICT':
      return { kind: 'RELOAD', message: '询价单有变化，已刷新，请再看一下' }
    case 'MALL_INQUIRY_QUOTE_EXPIRED':
      return { kind: 'RELOAD', message: '这份报价已过有效期，请选择其他报价或重新询价' }
    case 'MALL_INQUIRY_STATE_INVALID':
      return { kind: 'RELOAD', message: '询价单已选定或已关闭，已刷新' }
    default:
      break
  }
  if (failure.status === 412 || failure.status === 404) return { kind: 'RELOAD', message: '询价单有变化，已刷新，请再看一下' }
  if (failure.status === 0) return { kind: 'RETRY', message: '网络连接失败，请稍后重试' }
  return { kind: 'RETRY', message: failure.message || '没有选定成功，请稍后重试' }
}

// The name of a city code in the platform city directory; null when the directory has no such city.
export function cityNameIn(loaded: CityDirectory, cityCode: string): string | null {
  for (const province of loaded.provinces) {
    const city = province.cities.find((item) => item.code === cityCode)
    if (city) return city.name
  }
  return loaded.hotCities.find((item) => item.code === cityCode)?.name ?? null
}
