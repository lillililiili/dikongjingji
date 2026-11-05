import { describe, expect, it } from 'vitest'

import pagesSource from '../../../pages.json?raw'
import fulfillmentPageSource from '../presentation/fulfillment/pages/InspectionFulfillmentPage.vue?raw'
import {
  createInspectionPlanApi,
  type InspectionServiceOrder,
} from '../data/service-flow/inspection-plan-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import { buildInspectionFulfillmentUrl } from '../navigation/inspection-routes'

const ORDER_ID = '60000000-0000-4000-8000-000000000001'

describe('F06 fulfillment API and route', () => {
  it('loads the service order through its stable ID with authenticated headers', async () => {
    const calls: Array<{ url: string; method: string; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        return serviceOrder() as T
      },
      putFile: async () => ({ etag: 'unused' }),
    }
    const api = createInspectionPlanApi(http, { getAccessToken: () => 'customer-token' })

    await api.getServiceOrder(ORDER_ID)

    expect(calls).toEqual([{
      url: `/api/v1/inspection/service-orders/${ORDER_ID}`,
      method: 'GET',
      headers: { Authorization: 'Bearer customer-token' },
    }])
    expect(buildInspectionFulfillmentUrl(ORDER_ID)).toBe(
      `/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`,
    )
  })

  it('registers INS-M-007 as a custom-navigation page', () => {
    const pages = JSON.parse(pagesSource) as { pages: Array<{ path: string; style?: Record<string, string> }> }
    expect(pages.pages.find((page) => page.path === 'pages/service/inspection/fulfillment'))
      .toMatchObject({ style: { navigationStyle: 'custom' } })
  })
})

describe('F06 fulfillment page contract', () => {
  it('falls back to the shared order center instead of Home without valid history', () => {
    expect(fulfillmentPageSource).toContain("buildInspectionOrderCenterUrl('ALL')")
    expect(fulfillmentPageSource).not.toContain("uni.reLaunch({ url: '/pages/home/index' })")
  })

  it('renders real visit progress and allows retry without payment actions', () => {
    expect(fulfillmentPageSource).toContain('inspectionPlanApi.getServiceOrder(serviceOrderId.value)')
    expect(fulfillmentPageSource).toContain('inspectionPlanApi.getPlan(current.planId)')
    expect(fulfillmentPageSource).toContain('服务履约')
    expect(fulfillmentPageSource).toContain('FulfillmentSummaryCard')
    expect(fulfillmentPageSource).toContain('FulfillmentArrangementCard')
    expect(fulfillmentPageSource).toContain('VisitTimeline')
    expect(fulfillmentPageSource).toContain('@click="reload"')
    expect(fulfillmentPageSource).toContain('env(safe-area-inset-bottom)')
    expect(fulfillmentPageSource).toContain('buildInspectionDeliveryUrl(order.value.id, visitId)')
    expect(fulfillmentPageSource).not.toContain('progressPercent')
    expect(fulfillmentPageSource).not.toContain('BookingProgress')
    expect(fulfillmentPageSource).not.toContain('立即支付')
  })

  it('only offers acceptance while a visit waits for it', () => {
    expect(fulfillmentPageSource).toContain('v-if="pendingAcceptance && !isLoading && !loadError"')
    expect(fulfillmentPageSource).toContain('去验收')
  })

  it('refreshes on return so an accepted visit does not look pending', () => {
    expect(fulfillmentPageSource).toContain('onShow(() => {')
    expect(fulfillmentPageSource).toContain('if (order.value) void refresh()')
  })
})

function serviceOrder(): InspectionServiceOrder {
  return {
    id: ORDER_ID,
    serviceOrderNo: 'FW202607280001',
    reservationId: '40000000-0000-4000-8000-000000000001',
    planId: '50000000-0000-4000-8000-000000000001',
    customerId: '10000000-0000-4000-8000-000000000001',
    providerId: '30000000-0000-4000-8000-000000000001',
    status: 'ARRANGING',
    paymentMode: 'NONE',
    serviceSnapshot: {
      serviceId: '33333333-3333-4333-8333-333333333333',
      serviceTitle: '输电线路无人机巡检',
      serviceCoverUrl: null,
      cityCode: '370500',
      location: {
        address: '东营市河口区示范线路',
        longitude: 118.5,
        latitude: 37.4,
      },
      lineLengthMeters: 18_600,
      serviceDate: '2026-08-02',
      inspectionItems: [{ code: 'POWER_LINE', label: '线路本体巡检' }],
    },
    assignments: [{ type: 'TEAM_MEMBER', resourceId: 'team-01', displayName: '巡检作业组', roleLabel: '作业团队' }],
    progress: [{ id: 'progress-01', eventCode: 'ARRANGEMENT_READY', occurredAt: '2026-07-28T03:00:00Z', message: '作业资源已安排' }],
    visits: [{
      id: '61000000-0000-4000-8000-000000000001',
      serviceOrderId: ORDER_ID,
      visitNo: 1,
      plannedDate: '2026-08-02',
      plannedStartAt: '2026-08-02T01:00:00Z',
      status: 'SCHEDULED',
      progress: [],
      version: 1,
    }],
    createdAt: '2026-07-28T02:30:00Z',
    deliveredAt: null,
    acceptedAt: null,
    version: 1,
  }
}
