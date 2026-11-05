import { describe, expect, it } from 'vitest'

import type {
  InspectionPlan,
  InspectionPlanVersionSummary,
} from '../data/service-flow/inspection-plan-api'
import {
  describeConfirmation,
  describeCustomerParty,
  describeDeliveryTerms,
  describePaymentNodes,
  describePlanChange,
  describePlanStatus,
  describePreviousVersion,
  describeServiceArrangement,
  findPreviousPlanVersion,
  toQuoteRows,
} from '../presentation/plan/plan-confirmation-view'

describe('plan status chip', () => {
  it('says where the plan stands, cancelled and confirmed first', () => {
    const state = { isConfirmed: false, isExpired: false, isCancelled: false }
    expect(describePlanStatus(state)).toEqual({ label: '待确认', tone: 'pending' })
    expect(describePlanStatus({ ...state, isExpired: true }))
      .toEqual({ label: '已过期', tone: 'muted' })
    expect(describePlanStatus({ ...state, isExpired: true, isCancelled: true }))
      .toEqual({ label: '已取消', tone: 'muted' })
    expect(describePlanStatus({ ...state, isConfirmed: true }))
      .toEqual({ label: '已确认', tone: 'confirmed' })
  })
})

describe('plan versions', () => {
  const versions = [version(1, 1_200_000), version(2, 1_860_000), version(3, 1_500_000)]

  it('compares with the version right before this one', () => {
    expect(findPreviousPlanVersion(plan({ planVersion: 3 }), versions)?.planVersion).toBe(2)
    expect(findPreviousPlanVersion(plan({ planVersion: 2 }), versions)?.planVersion).toBe(1)
    expect(findPreviousPlanVersion(plan({ planVersion: 1 }), versions)).toBeNull()
    expect(findPreviousPlanVersion(plan({ planVersion: 2 }), [])).toBeNull()
  })

  it('states what changed and by how much', () => {
    const current = plan({ changeNote: '增加机器狗夜巡 6 次', totalAmountMinor: 1_860_000 })
    expect(describePlanChange(current, versions[0]))
      .toEqual({ text: '相比 v1：增加机器狗夜巡 6 次，', amountText: '+¥6,600', isIncrease: true })
  })

  it('shows a lower price with a minus sign, and no note when there is none', () => {
    expect(describePlanChange(plan({ changeNote: null, totalAmountMinor: 1_000_000 }), versions[0]))
      .toEqual({ text: '相比 v1：', amountText: '-¥2,000', isIncrease: false })
  })

  it('leaves the amount out when the price did not move', () => {
    expect(describePlanChange(plan({ changeNote: '调整巡检日期', totalAmountMinor: 1_200_000 }), versions[0]))
      .toEqual({ text: '相比 v1：调整巡检日期', amountText: '', isIncrease: false })
    expect(describePlanChange(plan({ changeNote: null, totalAmountMinor: 1_200_000 }), versions[0]))
      .toEqual({ text: '相比 v1：报价不变', amountText: '', isIncrease: false })
  })

  it('has nothing to compare on the first version', () => {
    expect(describePlanChange(plan({}), null)).toBeNull()
  })

  it('summarises the previous version in one line', () => {
    expect(describePreviousVersion({
      ...version(1, 1_200_000),
      planItems: [
        item('DRONE', '无人机巡检', 6, '次', 180_000),
        item('REPORT', '报告与隐患跟踪', 1, '项', 120_000),
      ],
    })).toEqual({
      title: '方案 v1',
      totalText: '¥12,000',
      itemsText: '无人机巡检 6 次 ¥10,800 + 报告与隐患跟踪 ¥1,200',
    })
  })
})

describe('quote rows', () => {
  it('hides the quantity of a lump-sum item and marks items the last version lacked', () => {
    const current = plan({
      planVersion: 2,
      planItems: [
        item('DRONE', '无人机巡检', 6, '次', 180_000),
        item('DOG', '机器狗夜巡', 6, '次', 110_000),
        item('REPORT', '报告与隐患跟踪', 1, '项', 120_000),
      ],
    })
    const previous = {
      ...version(1, 1_200_000),
      planItems: [item('DRONE', '无人机巡检', 6, '次', 180_000)],
    }

    expect(toQuoteRows(current, previous)).toEqual([
      { code: 'DRONE', name: '无人机巡检', quantityText: '6 次 × ¥1,800', amountText: '¥10,800', newLabel: '' },
      { code: 'DOG', name: '机器狗夜巡', quantityText: '6 次 × ¥1,100', amountText: '¥6,600', newLabel: 'v2 新增' },
      { code: 'REPORT', name: '报告与隐患跟踪', quantityText: '', amountText: '¥1,200', newLabel: 'v2 新增' },
    ])
  })

  it('marks nothing as new on the first version', () => {
    const rows = toQuoteRows(plan({ planItems: [item('DRONE', '无人机巡检', 1, '次', 100)] }), null)
    expect(rows[0]).toMatchObject({ quantityText: '1 次 × ¥1', newLabel: '' })
  })
})

describe('service arrangement', () => {
  it('derives the period from the visits', () => {
    const view = describeServiceArrangement(plan({
      scopeSummary: '约 42 万㎡',
      serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }, { code: 'DOG', label: '机器狗夜巡' }],
      visits: [
        { visitNo: 1, plannedDate: '2026-11-02' },
        { visitNo: 2, plannedDate: '2026-11-16' },
        { visitNo: 3, plannedDate: '2027-01-18' },
      ],
    }), null)

    expect(view.rows).toEqual([
      { label: '范围', value: '约 42 万㎡' },
      { label: '方式', value: '无人机巡检 + 机器狗夜巡' },
      { label: '周期', value: '11/02–01/18 · 共 3 次' },
    ])
    expect(view.visits).toEqual([
      { visitNo: 1, date: '11/02', label: '第 1 次 11/02' },
      { visitNo: 2, date: '11/16', label: '第 2 次 11/16' },
      { visitNo: 3, date: '01/18', label: '第 3 次 01/18' },
    ])
  })

  it('falls back to the line length and the plan summary for a plan without them', () => {
    const view = describeServiceArrangement(plan({
      scopeSummary: null,
      serviceMethods: [],
      summary: '无人机采集并交付报告',
      visits: [{ visitNo: 1, plannedDate: '2026-08-02' }],
    }), 18_600)

    expect(view.rows).toEqual([
      { label: '范围', value: '线路约 18.6 km' },
      { label: '方式', value: '无人机采集并交付报告' },
      { label: '周期', value: '08/02 · 共 1 次' },
    ])
  })

  it('leaves a row out rather than showing it empty', () => {
    const view = describeServiceArrangement(plan({
      scopeSummary: null,
      serviceMethods: [],
      summary: null,
      visits: [{ visitNo: 1, plannedDate: '2026-08-02' }],
    }), null)

    expect(view.rows.map((row) => row.label)).toEqual(['周期'])
  })
})

describe('delivery terms', () => {
  const deliverables = [
    { code: 'REPORT', label: '巡检报告' },
    { code: 'DEFECT_LIST', label: '隐患清单' },
  ]

  it('states the promised report time and media retention', () => {
    expect(describeDeliveryTerms(plan({
      deliverables,
      deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
    }))).toEqual([
      '每次巡检后 48 小时内交付巡检报告、隐患清单',
      '影像存档保存 90 天',
      '交付文件通过受控链接下载',
    ])
  })

  it('still lists what is delivered when no terms were agreed', () => {
    expect(describeDeliveryTerms(plan({ deliverables, deliveryTerms: null }))).toEqual([
      '交付内容：巡检报告、隐患清单',
      '交付文件通过受控链接下载',
    ])
  })
})

describe('payment nodes', () => {
  const nodes = [
    { nodeNo: 1, nodeType: 'DOWN_PAYMENT' as const, visitNo: null, amountMinor: 558_000 },
    ...[1, 2, 3, 4, 5, 6].map((visitNo) => ({
      nodeNo: visitNo + 1,
      nodeType: 'PER_VISIT' as const,
      visitNo,
      amountMinor: 217_000,
    })),
  ]

  it('shows the down payment and the per-visit total', () => {
    expect(describePaymentNodes(plan({ totalAmountMinor: 1_860_000, paymentNodes: nodes }), false)).toEqual([
      { key: 'DOWN_PAYMENT', name: '首付 30%', amountText: '¥5,580', stateText: '确认方案后支付', isDue: false },
      { key: 'PER_VISIT', name: '按次结算 70%', amountText: '¥13,020', stateText: '每次验收后支付 ¥2,170，共 6 次', isDue: false },
    ])
  })

  it('marks the down payment as due once the plan is confirmed', () => {
    expect(describePaymentNodes(plan({ totalAmountMinor: 1_860_000, paymentNodes: nodes }), true)[0])
      .toMatchObject({ stateText: '待支付', isDue: true })
  })

  it('calls out a last visit that absorbs the remainder', () => {
    const uneven = [
      { nodeNo: 1, nodeType: 'DOWN_PAYMENT' as const, visitNo: null, amountMinor: 30 },
      { nodeNo: 2, nodeType: 'PER_VISIT' as const, visitNo: 1, amountMinor: 23 },
      { nodeNo: 3, nodeType: 'PER_VISIT' as const, visitNo: 2, amountMinor: 23 },
      { nodeNo: 4, nodeType: 'PER_VISIT' as const, visitNo: 3, amountMinor: 24 },
    ]
    expect(describePaymentNodes(plan({ totalAmountMinor: 100, paymentNodes: uneven }), false)[1].stateText)
      .toBe('每次验收后支付 ¥0.23，最后一次 ¥0.24，共 3 次')
  })

  it('shows nothing while the platform has payment nodes switched off', () => {
    expect(describePaymentNodes(plan({ paymentNodes: [] }), false)).toEqual([])
    expect(describePaymentNodes(plan({ paymentNodes: undefined }), false)).toEqual([])
  })
})

describe('customer and confirmation lines', () => {
  it('names the organization and its operator', () => {
    expect(describeCustomerParty({ organizationName: '东营港务物流', operatorName: '王女士' }))
      .toEqual({ isOrganization: true, text: '东营港务物流（经办人 王女士）' })
  })

  it('shows a person without an organization by name alone', () => {
    expect(describeCustomerParty({ organizationName: null, operatorName: '王女士' }))
      .toEqual({ isOrganization: false, text: '王女士' })
    expect(describeCustomerParty(null)).toBeNull()
  })

  it('records who confirmed which version and when', () => {
    const confirmedAt = new Date(2026, 10, 1, 9, 30).toISOString()
    expect(describeConfirmation(plan({
      planVersion: 2,
      confirmedAt,
      confirmedBy: { subjectId: 'u1', displayName: '王女士' },
      customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    }))).toEqual({
      title: '已确认方案 v2',
      byText: '王女士（经办人）· 11/01 09:30',
      note: '你确认的是 v2；服务商如更新方案，需要重新确认',
    })
  })

  it('omits an unknown confirmer rather than leaving a dangling separator', () => {
    const confirmedAt = new Date(2026, 10, 1, 9, 30).toISOString()
    expect(describeConfirmation(plan({ confirmedAt, confirmedBy: null })).byText).toBe('11/01 09:30')
  })
})

function item(code: string, name: string, quantity: number, unit: string, unitPriceMinor: number) {
  return { code, name, quantity, unit, unitPriceMinor, lineAmountMinor: quantity * unitPriceMinor }
}

function version(planVersion: number, totalAmountMinor: number): InspectionPlanVersionSummary {
  return {
    id: `plan-${planVersion}`,
    planNo: `FA${planVersion}`,
    planVersion,
    status: 'WITHDRAWN',
    totalAmountMinor,
    planItems: [],
    changeNote: null,
    publishedAt: '2026-10-30T02:00:00Z',
  }
}

function plan(overrides: Partial<InspectionPlan>): InspectionPlan {
  return {
    id: 'plan-2',
    planNo: 'FA2',
    reservationId: 'reservation-1',
    providerId: 'provider-1',
    status: 'PUBLISHED',
    planVersion: 2,
    validUntil: '2026-11-01T10:00:00Z',
    serviceDate: '2026-11-02',
    visits: [{ visitNo: 1, plannedDate: '2026-11-02' }],
    summary: null,
    planItems: [],
    deliverables: [],
    totalAmountMinor: 1_860_000,
    currency: 'CNY',
    publishedAt: '2026-11-01T01:00:00Z',
    confirmedAt: null,
    serviceOrderId: null,
    version: 0,
    ...overrides,
  }
}
