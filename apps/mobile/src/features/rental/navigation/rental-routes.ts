import type { RentalOrderStatus } from '../domain/rental'

export const RENTAL_INQUIRY_PATH = '/pages/rental/inquiry'
export const RENTAL_QUOTE_PATH = '/pages/rental/quote'
export const RENTAL_ORDER_PATH = '/pages/rental/order'
export const RENTAL_CONTRACT_PATH = '/pages/rental/contract'
export const MY_RENTAL_ORDERS_PATH = '/pages/mine/rental-orders'

export interface RentalInquiryTarget {
  offerId: string
  // The mall product the offer belongs to: the form reads the device from it.
  productId: string
  cityCode: string
}

// Only stable ids and the city travel in the URL; every page reads the facts from the server.
export function buildRentalInquiryUrl(target: RentalInquiryTarget): string {
  return `${RENTAL_INQUIRY_PATH}?offerId=${encodeURIComponent(target.offerId)}`
    + `&productId=${encodeURIComponent(target.productId)}&cityCode=${encodeURIComponent(target.cityCode)}`
}

export function parseRentalInquiryTarget(query: Record<string, unknown> | undefined): RentalInquiryTarget | null {
  const read = (key: string) => (typeof query?.[key] === 'string' ? (query[key] as string).trim() : '')
  const target = { offerId: read('offerId'), productId: read('productId'), cityCode: read('cityCode') }
  return target.offerId && target.productId && target.cityCode ? target : null
}

export function buildRentalQuoteUrl(inquiryId: string): string {
  return `${RENTAL_QUOTE_PATH}?inquiryId=${encodeURIComponent(inquiryId)}`
}

export function buildRentalOrderUrl(rentalOrderId: string): string {
  return `${RENTAL_ORDER_PATH}?rentalOrderId=${encodeURIComponent(rentalOrderId)}`
}

export function buildRentalContractUrl(rentalOrderId: string): string {
  return `${RENTAL_CONTRACT_PATH}?rentalOrderId=${encodeURIComponent(rentalOrderId)}`
}

// The tabs of 我的 → 租赁订单. A tab may cover more than one server status.
export const RENTAL_ORDER_TABS = ['ALL', 'TO_PAY', 'TO_HANDOVER', 'IN_USE', 'ENDED', 'CLOSED'] as const
export type RentalOrderTab = (typeof RENTAL_ORDER_TABS)[number]

export const RENTAL_ORDER_TAB_LABELS: Record<RentalOrderTab, string> = {
  ALL: '全部',
  TO_PAY: '待付款',
  TO_HANDOVER: '待交接',
  IN_USE: '使用中',
  ENDED: '已结束',
  CLOSED: '已取消',
}

// null means "every status" (one unfiltered list).
export function rentalOrderStatusesOfTab(tab: RentalOrderTab): Array<RentalOrderStatus | null> {
  switch (tab) {
    case 'ALL': return [null]
    case 'TO_PAY': return ['PENDING_PAYMENT']
    case 'TO_HANDOVER': return ['AWAITING_HANDOVER', 'HANDOVER_RECORDED']
    case 'IN_USE': return ['IN_USE', 'RETURN_RECORDED']
    case 'ENDED': return ['ENDED']
    case 'CLOSED': return ['CANCELLED', 'CLOSED']
  }
}

export function buildMyRentalOrdersUrl(tab: RentalOrderTab = 'ALL'): string {
  return tab === 'ALL' ? MY_RENTAL_ORDERS_PATH : `${MY_RENTAL_ORDERS_PATH}?status=${tab}`
}

export function parseRentalOrderTab(value: unknown): RentalOrderTab {
  return typeof value === 'string' && (RENTAL_ORDER_TABS as readonly string[]).includes(value)
    ? value as RentalOrderTab
    : 'ALL'
}
