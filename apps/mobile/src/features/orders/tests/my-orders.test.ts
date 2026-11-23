import { describe, expect, it } from 'vitest'

import { mergedItems } from '../../../shared/list/merged-pages'

import type { Enrollment, EnrollmentTicket } from '../../activity/domain/enrollment'
import { pendingBooking, venueBooking } from '../../experience/tests/venue-booking-fixtures'
import type { InspectionOrderSummary } from '../../inspection/domain/service-flow/order-center'
import { rentalOrder } from '../../rental/tests/rental-fixtures'
import { NOW, paidOrder, tradeOrder } from '../../trade/tests/trade-fixtures'
import { describeMyOrderCard } from '../application/my-order-cards'
import {
  COMPLETE_PAGE_SIZE,
  MY_ORDER_PAGE_SIZE,
  loadFirstMyOrderPages,
  loadMoreMyOrders,
  type MyOrderApis,
} from '../application/my-order-loader'
import {
  MY_ORDER_KEYS,
  enrollItem,
  goodsItem,
  inspectionItem,
  isCompleteSource,
  matchesMyOrderFilter,
  myOrderChips,
  myOrderSources,
  rentalItem,
  venueItem,
  type MyOrderItem,
  type MyOrderSource,
} from '../application/my-order-sources'
import {
  MY_ORDER_CHIPS,
  MY_ORDER_TABS,
  buildMyOrdersUrl,
  parseMyOrderChip,
  parseMyOrderTab,
} from '../application/my-orders-route'

function ticket(overrides: Partial<EnrollmentTicket> = {}): EnrollmentTicket {
  return {
    ticketId: 't-1',
    participantId: 'p-1',
    participantName: '王小禾',
    relation: 'CHILD',
    age: 11,
    ticketCode: 'ACT-1234-5678-9ABC',
    status: 'ACTIVE',
    checkedInAt: null,
    cancelledAt: null,
    refund: null,
    result: null,
    ...overrides,
  }
}

function enrollment(overrides: Partial<Enrollment> = {}): Enrollment {
  return {
    enrollmentId: 'e-1',
    status: 'UPCOMING',
    activity: {
      activityId: 'a-1',
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    session: {
      sessionId: 's-1',
      label: null,
      startsAt: '2026-11-29T01:00:00Z',
      endsAt: '2026-11-29T05:00:00Z',
      checkInOpensAt: null,
    },
    location: { name: '东营低空与机器人体验馆', address: '示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-18T01:00:00Z',
    cancellableUntil: '2026-11-28T01:00:00Z',
    cancellable: true,
    tickets: [ticket()],
    payment: { status: 'PAID', tradeOrderId: 'o-9', amountMinor: 24000, unitAmountMinor: 24000, payBefore: null },
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: false,
    ...overrides,
  }
}

function inspection(overrides: Partial<InspectionOrderSummary> = {}): InspectionOrderSummary {
  return {
    reservationId: 'res-1',
    reservationNo: 'IR2026111800001',
    serviceId: 'svc-1',
    serviceTitle: '电力线路巡检',
    cityCode: '370500',
    locationSummary: '东营区',
    preferredServiceDate: '2026-11-25',
    submittedAt: '2026-11-17T01:00:00Z',
    stage: 'AWAITING_PLAN',
    reservationStatus: 'SUBMITTED',
    currency: 'CNY',
    ...overrides,
  }
}

describe('我的订单 route', () => {
  it('carries only the tab and the chip', () => {
    expect(buildMyOrdersUrl()).toBe('/pages/mine/orders')
    expect(buildMyOrdersUrl({ chip: 'pay' })).toBe('/pages/mine/orders?status=pay')
    expect(buildMyOrdersUrl({ tab: 'service', chip: 'accept' })).toBe('/pages/mine/orders?tab=service&status=accept')
    expect(buildMyOrdersUrl({ tab: 'all', chip: null })).toBe('/pages/mine/orders')
  })

  it('reads unknown values as 全部 and no chip', () => {
    expect(parseMyOrderTab('enroll')).toBe('enroll')
    expect(parseMyOrderTab('x')).toBe('all')
    expect(parseMyOrderTab(undefined)).toBe('all')
    expect(parseMyOrderChip('refund')).toBe('refund')
    expect(parseMyOrderChip('REFUND')).toBeNull()
    expect(parseMyOrderChip(undefined)).toBeNull()
  })
})

describe('我的订单 status chips (board OrderList)', () => {
  it('puts goods orders by status: unpaid, paid not shipped, refunds; shipped, received and closed in none', () => {
    expect(myOrderChips(goodsItem(tradeOrder()))).toEqual(['pay'])
    expect(myOrderChips(goodsItem(paidOrder()))).toEqual(['ship'])
    expect(myOrderChips(goodsItem(paidOrder({ status: 'SHIPPED' })))).toEqual([])
    expect(myOrderChips(goodsItem(paidOrder({ status: 'COMPLETED' })))).toEqual([])
    expect(myOrderChips(goodsItem(tradeOrder({ status: 'CLOSED' })))).toEqual([])
    for (const status of ['REFUNDING', 'REFUNDED', 'REFUND_FAILED'] as const) {
      expect(myOrderChips(goodsItem(paidOrder({ status })))).toEqual(['refund'])
    }
  })

  it('puts rentals waiting for payment or the handover in a chip; in use, ended or cancelled in none', () => {
    expect(myOrderChips(rentalItem(rentalOrder({ status: 'PENDING_PAYMENT' })))).toEqual(['pay'])
    expect(myOrderChips(rentalItem(rentalOrder({ status: 'AWAITING_HANDOVER' })))).toEqual(['ship'])
    expect(myOrderChips(rentalItem(rentalOrder({ status: 'HANDOVER_RECORDED' })))).toEqual(['ship'])
    for (const status of ['IN_USE', 'RETURN_RECORDED', 'ENDED', 'CANCELLED', 'CLOSED'] as const) {
      expect(myOrderChips(rentalItem(rentalOrder({ status })))).toEqual([])
    }
  })

  it('puts inspections in progress under 待发货/履约 and reports under 待验收; plan and confirmation in none', () => {
    expect(myOrderChips(inspectionItem(inspection({ stage: 'IN_PROGRESS' })))).toEqual(['ship'])
    expect(myOrderChips(inspectionItem(inspection({ stage: 'AWAITING_ACCEPTANCE' })))).toEqual(['accept'])
    for (const stage of ['AWAITING_PLAN', 'AWAITING_CONFIRMATION', 'COMPLETED', 'CANCELLED'] as const) {
      expect(myOrderChips(inspectionItem(inspection({ stage })))).toEqual([])
    }
  })

  it('puts sign-ups by payment, by whether someone is still going, and by refunds', () => {
    expect(myOrderChips(enrollItem(enrollment()))).toEqual(['attend'])
    expect(myOrderChips(enrollItem(enrollment({
      payment: { status: 'PENDING_PAYMENT', tradeOrderId: 'o-9', amountMinor: 24000, unitAmountMinor: 24000, payBefore: '2026-11-18T02:15:00Z' },
    })))).toEqual(['pay'])
    // One of two refunded and the other still going: both chips.
    expect(myOrderChips(enrollItem(enrollment({
      tickets: [ticket(), ticket({ ticketId: 't-2', status: 'CANCELLED', refund: { status: 'PROCESSING', amountMinor: 24000 } })],
    })))).toEqual(['attend', 'refund'])
    expect(myOrderChips(enrollItem(enrollment({
      status: 'CANCELLED',
      tickets: [ticket({ status: 'CANCELLED', refund: { status: 'SUCCEEDED', amountMinor: 24000 } })],
    })))).toEqual(['refund'])
    // Free and cancelled, or over: no chip.
    expect(myOrderChips(enrollItem(enrollment({
      status: 'CANCELLED',
      tickets: [ticket({ status: 'CANCELLED' })],
      payment: { status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null },
    })))).toEqual([])
    expect(myOrderChips(enrollItem(enrollment({ status: 'FINISHED', tickets: [ticket({ status: 'CHECKED_IN' })] })))).toEqual([])
  })

  it('puts venue bookings by status; a cancelled one with a refund under 退款/售后', () => {
    expect(myOrderChips(venueItem(pendingBooking()))).toEqual(['pay'])
    expect(myOrderChips(venueItem(venueBooking()))).toEqual(['attend'])
    expect(myOrderChips(venueItem(venueBooking({ status: 'CANCELLED', refund: { status: 'SUCCEEDED', amountMinor: 7800 } })))).toEqual(['refund'])
    for (const status of ['CHECKED_IN', 'EXPIRED', 'CLOSED'] as const) {
      expect(myOrderChips(venueItem(venueBooking({ status })))).toEqual([])
    }
  })

  it('filters by the business tab first', () => {
    const goods = goodsItem(tradeOrder())
    const rental = rentalItem(rentalOrder({ status: 'PENDING_PAYMENT' }))
    const booking = venueItem(pendingBooking())
    expect(matchesMyOrderFilter(goods, 'all', 'pay')).toBe(true)
    expect(matchesMyOrderFilter(goods, 'service', null)).toBe(false)
    expect(matchesMyOrderFilter(rental, 'service', 'pay')).toBe(true)
    expect(matchesMyOrderFilter(rental, 'goods', null)).toBe(false)
    expect(matchesMyOrderFilter(booking, 'enroll', 'pay')).toBe(true)
    expect(matchesMyOrderFilter(booking, 'enroll', 'attend')).toBe(false)
  })
})

describe('我的订单 sources', () => {
  const everything = () => true

  it('reads every list unfiltered for 全部 and only the tab\'s lists for a tab', () => {
    expect(myOrderSources('all', null, everything).map((source) => source.kind))
      .toEqual(['GOODS', 'RENTAL', 'INSPECTION', 'ENROLL', 'ENROLL', 'ENROLL', 'VENUE', 'VENUE', 'VENUE'])
    expect(myOrderSources('goods', null, everything)).toEqual([{ kind: 'GOODS', tab: 'ALL' }])
    expect(myOrderSources('service', null, everything)).toEqual([
      { kind: 'RENTAL', status: null },
      { kind: 'INSPECTION', stage: 'ALL' },
    ])
    expect(myOrderSources('enroll', 'accept', everything)).toEqual([])
  })

  it('reads a chip with the server filters that hold it', () => {
    expect(myOrderSources('all', 'pay', everything)).toEqual([
      { kind: 'GOODS', tab: 'TO_PAY' },
      { kind: 'RENTAL', status: 'PENDING_PAYMENT' },
      { kind: 'ENROLL', bucket: 'UPCOMING' },
      { kind: 'VENUE', bucket: 'UPCOMING' },
    ])
    expect(myOrderSources('service', 'ship', everything)).toEqual([
      { kind: 'RENTAL', status: 'AWAITING_HANDOVER' },
      { kind: 'RENTAL', status: 'HANDOVER_RECORDED' },
      { kind: 'INSPECTION', stage: 'IN_PROGRESS' },
    ])
    expect(myOrderSources('all', 'accept', everything)).toEqual([{ kind: 'INSPECTION', stage: 'AWAITING_ACCEPTANCE' }])
    expect(myOrderSources('all', 'refund', everything).map((source) => source.kind))
      .toEqual(['GOODS', 'ENROLL', 'ENROLL', 'ENROLL', 'VENUE'])
  })

  it('leaves out kinds whose pages are not open', () => {
    expect(myOrderSources('all', 'pay', (kind) => kind === 'GOODS')).toEqual([{ kind: 'GOODS', tab: 'TO_PAY' }])
  })

  it('pages goods and rentals, reads the other lists to the end', () => {
    expect(isCompleteSource({ kind: 'GOODS', tab: 'ALL' })).toBe(false)
    expect(isCompleteSource({ kind: 'RENTAL', status: null })).toBe(false)
    expect(isCompleteSource({ kind: 'INSPECTION', stage: 'ALL' })).toBe(true)
    expect(isCompleteSource({ kind: 'ENROLL', bucket: 'UPCOMING' })).toBe(true)
    expect(isCompleteSource({ kind: 'VENUE', bucket: 'CANCELLED' })).toBe(true)
  })

  it('every card a chip shows comes from one of the chip\'s lists', () => {
    const samples: Array<{ item: MyOrderItem; from: MyOrderSource[] }> = [
      { item: goodsItem(tradeOrder()), from: [{ kind: 'GOODS', tab: 'TO_PAY' }] },
      { item: goodsItem(paidOrder()), from: [{ kind: 'GOODS', tab: 'TO_SHIP' }] },
      { item: goodsItem(paidOrder({ status: 'REFUNDED' })), from: [{ kind: 'GOODS', tab: 'REFUND' }] },
      { item: rentalItem(rentalOrder({ status: 'HANDOVER_RECORDED' })), from: [{ kind: 'RENTAL', status: 'HANDOVER_RECORDED' }] },
      { item: inspectionItem(inspection({ stage: 'AWAITING_ACCEPTANCE' })), from: [{ kind: 'INSPECTION', stage: 'AWAITING_ACCEPTANCE' }] },
      { item: venueItem(venueBooking({ status: 'CANCELLED', refund: { status: 'PROCESSING', amountMinor: 7800 } })), from: [{ kind: 'VENUE', bucket: 'CANCELLED' }] },
    ]
    for (const tab of MY_ORDER_TABS) {
      for (const chip of MY_ORDER_CHIPS) {
        const wanted = myOrderSources(tab, chip, () => true)
        for (const sample of samples) {
          if (!matchesMyOrderFilter(sample.item, tab, chip)) continue
          expect(sample.from.some((source) => wanted.some((one) => JSON.stringify(one) === JSON.stringify(source)))).toBe(true)
        }
      }
    }
  })
})

describe('我的订单 loading', () => {
  function apis(overrides: Partial<MyOrderApis> = {}): MyOrderApis & { calls: string[] } {
    const calls: string[] = []
    const empty = { items: [], hasMore: false }
    return {
      calls,
      goods: async (_token, tab, page, size) => { calls.push(`goods ${tab} ${page} ${size}`); return empty },
      rental: async (_token, status, page, size) => { calls.push(`rental ${status} ${page} ${size}`); return empty },
      inspection: async (stage, page, size) => { calls.push(`inspection ${stage} ${page} ${size}`); return empty },
      enroll: async (_token, bucket, page, size) => { calls.push(`enroll ${bucket} ${page} ${size}`); return empty },
      venue: async (_token, bucket, page, size) => { calls.push(`venue ${bucket} ${page} ${size}`); return empty },
      ...overrides,
    }
  }

  it('reads a complete list page by page to its end, and a paged one a page at a time', async () => {
    const enrolled = [enrollment({ enrollmentId: 'e-1' }), enrollment({ enrollmentId: 'e-2' })]
    const fake = apis({
      enroll: async (_token, _bucket, page) => ({ items: [enrolled[page - 1]], hasMore: page < 2 }),
    })
    const loaded = await loadFirstMyOrderPages(fake, 'token', [
      { kind: 'GOODS', tab: 'ALL' },
      { kind: 'ENROLL', bucket: 'UPCOMING' },
    ])
    expect(fake.calls).toEqual([`goods ALL 1 ${MY_ORDER_PAGE_SIZE}`])
    expect(loaded[1].items.map((item) => item.id)).toEqual(['enroll:e-1', 'enroll:e-2'])
    expect(loaded[1].hasMore).toBe(false)
    expect(COMPLETE_PAGE_SIZE).toBe(50)
  })

  it('merges goods and rentals by order time, newest first, loading the next page that decides', async () => {
    const goodsPages = [
      [tradeOrder({ orderId: 'g-1', createdAt: '2026-11-18T05:00:00Z' }), tradeOrder({ orderId: 'g-2', createdAt: '2026-11-18T03:00:00Z' })],
      [tradeOrder({ orderId: 'g-3', createdAt: '2026-11-18T01:00:00Z' })],
    ]
    const fake = apis({
      goods: async (_token, _tab, page) => ({ items: goodsPages[page - 1], hasMore: page < 2 }),
      rental: async () => ({ items: [rentalOrder({ rentalOrderId: 'r-1', createdAt: '2026-11-18T02:00:00Z' })], hasMore: false }),
    })
    const sources: MyOrderSource[] = [{ kind: 'GOODS', tab: 'ALL' }, { kind: 'RENTAL', status: null }]
    const first = await loadFirstMyOrderPages(fake, 'token', sources)
    // The rental is older than the last loaded goods order; goods page 2 might hold something newer.
    expect(mergedItems(first, MY_ORDER_KEYS).map((item) => item.id)).toEqual(['goods:g-1', 'goods:g-2'])
    const more = await loadMoreMyOrders(fake, 'token', sources, first)
    expect(mergedItems(more, MY_ORDER_KEYS).map((item) => item.id)).toEqual(['goods:g-1', 'goods:g-2', 'rental:r-1', 'goods:g-3'])
  })
})

describe('我的订单 cards', () => {
  it('draws a goods card like 商品订单: countdown, cancel and pay', () => {
    const card = describeMyOrderCard(goodsItem(tradeOrder()), NOW)
    expect(card?.tag).toEqual({ label: '商品', tone: 'ORANGE' })
    expect(card?.head).toBe('示例机器人商家')
    expect(card?.status).toEqual({ text: '待付款', tone: 'ORANGE' })
    expect(card?.amount).toBe('¥1,299')
    expect(card?.lines).toEqual([
      { key: 'spec', text: '标准版 × 1 · 快递配送', tone: 'MUTED' },
      { key: 'hint', text: '剩 29:45 未支付将自动取消', tone: 'COUNTDOWN' },
    ])
    expect(card?.actions.map((action) => [action.label, action.style])).toEqual([['取消订单', 'OUTLINE'], ['继续支付', 'PRIMARY']])
    expect(card?.actions[1].target).toEqual({ kind: 'URL', url: '/pages/trade/cashier?orderId=o-1' })
    expect(card?.url).toBe('/pages/mine/goods-order?orderId=o-1')
  })

  it('draws a shipped goods card with 查看订单 and 确认收货', () => {
    const card = describeMyOrderCard(goodsItem(paidOrder({
      status: 'SHIPPED',
      delivery: { mode: 'EXPRESS', recipientName: null, phoneMasked: null, addressLine: null, pickupPoint: null, pickupCode: null, carrierName: '示例快递', trackingNo: 'SF1', shippedAt: '2026-11-03T02:24:00Z' },
    })), NOW)
    expect(card?.lines[1]).toEqual({ key: 'hint', text: '11/03 10:24 已发货 · 示例快递', tone: 'SHIPPED' })
    expect(card?.actions.map((action) => [action.label, action.style, action.target.kind]))
      .toEqual([['查看订单', 'OUTLINE', 'URL'], ['确认收货', 'ACCENT', 'GOODS_RECEIPT']])
  })

  it('draws a sign-up card: purple tag, participants, the time and place, 查看凭证', () => {
    const card = describeMyOrderCard(enrollItem(enrollment()), NOW)
    expect(card?.tag).toEqual({ label: '报名', tone: 'PURPLE' })
    expect(card?.status).toEqual({ text: '待参加', tone: 'PURPLE' })
    expect(card?.title).toBe('青少年无人机编程挑战赛')
    expect(card?.titleTail).toBe('（东营站）')
    expect(card?.amount).toBe('¥240')
    expect(card?.lines[0]).toEqual({ key: 'who', text: '参与人：王小禾', tone: 'MUTED' })
    expect(card?.lines[1].text).toMatch(/ · 东营低空与机器人体验馆$/)
    expect(card?.actions).toEqual([
      { key: 'ticket', label: '查看凭证', style: 'PRIMARY', target: { kind: 'URL', url: '/pages/activity/ticket?enrollmentId=e-1' } },
    ])
    // Paid: the card itself opens the order.
    expect(card?.url).toBe('/pages/activity/order?enrollmentId=e-1')
  })

  it('draws a rental card with the lessee, the period and 查看详情', () => {
    const card = describeMyOrderCard(rentalItem(rentalOrder()), NOW, { 'i-1': '/static/images/preview/robot-dog.jpg' })
    expect(card?.tag).toEqual({ label: '租赁', tone: 'ORANGE' })
    expect(card?.status.text).toBe('待交接')
    expect(card?.cover).toBe('/static/images/preview/robot-dog.jpg')
    expect(card?.title).toBe('巡检机器狗 ×1')
    expect(card?.lines.map((line) => line.text)).toEqual(['企业 · 东营港务物流', '租期 11/22–11/26 · 共 5 天'])
    const inUse = describeMyOrderCard(rentalItem(rentalOrder({ status: 'IN_USE' })), Date.parse('2026-11-24T02:00:00Z'))
    expect(inUse?.lines[1].text).toBe('租期 11/22–11/26 · 还有 2 天到期')
    expect(card?.actions.map((action) => action.label)).toEqual(['查看详情'])
    expect(card?.url).toBe('/pages/rental/order?rentalOrderId=r-1')
  })

  it('draws a venue booking card; unpaid ones go to the cashier', () => {
    const now = Date.parse('2026-11-20T02:00:00Z')
    const pending = describeMyOrderCard(venueItem(pendingBooking()), now)
    expect(pending?.tag.label).toBe('体验')
    expect(pending?.status).toEqual({ text: '待付款', tone: 'ORANGE' })
    expect(pending?.actions).toEqual([
      { key: 'pay', label: '去付款', style: 'PRIMARY', target: { kind: 'URL', url: '/pages/trade/cashier?orderId=o-1' } },
    ])
    const confirmed = describeMyOrderCard(venueItem(venueBooking()), now)
    expect(confirmed?.lines[0].text).toBe('参与人：王小禾、王小川')
    expect(confirmed?.actions.map((action) => action.label)).toEqual(['出示核销码'])
    expect(confirmed?.url).toBe('/pages/venue/booking?bookingId=b-1')
  })

  it('leaves inspection orders to their own card', () => {
    expect(describeMyOrderCard(inspectionItem(inspection()), NOW)).toBeNull()
  })
})
