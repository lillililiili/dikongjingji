import { TRADE_ORDER_TABS, type TradeOrderTab } from '../domain/trade-order'

export const TRADE_CASHIER_PATH = '/pages/trade/cashier'
export const TRADE_PAY_RESULT_PATH = '/pages/trade/pay-result'
export const GOODS_ORDERS_PATH = '/pages/mine/goods-orders'
export const GOODS_ORDER_PATH = '/pages/mine/goods-order'

// Only the order id travels; every page reads the order from the server again.
export function buildCashierUrl(orderId: string): string {
  return `${TRADE_CASHIER_PATH}?orderId=${encodeURIComponent(orderId)}`
}

export function buildPayResultUrl(orderId: string): string {
  return `${TRADE_PAY_RESULT_PATH}?orderId=${encodeURIComponent(orderId)}`
}

export function buildGoodsOrderUrl(orderId: string): string {
  return `${GOODS_ORDER_PATH}?orderId=${encodeURIComponent(orderId)}`
}

export function buildGoodsOrdersUrl(tab: TradeOrderTab = 'ALL'): string {
  return `${GOODS_ORDERS_PATH}?tab=${tab}`
}

export function parseTradeOrderTab(value: unknown): TradeOrderTab {
  return typeof value === 'string' && (TRADE_ORDER_TABS as readonly string[]).includes(value)
    ? value as TradeOrderTab
    : 'ALL'
}
