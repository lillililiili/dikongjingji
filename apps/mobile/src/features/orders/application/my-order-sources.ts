import type { MergedKeys } from '../../../shared/list/merged-pages'

import type { Enrollment, EnrollmentStatus } from '../../activity/domain/enrollment'
import type { VenueBooking, VenueBookingBucket } from '../../experience/domain/venue-booking'
import type {
  InspectionOrderFilter,
  InspectionOrderSummary,
} from '../../inspection/domain/service-flow/order-center'
import type { RentalOrder, RentalOrderStatus } from '../../rental/domain/rental'
import type { TradeOrder, TradeOrderTab } from '../../trade/domain/trade-order'
import type { MyOrderChip, MyOrderTab } from './my-orders-route'

/**
 * The records behind 我的订单. There is no list of every order on the server (plan 06 R6-1: no new
 * aggregate API), so the page reads the existing lists and merges them newest first:
 * 商品 = goods orders (trade, mall source only); 服务 = rental orders and inspection orders;
 * 报名 = activity sign-ups and venue bookings.
 */
export type MyOrderKind = 'GOODS' | 'RENTAL' | 'INSPECTION' | 'ENROLL' | 'VENUE'

export type MyOrderItem =
  | { kind: 'GOODS'; id: string; time: string; order: TradeOrder }
  | { kind: 'RENTAL'; id: string; time: string; order: RentalOrder }
  | { kind: 'INSPECTION'; id: string; time: string; order: InspectionOrderSummary }
  | { kind: 'ENROLL'; id: string; time: string; enrollment: Enrollment }
  | { kind: 'VENUE'; id: string; time: string; booking: VenueBooking }

// One server list with its filter. A source is read with the filter that matches the chip, so a
// page of it holds only cards the chip shows.
export type MyOrderSource =
  | { kind: 'GOODS'; tab: TradeOrderTab }
  | { kind: 'RENTAL'; status: RentalOrderStatus | null }
  | { kind: 'INSPECTION'; stage: InspectionOrderFilter }
  | { kind: 'ENROLL'; bucket: EnrollmentStatus }
  | { kind: 'VENUE'; bucket: VenueBookingBucket }

export const MY_ORDER_TAB_KINDS: Record<MyOrderTab, readonly MyOrderKind[]> = {
  all: ['GOODS', 'RENTAL', 'INSPECTION', 'ENROLL', 'VENUE'],
  goods: ['GOODS'],
  service: ['RENTAL', 'INSPECTION'],
  enroll: ['ENROLL', 'VENUE'],
}

/**
 * Goods and rental orders come newest order first, so they are paged and merged by that time
 * (shared/list/merged-pages.ts). The other three lists are sorted otherwise on the server —
 * inspection orders by their last change, sign-ups and venue bookings by the session time — so
 * they are read to the end at once and only sorted here.
 */
export function isCompleteSource(source: MyOrderSource): boolean {
  return source.kind === 'INSPECTION' || source.kind === 'ENROLL' || source.kind === 'VENUE'
}

const ALL_SOURCES: readonly MyOrderSource[] = [
  { kind: 'GOODS', tab: 'ALL' },
  { kind: 'RENTAL', status: null },
  { kind: 'INSPECTION', stage: 'ALL' },
  { kind: 'ENROLL', bucket: 'UPCOMING' },
  { kind: 'ENROLL', bucket: 'FINISHED' },
  { kind: 'ENROLL', bucket: 'CANCELLED' },
  { kind: 'VENUE', bucket: 'UPCOMING' },
  { kind: 'VENUE', bucket: 'FINISHED' },
  { kind: 'VENUE', bucket: 'CANCELLED' },
]

// The lists each chip needs, filtered on the server where the list allows it (see myOrderChips).
const CHIP_SOURCES: Record<MyOrderChip, readonly MyOrderSource[]> = {
  pay: [
    { kind: 'GOODS', tab: 'TO_PAY' },
    { kind: 'RENTAL', status: 'PENDING_PAYMENT' },
    // Unpaid sign-ups and bookings are listed with the coming ones.
    { kind: 'ENROLL', bucket: 'UPCOMING' },
    { kind: 'VENUE', bucket: 'UPCOMING' },
  ],
  ship: [
    { kind: 'GOODS', tab: 'TO_SHIP' },
    { kind: 'RENTAL', status: 'AWAITING_HANDOVER' },
    { kind: 'RENTAL', status: 'HANDOVER_RECORDED' },
    { kind: 'INSPECTION', stage: 'IN_PROGRESS' },
  ],
  attend: [
    { kind: 'ENROLL', bucket: 'UPCOMING' },
    { kind: 'VENUE', bucket: 'UPCOMING' },
  ],
  accept: [{ kind: 'INSPECTION', stage: 'AWAITING_ACCEPTANCE' }],
  refund: [
    { kind: 'GOODS', tab: 'REFUND' },
    // A sign-up may have had some of its people refunded and still be coming or over.
    { kind: 'ENROLL', bucket: 'UPCOMING' },
    { kind: 'ENROLL', bucket: 'FINISHED' },
    { kind: 'ENROLL', bucket: 'CANCELLED' },
    // A booking is refunded as a whole when it is cancelled.
    { kind: 'VENUE', bucket: 'CANCELLED' },
  ],
}

export function myOrderSources(
  tab: MyOrderTab,
  chip: MyOrderChip | null,
  enabled: (kind: MyOrderKind) => boolean,
): MyOrderSource[] {
  const kinds = MY_ORDER_TAB_KINDS[tab]
  return (chip ? CHIP_SOURCES[chip] : ALL_SOURCES)
    .filter((source) => kinds.includes(source.kind) && enabled(source.kind))
}

/**
 * The chips a card belongs to (board OrderList, renderVals): 待付款, 待发货/履约 (goods paid and not
 * shipped or picked up, rentals waiting for the handover, inspections in progress), 待参加,
 * 待验收, and 退款/售后 for the refunds of goods and of sign-ups and bookings. A shipped or
 * received goods order, a cancelled one, a rental in use and an inspection waiting for its plan or
 * for the customer's confirmation belong to none: they show only under 全部 and their own tab.
 */
export function myOrderChips(item: MyOrderItem): MyOrderChip[] {
  switch (item.kind) {
    case 'GOODS':
      switch (item.order.status) {
        case 'PENDING_PAYMENT': return ['pay']
        case 'PAID': return ['ship']
        case 'REFUNDING':
        case 'REFUNDED':
        case 'REFUND_FAILED': return ['refund']
        default: return []
      }
    case 'RENTAL':
      switch (item.order.status) {
        case 'PENDING_PAYMENT': return ['pay']
        case 'AWAITING_HANDOVER':
        case 'HANDOVER_RECORDED': return ['ship']
        default: return []
      }
    case 'INSPECTION':
      if (item.order.stage === 'IN_PROGRESS') return ['ship']
      if (item.order.stage === 'AWAITING_ACCEPTANCE') return ['accept']
      return []
    case 'ENROLL': {
      const enrollment = item.enrollment
      const chips: MyOrderChip[] = []
      const payment = enrollment.payment.status
      if (payment === 'PENDING_PAYMENT' && enrollment.status === 'UPCOMING') chips.push('pay')
      if (enrollment.status === 'UPCOMING' && (payment === 'FREE' || payment === 'PAID')
        && enrollment.tickets.some((ticket) => ticket.status === 'ACTIVE')) chips.push('attend')
      if (enrollment.tickets.some((ticket) => ticket.refund)) chips.push('refund')
      return chips
    }
    case 'VENUE': {
      const booking = item.booking
      if (booking.status === 'PENDING_PAYMENT') return ['pay']
      if (booking.status === 'CONFIRMED') return ['attend']
      if (booking.status === 'CANCELLED' && booking.refund) return ['refund']
      return []
    }
  }
}

export function matchesMyOrderFilter(item: MyOrderItem, tab: MyOrderTab, chip: MyOrderChip | null): boolean {
  if (!MY_ORDER_TAB_KINDS[tab].includes(item.kind)) return false
  return chip === null || myOrderChips(item).includes(chip)
}

// Ids carry the kind, so records of two lists never collide. The time is when the order was
// placed (submitted, signed up, booked).
export function goodsItem(order: TradeOrder): MyOrderItem {
  return { kind: 'GOODS', id: `goods:${order.orderId}`, time: order.createdAt, order }
}

export function rentalItem(order: RentalOrder): MyOrderItem {
  return { kind: 'RENTAL', id: `rental:${order.rentalOrderId}`, time: order.createdAt, order }
}

export function inspectionItem(order: InspectionOrderSummary): MyOrderItem {
  return { kind: 'INSPECTION', id: `inspection:${order.reservationId}`, time: order.submittedAt, order }
}

export function enrollItem(enrollment: Enrollment): MyOrderItem {
  return { kind: 'ENROLL', id: `enroll:${enrollment.enrollmentId}`, time: enrollment.createdAt, enrollment }
}

export function venueItem(booking: VenueBooking): MyOrderItem {
  return { kind: 'VENUE', id: `venue:${booking.bookingId}`, time: booking.createdAt, booking }
}

export const MY_ORDER_KEYS: MergedKeys<MyOrderItem> = {
  idOf: (item) => item.id,
  timeOf: (item) => item.time,
}

/**
 * 退款/售后 (board OrderList, Batch5Spec #14): goods after-sales cases are listed in the page under
 * this chip — not a jump to the list page — with “全部售后单 ›” to it, on 全部 and 商品 only. They do not
 * join the default list. A goods order whose case is shown is not listed again as an order.
 */
export function showsAftersales(tab: MyOrderTab, chip: MyOrderChip | null): boolean {
  return chip === 'refund' && (tab === 'all' || tab === 'goods')
}

export function withoutAftersaleOrders(items: MyOrderItem[], aftersaleOrderIds: ReadonlySet<string>): MyOrderItem[] {
  if (aftersaleOrderIds.size === 0) return items
  return items.filter((item) => item.kind !== 'GOODS' || !aftersaleOrderIds.has(item.order.orderId))
}
