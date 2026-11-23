import { describe, expect, it } from 'vitest'

import { aftersale } from '../../trade/tests/aftersale-fixtures'
import { tradeOrder } from '../../trade/tests/trade-fixtures'
import { describeAftersaleCard } from '../application/aftersale-cards'
import { goodsItem, showsAftersales, withoutAftersaleOrders } from '../application/my-order-sources'

const ACTIONS_NONE = { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: false }

describe('aftersale card (board AftersaleList)', () => {
  it('draws a return waiting to be sent back: orange tag, urgent line, 查看进度 and 填写运单', () => {
    const card = describeAftersaleCard(aftersale({
      status: 'AWAITING_RETURN',
      dueAt: '2026-11-12T07:40:00Z',
      actions: { ...ACTIONS_NONE, canRevoke: true, canSubmitReturn: true },
    }))
    expect(card.tag).toEqual({ label: '退货退款', tone: 'ORANGE' })
    expect(card.head).toBe('示例机器人商家')
    expect(card.headMuted).toBe(true)
    expect(card.status).toEqual({ text: '待寄回', tone: 'ORANGE' })
    expect(card.title).toBe('编程机器人探索套装')
    expect(card.amount).toBe('¥1,299')
    expect(card.lines).toEqual([
      { key: 'spec', text: '标准套装（1 台 + 数字课程） × 1', tone: 'MUTED' },
      { key: 'note', text: '商家已同意，请在 11/12 15:40 前寄回', tone: 'ORANGE' },
    ])
    // The sample product has the prototype picture in a preview build (vitest runs as DEV).
    expect(card.cover).toBe('/static/images/preview/robot-rover.jpg')
    expect(card.url).toBe('/pages/trade/aftersale?aftersaleId=as-1')
    expect(card.actions.map((action) => [action.label, action.style, action.target])).toEqual([
      ['查看进度', 'OUTLINE', { kind: 'URL', url: '/pages/trade/aftersale?aftersaleId=as-1' }],
      ['填写运单', 'PRIMARY', { kind: 'URL', url: '/pages/trade/aftersale?aftersaleId=as-1&waybill=1' }],
    ])
  })

  it('draws a refund before shipping in blue, and a refunded case with 查看订单', () => {
    const refunding = describeAftersaleCard(aftersale({ kind: 'REFUND_ONLY', reason: 'NOT_WANTED', status: 'REFUNDING', actions: ACTIONS_NONE }))
    expect(refunding.tag).toEqual({ label: '仅退款', tone: 'BLUE' })
    expect(refunding.status).toEqual({ text: '退款中', tone: 'BLUE' })
    expect(refunding.actions.map((action) => action.label)).toEqual(['查看进度'])
    const refunded = describeAftersaleCard(aftersale({ status: 'REFUNDED', refundMinor: 100000, refundedAt: '2026-11-09T01:30:00Z', actions: ACTIONS_NONE }))
    expect(refunded.status).toEqual({ text: '已退款', tone: 'GREEN' })
    expect(refunded.amount).toBe('¥1,000')
    expect(refunded.actions.map((action) => [action.label, action.target])).toEqual([
      ['查看详情', { kind: 'URL', url: '/pages/trade/aftersale?aftersaleId=as-1' }],
      ['查看订单', { kind: 'URL', url: '/pages/mine/goods-order?orderId=o-1' }],
    ])
  })

  it('offers 重新申请 on a closed case only when the server allows it', () => {
    const refused = describeAftersaleCard(aftersale({
      status: 'REJECTED',
      rejectReason: '凭证不足',
      actions: { ...ACTIONS_NONE, canReapply: true },
    }))
    expect(refused.status).toEqual({ text: '商家未同意', tone: 'RED' })
    expect(refused.actions.map((action) => [action.label, action.target])).toEqual([
      ['查看详情', { kind: 'URL', url: '/pages/trade/aftersale?aftersaleId=as-1' }],
      ['重新申请', { kind: 'URL', url: '/pages/trade/aftersale-apply?orderId=o-1' }],
    ])
    const revoked = describeAftersaleCard(aftersale({ status: 'REVOKED', actions: ACTIONS_NONE }))
    expect(revoked.status).toEqual({ text: '已撤销', tone: 'GREY' })
    expect(revoked.actions.map((action) => action.label)).toEqual(['查看详情', '查看订单'])
  })
})

describe('退款/售后 chip of 我的订单 (board OrderList)', () => {
  it('lists the cases on 全部 and 商品 only, and only under that chip', () => {
    expect(showsAftersales('all', 'refund')).toBe(true)
    expect(showsAftersales('goods', 'refund')).toBe(true)
    expect(showsAftersales('enroll', 'refund')).toBe(false)
    expect(showsAftersales('all', null)).toBe(false)
    expect(showsAftersales('all', 'pay')).toBe(false)
  })

  it('does not list a goods order again when its case is shown', () => {
    const items = [goodsItem(tradeOrder({ orderId: 'o-1' })), goodsItem(tradeOrder({ orderId: 'o-2' }))]
    expect(withoutAftersaleOrders(items, new Set(['o-1'])).map((item) => item.id)).toEqual(['goods:o-2'])
    expect(withoutAftersaleOrders(items, new Set())).toBe(items)
  })
})
