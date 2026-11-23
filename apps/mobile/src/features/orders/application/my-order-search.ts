import { highlightKeyword, type HighlightParts } from '../../search/application/search-view'
import { inspectionServiceImage } from '../../inspection/data/inspection-service-images'
import {
  formatInspectionOrderAmount,
  getInspectionOrderStageColour,
  getInspectionOrderStageText,
} from '../../inspection/presentation/orders/order-center-view'
import { resolveInspectionOrderDestination } from '../../inspection/application/service-flow/inspection-order-navigation'
import { describeMyOrderCard, type MyOrderTagTone, type MyOrderTone } from './my-order-cards'
import { MY_ORDER_KEYS, type MyOrderItem } from './my-order-sources'

// 订单搜索 (board OrderSearch): one keyword over the title, the merchant, provider or place, the
// grey line and the order number of every order of this account. Order numbers match in any case.

export interface MyOrderSearchHit {
  key: string
  tag: { label: string; tone: MyOrderTagTone }
  who: HighlightParts
  status: { text: string; tone: MyOrderTone | null; colour: string | null }
  image: string | null
  imageAlt: string
  title: HighlightParts
  amount: string
  sub: HighlightParts
  // Null when the record has no number to show (sign-ups and venue bookings).
  orderNo: HighlightParts | null
  url: string | null
}

function hasHit(parts: HighlightParts | null): boolean {
  return Boolean(parts && parts.hit)
}

function orderNumber(item: MyOrderItem): string | null {
  switch (item.kind) {
    case 'GOODS': return item.order.orderNo
    case 'RENTAL': return item.order.contract.contractNo
    case 'INSPECTION': return item.order.reservationNo
    default: return null
  }
}

function hit(item: MyOrderItem, keyword: string, now: number, rentalCovers: Record<string, string | null>): MyOrderSearchHit | null {
  const no = orderNumber(item)
  const orderNo = no ? highlightKeyword(no, keyword) : null
  if (item.kind === 'INSPECTION') {
    const order = item.order
    const candidate: MyOrderSearchHit = {
      key: item.id,
      tag: { label: '服务', tone: 'GREEN' },
      // The order list does not carry the provider's name yet (plan 08 S4-4).
      who: highlightKeyword('巡检服务', keyword),
      status: { text: getInspectionOrderStageText(order), tone: null, colour: getInspectionOrderStageColour(order.stage) },
      image: inspectionServiceImage({ coverUrl: order.serviceCoverUrl, title: order.serviceTitle }),
      imageAlt: order.serviceTitle,
      title: highlightKeyword(order.serviceTitle, keyword),
      amount: formatInspectionOrderAmount(order.quoteAmountMinor),
      sub: highlightKeyword(order.locationSummary, keyword),
      orderNo,
      url: resolveInspectionOrderDestination(order),
    }
    return [candidate.who, candidate.title, candidate.sub, candidate.orderNo].some(hasHit) ? candidate : null
  }
  const card = describeMyOrderCard(item, now, rentalCovers)
  if (!card) return null
  const title = `${card.title}${card.titleTail}`
  const candidate: MyOrderSearchHit = {
    key: item.id,
    tag: card.tag,
    who: highlightKeyword(card.head, keyword),
    status: { text: card.status.text, tone: card.status.tone, colour: null },
    image: card.cover,
    imageAlt: card.coverAlt,
    title: highlightKeyword(title, keyword),
    amount: card.amount,
    sub: highlightKeyword(card.lines[0]?.text ?? '', keyword),
    orderNo,
    url: card.url,
  }
  return [candidate.who, candidate.title, candidate.sub, candidate.orderNo].some(hasHit) ? candidate : null
}

/** The matching orders, newest first. A blank keyword matches nothing. */
export function searchMyOrders(
  items: readonly MyOrderItem[],
  keyword: string,
  now: number,
  rentalCovers: Record<string, string | null> = {},
): MyOrderSearchHit[] {
  if (!keyword.replace(/\s+/g, '')) return []
  return [...items]
    .sort((a, b) => MY_ORDER_KEYS.timeOf(b).localeCompare(MY_ORDER_KEYS.timeOf(a)))
    .map((item) => hit(item, keyword, now, rentalCovers))
    .filter((found): found is MyOrderSearchHit => found !== null)
}

// 可以这样搜 on board OrderSearch.
export const MY_ORDER_SEARCH_TIPS: ReadonlyArray<{ key: string; text: string }> = [
  { key: '订单号', text: '完整或后几位' },
  { key: '商品名', text: '编程机器人探索套装' },
  { key: '服务商', text: '鹰眼智能巡检' },
  { key: '活动名', text: '青少年无人机编程挑战赛' },
]
