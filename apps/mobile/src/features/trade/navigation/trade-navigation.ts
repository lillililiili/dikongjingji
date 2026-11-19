import { GOODS_ORDERS_PATH, GOODS_ORDER_PATH, buildGoodsOrderUrl, buildGoodsOrdersUrl } from './trade-routes'

// The page under the current one, as "/pages/..."; null when the current page is alone.
function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

/**
 * The cashier and the result page replace the confirm page (redirectTo), so their way back is the
 * goods order list: back to it when it is right underneath, else the list replaces this page.
 * navigateBack alone is not safe on H5 with one page in the stack (it reloads the page).
 */
export function leaveToGoodsOrders(): void {
  if (previousRoute() === GOODS_ORDERS_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildGoodsOrdersUrl() })
}

// 查看订单 from the cashier or the result page: back to the order page when the payment started there.
export function showGoodsOrder(orderId: string): void {
  if (previousRoute() === GOODS_ORDER_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildGoodsOrderUrl(orderId) })
}

export function goBackOr(fallback: () => void): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  fallback()
}
