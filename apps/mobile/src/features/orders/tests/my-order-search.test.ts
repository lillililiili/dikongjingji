import { describe, expect, it } from 'vitest'

import { venueBooking } from '../../experience/tests/venue-booking-fixtures'
import type { InspectionOrderSummary } from '../../inspection/domain/service-flow/order-center'
import { rentalOrder } from '../../rental/tests/rental-fixtures'
import { NOW, tradeOrder } from '../../trade/tests/trade-fixtures'
import { MY_ORDER_SEARCH_TIPS, searchMyOrders } from '../application/my-order-search'
import { goodsItem, inspectionItem, rentalItem, venueItem } from '../application/my-order-sources'
import pagesJson from '../../../pages.json?raw'
import ordersPageSource from '../presentation/MyOrdersPage.vue?raw'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'

function inspection(overrides: Partial<InspectionOrderSummary> = {}): InspectionOrderSummary {
  return {
    reservationId: 'res-1',
    reservationNo: 'IR2026111800001',
    serviceId: 'svc-1',
    serviceTitle: '物流园区季度安防巡检',
    cityCode: '370500',
    locationSummary: '东营港物流园',
    preferredServiceDate: '2026-11-25',
    submittedAt: '2026-11-17T01:00:00Z',
    stage: 'AWAITING_PLAN',
    reservationStatus: 'SUBMITTED',
    currency: 'CNY',
    ...overrides,
  }
}

const ITEMS = [
  goodsItem(tradeOrder()),
  rentalItem(rentalOrder()),
  inspectionItem(inspection()),
  venueItem(venueBooking()),
]

describe('订单搜索 (board OrderSearch)', () => {
  it('finds goods by product name and by merchant, with the words cut out for the highlight', () => {
    const [hit] = searchMyOrders(ITEMS, '编程机器人', NOW)
    expect(hit.key).toBe('goods:' + tradeOrder().orderId)
    expect(hit.tag.label).toBe('商品')
    expect(hit.title.hit).toBe('编程机器人')
    expect(hit.orderNo?.pre).toBe('MO2026111812345678')
    expect(searchMyOrders(ITEMS, '示例机器人商家', NOW).map((found) => found.key)).toContain('goods:' + tradeOrder().orderId)
  })

  it('matches order numbers in any case and by their last digits', () => {
    expect(searchMyOrders(ITEMS, 'mo2026', NOW).map((found) => found.tag.label)).toEqual(['商品'])
    const [hit] = searchMyOrders(ITEMS, '00001', NOW)
    expect(hit.tag.label).toBe('服务')
    expect(hit.orderNo).toEqual({ pre: 'IR20261118', hit: '00001', post: '' })
    expect(hit.sub.pre).toBe('东营港物流园')
    expect(hit.url).toBeTruthy()
  })

  it('finds a venue booking by its place and returns nothing for blank or unknown words', () => {
    const venue = venueBooking()
    expect(searchMyOrders(ITEMS, venue.venue.name.slice(0, 4), NOW).some((found) => found.key === `venue:${venue.bookingId}`)).toBe(true)
    expect(searchMyOrders(ITEMS, '激光雷达模块', NOW)).toEqual([])
    expect(searchMyOrders(ITEMS, '   ', NOW)).toEqual([])
  })

  it('lists the four ways to search, has its page and a search icon on 我的订单', () => {
    expect(MY_ORDER_SEARCH_TIPS.map((tip) => tip.key)).toEqual(['订单号', '商品名', '服务商', '活动名'])
    expect(pagesJson).toContain('"path": "pages/mine/order-search"')
    expect(ordersPageSource).toContain('data-testid="my-orders-search"')
    // A guest signs in and comes back to the search.
    expect(normalizeInspectionReturnUrl('/pages/mine/order-search')).toBe('/pages/mine/order-search')
  })
})
