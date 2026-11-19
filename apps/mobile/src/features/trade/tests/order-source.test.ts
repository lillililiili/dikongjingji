import { describe, expect, it } from 'vitest'

import {
  cancelUnpaidConfirm,
  cancelledToast,
  cashierClosedNote,
  cashierCountdownLine,
  cashierDueNote,
  cashierRows,
  failedNote,
  isSourceOrder,
  orderDestination,
  orderLinkLabel,
  paidFrame,
} from '../application/order-source'
import { TRADE_COMMAND_CONFIRMS } from '../application/order-commands'
import { describeOrderStatus, listCardGoods } from '../application/order-view'
import { toTradeOrder } from '../data/trade-api'
import { NOW, enrollmentOrder, paidOrder, rentalTradeOrder, tradeOrder, venueOrder } from './trade-fixtures'

describe('order source (contract 0.2.0)', () => {
  it('reads a sign-up order without delivery or merchant type, and an older order as a mall order', () => {
    const dto = { ...enrollmentOrder(), merchant: { organizationId: 'org-2', name: '示例研学机构', kind: null } }
    const order = toTradeOrder(dto as never)
    expect(order).toMatchObject({ source: 'ACTIVITY', sourceRef: 'e-1', delivery: null })
    expect(order.merchant.kind).toBeNull()

    const older = { ...tradeOrder(), source: undefined, sourceRef: undefined }
    expect(toTradeOrder(older as never)).toMatchObject({ source: 'MALL', sourceRef: null })
    // A mall order never carries a business reference, whatever is sent.
    expect(toTradeOrder({ ...tradeOrder(), sourceRef: 'x' } as never).sourceRef).toBeNull()
  })

  it('leads a sign-up order to its ticket and a goods order to the order page', () => {
    expect(orderDestination(enrollmentOrder())).toEqual({ kind: 'ACTIVITY_ENROLLMENT', enrollmentId: 'e-1' })
    expect(orderDestination(tradeOrder())).toEqual({ kind: 'GOODS_ORDER', orderId: 'o-1' })
    // Without its reference a sign-up order has nowhere else to go.
    expect(orderDestination(enrollmentOrder({ sourceRef: null }))).toEqual({ kind: 'GOODS_ORDER', orderId: 'o-1' })
  })

  it('describes a sign-up on the cashier like the board CashierEnroll', () => {
    const order = enrollmentOrder()
    expect(cashierRows(order)).toEqual([
      { key: 'activity', label: '活动', value: '机器人研学体验' },
      { key: 'session', label: '场次', value: '11月28日 09:30 上午' },
      { key: 'people', label: '人数', value: '2 人' },
    ])
    expect(cashierDueNote(order)).toBe('报名费 2 人 × ¥168')
    expect(cashierCountdownLine(order, NOW)).toBe('名额保留 14:45 · 超时自动释放')
    expect(cashierClosedNote(order)).toBe('没有扣款，名额已释放')
    expect(cancelUnpaidConfirm(order).title).toBe('取消报名')
  })

  it('keeps the goods wording of E07 on mall orders', () => {
    const order = tradeOrder()
    expect(cashierRows(order)).toEqual([{ key: 'goods', label: '商品', value: '编程机器人探索套装 · 标准版 × 1' }])
    expect(cashierDueNote(order)).toBeNull()
    expect(cashierCountdownLine(order, NOW)).toBe('剩余支付时间 29:45 · 超时订单自动取消')
    expect(cashierClosedNote(order)).toBe('没有扣款，可以重新下单')
    expect(cancelUnpaidConfirm(order)).toBe(TRADE_COMMAND_CONFIRMS.CANCEL)
    expect(orderLinkLabel(paidOrder())).toBe('查看订单')
    expect(paidFrame(paidOrder())).toEqual({ title: '已支付', note: null })
  })

  it('names the way on from the result page by what the order is', () => {
    expect(orderLinkLabel(enrollmentOrder())).toBe('查看报名')
    expect(orderLinkLabel(enrollmentOrder({ status: 'CLOSED' }))).toBe('查看报名')
    expect(orderLinkLabel(enrollmentOrder({ status: 'PAID' }))).toBe('查看凭证')
    expect(paidFrame(enrollmentOrder({ status: 'PAID' }))).toEqual({ title: '报名成功', note: '2 个名额已确认 · 一人一码' })
  })

  it('lists and labels an order without delivery', () => {
    const order = enrollmentOrder({ status: 'PAID' })
    expect(describeOrderStatus(order, NOW)).toEqual({ label: '已付款', tone: 'DONE' })
    expect(listCardGoods(order)).toEqual({ name: '机器人研学体验', spec: '11月28日 09:30 上午 × 2' })
  })

  it('reads a venue booking order and leads it to the booking\'s code (E10)', () => {
    const order = toTradeOrder({ ...venueOrder(), merchant: { organizationId: 'org-4', name: '东营低空与机器人体验馆', kind: null } } as never)
    expect(order).toMatchObject({ source: 'VENUE', sourceRef: 'b-1', delivery: null })
    expect(isSourceOrder(order)).toBe(true)
    expect(orderDestination(order)).toEqual({ kind: 'VENUE_BOOKING', bookingId: 'b-1' })
    expect(orderDestination(venueOrder({ sourceRef: null }))).toEqual({ kind: 'GOODS_ORDER', orderId: 'o-1' })
  })

  it('describes a venue booking on the cashier like the board CashierVenue', () => {
    const order = venueOrder()
    expect(cashierRows(order)).toEqual([
      { key: 'venue-item', label: '预约内容', value: '东营低空与机器人体验馆 机器人避障闯关 · 11月21日 10:00' },
      { key: 'people', label: '人数', value: '1 人' },
    ])
    expect(cashierDueNote(order)).toBe('到馆体验费 1 人 × ¥39')
    expect(cashierCountdownLine(order, NOW)).toBe('剩余支付时间 14:45 · 超时释放场次名额')
    expect(cashierClosedNote(order)).toBe('没有扣款，名额已释放')
    expect(cancelUnpaidConfirm(order).title).toBe('取消预约')
    expect(cancelledToast(order)).toBe('预约已取消')
    expect(failedNote(order)).toBe('预约仍为待支付，超时释放场次名额')
    expect(orderLinkLabel(order)).toBe('查看预约')
    expect(orderLinkLabel(venueOrder({ status: 'PAID' }))).toBe('查看核销码')
    expect(paidFrame(venueOrder({ status: 'PAID' }))).toEqual({ title: '预约成功 · 已支付', note: '核销码已生成，到馆时出示' })
  })

  it('keeps the wording of mall and sign-up orders on the shared messages', () => {
    expect(cancelledToast(tradeOrder())).toBe('订单已取消')
    expect(cancelledToast(enrollmentOrder())).toBe('报名已取消')
    expect(failedNote(tradeOrder())).toBe('订单仍为待付款，超时订单自动取消')
    expect(failedNote(enrollmentOrder())).toBe('名额仍为你保留，超时自动释放')
    expect(isSourceOrder(tradeOrder())).toBe(false)
  })

  it('reads a rental order and leads it to the rental order page (F3)', () => {
    const order = toTradeOrder({ ...rentalTradeOrder(), merchant: { organizationId: 'org-1', name: '示例机器人商家', kind: null } } as never)
    expect(order).toMatchObject({ source: 'RENTAL', sourceRef: 'r-1', delivery: null })
    expect(isSourceOrder(order)).toBe(true)
    expect(orderDestination(order)).toEqual({ kind: 'RENTAL_ORDER', rentalOrderId: 'r-1' })
    expect(orderDestination(rentalTradeOrder({ sourceRef: null }))).toEqual({ kind: 'GOODS_ORDER', orderId: 'o-1' })
  })

  it('describes a rental on the cashier like the board CashierRental', () => {
    const order = rentalTradeOrder()
    expect(cashierRows(order)).toEqual([
      { key: 'rental-item', label: '租赁内容', value: '巡检机器狗 × 1 台' },
      { key: 'rental-period', label: '租期', value: '11/22–11/26 共 5 天' },
      { key: 'lessor', label: '出租方', value: '示例机器人商家' },
    ])
    expect(cashierDueNote(order)).toBe('租金 · 合同确认后一次付清')
    expect(cashierCountdownLine(order, NOW)).toBe('剩余支付时间 29:45 · 超时订单关闭，需重新询价')
    expect(cashierClosedNote(order)).toBe('没有扣款，报价已失效，要租需要重新询价')
    expect(cancelUnpaidConfirm(order).title).toBe('取消支付')
    expect(cancelledToast(order)).toBe('租赁订单已关闭')
    expect(failedNote(order)).toBe('租金仍为待支付，超时订单关闭、报价失效')
    expect(orderLinkLabel(order)).toBe('查看租赁订单')
    expect(paidFrame(rentalTradeOrder({ status: 'PAID' }))).toEqual({ title: '租金已支付', note: '商家将按约定时间交接设备，交接后由你确认' })
  })
})
