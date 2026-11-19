import { ACTIVITY_TICKET_PATH, buildActivityTicketUrl } from '@/features/activity/navigation/activity-routes'
import { VENUE_BOOKING_PATH, buildVenueBookingUrl } from '@/features/experience/navigation/experience-routes'
import type { TradeOrderDestination } from '@/features/trade/application/order-source'
import { leaveToGoodsOrders, showGoodsOrder } from '@/features/trade/navigation/trade-navigation'
import { buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

// The trade pages only know an order's destination; the shell turns it into a route, so the trade
// module never imports another business module.

export function destinationUrl(destination: TradeOrderDestination): string {
  switch (destination.kind) {
    case 'ACTIVITY_ENROLLMENT': return buildActivityTicketUrl(destination.enrollmentId)
    case 'VENUE_BOOKING': return buildVenueBookingUrl(destination.bookingId)
    case 'GOODS_ORDER': return buildGoodsOrderUrl(destination.orderId)
  }
}

// The business page an order belongs to, when the payment can have started from it.
function ownPagePath(destination: TradeOrderDestination): string | null {
  if (destination.kind === 'ACTIVITY_ENROLLMENT') return ACTIVITY_TICKET_PATH
  if (destination.kind === 'VENUE_BOOKING') return VENUE_BOOKING_PATH
  return null
}

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

/**
 * 查看订单 / 查看凭证 / 查看核销码 from the cashier or the result page. A goods order keeps its E07
 * behaviour; a sign-up's ticket or a booking's code replaces the page, or is gone back to when the
 * payment started from it.
 */
export function showOrderDestination(destination: TradeOrderDestination): void {
  if (destination.kind === 'GOODS_ORDER') {
    showGoodsOrder(destination.orderId)
    return
  }
  if (previousRoute() === ownPagePath(destination)) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: destinationUrl(destination) })
}

/**
 * Back from the cashier or the result page. Goods: the order list (E07). A sign-up or a venue
 * booking: the page under this one when there is one (the activity or venue, or the ticket or code
 * page the payment started from), else its own page.
 */
export function leaveTradePage(destination: TradeOrderDestination | null, fromCashier: boolean): void {
  if (destination && destination.kind !== 'GOODS_ORDER') {
    if (previousRoute() === ownPagePath(destination) || (fromCashier && getCurrentPages().length > 1)) {
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
