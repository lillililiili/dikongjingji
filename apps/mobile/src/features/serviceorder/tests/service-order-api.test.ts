import { describe, expect, it } from 'vitest'

import { createServiceOrderApi, toServiceOrder, toServiceOrderSummary } from '../data/service-order-api'
import { PLAN, serviceOrderDto } from './service-order-fixtures'

type Call = { url: string; method: string; data?: unknown; headers?: Record<string, string> }

function recorder(responses: unknown[]) {
  const calls: Call[] = []
  const request = async <T>(input: Call): Promise<T> => {
    calls.push(input)
    return responses.shift() as T
  }
  return { calls, api: createServiceOrderApi(request) }
}

describe('service order api (contract 1.0.0)', () => {
  it('reads an order: snapshot, lines, plan, payment, deliveries and only the customer actions', () => {
    const order = toServiceOrder(serviceOrderDto({
      status: 'DELIVERED',
      plan: { ...PLAN, confirmedAt: '2026-11-23T03:00:00Z', confirmedBy: '王女士', terms: ['a', ' '] },
      payment: {
        amountMinor: 386_000, collectable: true, paidAt: '2026-11-23T04:00:00Z', pendingTradeOrderId: null,
        pendingPayBefore: null, paidTradeOrderId: 't-1', refundedMinor: 0, refunds: [],
      },
      deliveries: [
        { round: 2, status: 'SUBMITTED', note: 'b', files: [], deliveredAt: 'x2', reviewNote: null, reviewedAt: null, reviewedBy: null },
        { round: 1, status: 'RETURNED', note: 'a', files: [{ fileId: 'f', name: 'n.pdf', contentType: 'application/pdf', sizeBytes: 1, uploadedAt: 'u' }], deliveredAt: 'x1', reviewNote: ' 缺报告 ', reviewedAt: 'r', reviewedBy: '王' },
      ],
      actions: { confirmPlan: false, pay: false, cancel: false, accept: true, rework: true, issuePlan: true, start: true, deliver: true, terminate: true },
      version: 7,
    }) as never)
    expect(order).toMatchObject({
      orderId: 'so-1',
      status: 'DELIVERED',
      type: 'MAINTENANCE',
      customer: { kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' },
      provider: { providerId: 'p-1', name: '云翼科技', qualificationReviewed: true },
      quote: { quoteVersion: 2, team: '东营本地', nightWork: null, reportHours: 48, archiveDays: 90, firstVisitDate: '2026-11-26' },
      plan: { version: 2, terms: ['a'], confirmedBy: '王女士' },
      payment: { collectable: true, paidTradeOrderId: 't-1' },
      actions: { confirmPlan: false, pay: false, cancel: false, accept: true, rework: true },
      version: 7,
    })
    expect(order.lines).toEqual([
      { name: '上门检测', quantity: 4, unit: '台', unitAmountMinor: 59_000, amountMinor: 236_000 },
      { name: '更换件', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 150_000 },
    ])
    // In round order, with the reason trimmed.
    expect(order.deliveries.map((item) => item.round)).toEqual([1, 2])
    expect(order.deliveries[0].reviewNote).toBe('缺报告')
    expect(Object.keys(order.actions)).toEqual(['confirmPlan', 'pay', 'cancel', 'accept', 'rework'])
  })

  it('never reads a missing flag as allowed, and an unknown status as a live one', () => {
    const order = toServiceOrder({ orderId: 'so-2', status: 'SOMETHING', version: 1, actions: { pay: null } } as never)
    expect(order.status).toBe('CANCELLED')
    expect(order.actions).toEqual({ confirmPlan: false, pay: false, cancel: false, accept: false, rework: false })
    expect(order.provider.qualificationReviewed).toBe(false)
    expect(order.plan).toBeNull()
    expect(order.payment.collectable).toBe(false)
  })

  it('lists by status and maps the summary', async () => {
    const { calls, api } = recorder([{ total: 1, hasMore: false, items: [{ orderId: 'so-1', status: 'AWAITING_PAYMENT', planVersion: 2, provider: { name: 'x' } }] }])
    const page = await api.list('tok', 'AWAITING_PAYMENT', 2, 10)
    expect(calls[0]).toEqual({
      url: '/api/v1/service-orders?status=AWAITING_PAYMENT&page=2&size=10',
      method: 'GET',
      headers: { Authorization: 'Bearer tok' },
    })
    expect(page.items[0]).toMatchObject({ orderId: 'so-1', status: 'AWAITING_PAYMENT', planVersion: 2, paidAt: null })
    expect(toServiceOrderSummary({ orderId: 'a', status: 'X', updatedAt: null, createdAt: 'c' } as never).updatedAt).toBe('c')
  })

  it('confirms the plan on screen with the order version as If-Match', async () => {
    const { calls, api } = recorder([serviceOrderDto({ status: 'AWAITING_PAYMENT', version: 4 })])
    const order = await api.confirmPlan('tok', 'so 1', 3, 2)
    expect(order.status).toBe('AWAITING_PAYMENT')
    expect(calls[0]).toEqual({
      url: '/api/v1/service-orders/so%201/plan-confirmation',
      method: 'POST',
      data: { planVersion: 2, agreed: true },
      headers: { Authorization: 'Bearer tok', 'If-Match': '"3"' },
    })
  })

  it('starts a payment and sends cancel, acceptance and rework with their bodies', async () => {
    const order = serviceOrderDto()
    const { calls, api } = recorder([
      { orderId: 'so-1', tradeOrderId: 't-9', amountMinor: 386_000, payBefore: '2026-11-23T04:15:00Z' },
      order, order, order,
    ])
    expect(await api.startPayment('tok', 'so-1')).toEqual({
      orderId: 'so-1', tradeOrderId: 't-9', amountMinor: 386_000, payBefore: '2026-11-23T04:15:00Z',
    })
    await api.cancel('tok', 'so-1', 5, '  ')
    await api.accept('tok', 'so-1', 6, 2)
    await api.rework('tok', 'so-1', 7, 2, ' 缺少检测报告 ')
    expect(calls.map((call) => [call.url, call.data, call.headers?.['If-Match']])).toEqual([
      ['/api/v1/service-orders/so-1/payments', undefined, undefined],
      ['/api/v1/service-orders/so-1/cancel', { reason: null }, '"5"'],
      ['/api/v1/service-orders/so-1/acceptance', { round: 2 }, '"6"'],
      ['/api/v1/service-orders/so-1/rework', { round: 2, reason: '缺少检测报告' }, '"7"'],
    ])
  })

  it('asks for a short-lived download link of one file', async () => {
    const { calls, api } = recorder([{ url: '/api/v1/service-order-files/tok-1', expiresAt: '2026-11-26T08:10:00Z' }])
    expect(await api.createDownload('tok', 'so-1', 'f 1')).toEqual({
      url: '/api/v1/service-order-files/tok-1', expiresAt: '2026-11-26T08:10:00Z',
    })
    expect(calls[0]).toMatchObject({ url: '/api/v1/service-orders/so-1/files/f%201/download-intents', method: 'POST' })
  })
})
