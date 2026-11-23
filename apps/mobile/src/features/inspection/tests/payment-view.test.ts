import { describe, expect, it } from 'vitest'

import type {
  InspectionOrderPaymentNode,
  InspectionServiceOrder,
  InspectionServiceVisit,
} from '../data/service-flow/inspection-plan-api'
import { nodeNoOfTradeOrder, planVersionOfLine, toSourceView } from '../presentation/shared/inspection-cashier-source'
import { paymentDestinationUrl } from '../application/service-flow/payment-destination'
import type { InspectionOrderSummary } from '../domain/service-flow/order-center'
import { getInspectionOrderPayment } from '../presentation/orders/order-center-view'
import {
  describeOrderChain,
  describeOrderHeadline,
  describeOrderPaymentCard,
  describePrimaryAction,
} from '../presentation/orders/order-detail-view'
import { toVisitRows } from '../presentation/fulfillment/fulfillment-view'
import {
  describeDuePayment,
  describeInspectionCashier,
  describeOrderPaymentGroups,
  isSettled,
  visitFeeLine,
} from '../presentation/shared/payment-view'
import type { TradeOrder } from '../../trade/domain/trade-order'

// S4-1 (boards PlanConfirm, FulfillmentLater, OrderService and the four Cashier* boards): the
// 物流园区季度安防巡检 example — ¥18,600, 30% down (¥5,580), six visits of ¥2,170.

const DOWN = 558_000
const FEE = 217_000

function visit(visitNo: number, status: InspectionServiceVisit['status'], extra: Partial<InspectionServiceVisit> = {}): InspectionServiceVisit {
  return {
    id: `visit-${visitNo}`,
    serviceOrderId: 'order-1',
    visitNo,
    plannedDate: `2026-12-${String(visitNo * 2).padStart(2, '0')}`,
    plannedStartAt: null,
    status,
    progress: [],
    startedAt: status === 'PENDING_SCHEDULE' || status === 'SCHEDULED' ? null : '2026-11-02T01:00:00Z',
    deliveredAt: status === 'DELIVERED' || status === 'ACCEPTED' ? '2026-11-03T02:00:00Z' : null,
    acceptedAt: status === 'ACCEPTED' ? '2026-11-03T08:20:00Z' : null,
    version: 1,
    ...extra,
  }
}

function node(nodeNo: number, extra: Partial<InspectionOrderPaymentNode> = {}): InspectionOrderPaymentNode {
  return nodeNo === 1
    ? { nodeNo, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: DOWN, payable: false, paidAt: null, ...extra }
    : { nodeNo, nodeType: 'PER_VISIT', visitNo: nodeNo - 1, amountMinor: FEE, payable: false, paidAt: null, ...extra }
}

function order(extra: Partial<InspectionServiceOrder> = {}): InspectionServiceOrder {
  return {
    id: 'order-1',
    serviceOrderNo: 'FW1',
    reservationId: 'res-1',
    planId: 'plan-1',
    customerId: 'c',
    providerId: 'p',
    status: 'IN_PROGRESS',
    paymentMode: 'NODES',
    paymentNodes: [node(1, { paidAt: '2026-11-01T01:30:00Z', tradeOrderId: 'trade-down' }), ...[2, 3, 4, 5, 6, 7].map((n) => node(n))],
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    serviceSnapshot: {
      serviceId: 's', serviceTitle: '物流园区季度安防巡检', serviceCoverUrl: null, cityCode: '370500',
      location: { address: '', longitude: null, latitude: null }, lineLengthMeters: null, serviceDate: '2026-11-02', inspectionItems: [],
    },
    assignments: [],
    progress: [],
    visits: [1, 2, 3, 4, 5, 6].map((n) => visit(n, 'PENDING_SCHEDULE')),
    createdAt: '2026-11-01T01:30:00Z',
    deliveredAt: null,
    acceptedAt: null,
    version: 3,
    ...extra,
  }
}

describe('payment groups (board OrderService)', () => {
  it('shows the paid down payment and the per-visit fees with how many are paid', () => {
    const first = order({
      visits: [visit(1, 'DELIVERED'), ...[2, 3, 4, 5, 6].map((n) => visit(n, 'PENDING_SCHEDULE'))],
    })
    expect(describeOrderPaymentGroups(first)).toEqual([
      expect.objectContaining({ name: '首付 30%', amountText: '¥5,580', stateText: '已支付 · 11/01', isPaid: true, isDue: false }),
      expect.objectContaining({ name: '按次结算 70%', amountText: '¥13,020', tag: '第 1 次待验收', stateText: '每次验收后支付 ¥2,170 · 已付 0/6', isDue: false }),
    ])
  })

  it('names a one-visit order 验收后付款 70% and says when it is due', () => {
    const single = order({
      paymentNodes: [node(1, { paidAt: '2026-11-04T03:05:00Z' }), node(2, { amountMinor: 420_000, payable: true })],
      visits: [visit(1, 'ACCEPTED')],
      status: 'ACCEPTED',
    })
    expect(describeOrderPaymentGroups(single)[1]).toEqual(expect.objectContaining({
      name: '验收后付款 70%', amountText: '¥4,200', stateText: '待支付', isDue: true,
    }))
    expect(isSettled(single)).toBe(false)
  })

  it('says a cancelled order takes nothing and tracks the refund of its down payment', () => {
    const cancelled = order({
      status: 'CANCELLED',
      paymentNodes: [
        node(1, { paidAt: '2026-11-01T01:30:00Z', refund: { refundId: 'r', amountMinor: DOWN, status: 'PROCESSING', requestedAt: '2026-11-01T02:00:00Z' } }),
        node(2),
      ],
      visits: [visit(1, 'SCHEDULED')],
    })
    const rows = describeOrderPaymentGroups(cancelled)
    expect(rows[0].stateText).toBe('订单已取消 · ¥5,580 原路退回中')
    expect(rows[1].stateText).toBe('订单已取消，无需支付')
    expect(rows.every((row) => row.isMuted)).toBe(true)
    expect(describeOrderHeadline(cancelled)).toEqual(expect.objectContaining({ title: '已取消', isCancelled: true }))
  })

  it('has no rows for an order without payment nodes', () => {
    expect(describeOrderPaymentGroups(order({ paymentMode: 'NONE', paymentNodes: [] }))).toEqual([])
    expect(isSettled(order({ paymentMode: 'NONE', paymentNodes: [] }))).toBe(true)
  })
})

describe('fees on the fulfillment page (board FulfillmentLater)', () => {
  const later = order({
    paymentNodes: [node(1, { paidAt: '2026-11-01T01:30:00Z' }), node(2, { paidAt: '2026-11-03T09:00:00Z' }), node(3, { payable: true }), ...[4, 5, 6, 7].map((n) => node(n))],
    visits: [
      visit(1, 'ACCEPTED'),
      visit(2, 'ACCEPTED'),
      visit(3, 'DELIVERED', { plannedDate: '2026-12-08', originalPlannedDate: '2026-12-01' }),
      visit(4, 'IN_PROGRESS'),
      visit(5, 'SCHEDULED'),
      visit(6, 'PENDING_SCHEDULE'),
    ],
  })

  it('reminds of the smallest unpaid fee without holding up later visits', () => {
    expect(describeDuePayment(later)).toEqual({
      nodeNo: 3,
      title: '第 2 次服务费 ¥2,170 待支付',
      note: '不影响后续巡检，全部结清后服务单才算完成',
    })
  })

  it('writes the fee under each accepted visit and the moved date under the third', () => {
    expect(visitFeeLine(later, 1)).toEqual({ text: '服务费已支付', paid: true })
    expect(visitFeeLine(later, 2)).toEqual({ text: '服务费 ¥2,170 待支付', paid: false })
    expect(visitFeeLine(later, 3)).toBeNull()
    const rows = toVisitRows(later.visits, null, later)
    expect(rows[2].note).toBe('原定 12/01，服务商已改期')
    expect(rows[1].fee).toEqual({ text: '服务费 ¥2,170 待支付', paid: false })
  })

  it('reminds of the down payment while the order waits for it', () => {
    const awaiting = order({ status: 'AWAITING_PAYMENT', paymentNodes: [node(1, { payable: true }), node(2)] })
    expect(describeDuePayment(awaiting)).toEqual(expect.objectContaining({ nodeNo: 1, title: '首付 ¥5,580 待支付' }))
  })

  it('says a visit is being reworked', () => {
    const rows = toVisitRows([visit(1, 'REWORK')], null)
    expect(rows[0]).toEqual(expect.objectContaining({ statusText: '退回整改 · 等服务商重新交付', isExpanded: true }))
  })
})

describe('cashier wording (boards CashierFirst, CashierFinal, CashierOneFirst, CashierOneFinal)', () => {
  it('first payment of a six-visit order', () => {
    const awaiting = order({ status: 'AWAITING_PAYMENT', paymentNodes: [node(1, { payable: true }), ...[2, 3, 4, 5, 6, 7].map((n) => node(n))] })
    expect(describeInspectionCashier(awaiting, 2, 1)).toEqual({
      dueChip: '付款节点 1 · 首付 30%（方案 v2 已确认）',
      nodeRow: '首付 30% ¥5,580 · 其余按次结算',
      payer: '东营港务物流（企业身份）',
      paidTitle: '首付已支付',
      paidNote: '服务商将按约定开始履约',
      failedNote: '首付仍为待支付',
      next: { label: '查看履约安排', target: 'FULFILLMENT' },
      back: null,
    })
  })

  it('a visit fee of a six-visit order', () => {
    const view = describeInspectionCashier(order({ visits: [visit(1, 'ACCEPTED'), ...[2, 3, 4, 5, 6].map((n) => visit(n, 'PENDING_SCHEDULE'))] }), 2, 2)
    expect(view).toEqual(expect.objectContaining({
      dueChip: '按次结算 · 第 1/6 次服务费（第 1 次已验收）',
      nodeRow: '首付已付 · 第 1/6 次 ¥2,170',
      paidTitle: '第 1 次服务费已支付',
      paidNote: '其余 5 次在每次验收后支付',
      next: { label: '返回服务订单', target: 'ORDER' },
      back: { label: '查看后续巡检进度', target: 'FULFILLMENT' },
    }))
  })

  it('both payments of a one-visit order, for a person', () => {
    const single = order({
      customerParty: { organizationName: null, operatorName: '王女士' },
      paymentNodes: [node(1, { amountMinor: 180_000, payable: true }), node(2, { amountMinor: 420_000 })],
      visits: [visit(1, 'PENDING_SCHEDULE')],
      status: 'AWAITING_PAYMENT',
    })
    expect(describeInspectionCashier(single, 1, 1)).toEqual(expect.objectContaining({
      dueChip: '付款节点 1 · 首付 30%（方案 v1 已确认）',
      nodeRow: '首付 ¥1,800 · 验收后付 ¥4,200',
      payer: '王女士（个人身份）',
    }))
    expect(describeInspectionCashier(single, 1, 2)).toEqual(expect.objectContaining({
      dueChip: '付款节点 2 · 验收后付款 70%（已验收）',
      nodeRow: '首付已付 · 验收后付 ¥4,200',
      paidTitle: '验收款已支付',
      paidNote: '全部款项已结清，服务单已完成',
      next: { label: '查看服务进度', target: 'FULFILLMENT' },
    }))
  })

  it('finds the node a trade order pays and the plan version on its line', () => {
    const withRecords = order({
      paymentRecords: [{ attemptId: 'a', nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, tradeOrderId: 'trade-2', amountMinor: FEE, status: 'OPEN', payBefore: '', createdAt: '' }],
    })
    expect(nodeNoOfTradeOrder(withRecords, 'trade-2')).toBe(2)
    expect(nodeNoOfTradeOrder(withRecords, 'trade-down')).toBe(1)
    expect(nodeNoOfTradeOrder(withRecords, 'other')).toBeNull()
    expect(planVersionOfLine('物流园区季度安防巡检 · 方案 v2')).toBe(2)
    expect(planVersionOfLine('物流园区季度安防巡检')).toBeNull()
  })

  it('turns the wording into the trade pages’ source view with inspection destinations', () => {
    const trade = { orderId: 'trade-down', lines: [{ productName: '物流园区季度安防巡检 · 方案 v2', productId: 'order-1' }] } as unknown as TradeOrder
    const awaiting = order({ status: 'AWAITING_PAYMENT', paymentNodes: [node(1, { payable: true, tradeOrderId: 'trade-down' }), node(2)] })
    const view = toSourceView(trade, awaiting)
    expect(view?.rows).toEqual([
      { key: 'inspection-payer', label: '付款主体', value: '东营港务物流（企业身份）' },
      { key: 'inspection-node', label: '付款节点', value: '首付 30% ¥5,580 · 其余按次结算' },
    ])
    expect(view?.next).toEqual({ label: '查看履约安排', destination: { kind: 'INSPECTION_FULFILLMENT', serviceOrderId: 'order-1' } })
  })
})

describe('order detail (board OrderService)', () => {
  it('stops the chain at 履约中 while the first report waits for acceptance, and offers 去验收', () => {
    const first = order({ visits: [visit(1, 'DELIVERED'), ...[2, 3, 4, 5, 6].map((n) => visit(n, 'PENDING_SCHEDULE'))] })
    expect(describeOrderChain(first).map((step) => [step.label, step.done, step.current])).toEqual([
      ['待报价', true, false], ['待确认', true, false], ['待履约', true, false],
      ['履约中', false, true], ['待验收', false, false], ['已完成', false, false],
    ])
    expect(describeOrderHeadline(first).title).toBe('履约中 · 第 1 次待验收')
    expect(describePrimaryAction(first, true)).toEqual({ kind: 'ACCEPT', visitId: 'visit-1', label: '去验收' })
  })

  it('offers to pay what is due first, only where payment is open', () => {
    const due = order({ paymentNodes: [node(1, { paidAt: '2026-11-01T01:30:00Z' }), node(2, { payable: true })], visits: [visit(1, 'ACCEPTED'), visit(2, 'DELIVERED')] })
    expect(describePrimaryAction(due, true)).toEqual({ kind: 'PAY', nodeNo: 2, label: '去支付 ¥2,170' })
    expect(describePrimaryAction(due, false)).toEqual({ kind: 'ACCEPT', visitId: 'visit-2', label: '去验收' })
  })

  it('is complete only when every visit is accepted and every fee paid', () => {
    const accepted = order({ status: 'ACCEPTED', paymentNodes: [node(1, { paidAt: 'x' }), node(2, { payable: true })], visits: [visit(1, 'ACCEPTED')] })
    expect(describeOrderHeadline(accepted).title).toBe('待付款 · 验收款待支付')
    expect(describeOrderChain(accepted).find((step) => step.current)?.label).toBe('待验收')
    const settled = order({ status: 'ACCEPTED', paymentNodes: [node(1, { paidAt: 'x' }), node(2, { paidAt: 'y' })], visits: [visit(1, 'ACCEPTED')] })
    expect(describeOrderHeadline(settled).title).toBe('已完成')
    expect(describeOrderChain(settled).every((step) => step.done)).toBe(true)
  })

  it('lists the paid records under the nodes', () => {
    const card = describeOrderPaymentCard(order({
      paymentRecords: [
        { attemptId: 'a1', nodeNo: 1, nodeType: 'DOWN_PAYMENT', tradeOrderId: 't0', amountMinor: DOWN, status: 'CLOSED', payBefore: '', createdAt: '' },
        { attemptId: 'a2', nodeNo: 1, nodeType: 'DOWN_PAYMENT', tradeOrderId: 't1', amountMinor: DOWN, status: 'PAID', payBefore: '', createdAt: '', paidAt: '2026-11-01T01:30:00Z' },
      ],
    }), 1_860_000)
    expect(card?.totalText).toBe('¥18,600')
    expect(card?.records).toEqual([{ key: 'a2', text: '首付 ¥5,580', timeText: '11/01 09:30 已支付' }])
  })
})

describe('order list card', () => {
  const summary = (extra: Partial<InspectionOrderSummary>): InspectionOrderSummary => ({
    reservationId: 'r', reservationNo: 'IR1', serviceId: 's', serviceTitle: 't', cityCode: '370500',
    locationSummary: '', preferredServiceDate: '2026-11-02', submittedAt: '', stage: 'IN_PROGRESS',
    reservationStatus: 'PLAN_PUBLISHED', currency: 'CNY', serviceOrderId: 'order-1', visitCount: 6, ...extra,
  })

  it('says 去付款 for a due node only where payment is open', () => {
    expect(getInspectionOrderPayment(summary({ paymentDueNodeNo: 1, paymentDueNodeType: 'DOWN_PAYMENT' }), true))
      .toEqual({ nodeNo: 1, stageText: '待付首付' })
    expect(getInspectionOrderPayment(summary({ paymentDueNodeNo: 3, paymentDueNodeType: 'PER_VISIT', paymentDueVisitNo: 2 }), true))
      .toEqual({ nodeNo: 3, stageText: '第 2 次待付款' })
    expect(getInspectionOrderPayment(summary({ paymentDueNodeNo: 3, paymentDueNodeType: 'PER_VISIT', paymentDueVisitNo: 2 }), false)).toBeNull()
    expect(getInspectionOrderPayment(summary({}), true)).toBeNull()
  })

  it('goes to the cashier for an open payment and to the result for a paid one', () => {
    expect(paymentDestinationUrl({ nodeNo: 1, tradeOrderId: 't1', paid: false })).toBe('/pages/trade/cashier?orderId=t1')
    expect(paymentDestinationUrl({ nodeNo: 1, tradeOrderId: 't1', paid: true })).toBe('/pages/trade/pay-result?orderId=t1')
  })
})
