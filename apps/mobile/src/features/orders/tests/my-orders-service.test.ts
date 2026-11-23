import { describe, expect, it } from 'vitest'

import type { ServiceOrderSummary } from '../../serviceorder/domain/service-order'
import { summary } from '../../serviceorder/tests/service-order-fixtures'
import { describeMyOrderCard } from '../application/my-order-cards'
import { loadFirstMyOrderPages, type MyOrderApis } from '../application/my-order-loader'
import { matchesMyOrderFilter, myOrderChips, serviceItem } from '../application/my-order-sources'

// General service orders (S4-3) on 我的订单 → 服务, mixed in by time with rentals and inspections.
describe('我的订单: service orders', () => {
  it('puts each status under the chip it belongs to', () => {
    const chips = (overrides: Partial<ServiceOrderSummary>) => myOrderChips(serviceItem(summary(overrides)))
    expect(chips({ status: 'AWAITING_PAYMENT' })).toEqual(['pay'])
    expect(chips({ status: 'AWAITING_START' })).toEqual(['ship'])
    expect(chips({ status: 'IN_SERVICE' })).toEqual(['ship'])
    expect(chips({ status: 'REWORK' })).toEqual(['ship'])
    expect(chips({ status: 'DELIVERED' })).toEqual(['accept'])
    expect(chips({ status: 'CANCELLED', paidAt: '2026-11-23T04:00:00Z' })).toEqual(['refund'])
    expect(chips({ status: 'CANCELLED' })).toEqual([])
    expect(chips({ status: 'AWAITING_PLAN' })).toEqual([])
    expect(chips({ status: 'TERMINATED', paidAt: 'x' })).toEqual([])
    const item = serviceItem(summary({ status: 'AWAITING_PAYMENT' }))
    expect(matchesMyOrderFilter(item, 'service', 'pay')).toBe(true)
    expect(matchesMyOrderFilter(item, 'goods', null)).toBe(false)
    expect(item).toMatchObject({ id: 'service:so-1', time: '2026-11-23T01:00:00Z' })
  })

  it('draws the 服务 card with its next step', () => {
    const card = describeMyOrderCard(serviceItem(summary({ status: 'AWAITING_PAYMENT', planVersion: 2 })), Date.now())!
    expect(card).toMatchObject({
      tag: { label: '服务', tone: 'GREEN' },
      head: '云翼科技',
      status: { text: '待付款', tone: 'ORANGE' },
      title: '设备维护需求',
      amount: '¥3,860',
      url: '/pages/service-order/detail?orderId=so-1',
    })
    expect(card.lines.map((line) => line.text)).toEqual(['企业 · 东营港务物流', '方案 v2 已确认 · 待一次付清'])
    expect(card.actions.map((action) => [action.label, action.target])).toEqual([
      ['查看订单', { kind: 'URL', url: '/pages/service-order/detail?orderId=so-1' }],
      ['去付款', { kind: 'URL', url: '/pages/service-order/plan?orderId=so-1' }],
    ])
    const accept = describeMyOrderCard(serviceItem(summary({ status: 'DELIVERED' })), Date.now())!
    expect(accept.actions[1]).toMatchObject({ label: '去验收', target: { kind: 'URL', url: '/pages/service-order/delivery?orderId=so-1' } })
    const plan = describeMyOrderCard(serviceItem(summary({ status: 'PLAN_PENDING', planVersion: 1 })), Date.now())!
    expect(plan.actions[1]).toMatchObject({ label: '去确认方案', target: { kind: 'URL', url: '/pages/service-order/plan?orderId=so-1' } })
    expect(describeMyOrderCard(serviceItem(summary({ status: 'COMPLETED' })), Date.now())!.status).toEqual({ text: '已完成', tone: 'GREEN' })
  })

  it('pages the service-order list with the chip filter', async () => {
    const calls: string[] = []
    const empty = { items: [], hasMore: false }
    const apis: MyOrderApis = {
      goods: async () => empty,
      rental: async () => empty,
      inspection: async () => empty,
      service: async (_token, status, page, size) => {
        calls.push(`${status} ${page} ${size}`)
        return { items: [summary({ status: 'DELIVERED' })], hasMore: true }
      },
      enroll: async () => empty,
      venue: async () => empty,
    }
    const pages = await loadFirstMyOrderPages(apis, 'tok', [{ kind: 'SERVICE', status: 'DELIVERED' }])
    expect(calls).toEqual(['DELIVERED 1 10'])
    expect(pages[0]).toMatchObject({ hasMore: true, page: 1 })
    expect(pages[0].items[0]).toMatchObject({ kind: 'SERVICE', id: 'service:so-1' })
  })
})
