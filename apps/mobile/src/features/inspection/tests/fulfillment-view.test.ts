import { describe, expect, it } from 'vitest'

import type {
  InspectionServiceOrder,
  InspectionServiceVisit,
} from '../data/service-flow/inspection-plan-api'
import {
  describeArrangement,
  describeFulfillmentProgress,
  describePendingAcceptance,
  toVisitRows,
} from '../presentation/fulfillment/fulfillment-view'

const at = (month: number, day: number, hour: number, minute = 0): string =>
  new Date(2026, month - 1, day, hour, minute).toISOString()

describe('fulfillment progress', () => {
  it('counts delivered visits as finished and names the next one', () => {
    const view = describeFulfillmentProgress(order('IN_PROGRESS', [
      visit(1, 'DELIVERED', '2026-11-02'),
      visit(2, 'SCHEDULED', '2026-11-16'),
      visit(3, 'PENDING_SCHEDULE', '2027-01-18'),
    ]))

    expect(view).toEqual({
      statusLabel: '履约中',
      isFinished: false,
      countText: '3 次中已完成 1 次',
      segments: [
        { id: 'visit-1', done: true },
        { id: 'visit-2', done: false },
        { id: 'visit-3', done: false },
      ],
      scheduleText: '下次巡检 11/16 · 周期 11/02–01/18',
    })
  })

  it('prefers the arranged start time over the planned date', () => {
    const view = describeFulfillmentProgress(order('ARRANGING', [
      visit(1, 'SCHEDULED', '2026-11-02', { plannedStartAt: at(11, 3, 9) }),
    ]))

    expect(view.statusLabel).toBe('待履约')
    expect(view.scheduleText).toBe('下次巡检 11/03 · 周期 11/03')
  })

  it('drops the next visit once none is left', () => {
    const view = describeFulfillmentProgress(order('ACCEPTED', [
      visit(1, 'ACCEPTED', '2026-11-02'),
      visit(2, 'ACCEPTED', '2026-11-16'),
    ]))

    expect(view).toMatchObject({
      statusLabel: '已完成',
      isFinished: true,
      countText: '2 次中已完成 2 次',
      scheduleText: '周期 11/02–11/16',
    })
  })

  it('lights the segment of the visit that finished, even out of order', () => {
    const view = describeFulfillmentProgress(order('IN_PROGRESS', [
      visit(1, 'IN_PROGRESS', '2026-11-02'),
      visit(2, 'DELIVERED', '2026-11-16'),
      visit(3, 'SCHEDULED', '2026-12-01'),
    ]))

    expect(view.segments.map((segment) => segment.done)).toEqual([false, true, false])
    expect(view.countText).toBe('3 次中已完成 1 次')
  })

  it('stretches the period to a visit moved past its planned date', () => {
    const view = describeFulfillmentProgress(order('IN_PROGRESS', [
      visit(1, 'SCHEDULED', '2026-11-02'),
      visit(2, 'SCHEDULED', '2026-12-20', { plannedStartAt: at(12, 28, 9) }),
    ]))

    expect(view.scheduleText).toBe('下次巡检 11/02 · 周期 11/02–12/28')
  })

  it('reads the status of every visit from the order, not from a fixed percentage', () => {
    expect(describeFulfillmentProgress(order('DELIVERED', [
      visit(1, 'DELIVERED', '2026-11-02'),
    ])).statusLabel).toBe('待验收')
  })
})

describe('arrangement', () => {
  it('groups people by role and devices by name, keeping the lead apart', () => {
    expect(describeArrangement([
      member('王工', '项目负责人', '138****6621'),
      member('飞手甲', '持证飞手'),
      member('飞手乙', '持证飞手'),
      device('行业无人机'),
      device('巡检机器狗'),
      device('行业无人机'),
    ])).toEqual({
      lead: { name: '王工', mobileText: '138****6621' },
      rows: [
        { label: '持证飞手', value: '2 名' },
        { label: '设备', value: '行业无人机 ×2、巡检机器狗 ×1' },
      ],
    })
  })

  it('trusts the role over a mobile entered on someone else', () => {
    expect(describeArrangement([
      member('飞手甲', '持证飞手', '138****0001'),
      member('王工', '项目负责人'),
    ])).toEqual({
      lead: { name: '王工', mobileText: '' },
      rows: [{ label: '持证飞手', value: '1 名' }],
    })
  })

  it('falls back to the person with a mobile when no role says lead', () => {
    expect(describeArrangement([member('张三', '作业组长', '138****0002')]).lead)
      .toEqual({ name: '张三', mobileText: '138****0002' })
  })

  it('finds the lead by role when no mobile was entered', () => {
    expect(describeArrangement([member('东营巡检作业组', '项目负责人')]).lead)
      .toEqual({ name: '东营巡检作业组', mobileText: '' })
  })

  it('counts unlabelled people as crew, and shows no lead it cannot identify', () => {
    expect(describeArrangement([member('张三', null)])).toEqual({
      lead: null,
      rows: [{ label: '作业人员', value: '1 名' }],
    })
  })

  it('has nothing to show before the provider arranges anyone', () => {
    expect(describeArrangement([])).toEqual({ lead: null, rows: [] })
  })
})

describe('visit rows', () => {
  it('expands a delivered visit with its times and its way into the delivery', () => {
    const [row] = toVisitRows([
      visit(1, 'DELIVERED', '2026-11-02', {
        progress: [
          event('e1', 'SERVICE_STARTED', at(11, 2, 19), '现场巡检已开始'),
          event('e2', 'SERVICE_PROGRESS', at(11, 2, 20), '已完成东区巡检'),
          event('e3', 'SERVICE_COMPLETED', at(11, 2, 21, 30), '现场巡检已完成'),
        ],
        deliveredAt: at(11, 3, 10),
      }),
    ], 48)

    expect(row).toMatchObject({
      title: '第 1 次 · 11/02',
      statusText: '已完成 · 报告已交付',
      tone: 'delivered',
      badge: '待验收',
      deliveryLabel: '查看第 1 次交付',
      isExpanded: true,
      isNext: false,
    })
    expect(row.events).toEqual([
      { id: 'e1', time: '11/02 19:00', text: '开始作业' },
      { id: 'e2', time: '11/02 20:00', text: '已完成东区巡检' },
      { id: 'e3', time: '11/02 21:30', text: '完成作业' },
      { id: 'delivered', time: '11/03 10:00', text: '报告已交付（48 小时内）' },
    ])
  })

  it('only claims the promised report time when it was met', () => {
    const [row] = toVisitRows([
      visit(1, 'DELIVERED', '2026-11-02', {
        progress: [event('e3', 'SERVICE_COMPLETED', at(11, 2, 21), '现场巡检已完成')],
        deliveredAt: at(11, 6, 10),
      }),
    ], 48)

    expect(row.events.at(-1)).toMatchObject({ text: '报告已交付' })
  })

  it('collapses an accepted visit and keeps its delivery reachable', () => {
    const [row] = toVisitRows([
      visit(1, 'ACCEPTED', '2026-11-02', { acceptedAt: at(11, 3, 16, 20) }),
    ], null)

    expect(row).toMatchObject({
      statusText: '已验收 · 11/03 16:20',
      tone: 'accepted',
      badge: '',
      deliveryLabel: '查看第 1 次交付',
      isExpanded: false,
    })
  })

  it('shows work under way, and when the site work is done before the report', () => {
    const rows = toVisitRows([
      visit(1, 'IN_PROGRESS', '2026-11-02', {
        progress: [event('e1', 'SERVICE_STARTED', at(11, 2, 19), '现场巡检已开始')],
      }),
      visit(2, 'IN_PROGRESS', '2026-11-16', {
        progress: [event('e2', 'SERVICE_COMPLETED', at(11, 16, 19), '现场巡检已完成')],
      }),
    ], null)

    expect(rows[0]).toMatchObject({ statusText: '作业中', tone: 'active', isExpanded: true, deliveryLabel: '' })
    expect(rows[1]).toMatchObject({ statusText: '现场已完成 · 报告整理中', tone: 'active' })
  })

  it('marks only the next visit, and gives the time once it is arranged', () => {
    const rows = toVisitRows([
      visit(1, 'SCHEDULED', '2026-11-16', { plannedStartAt: at(11, 16, 9) }),
      visit(2, 'PENDING_SCHEDULE', '2026-12-01'),
    ], null)

    expect(rows[0]).toMatchObject({
      title: '第 1 次 · 11/16',
      statusText: '已排期 · 09:00',
      tone: 'scheduled',
      isNext: true,
      isExpanded: false,
    })
    expect(rows[1]).toMatchObject({ statusText: '待排期', tone: 'pending', isNext: false })
  })

  it('lists visits in order whatever order they arrive in', () => {
    const rows = toVisitRows([
      visit(2, 'SCHEDULED', '2026-11-16'),
      visit(1, 'DELIVERED', '2026-11-02'),
    ], null)

    expect(rows.map((row) => row.visitNo)).toEqual([1, 2])
  })
})

describe('pending acceptance', () => {
  it('points at the earliest delivered visit', () => {
    const view = describePendingAcceptance([
      visit(2, 'DELIVERED', '2026-11-16', { deliveredAt: at(11, 17, 8) }),
      visit(1, 'DELIVERED', '2026-11-02', { deliveredAt: at(11, 3, 10) }),
    ])

    expect(view).toEqual({
      visitId: 'visit-1',
      title: '第 1 次报告待验收',
      text: '11/03 10:00 已交付，请核对后验收',
    })
  })

  it('is absent when nothing waits for acceptance', () => {
    expect(describePendingAcceptance([visit(1, 'ACCEPTED', '2026-11-02')])).toBeNull()
  })
})

function member(displayName: string, roleLabel: string | null, contactMobileMasked: string | null = null) {
  return {
    type: 'TEAM_MEMBER' as const,
    resourceId: displayName,
    displayName,
    roleLabel,
    contactMobileMasked,
  }
}

function device(displayName: string) {
  return { type: 'DEVICE' as const, resourceId: displayName, displayName, roleLabel: null }
}

function event(id: string, eventCode: string, occurredAt: string, message: string) {
  return { id, eventCode, occurredAt, message }
}

function visit(
  visitNo: number,
  status: InspectionServiceVisit['status'],
  plannedDate: string,
  overrides: Partial<InspectionServiceVisit> = {},
): InspectionServiceVisit {
  return {
    id: `visit-${visitNo}`,
    serviceOrderId: 'order-1',
    visitNo,
    plannedDate,
    plannedStartAt: null,
    status,
    progress: [],
    startedAt: null,
    deliveredAt: null,
    acceptedAt: null,
    version: 1,
    ...overrides,
  }
}

function order(
  status: InspectionServiceOrder['status'],
  visits: InspectionServiceVisit[],
): Pick<InspectionServiceOrder, 'status' | 'visits'> {
  return { status, visits }
}
