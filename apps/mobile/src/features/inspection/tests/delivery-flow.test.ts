import { describe, expect, it } from 'vitest'

import pagesSource from '../../../pages.json?raw'
import deliveryPageSource from '../presentation/delivery/pages/InspectionDeliveryPage.vue?raw'
import {
  createInspectionPlanApi,
  type InspectionAcceptance,
  type InspectionDelivery,
} from '../data/service-flow/inspection-plan-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import {
  buildInspectionDeliveryUrl,
  normalizeInspectionReturnUrl,
} from '../navigation/inspection-routes'
import { resolveInspectionDeliveryBackAction } from '../application/service-flow/inspection-order-navigation'

const ORDER_ID = '60000000-0000-4000-8000-000000000001'
const VISIT_ID = '61000000-0000-4000-8000-000000000001'
const FILE_ID = '70000000-0000-4000-8000-000000000001'
const VISIT_URL = `/api/v1/inspection/service-orders/${ORDER_ID}/visits/${VISIT_ID}`

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

    await api.getDelivery(ORDER_ID, VISIT_ID)
    await api.createDownloadIntent(ORDER_ID, VISIT_ID, FILE_ID, 'key-file')
    await api.createDownloadAllIntent(ORDER_ID, VISIT_ID, 'key-all')
    const accepted = await api.acceptDelivery(ORDER_ID, VISIT_ID, 8, 'key-accept')

    expect(calls).toEqual([
      { url: `${VISIT_URL}/delivery`, method: 'GET', headers: { Authorization: 'Bearer customer-token' } },
      { url: `${VISIT_URL}/delivery-files/${FILE_ID}/download-intent`, method: 'POST', headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-file' } },
      { url: `${VISIT_URL}/delivery-files/download-all-intent`, method: 'POST', headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-all' } },
      { url: `${VISIT_URL}/acceptance`, method: 'POST', data: { confirmed: true, note: null }, headers: { Authorization: 'Bearer customer-token', 'Idempotency-Key': 'key-accept', 'If-Match': '"8"' } },
    ])
    expect(accepted).toEqual(acceptance())
    expect(buildInspectionDeliveryUrl(ORDER_ID, VISIT_ID))
      .toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}&visitId=${VISIT_ID}`)
  })

  it('keeps the visit when login sends the customer back to a delivery', () => {
    const url = buildInspectionDeliveryUrl(ORDER_ID, VISIT_ID)

    expect(normalizeInspectionReturnUrl(url)).toBe(url)
    expect(normalizeInspectionReturnUrl(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`))
      .toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
  })

  it('registers INS-M-008 as a custom-navigation page', () => {
    const pages = JSON.parse(pagesSource) as { pages: Array<{ path: string; style?: Record<string, string> }> }
    expect(pages.pages.find((page) => page.path === 'pages/service/inspection/delivery'))
      .toMatchObject({ style: { navigationStyle: 'custom' } })
  })

})

describe('F07 delivery page contract', () => {
  it('keeps the page back action below the Android status bar', () => {
    expect(deliveryPageSource).toContain('padding: calc(8px + var(--status-bar-height)) 8px 8px')
    expect(deliveryPageSource).not.toContain('env(safe-area-inset-top)')
  })

  it('keeps real workflow history and otherwise returns delivery to the shared order center', () => {
    expect(resolveInspectionDeliveryBackAction('pages/mine/inspection-orders')).toBe('navigateBack')
    expect(resolveInspectionDeliveryBackAction('pages/service/inspection/fulfillment')).toBe('navigateBack')
    expect(resolveInspectionDeliveryBackAction(undefined)).toBe('reLaunchOrderCenter')
  })

  it('loads the delivery of one visit, creates controlled download intents and guards duplicate acceptance', () => {
    expect(deliveryPageSource).toContain('inspectionPlanApi.getDelivery(serviceOrderId.value, currentVisit.id)')
    expect(deliveryPageSource).toContain('inspectionPlanApi.createDownloadIntent(')
    expect(deliveryPageSource).toContain('inspectionPlanApi.createDownloadAllIntent(')
    expect(deliveryPageSource).toContain('inspectionPlanApi.acceptDelivery')
    expect(deliveryPageSource).toContain('visit.value.version')
    expect(deliveryPageSource).toContain('await reload()')
    expect(deliveryPageSource).toContain('交付与验收')
    expect(deliveryPageSource).toContain('DeliveryResultCard')
    expect(deliveryPageSource).toContain('DeliveryFilesCard')
    expect(deliveryPageSource).toContain('AcceptanceConfirmSheet')
    expect(deliveryPageSource).not.toContain('待评价')
    expect(deliveryPageSource).toContain('min-height: 44px')
    expect(deliveryPageSource).toContain('env(safe-area-inset-bottom)')
    expect(deliveryPageSource).not.toContain('立即支付')
  })

  it('asks for confirmation before accepting, and a back press closes that first', () => {
    expect(deliveryPageSource).toContain('@click="isSheetOpen = true"')
    expect(deliveryPageSource).toContain('@confirm="acceptDelivery"')
    expect(deliveryPageSource).toContain('if (isSheetOpen.value) {')
  })
})

function delivery(): InspectionDelivery {
  return {
    id: '80000000-0000-4000-8000-000000000001', serviceOrderId: ORDER_ID, visitId: VISIT_ID,
    status: 'PUBLISHED', towerCount: 12, inspectedLengthMeters: 18600, metrics: [], issueCount: 3,
    issueGroupBy: 'SEVERITY',
    issueSummary: [{ groupCode: 'HIGH', groupName: '高', count: 1 }],
    files: [{ fileId: FILE_ID, type: 'INSPECTION_REPORT', displayName: '巡检报告.pdf', pointCount: null, contentType: 'application/pdf', sizeBytes: 1024, status: 'READY' }],
    publishedAt: '2026-07-28T08:00:00Z', acceptedAt: null, version: 2,
  }
}

function acceptance(): InspectionAcceptance {
  return {
    id: '90000000-0000-4000-8000-000000000001',
    serviceOrderId: ORDER_ID,
    visitId: VISIT_ID,
    deliveryId: '80000000-0000-4000-8000-000000000001',
    acceptedBy: '10000000-0000-4000-8000-000000000001',
    acceptedAt: '2026-07-28T09:00:00Z',
    visitStatus: 'ACCEPTED',
    orderStatus: 'ACCEPTED',
  }
}
