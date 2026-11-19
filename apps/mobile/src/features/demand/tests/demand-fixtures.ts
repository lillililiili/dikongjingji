import type { Demand, DemandQuote } from '../domain/demand'

// A demand as the API mapper returns it; tests override what they need.
export function demandOf(overrides: Partial<Demand> = {}): Demand {
  return {
    demandId: 'd1',
    status: 'OPEN',
    closeReason: null,
    type: 'INSPECTION',
    title: '巡检需求',
    publisher: { kind: 'ORGANIZATION', organizationName: '东营示例物流公司' },
    cityCode: '370500',
    cityName: '东营市',
    address: '东营经济技术开发区',
    scope: '约 1.2 平方公里',
    dueDate: '2026-11-30',
    budgetMinMinor: 1_000_000,
    budgetMaxMinor: 2_000_000,
    deliverables: [
      { code: 'INSPECTION_REPORT', label: '巡检报告' },
      { code: 'ORTHOPHOTO', label: '正射影像' },
    ],
    note: null,
    quoteScope: 'MATCH',
    invitedProviders: [],
    submittedAt: '2026-11-19T02:00:00Z',
    quoteDeadline: '2026-11-22T02:00:00Z',
    quotingOpen: true,
    quoteCount: 0,
    chosen: null,
    editable: false,
    cancellable: true,
    deletable: false,
    createdAt: '2026-11-19T01:00:00Z',
    updatedAt: '2026-11-19T02:00:00Z',
    version: 1,
    ...overrides,
  }
}

export function quoteOf(overrides: Partial<DemandQuote> = {}): DemandQuote {
  return {
    quoteId: 'q1',
    provider: { providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证' },
    version: 2,
    status: 'ACTIVE',
    amountMinor: 1_860_000,
    method: '无人机 + 机器狗',
    schedule: '共 6 次 · 11/25 首次进场',
    durationDays: 90,
    deliverables: '巡检报告、隐患清单',
    validUntil: '2026-11-26T10:00:00Z',
    submittedAt: '2026-11-19T03:00:00Z',
    revisedAt: '2026-11-19T04:00:00Z',
    choosable: true,
    ...overrides,
  }
}
