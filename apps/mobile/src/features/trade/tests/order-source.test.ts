import { describe, expect, it } from 'vitest'

import {
  cancelUnpaidConfirm,
  cashierClosedNote,
  cashierCountdownLine,
  cashierDueNote,
  cashierRows,
  orderDestination,
  orderLinkLabel,
  paidFrame,
} from '../application/order-source'
import { TRADE_COMMAND_CONFIRMS } from '../application/order-commands'
import { describeOrderStatus, listCardGoods } from '../application/order-view'
import { toTradeOrder } from '../data/trade-api'
import { NOW, enrollmentOrder, paidOrder, tradeOrder } from './trade-fixtures'

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
})
