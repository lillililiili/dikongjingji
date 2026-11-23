import type { Aftersale, AftersaleEligibility, AftersaleEvent } from '../domain/aftersale'

export const LINE = {
  lineId: 'l-1',
  productId: 'c2000000-0000-4000-8000-000000000101',
  offerId: 'f-1',
  productName: '编程机器人探索套装',
  skuName: '标准套装（1 台 + 数字课程）',
  coverImage: null,
  unitAmountMinor: 129900,
  quantity: 1,
  subtotalMinor: 129900,
}

export function event(action: AftersaleEvent['action'], status: AftersaleEvent['status'], occurredAt: string): AftersaleEvent {
  return { action, status, occurredAt }
}

// 2026-11-05 09:12 Beijing time.
export const SUBMITTED = '2026-11-05T01:12:00Z'
// 11/05 15:40
export const AGREED = '2026-11-05T07:40:00Z'
// 11/06 10:05
export const SHIPPED = '2026-11-06T02:05:00Z'
// 11/08 14:20
export const RECEIVED = '2026-11-08T06:20:00Z'
// 11/09 09:30
export const REFUNDED = '2026-11-09T01:30:00Z'

export function aftersale(overrides: Partial<Aftersale> = {}): Aftersale {
  return {
    aftersaleId: 'as-1',
    aftersaleNo: 'AS2026110500001',
    orderId: 'o-1',
    orderNo: 'MO2026110220150001',
    kind: 'RETURN_REFUND',
    reason: 'NO_REASON_7D',
    description: null,
    status: 'PENDING_REVIEW',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', aftersaleParty: null },
    lines: [LINE],
    paidMinor: 129900,
    requestedMinor: 129900,
    refundMinor: null,
    returnAddress: null,
    returnWaybill: null,
    rejectReason: null,
    merchantNote: null,
    refund: null,
    images: [],
    dueAt: null,
    submittedAt: SUBMITTED,
    reviewedAt: null,
    returnShippedAt: null,
    receivedAt: null,
    refundedAt: null,
    closedAt: null,
    timeline: [event('SUBMIT', 'PENDING_REVIEW', SUBMITTED)],
    actions: { canRevoke: true, canSubmitReturn: false, canEditReturn: false, canReapply: false },
    version: 1,
    ...overrides,
  }
}

export function eligibility(overrides: Partial<AftersaleEligibility> = {}): AftersaleEligibility {
  return {
    orderId: 'o-1',
    orderNo: 'MO2026110220150001',
    orderStatus: 'SHIPPED',
    lines: [LINE],
    paidMinor: 129900,
    maxRefundMinor: 129900,
    merchant: { organizationId: 'm-1', name: '示例机器人商家', aftersaleParty: '示例机器人商家 · 厂家保修' },
    kinds: [
      { kind: 'REFUND_ONLY', available: false, unavailableReason: 'ALREADY_SHIPPED' },
      { kind: 'RETURN_REFUND', available: true, unavailableReason: null },
    ],
    reasons: [
      { reason: 'NOT_WANTED', kind: 'REFUND_ONLY', available: false, unavailableReason: 'ALREADY_SHIPPED' },
      { reason: 'NO_REASON_7D', kind: 'RETURN_REFUND', available: true, unavailableReason: null },
      { reason: 'NOT_AS_DESCRIBED', kind: 'RETURN_REFUND', available: true, unavailableReason: null },
      { reason: 'DAMAGED', kind: 'RETURN_REFUND', available: true, unavailableReason: null },
    ],
    openAftersaleId: null,
    shippedAt: '2026-11-03T02:24:00Z',
    completedAt: null,
    ...overrides,
  }
}
