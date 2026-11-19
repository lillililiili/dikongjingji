import { describe, expect, it } from 'vitest'

import {
  autoReceiptAt,
  canPay,
  cashierGoodsLine,
  closedOrderTitle,
  describeCashierClosed,
  describeClosedOrder,
  describeOrderHeader,
  describeOrderStatus,
  describePayment,
  detailActions,
  formatCountdown,
  formatTradeAmount,
  formatTradeDateTime,
  formatTradeTime,
  isPaymentOverdue,
  listCardActions,
  listCardGoods,
  listCardHint,
} from '../application/order-view'
import type { TradeOrder, TradeOrderStatus } from '../domain/trade-order'
import { NOW, paidOrder, tradeOrder } from './trade-fixtures'

const SHIPPED_AT = '2026-11-18T02:24:00Z'

function shippedOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  const base = paidOrder()
  return paidOrder({
    status: 'SHIPPED',
    cancellable: false,
    delivery: { ...base.delivery, carrierName: '示例快递', trackingNo: 'SF1', shippedAt: SHIPPED_AT },
    ...overrides,
  })
}

describe('trade formatting', () => {
  it('formats the countdown as mm:ss, past an hour as h:mm:ss, never below zero', () => {
    expect(formatCountdown(29 * 60_000 + 45_000)).toBe('29:45')
    expect(formatCountdown(59_999)).toBe('00:59')
    expect(formatCountdown(3_909_000)).toBe('1:05:09')
    expect(formatCountdown(-5_000)).toBe('00:00')
  })

  it('writes times in Beijing time and amounts in yuan', () => {
    expect(formatTradeTime('2026-11-17T16:05:00Z')).toBe('11/18 00:05')
    expect(formatTradeDateTime('2026-11-18T02:24:00Z')).toBe('2026-11-18 10:24')
    expect(formatTradeAmount(129900)).toBe('¥1,299')
    expect(formatTradeAmount(32950)).toBe('¥329.50')
  })

  it('confirms receipt automatically ten days after shipping', () => {
    expect(autoReceiptAt(SHIPPED_AT)).toBe('2026-11-28T02:24:00.000Z')
  })
})

describe('order states', () => {
  it('can pay only while unpaid and before the deadline', () => {
    expect(canPay(tradeOrder(), NOW)).toBe(true)
    expect(canPay(tradeOrder(), Date.parse('2026-11-18T02:29:45Z'))).toBe(false)
    expect(isPaymentOverdue(tradeOrder(), Date.parse('2026-11-18T02:30:00Z'))).toBe(true)
    expect(canPay(paidOrder(), NOW)).toBe(false)
    expect(isPaymentOverdue(paidOrder(), NOW)).toBe(false)
  })

  it('labels every status on a card', () => {
    const labels: Record<TradeOrderStatus, string> = {
      PENDING_PAYMENT: '待付款',
      PAID: '待发货',
      SHIPPED: '已发货',
      COMPLETED: '已完成',
      CLOSED: '已关闭',
      REFUNDING: '退款中',
      REFUNDED: '已退款',
      REFUND_FAILED: '退款异常',
    }
    for (const [status, label] of Object.entries(labels)) {
      expect(describeOrderStatus(tradeOrder({ status: status as TradeOrderStatus }), NOW).label).toBe(label)
    }
    const pickup = paidOrder({ delivery: { ...paidOrder().delivery, mode: 'PICKUP' } })
    expect(describeOrderStatus(pickup, NOW)).toEqual({ label: '待自提', tone: 'ACTION' })
    expect(describeOrderStatus(tradeOrder(), Date.parse('2026-11-18T03:00:00Z'))).toEqual({ label: '已超时', tone: 'MUTED' })
    expect(describeOrderStatus(tradeOrder({ status: 'COMPLETED' }), NOW).tone).toBe('DONE')
    expect(describeOrderStatus(tradeOrder({ status: 'REFUND_FAILED' }), NOW).tone).toBe('ALERT')
  })

  it('heads the order page with the countdown, the auto-receipt date or what happened to the money', () => {
    expect(describeOrderHeader(tradeOrder(), NOW))
      .toEqual({ title: '待付款', line: '请在 29:45 内完成支付，超时订单自动取消' })
    expect(describeOrderHeader(tradeOrder(), Date.parse('2026-11-18T02:31:00Z')).title).toBe('支付已超时')
    expect(describeOrderHeader(shippedOrder(), NOW))
      .toEqual({ title: '已发货', line: '11/28 10:24 将自动确认收货（发货 10 天后）' })
    expect(describeOrderHeader(paidOrder({ delivery: { ...paidOrder().delivery, mode: 'PICKUP' } }), NOW).title).toBe('待自提')
    expect(describeOrderHeader(paidOrder(), NOW).title).toBe('待发货')
    expect(describeOrderHeader(tradeOrder({ status: 'CLOSED', payBefore: null }), NOW))
      .toEqual({ title: '订单已关闭', line: '订单未支付，已关闭，没有扣款' })
    expect(describeOrderHeader(tradeOrder({ status: 'CLOSED', payBefore: null, closeReason: 'BUYER_CANCEL' }), NOW))
      .toEqual({ title: '订单已取消', line: '你已取消这笔订单，没有扣款' })
    expect(describeOrderHeader(tradeOrder({ status: 'CLOSED', payBefore: null, closeReason: 'TIMEOUT' }), NOW))
      .toEqual({ title: '订单已超时关闭', line: '超过支付时限未支付，订单已自动关闭，没有扣款' })
    const refunded = paidOrder({
      status: 'REFUNDED',
      amounts: { goodsMinor: 129900, shippingMinor: 0, payableMinor: 129900, paidMinor: 129900, refundedMinor: 129900 },
    })
    expect(describeOrderHeader(refunded, NOW)).toEqual({ title: '已退款', line: '¥1,299 已原路退回' })
    expect(describeOrderHeader(paidOrder({ status: 'REFUNDING' }), NOW).title).toBe('退款中')
    expect(describeOrderHeader(paidOrder({ status: 'REFUND_FAILED' }), NOW).title).toBe('退款异常')
    expect(describeOrderHeader(shippedOrder({ status: 'COMPLETED' }), NOW).line).toBe('已确认收货，交易完成')
  })
})

describe('order buttons', () => {
  it('offers cancel and pay while unpaid, receipt once shipped, else just a look', () => {
    expect(listCardActions(tradeOrder(), NOW)).toEqual(['CANCEL', 'PAY'])
    expect(listCardActions(shippedOrder(), NOW)).toEqual(['VIEW', 'CONFIRM_RECEIPT'])
    expect(listCardActions(paidOrder(), NOW)).toEqual(['VIEW'])
    expect(listCardActions(tradeOrder(), Date.parse('2026-11-18T03:00:00Z'))).toEqual(['VIEW'])
  })

  it('lets the order page cancel a paid order only while the server says it can, as a refund', () => {
    expect(detailActions(tradeOrder(), NOW)).toEqual(['CANCEL', 'PAY'])
    expect(detailActions(paidOrder(), NOW)).toEqual(['CANCEL_AND_REFUND'])
    expect(detailActions(paidOrder({ cancellable: false }), NOW)).toEqual([])
    expect(detailActions(shippedOrder(), NOW)).toEqual(['CONFIRM_RECEIPT'])
    expect(detailActions(paidOrder({ status: 'REFUNDING', cancellable: false }), NOW)).toEqual([])
    expect(detailActions(tradeOrder({ status: 'CLOSED', payBefore: null, cancellable: false }), NOW)).toEqual([])
  })
})

describe('card and cashier text', () => {
  it('hints the countdown, the shipment, the pickup point or the closing', () => {
    expect(listCardHint(tradeOrder(), NOW)).toEqual({ kind: 'COUNTDOWN', text: '剩 29:45 未支付将自动取消' })
    expect(listCardHint(shippedOrder(), NOW)).toEqual({ kind: 'SHIPPED', text: '11/18 10:24 已发货 · 示例快递' })
    const pickup = paidOrder({ delivery: { ...paidOrder().delivery, mode: 'PICKUP', pickupPoint: '体验馆自提点' } })
    expect(listCardHint(pickup, NOW)).toEqual({ kind: 'NOTE', text: '自提点 体验馆自提点' })
    expect(listCardHint(tradeOrder({ status: 'CLOSED', payBefore: null }), NOW)).toEqual({ kind: 'NOTE', text: '订单未支付，已关闭' })
    expect(listCardHint(tradeOrder({ status: 'CLOSED', payBefore: null, closeReason: 'BUYER_CANCEL' }), NOW))
      .toEqual({ kind: 'NOTE', text: '订单已取消' })
    expect(listCardHint(tradeOrder({ status: 'CLOSED', payBefore: null, closeReason: 'TIMEOUT' }), NOW))
      .toEqual({ kind: 'NOTE', text: '订单已超时关闭' })
    expect(listCardHint(paidOrder(), NOW)).toBeNull()
  })

  it('stands the first line for the order and counts the others', () => {
    expect(listCardGoods(tradeOrder())).toEqual({ name: '编程机器人探索套装', spec: '标准版 × 1 · 快递配送' })
    const two = tradeOrder({
      lines: [
        tradeOrder().lines[0],
        { ...tradeOrder().lines[0], lineId: 'l-2', productName: '避障训练场地套件', quantity: 2 },
      ],
    })
    expect(listCardGoods(two)).toEqual({ name: '编程机器人探索套装 等 2 种商品', spec: '共 3 件 · 快递配送' })
    expect(cashierGoodsLine(tradeOrder())).toBe('编程机器人探索套装 · 标准版 × 1')
    expect(cashierGoodsLine(two)).toBe('编程机器人探索套装 · 标准版 × 1、避障训练场地套件 · 标准版 × 2（共 3 件）')
  })

  it('says whether the payment was refunded', () => {
    expect(describePayment(tradeOrder())).toBeNull()
    expect(describePayment(paidOrder())).toBe('已支付')
    const partly = paidOrder({ amounts: { ...paidOrder().amounts, refundedMinor: 2000 } })
    expect(describePayment(partly)).toBe('已支付 · 已退 ¥20')
    const fully = paidOrder({ amounts: { ...paidOrder().amounts, refundedMinor: 129900 } })
    expect(describePayment(fully)).toBe('已支付 · 已全额退款')
  })
})

describe('closed orders', () => {
  const closed = (closeReason: TradeOrder['closeReason']) =>
    tradeOrder({ status: 'CLOSED', payBefore: null, cancellable: false, closedAt: '2026-11-18T02:30:00Z', closeReason })

  it('says whether the buyer cancelled the order or time ran out', () => {
    expect(closedOrderTitle('BUYER_CANCEL')).toBe('订单已取消')
    expect(closedOrderTitle('TIMEOUT')).toBe('订单已超时关闭')
    expect(closedOrderTitle(null)).toBeNull()
    expect(describeClosedOrder(closed('BUYER_CANCEL')).title).toBe('订单已取消')
    expect(describeClosedOrder(closed('TIMEOUT')).title).toBe('订单已超时关闭')
    expect(describeClosedOrder(closed(null))).toEqual({ title: '订单已关闭', line: '订单未支付，已关闭，没有扣款' })
    // The card keeps its short status; the reason goes in the line under the goods.
    expect(describeOrderStatus(closed('TIMEOUT'), NOW)).toEqual({ label: '已关闭', tone: 'MUTED' })
  })

  it('frames the cashier by the reason and keeps 支付已超时 for an unpaid order past its deadline', () => {
    expect(describeCashierClosed(tradeOrder(), Date.parse('2026-11-18T02:31:00Z'), false))
      .toEqual({ title: '支付已超时', text: '超过支付时限未支付，订单将自动关闭' })
    expect(describeCashierClosed(closed('BUYER_CANCEL'), NOW, false))
      .toEqual({ title: '订单已取消', text: '你已取消这笔订单，订单已关闭' })
    expect(describeCashierClosed(closed('TIMEOUT'), NOW, false))
      .toEqual({ title: '订单已超时关闭', text: '超过支付时限未支付，订单已自动关闭' })
    // The server's reason wins over what happened on the page.
    expect(describeCashierClosed(closed('TIMEOUT'), NOW, true).title).toBe('订单已超时关闭')
    // An older server says nothing: cancelled here, or closed some other way.
    expect(describeCashierClosed(closed(null), NOW, true).title).toBe('订单已取消')
    expect(describeCashierClosed(closed(null), NOW, false))
      .toEqual({ title: '订单已关闭', text: '这笔订单没有支付，已关闭' })
  })
})
