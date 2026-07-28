import { describe, expect, it } from 'vitest'

import pagesSource from '../../../pages.json?raw'
import deliveryPageSource from '../presentation/delivery/pages/InspectionDeliveryPage.vue?raw'
import {
  createInspectionPlanApi,
  type InspectionAcceptance,
  type InspectionDelivery,
} from '../data/service-flow/inspection-plan-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import { buildInspectionDeliveryUrl } from '../navigation/inspection-routes'

const ORDER_ID = '60000000-0000-4000-8000-000000000001'
const FILE_ID = '70000000-0000-4000-8000-000000000001'

describe('F07 delivery API and route', () => {
  it('uses authenticated controlled endpoints for delivery, downloads and acceptance', async () => {
    const calls: Array<{ url: string; method: string; data?: unknown; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' | 'POST'; data?: unknown; headers?: Record<string, string> }) => {
        calls.push(input)
        const response = input.url.endsWith('/acceptance') ? acceptance() : delivery()
        return response as T
      },
      putFile: async () => ({ etag: 'unused' }),
    }
    const api = createInspectionPlanApi(http, { getAccessToken: () => 'customer-token' })

    await api.getDelivery(ORDER_ID)
    await api.createDownloadIntent(ORDER_ID, FILE_ID, 'key-file')
    await api.createDownloadAllIntent(ORDER_ID, 'key-all')
    const accepted = await api.acceptDelivery(ORDER_ID, 8, 'key-accept')

    expect(calls).toEqual([
      { url: `/api/v1/inspection/service-orders/${ORDER_ID}/delivery`, method: 'GET', headers: { Authorization: 'Bearer customer-token' } },
      { url: `/api/v1/inspection/service-orders/${ORDER_ID}/delivery-files/${FILE_ID}/download-intent`, method: 'POST', headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-file' } },
      { url: `/api/v1/inspection/service-orders/${ORDER_ID}/delivery-files/download-all-intent`, method: 'POST', headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-all' } },
      { url: `/api/v1/inspection/service-orders/${ORDER_ID}/acceptance`, method: 'POST', data: { confirmed: true, note: null }, headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-accept', 'If-Match': '"8"' } },
    ])
    expect(accepted).toEqual(acceptance())
    expect(buildInspectionDeliveryUrl(ORDER_ID)).toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
  })

  it('registers INS-M-008 as a custom-navigation page', () => {
    const pages = JSON.parse(pagesSource) as { pages: Array<{ path: string; style?: Record<string, string> }> }
    expect(pages.pages.find((page) => page.path === 'pages/service/inspection/delivery'))
      .toMatchObject({ style: { navigationStyle: 'custom' } })
  })

  it('renders the three delivery-board stages without changing earlier shared pages', () => {
    expect(deliveryPageSource).toContain("['服务安排', '现场巡检', '报告交付']")
    expect(deliveryPageSource).toContain('delivery__flow-label--current')
  })
})

describe('F07 delivery page contract', () => {
  it('loads real delivery, creates controlled download intents and guards duplicate acceptance', () => {
    expect(deliveryPageSource).toContain('inspectionPlanApi.getDelivery(serviceOrderId.value)')
    expect(deliveryPageSource).toContain('inspectionPlanApi.createDownloadIntent(')
    expect(deliveryPageSource).toContain('inspectionPlanApi.createDownloadAllIntent(')
    expect(deliveryPageSource).toContain('inspectionPlanApi.acceptDelivery')
    expect(deliveryPageSource).toContain('class="delivery__flow"')
    expect(deliveryPageSource).toContain('await reload()')
    expect(deliveryPageSource).toContain('报告交付')
    expect(deliveryPageSource).toContain('服务单号')
    expect(deliveryPageSource).toContain('巡检结果')
    expect(deliveryPageSource).toContain('服务进度')
    expect(deliveryPageSource).toContain('验收已完成')
    expect(deliveryPageSource).not.toContain('待评价')
    expect(deliveryPageSource).toContain('delivery__files-grid')
    expect(deliveryPageSource).toContain('min-height:44px')
    expect(deliveryPageSource).toContain('env(safe-area-inset-bottom)')
    expect(deliveryPageSource).not.toContain('立即支付')
  })
})

function delivery(): InspectionDelivery {
  return {
    id: '80000000-0000-4000-8000-000000000001', serviceOrderId: ORDER_ID,
    status: 'PUBLISHED', towerCount: 12, inspectedLengthMeters: 18600, issueCount: 3,
    issueSummary: [{ severityCode: 'HIGH', severityName: '高', count: 1 }],
    files: [{ fileId: FILE_ID, type: 'INSPECTION_REPORT', displayName: '巡检报告.pdf', pointCount: null, contentType: 'application/pdf', sizeBytes: 1024, status: 'READY' }],
    publishedAt: '2026-07-28T08:00:00Z', acceptedAt: null, version: 2,
  }
}

function acceptance(): InspectionAcceptance {
  return {
    id: '90000000-0000-4000-8000-000000000001',
    serviceOrderId: ORDER_ID,
    deliveryId: '80000000-0000-4000-8000-000000000001',
    acceptedBy: '10000000-0000-4000-8000-000000000001',
    acceptedAt: '2026-07-28T09:00:00Z',
    orderStatus: 'ACCEPTED',
  }
}
