import { ACTIVITY_TICKET_PATH, buildActivityTicketUrl } from '@/features/activity/navigation/activity-routes'
import { VENUE_BOOKING_PATH, buildVenueBookingUrl } from '@/features/experience/navigation/experience-routes'
import { RENTAL_ORDER_PATH, buildRentalOrderUrl } from '@/features/rental/navigation/rental-routes'
import {
  SERVICE_ORDER_DETAIL_PATH,
  SERVICE_ORDER_PLAN_PATH,
  buildServiceOrderDetailUrl,
} from '@/features/serviceorder/navigation/service-order-routes'
import type { TradeOrderDestination } from '@/features/trade/application/order-source'
import { leaveToGoodsOrders, showGoodsOrder } from '@/features/trade/navigation/trade-navigation'
import { buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

// The trade pages only know an order's destination; the shell turns it into a route, so the trade
// module never imports another business module.

export function destinationUrl(destination: TradeOrderDestination): string {
  switch (destination.kind) {
    case 'ACTIVITY_ENROLLMENT': return buildActivityTicketUrl(destination.enrollmentId)
    case 'VENUE_BOOKING': return buildVenueBookingUrl(destination.bookingId)
    case 'RENTAL_ORDER': return buildRentalOrderUrl(destination.rentalOrderId)
    case 'SERVICE_ORDER': return buildServiceOrderDetailUrl(destination.orderId)
    case 'GOODS_ORDER': return buildGoodsOrderUrl(destination.orderId)
  }
}

// The business pages an order belongs to, when the payment can have started from them. A service
// order is paid from its order page or its plan page.
function ownPagePaths(destination: TradeOrderDestination): string[] {
  if (destination.kind === 'ACTIVITY_ENROLLMENT') return [ACTIVITY_TICKET_PATH]
  if (destination.kind === 'VENUE_BOOKING') return [VENUE_BOOKING_PATH]
  if (destination.kind === 'RENTAL_ORDER') return [RENTAL_ORDER_PATH]
  if (destination.kind === 'SERVICE_ORDER') return [SERVICE_ORDER_DETAIL_PATH, SERVICE_ORDER_PLAN_PATH]
  return []
}

function cameFromOwnPage(destination: TradeOrderDestination): boolean {
  const previous = previousRoute()
  return previous !== null && ownPagePaths(destination).includes(previous)
}

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

/**
 * 查看订单 / 查看凭证 / 查看核销码 / 查看租赁订单 from the cashier or the result page (a service
 * order's 查看订单 goes back to its plan or order page when the payment started there). A goods order keeps its E07
 * behaviour; a sign-up's ticket or a booking's code replaces the page, or is gone back to when the
 * payment started from it.
 */
export function showOrderDestination(destination: TradeOrderDestination): void {
  if (destination.kind === 'GOODS_ORDER') {
    showGoodsOrder(destination.orderId)
    return
  }
  if (cameFromOwnPage(destination)) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: destinationUrl(destination) })
}

/**
 * Back from the cashier or the result page. Goods: the order list (E07). A sign-up, a venue
 * booking or a rental: the page under this one when there is one (the activity, venue or rental
 * quote, or the ticket, code or rental order page the payment started from), else its own page.
 */
export function leaveTradePage(destination: TradeOrderDestination | null, fromCashier: boolean): void {
  if (destination && destination.kind !== 'GOODS_ORDER') {
    if (cameFromOwnPage(destination) || (fromCashier && getCurrentPages().length > 1)) {
      uni.navigateBack()
      return
    }
    uni.redirectTo({ url: destinationUrl(destination) })
    return
  }
  if (fromCashier && getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  leaveToGoodsOrders()
}
