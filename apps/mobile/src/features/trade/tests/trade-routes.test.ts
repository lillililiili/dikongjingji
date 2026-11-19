import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import {
  GOODS_ORDERS_PATH,
  buildCashierUrl,
  buildGoodsOrderUrl,
  buildGoodsOrdersUrl,
  buildPayResultUrl,
  parseTradeOrderTab,
} from '../navigation/trade-routes'

describe('trade routes', () => {
  it('carries only the order id or the tab', () => {
    expect(buildCashierUrl('o 1')).toBe('/pages/trade/cashier?orderId=o%201')
    expect(buildPayResultUrl('o-1')).toBe('/pages/trade/pay-result?orderId=o-1')
    expect(buildGoodsOrderUrl('o-1')).toBe('/pages/mine/goods-order?orderId=o-1')
    expect(buildGoodsOrdersUrl()).toBe('/pages/mine/goods-orders?tab=ALL')
    expect(buildGoodsOrdersUrl('REFUND')).toBe('/pages/mine/goods-orders?tab=REFUND')
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    expect(normalizeInspectionReturnUrl(buildCashierUrl('o-1'))).toBe('/pages/trade/cashier?orderId=o-1')
    expect(normalizeInspectionReturnUrl(buildPayResultUrl('o-1'))).toBe('/pages/trade/pay-result?orderId=o-1')
    expect(normalizeInspectionReturnUrl(buildGoodsOrderUrl('o-1'))).toBe('/pages/mine/goods-order?orderId=o-1')
    expect(normalizeInspectionReturnUrl(buildGoodsOrdersUrl('TO_PAY'))).toBe('/pages/mine/goods-orders?tab=TO_PAY')
    expect(normalizeInspectionReturnUrl(GOODS_ORDERS_PATH)).toBe(GOODS_ORDERS_PATH)
  })

  it('refuses order pages without an order and drops unknown parameters', () => {
    expect(normalizeInspectionReturnUrl('/pages/trade/cashier')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/mine/goods-order?orderNo=MO1')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/trade/pay-result?orderId=o-1&next=https://evil.example'))
      .toBe('/pages/trade/pay-result?orderId=o-1')
  })

  it('reads a known tab from the URL, else 全部', () => {
    expect(parseTradeOrderTab('TO_RECEIVE')).toBe('TO_RECEIVE')
    expect(parseTradeOrderTab('to_pay')).toBe('ALL')
    expect(parseTradeOrderTab(undefined)).toBe('ALL')
  })
})
