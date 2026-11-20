import type { Page, Route } from '@playwright/test'

// Stubbed answers for the service-flow pages (plan, order, visits, delivery). Shared by the
// behaviour spec and the screenshot spec so both look at the same data.
export const ORDER_ID = '60000000-0000-4000-8000-000000000001'
export const PLAN_ID = '50000000-0000-4000-8000-000000000002'
export const RESERVATION_ID = '40000000-0000-4000-8000-000000000001'
export const FIRST_VISIT = '61000000-0000-4000-8000-000000000001'
export const SECOND_VISIT = '61000000-0000-4000-8000-000000000002'
export const NOW = new Date('2026-11-03T09:00:00+08:00')
export const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
}

export type Answer = { status?: number; body: unknown }


export function plan(overrides: Record<string, unknown> = {}) {
  return {
    id: PLAN_ID,
    planNo: 'FA2',
    reservationId: RESERVATION_ID,
    providerId: 'p1',
    provider: { providerId: 'p1', name: '鹰眼智能巡检', certificationLabel: '企业认证', qualificationReviewed: true },
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    status: 'PUBLISHED',
    planVersion: 1,
    validUntil: '2026-11-05T18:00:00+08:00',
    serviceDate: '2026-11-06',
    visits: [{ visitNo: 1, plannedDate: '2026-11-06' }],
    summary: null,
    scopeSummary: '约 42 万㎡',
    serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
    deliveryTerms: { reportDueHours: 48, mediaRetentionDays: 90 },
    changeNote: null,
    planItems: [{
      code: 'DRONE',
      name: '无人机巡检',
      quantity: 6,
      unit: '次',
      unitPriceMinor: 180_000,
      lineAmountMinor: 1_080_000,
    }],
    deliverables: [{ code: 'REPORT', label: '巡检报告' }],
    totalAmountMinor: 1_080_000,
    currency: 'CNY',
    paymentNodes: [],
    publishedAt: '2026-11-01T08:30:00+08:00',
    confirmedAt: null,
    confirmedBy: null,
    serviceOrderId: null,
    version: 2,
    ...overrides,
  }
}

export function reservation(overrides: Record<string, unknown> = {}) {
  return {
    id: RESERVATION_ID,
    reservationNo: 'RS1',
    customerId: 'c1',
    serviceId: 's1',
    serviceTitle: '物流园区季度安防巡检',
    serviceCoverUrl: null,
    cityCode: '370500',
    status: 'PLAN_PUBLISHED',
    location: { address: '东营港物流园', longitude: null, latitude: null },
    lineLengthMeters: null,
    preferredServiceDate: '2026-11-06',
    preferredTimeSlotCode: 'MORNING',
    inspectionItems: [],
    note: null,
    contact: { name: '王女士', mobile: '13800000000' },
    attachments: [],
    submittedAt: '2026-10-28T09:00:00+08:00',
    currentPlanId: PLAN_ID,
    version: 3,
    ...overrides,
  }
}

export function visit(visitNo: number, id: string, status: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    serviceOrderId: ORDER_ID,
    visitNo,
    plannedDate: `2026-11-0${visitNo + 1}`,
    plannedStartAt: null,
    status,
    progress: [],
    startedAt: null,
    deliveredAt: null,
    acceptedAt: null,
    version: 4,
    ...overrides,
  }
}

export function order(visits: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    id: ORDER_ID,
    serviceOrderNo: 'FW1',
    reservationId: RESERVATION_ID,
    planId: PLAN_ID,
    customerId: 'c1',
    providerId: 'p1',
    status: 'IN_PROGRESS',
    paymentMode: 'NONE',
    paymentNodes: [],
    provider: { providerId: 'p1', name: '鹰眼智能巡检', certificationLabel: '企业认证', qualificationReviewed: true },
    customerParty: { organizationName: '东营港务物流', operatorName: '王女士' },
    serviceSnapshot: {
      serviceId: 's1',
      serviceTitle: '物流园区季度安防巡检',
      serviceCoverUrl: null,
      cityCode: '370500',
      location: { address: '东营港物流园', longitude: null, latitude: null },
      lineLengthMeters: null,
      serviceDate: '2026-11-02',
      inspectionItems: [],
    },
    assignments: [],
    progress: [],
    visits,
    createdAt: '2026-11-01T09:30:00+08:00',
    deliveredAt: null,
    acceptedAt: null,
    version: 2,
    ...overrides,
  }
}

export function delivery(visitId: string, overrides: Record<string, unknown> = {}) {
  return {
    id: `delivery-${visitId}`,
    serviceOrderId: ORDER_ID,
    visitId,
    status: 'PUBLISHED',
    towerCount: null,
    inspectedLengthMeters: null,
    metrics: [{ code: 'COVERED_AREA', label: '巡检覆盖', value: 42, unit: '万㎡' }],
    issueCount: 0,
    issueGroupBy: 'CATEGORY',
    issueSummary: [],
    files: [{
      fileId: 'f1',
      type: 'INSPECTION_REPORT',
      displayName: '巡检报告',
      pointCount: null,
      itemCount: null,
      contentType: 'application/pdf',
      sizeBytes: 1024,
      status: 'READY',
    }],
    publishedAt: '2026-11-03T08:00:00+08:00',
    acceptedAt: null,
    acceptedByName: null,
    version: 1,
    ...overrides,
  }
}

export async function answerApi(
  page: Page,
  answer: (path: string, method: string) => Answer | null,
): Promise<void> {
  await page.clock.setFixedTime(NOW)
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'e2e-token'))
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    const result = answer(new URL(request.url()).pathname, request.method())
    await route.fulfill({
      status: result?.status ?? (result ? 200 : 404),
      headers: CORS,
      json: result?.body ?? { code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 't' },
    })
  })
}

export function planAnswers(currentPlan: unknown, currentReservation: unknown = reservation()) {
  return (path: string): Answer | null => {
    if (path.endsWith('/plans')) return { body: [] }
    if (path.includes('/plans/')) return { body: currentPlan }
    if (path.includes('/reservations/')) return { body: currentReservation }
    return null
  }
}
