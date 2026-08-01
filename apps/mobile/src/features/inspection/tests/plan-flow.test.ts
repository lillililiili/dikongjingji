import { describe, expect, it } from 'vitest'

import pagesSource from '../../../pages.json?raw'
import planPageSource from '../presentation/plan/pages/InspectionPlanConfirmationPage.vue?raw'
import {
  PLAN_AGREEMENT_VERSION,
  formatPlanAmount,
  isPlanConfirmable,
} from '../domain/service-flow/plan'
import {
  createInspectionPlanApi,
  type InspectionPlan,
} from '../data/service-flow/inspection-plan-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import {
  buildInspectionFulfillmentUrl,
  buildInspectionPlanConfirmationUrl,
} from '../navigation/inspection-routes'

const PLAN_ID = '50000000-0000-4000-8000-000000000001'
const ORDER_ID = '60000000-0000-4000-8000-000000000001'

describe('F05 plan domain', () => {
  it('formats minor currency units without floating-point drift', () => {
    expect(formatPlanAmount(859770)).toBe('¥8,597.70')
    expect(formatPlanAmount(0)).toBe('¥0.00')
  })

  it('allows confirmation only for an unexpired published plan', () => {
    expect(isPlanConfirmable(plan(), new Date('2026-07-28T02:00:00Z'))).toBe(true)
    expect(isPlanConfirmable(
      { ...plan(), status: 'CONFIRMED' },
      new Date('2026-07-28T02:00:00Z'),
    )).toBe(false)
    expect(isPlanConfirmable(plan(), new Date('2026-07-30T02:00:00Z'))).toBe(false)
  })
})

describe('F05 plan API and navigation', () => {
  it('loads and confirms using stable IDs, idempotency and optimistic version', async () => {
    const calls: Array<{ url: string; method: string; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        return (input.method === 'GET' ? plan() : {
          id: ORDER_ID,
          status: 'ARRANGING',
        }) as T
      },
      putFile: async () => ({ etag: 'unused' }),
    }
    const api = createInspectionPlanApi(http, {
      getAccessToken: () => 'customer-token',
    })

    await api.getPlan(PLAN_ID)
    await api.confirmPlan(PLAN_ID, 2, '70000000-0000-4000-8000-000000000001')

    expect(calls).toEqual([
      {
        url: `/api/v1/inspection/plans/${PLAN_ID}`,
        method: 'GET',
        headers: { Authorization: 'Bearer customer-token' },
      },
      {
        url: `/api/v1/inspection/plans/${PLAN_ID}/confirm`,
        method: 'POST',
        data: {
          agreementVersion: PLAN_AGREEMENT_VERSION,
          agreementAccepted: true,
        },
        headers: {
          Authorization: 'Bearer customer-token',
          'Idempotency-Key': '70000000-0000-4000-8000-000000000001',
          'If-Match': '"2"',
        },
      },
    ])
    expect(buildInspectionPlanConfirmationUrl(PLAN_ID)).toBe(
      `/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`,
    )
    expect(buildInspectionFulfillmentUrl(ORDER_ID)).toBe(
      `/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`,
    )
  })

  it('registers INS-M-006 as a custom-navigation page', () => {
    const pages = JSON.parse(pagesSource) as {
      pages: Array<{ path: string; style?: Record<string, string> }>
    }
    expect(
      pages.pages.find((page) =>
        page.path === 'pages/service/inspection/plan-confirmation'),
    ).toMatchObject({ style: { navigationStyle: 'custom' } })
  })
})

describe('F05 plan page contract', () => {
  it('falls back to the shared order center instead of Home without valid history', () => {
    expect(planPageSource).toContain("buildInspectionOrderCenterUrl('ALL')")
    expect(planPageSource).not.toContain("uni.reLaunch({ url: '/pages/home/index' })")
  })

  it('matches the approved pending-confirmation structure and real DTO actions', () => {
    expect(planPageSource).toContain('<BookingProgress :active="2"')
    expect(planPageSource).toContain('巡检方案已生成')
    expect(planPageSource).toContain('plan-confirmation__route-preview')
    expect(planPageSource).toContain('报价明细')
    expect(planPageSource).toContain('最终报价')
    expect(planPageSource).toContain('申请调整')
    expect(planPageSource).toContain('确认方案')
    expect(planPageSource).toContain("isConfirmed ? 'checkmarkempty' : 'info-filled'")
    expect(planPageSource).toContain('plan-confirmation__status-icon--confirmed')
    expect(planPageSource).toContain('inspectionPlanApi.getPlan(planId)')
    expect(planPageSource).toContain('inspectionPlanApi.confirmPlan(')
    expect(planPageSource).toContain('env(safe-area-inset-bottom)')
    expect(planPageSource).not.toContain('立即支付')
  })
})

function plan(): InspectionPlan {
  return {
    id: PLAN_ID,
    planNo: 'FA202607270001',
    reservationId: '40000000-0000-4000-8000-000000000001',
    providerId: '30000000-0000-4000-8000-000000000001',
    status: 'PUBLISHED',
    planVersion: 1,
    validUntil: '2026-07-29T02:00:00Z',
    serviceDate: '2026-08-02',
    summary: '无人机采集、专业人员复核',
    planItems: [{
      code: 'POWER_LINE',
      name: '巡检服务',
      quantity: 12,
      unit: '公里',
      unitPriceMinor: 69900,
      lineAmountMinor: 838800,
    }],
    deliverables: [
      { code: 'HD_MEDIA', label: '高清巡检影像' },
      { code: 'DEFECT_LIST', label: '缺陷清单' },
      { code: 'POINT_MARKS', label: '点位标注' },
      { code: 'REPORT', label: '巡检报告' },
    ],
    totalAmountMinor: 838800,
    currency: 'CNY',
    publishedAt: '2026-07-28T02:00:00Z',
    confirmedAt: null,
    serviceOrderId: null,
    version: 2,
  }
}
