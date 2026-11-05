import { describe, expect, it } from 'vitest'

import type {
  InspectionDelivery,
  InspectionServiceOrder,
  InspectionServiceVisit,
} from '../data/service-flow/inspection-plan-api'
import {
  describeAcceptanceBanner,
  describeAcceptanceSheet,
  describeDeliveryHeader,
  toFileRows,
  toIssueRows,
  toResultTiles,
} from '../presentation/delivery/delivery-view'

const at = (month: number, day: number, hour: number, minute = 0): string =>
  new Date(2026, month - 1, day, hour, minute).toISOString()

describe('delivery header', () => {
  it('names the service, the visit and when the report arrived', () => {
    expect(describeDeliveryHeader(order(), visit(), delivery())).toEqual({
      contextText: '物流园区季度安防巡检 · 鹰眼智能巡检',
      title: '第 1 次巡检 · 11/02',
      status: { label: '待验收', isAccepted: false },
      deliveredText: '报告 11/03 10:00 已交付',
    })
  })

  it('leaves the provider out when it is unknown', () => {
    expect(describeDeliveryHeader({ ...order(), provider: null }, visit(), delivery()).contextText)
      .toBe('物流园区季度安防巡检')
  })

  it('reports acceptance', () => {
    expect(describeDeliveryHeader(order(), visit(), accepted()).status)
      .toEqual({ label: '已验收', isAccepted: true })
  })
})

describe('acceptance banner', () => {
  it('states who accepted which visit and when', () => {
    expect(describeAcceptanceBanner(visit(), accepted())).toEqual({
      title: '第 1 次已验收',
      text: '11/03 16:20 · 王女士 · 验收记录已留存',
    })
  })

  it('still confirms acceptance when the name is unknown', () => {
    expect(describeAcceptanceBanner(visit(), { ...accepted(), acceptedByName: null })?.text)
      .toBe('11/03 16:20 · 验收记录已留存')
  })

  it('is absent before acceptance', () => {
    expect(describeAcceptanceBanner(visit(), delivery())).toBeNull()
  })
})

describe('result tiles', () => {
  it('shows each metric and the issue count last', () => {
    expect(toResultTiles(delivery({
      metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 42, unit: '万㎡' }],
      issueCount: 3,
    }))).toEqual([
      { key: 'COVERED_AREA', label: '巡检覆盖', valueText: '42 万㎡', isIssue: false },
      { key: 'ISSUES', label: '发现隐患', valueText: '3 项', isIssue: true },
    ])
  })

  it('keeps the tower and length of a power-line delivery', () => {
    expect(toResultTiles(delivery({
      towerCount: 24,
      inspectedLengthMeters: 12_000,
      metrics: [],
      issueCount: 0,
    }))).toEqual([
      { key: 'TOWER_COUNT', label: '巡检杆塔', valueText: '24 基', isIssue: false },
      { key: 'INSPECTED_LENGTH', label: '巡检长度', valueText: '12 km', isIssue: false },
      { key: 'ISSUES', label: '发现隐患', valueText: '0 项', isIssue: true },
    ])
  })

  it('formats large and fractional metric values', () => {
    expect(toResultTiles(delivery({
      metrics: [
        { code: 'AREA', label: '面积', value: 420_000, unit: '㎡' },
        { code: 'RATE', label: '覆盖率', value: 99.5, unit: '%' },
      ],
    })).map((tile) => tile.valueText)).toEqual(['420,000 ㎡', '99.5 %', '3 项'])
  })
})

describe('issue rows', () => {
  it('lists each group with its count', () => {
    expect(toIssueRows(delivery({
      issueSummary: [
        { groupCode: 'FENCE', groupName: '围栏破损', count: 1 },
        { groupCode: 'LIGHT', groupName: '照明故障', count: 2 },
      ],
    }))).toEqual([
      { key: 'FENCE', name: '围栏破损', countText: '1 项' },
      { key: 'LIGHT', name: '照明故障', countText: '2 项' },
    ])
  })
})

describe('file rows', () => {
  it('adds the image count to the archive and the point count to the KML', () => {
    const rows = toFileRows(delivery({
      files: [
        file('f1', 'IMAGE_ARCHIVE', '影像存档', { itemCount: 36, sizeBytes: 5 * 1024 * 1024 }),
        file('f2', 'POINT_KML', '巡检点位', { pointCount: 24, sizeBytes: 2048 }),
      ],
    }), {})

    expect(rows.map(({ name, detailText }) => ({ name, detailText }))).toEqual([
      { name: '影像存档（36 张）', detailText: '5.0 MB' },
      { name: '巡检点位', detailText: '24 个点位 · 2 KB' },
    ])
  })

  it('only documents can be opened in place', () => {
    const rows = toFileRows(delivery({
      files: [
        file('f1', 'INSPECTION_REPORT', '巡检报告'),
        file('f2', 'DEFECT_LIST', '隐患清单'),
        file('f3', 'IMAGE_ARCHIVE', '影像存档'),
        file('f4', 'POINT_KML', '巡检点位'),
      ],
    }), {})

    expect(rows.map((row) => row.canPreview)).toEqual([true, true, false, false])
  })

  it('shows when a generated link stops working, from what the server returned', () => {
    const [row, other] = toFileRows(delivery({
      files: [file('f1', 'INSPECTION_REPORT', '巡检报告'), file('f2', 'DEFECT_LIST', '隐患清单')],
    }), { f1: at(11, 3, 16, 5) })

    expect(row.linkText).toBe('下载链接已生成，16:05 前有效')
    expect(other.linkText).toBe('')
  })
})

describe('acceptance sheet', () => {
  it('sums up what is being accepted and by whom', () => {
    expect(describeAcceptanceSheet(order(), visit(), delivery({
      files: [file('f1', 'INSPECTION_REPORT', '巡检报告')],
    }))).toEqual({
      title: '确认第 1 次验收',
      items: [{ key: 'f1', name: '巡检报告' }],
      providerName: '鹰眼智能巡检',
      acceptorText: '王女士 · 东营港务物流 经办人',
      feeText: '',
    })
  })

  it('shows the fee of this visit when payment nodes are on', () => {
    expect(describeAcceptanceSheet({
      ...order(),
      paymentNodes: [
        { nodeNo: 1, nodeType: 'DOWN_PAYMENT', visitNo: null, amountMinor: 558_000, payable: true },
        { nodeNo: 2, nodeType: 'PER_VISIT', visitNo: 1, amountMinor: 217_000, payable: false },
        { nodeNo: 3, nodeType: 'PER_VISIT', visitNo: 2, amountMinor: 217_100, payable: false },
      ],
    }, visit(), delivery()).feeText).toBe('¥2,170 · 验收后支付')
  })

  it('leaves out what it does not know', () => {
    const sheet = describeAcceptanceSheet({
      ...order(),
      provider: null,
      customerParty: { organizationName: null, operatorName: '王女士' },
    }, visit(), delivery())

    expect(sheet.providerName).toBe('')
    expect(sheet.acceptorText).toBe('王女士')
    expect(describeAcceptanceSheet({ ...order(), customerParty: null }, visit(), delivery()).acceptorText)
      .toBe('')
  })
})

function file(
  fileId: string,
  type: InspectionDelivery['files'][number]['type'],
  displayName: string,
  overrides: Partial<InspectionDelivery['files'][number]> = {},
): InspectionDelivery['files'][number] {
  return {
    fileId,
    type,
    displayName,
    pointCount: null,
    itemCount: null,
    contentType: 'application/pdf',
    sizeBytes: 1024,
    status: 'READY',
    ...overrides,
  }
}

function delivery(overrides: Partial<InspectionDelivery> = {}): InspectionDelivery {
  return {
    id: 'delivery-1',
    serviceOrderId: 'order-1',
    visitId: 'visit-1',
    towerCount: null,
    inspectedLengthMeters: null,
    metrics: [],
    issueCount: 3,
    issueGroupBy: 'CATEGORY',
    issueSummary: [],
    files: [],
    publishedAt: at(11, 3, 10),
    version: 1,
    status: 'PUBLISHED',
    acceptedAt: null,
    ...overrides,
  } as InspectionDelivery
}

function accepted(): InspectionDelivery {
  return delivery({
    status: 'ACCEPTED',
    acceptedAt: at(11, 3, 16, 20),
    acceptedByName: '王女士',
  } as Partial<InspectionDelivery>)
}

function visit(): InspectionServiceVisit {
  return {
    id: 'visit-1',
    serviceOrderId: 'order-1',
    visitNo: 1,
    plannedDate: '2026-11-02',
    plannedStartAt: null,
    status: 'DELIVERED',
    progress: [],
    version: 3,
  }
}

function order(): Pick<InspectionServiceOrder, 'provider' | 'customerParty' | 'paymentNodes' | 'serviceSnapshot'> {
  return {
    provider: { providerId: 'p1', name: '鹰眼智能巡检', certificationLabel: '资质已审核' },
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    paymentNodes: [],
    serviceSnapshot: {
      serviceId: 's1',
      serviceTitle: '物流园区季度安防巡检',
      serviceCoverUrl: null,
      cityCode: '370500',
      location: { address: '东营市', longitude: null, latitude: null },
      lineLengthMeters: null,
      serviceDate: '2026-11-02',
      inspectionItems: [],
    },
  }
}
