import { toServiceOrder } from '../data/service-order-api'
import type { ServiceOrder, ServiceOrderDelivery, ServiceOrderSummary } from '../domain/service-order'

// Shapes of contracts/openapi-service-order.yaml 1.0.0 for the unit tests.
export function serviceOrderDto(overrides: Record<string, unknown> = {}) {
  return {
    orderId: 'so-1',
    orderNo: 'SV20261123000001',
    status: 'AWAITING_PLAN',
    type: 'MAINTENANCE',
    title: '设备维护需求',
    demandId: 'd-1',
    customer: { kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' },
    provider: { providerId: 'p-1', name: '云翼科技', qualificationReviewed: true },
    cityCode: '370500',
    cityName: '东营市',
    address: '东营港 示例港路 6 号',
    scope: '无人机 4 台',
    dueDate: '2026-11-30',
    amountMinor: 386_000,
    lines: [
      { name: '上门检测', quantity: 4, unit: '台', unitAmountMinor: 59_000, amountMinor: 236_000 },
      { name: '更换件', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 150_000 },
    ],
    quote: {
      quoteId: 'q-1',
      quoteVersion: 2,
      method: '上门检测 + 寄修',
      schedule: '11/26 上门',
      durationDays: 5,
      deliverables: '维修记录、检测报告',
      team: '东营本地',
      nightWork: null,
      reportHours: 48,
      archiveDays: 90,
      firstVisitDate: '2026-11-26',
    },
    plan: null,
    payment: {
      amountMinor: 386_000,
      collectable: true,
      paidAt: null,
      pendingTradeOrderId: null,
      pendingPayBefore: null,
      paidTradeOrderId: null,
      refundedMinor: 0,
      refunds: [],
    },
    deliveries: [],
    stagedFiles: [],
    events: [],
    startedAt: null,
    completedAt: null,
    closedAt: null,
    closedBy: null,
    closeReason: null,
    actions: {
      confirmPlan: false, pay: false, cancel: true, accept: false, rework: false,
      issuePlan: false, start: false, deliver: false, terminate: false,
    },
    createdAt: '2026-11-23T01:00:00Z',
    updatedAt: '2026-11-23T01:00:00Z',
    version: 0,
    ...overrides,
  }
}

export const PLAN = {
  version: 2,
  amountMinor: 386_000,
  schedule: '11/26 上门检测，约半天',
  terms: ['检测后 48 小时内交付维修记录', '更换件保留旧件'],
  note: null,
  issuedAt: '2026-11-23T02:00:00Z',
  confirmedAt: null,
  confirmedBy: null,
}

export function serviceOrder(overrides: Partial<ServiceOrder> = {}): ServiceOrder {
  return { ...toServiceOrder(serviceOrderDto() as never), ...overrides }
}

export function delivery(overrides: Partial<ServiceOrderDelivery> = {}): ServiceOrderDelivery {
  return {
    round: 1,
    status: 'SUBMITTED',
    note: '4 台无人机检测完毕，更换桨叶 2 副',
    files: [
      { fileId: 'f-1', name: '维修记录.pdf', contentType: 'application/pdf', sizeBytes: 2_400_000, uploadedAt: '2026-11-26T07:00:00Z' },
      { fileId: 'f-2', name: '现场照片.jpg', contentType: 'image/jpeg', sizeBytes: 860_000, uploadedAt: '2026-11-26T07:00:00Z' },
    ],
    deliveredAt: '2026-11-26T08:00:00Z',
    reviewNote: null,
    reviewedAt: null,
    reviewedBy: null,
    ...overrides,
  }
}

// The order in each status, with what the customer may do then (as the server would say).
export function orderIn(status: ServiceOrder['status'], overrides: Partial<ServiceOrder> = {}): ServiceOrder {
  const base = serviceOrder()
  const plan = { ...PLAN }
  const confirmed = { ...plan, confirmedAt: '2026-11-23T03:00:00Z', confirmedBy: '王女士' }
  const paid = { ...base.payment, paidAt: '2026-11-23T04:00:00Z', paidTradeOrderId: 't-1' }
  const none = { confirmPlan: false, pay: false, cancel: false, accept: false, rework: false }
  switch (status) {
    case 'AWAITING_PLAN':
      return { ...base, status, actions: { ...none, cancel: true }, ...overrides }
    case 'PLAN_PENDING':
      return { ...base, status, plan, actions: { ...none, confirmPlan: true, cancel: true }, ...overrides }
    case 'AWAITING_PAYMENT':
      return { ...base, status, plan: confirmed, actions: { ...none, pay: true, cancel: true }, ...overrides }
    case 'AWAITING_START':
      return { ...base, status, plan: confirmed, payment: paid, actions: { ...none, cancel: true }, ...overrides }
    case 'IN_SERVICE':
      return { ...base, status, plan: confirmed, payment: paid, startedAt: '2026-11-26T01:00:00Z', actions: none, ...overrides }
    case 'DELIVERED':
      return {
        ...base, status, plan: confirmed, payment: paid, startedAt: '2026-11-26T01:00:00Z',
        deliveries: [delivery()], actions: { ...none, accept: true, rework: true }, ...overrides,
      }
    case 'REWORK':
      return {
        ...base, status, plan: confirmed, payment: paid, startedAt: '2026-11-26T01:00:00Z',
        deliveries: [delivery({ status: 'RETURNED', reviewNote: '缺少检测报告', reviewedAt: '2026-11-26T09:00:00Z', reviewedBy: '王女士' })],
        actions: none, ...overrides,
      }
    case 'COMPLETED':
      return {
        ...base, status, plan: confirmed, payment: paid, startedAt: '2026-11-26T01:00:00Z',
        completedAt: '2026-11-27T02:00:00Z',
        deliveries: [delivery({ status: 'ACCEPTED', reviewedAt: '2026-11-27T02:00:00Z', reviewedBy: '王女士' })],
        actions: none, ...overrides,
      }
    case 'CANCELLED':
      return { ...base, status, closedAt: '2026-11-23T05:00:00Z', closedBy: 'CUSTOMER', actions: none, ...overrides }
    case 'TERMINATED':
      return {
        ...base, status, plan: confirmed, payment: paid, startedAt: '2026-11-26T01:00:00Z',
        closedAt: '2026-11-27T05:00:00Z', closedBy: 'PROVIDER', closeReason: '设备停产无法维修', actions: none, ...overrides,
      }
  }
}

export function summary(overrides: Partial<ServiceOrderSummary> = {}): ServiceOrderSummary {
  return {
    orderId: 'so-1',
    orderNo: 'SV20261123000001',
    status: 'AWAITING_PLAN',
    type: 'MAINTENANCE',
    title: '设备维护需求',
    scope: '无人机 4 台',
    cityName: '东营市',
    customer: { kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' },
    provider: { providerId: 'p-1', name: '云翼科技', qualificationReviewed: true },
    amountMinor: 386_000,
    planVersion: null,
    paidAt: null,
    latestDeliveryAt: null,
    createdAt: '2026-11-23T01:00:00Z',
    updatedAt: '2026-11-23T01:00:00Z',
    ...overrides,
  }
}
