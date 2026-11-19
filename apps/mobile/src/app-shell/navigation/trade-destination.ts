import { ACTIVITY_TICKET_PATH, buildActivityTicketUrl } from '@/features/activity/navigation/activity-routes'
import type { TradeOrderDestination } from '@/features/trade/application/order-source'
import { leaveToGoodsOrders, showGoodsOrder } from '@/features/trade/navigation/trade-navigation'
import { buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

// The trade pages only know an order's destination; the shell turns it into a route, so the trade
// module never imports another business module.

export function destinationUrl(destination: TradeOrderDestination): string {
  return destination.kind === 'ACTIVITY_ENROLLMENT'
    ? buildActivityTicketUrl(destination.enrollmentId)
    : buildGoodsOrderUrl(destination.orderId)
}

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

/**
 * 查看订单 / 查看凭证 from the cashier or the result page. A goods order keeps its E07 behaviour;
 * a sign-up's ticket replaces the page, or is gone back to when the payment started from it.
 */
export function showOrderDestination(destination: TradeOrderDestination): void {
  if (destination.kind === 'GOODS_ORDER') {
    showGoodsOrder(destination.orderId)
    return
  }
  if (previousRoute() === ACTIVITY_TICKET_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: destinationUrl(destination) })
}

/**
 * Back from the cashier or the result page. Goods: the order list (E07). A sign-up: the page under
 * this one when there is one (the activity, or the ticket the payment started from), else its ticket.
 */
export function leaveTradePage(destination: TradeOrderDestination | null, fromCashier: boolean): void {
  if (destination?.kind === 'ACTIVITY_ENROLLMENT') {
    if (previousRoute() === ACTIVITY_TICKET_PATH || (fromCashier && getCurrentPages().length > 1)) {
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
