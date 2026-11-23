// The three pages of a general service order (S4-3). Only the order's stable id travels in the URL;
// every page reads the order from the server again.
export const SERVICE_ORDER_DETAIL_PATH = '/pages/service-order/detail'
export const SERVICE_ORDER_PLAN_PATH = '/pages/service-order/plan'
export const SERVICE_ORDER_DELIVERY_PATH = '/pages/service-order/delivery'

export const SERVICE_ORDER_PATHS = [SERVICE_ORDER_DETAIL_PATH, SERVICE_ORDER_PLAN_PATH, SERVICE_ORDER_DELIVERY_PATH] as const

const withOrder = (path: string, orderId: string) => `${path}?orderId=${encodeURIComponent(orderId)}`

export function buildServiceOrderDetailUrl(orderId: string): string {
  return withOrder(SERVICE_ORDER_DETAIL_PATH, orderId)
}

export function buildServiceOrderPlanUrl(orderId: string): string {
  return withOrder(SERVICE_ORDER_PLAN_PATH, orderId)
}

export function buildServiceOrderDeliveryUrl(orderId: string): string {
  return withOrder(SERVICE_ORDER_DELIVERY_PATH, orderId)
}

export function parseServiceOrderId(query: Record<string, unknown> | undefined): string | null {
  const value = typeof query?.orderId === 'string' ? query.orderId.trim() : ''
  return value || null
}
