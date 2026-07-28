import { PLAN_AGREEMENT_VERSION } from '../../domain/service-flow/plan'
import type {
  AccessTokenPort,
  BookingHttpClient,
} from './inspection-booking-api'

export interface InspectionPlan {
  id: string
  planNo: string
  reservationId: string
  providerId: string
  status: 'DRAFT' | 'PUBLISHED' | 'CONFIRMED' | 'EXPIRED' | 'WITHDRAWN'
  planVersion: number
  validUntil: string
  serviceDate: string
  summary: string | null
  planItems: Array<{
    code: string
    name: string
    quantity: number
    unit: string
    unitPriceMinor: number
    lineAmountMinor: number
  }>
  deliverables: Array<{ code: string; label: string }>
  totalAmountMinor: number
  currency: 'CNY'
  publishedAt: string
  confirmedAt: string | null
  serviceOrderId: string | null
  version: number
}

export interface InspectionServiceOrder {
  id: string
  serviceOrderNo: string
  reservationId: string
  planId: string
  customerId: string
  providerId: string
  status: 'ARRANGING' | 'IN_PROGRESS' | 'DELIVERED' | 'ACCEPTED'
  serviceSnapshot: {
    serviceId: string
    serviceTitle: string
    serviceCoverUrl: string | null
    cityCode: string
    location: {
      address: string
      longitude: number | null
      latitude: number | null
    }
    lineLengthMeters: number | null
    serviceDate: string
    inspectionItems: Array<{ code: string; label: string }>
  }
  plannedStartAt: string | null
  assignments: Array<{
    type: 'TEAM_MEMBER' | 'DEVICE'
    resourceId: string
    displayName: string
    roleLabel: string | null
  }>
  progress: Array<{
    id: string
    eventCode: 'ARRANGEMENT_READY' | 'SERVICE_STARTED' | 'SERVICE_PROGRESS' | 'SERVICE_COMPLETED'
    occurredAt: string
    message: string
  }>
  createdAt: string
  deliveredAt: string | null
  acceptedAt: string | null
  version: number
}

export type InspectionDeliveryFileType =
  | 'IMAGE_ARCHIVE'
  | 'DEFECT_LIST'
  | 'POINT_KML'
  | 'INSPECTION_REPORT'

type InspectionDeliveryBase = {
  id: string
  serviceOrderId: string
  towerCount: number
  inspectedLengthMeters: number
  issueCount: number
  issueSummary: Array<{ severityCode: string; severityName: string; count: number }>
  files: Array<{
    fileId: string
    type: InspectionDeliveryFileType
    displayName: string
    pointCount: number | null
    contentType: string
    sizeBytes: number
    status: 'PENDING_UPLOAD' | 'UPLOADED' | 'SCANNING' | 'READY' | 'REJECTED' | 'DELETED'
  }>
  publishedAt: string
  version: number
}

export type InspectionDelivery = InspectionDeliveryBase & (
  | { status: 'PUBLISHED'; acceptedAt: null }
  | { status: 'ACCEPTED'; acceptedAt: string }
)

export interface InspectionDownloadIntent {
  method: 'GET'
  downloadUrl: string
  suggestedFileName: string
  expiresAt: string
}

export interface InspectionAcceptance {
  id: string
  serviceOrderId: string
  deliveryId: string
  acceptedBy: string
  acceptedAt: string
  orderStatus: 'ACCEPTED'
}

export interface InspectionPlanApi {
  getPlan(planId: string): Promise<InspectionPlan>
  getServiceOrder(serviceOrderId: string): Promise<InspectionServiceOrder>
  getDelivery(serviceOrderId: string): Promise<InspectionDelivery>
  createDownloadIntent(serviceOrderId: string, fileId: string, idempotencyKey: string): Promise<InspectionDownloadIntent>
  createDownloadAllIntent(serviceOrderId: string, idempotencyKey: string): Promise<InspectionDownloadIntent>
  acceptDelivery(serviceOrderId: string, version: number, idempotencyKey: string): Promise<InspectionAcceptance>
  confirmPlan(
    planId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<InspectionServiceOrder>
}

export function createInspectionPlanApi(
  http: BookingHttpClient,
  accessToken: AccessTokenPort = { getAccessToken: () => null },
): InspectionPlanApi {
  const authenticatedHeaders = (
    extra: Record<string, string> = {},
  ): Record<string, string> => {
    const token = accessToken.getAccessToken()
    return {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...extra,
    }
  }

  return {
    getPlan(planId) {
      return http.request<InspectionPlan>({
        url: `/api/v1/inspection/plans/${encodeURIComponent(planId)}`,
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    getServiceOrder(serviceOrderId) {
      return http.request<InspectionServiceOrder>({
        url: `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}`,
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    getDelivery(serviceOrderId) {
      return http.request<InspectionDelivery>({
        url: `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}/delivery`, method: 'GET', headers: authenticatedHeaders(),
      })
    },
    createDownloadIntent(serviceOrderId, fileId, idempotencyKey) {
      return http.request<InspectionDownloadIntent>({
        url: `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}/delivery-files/${encodeURIComponent(fileId)}/download-intent`, method: 'POST', headers: authenticatedHeaders({ 'Idempotency-Key': idempotencyKey }),
      })
    },
    createDownloadAllIntent(serviceOrderId, idempotencyKey) {
      return http.request<InspectionDownloadIntent>({
        url: `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}/delivery-files/download-all-intent`, method: 'POST', headers: authenticatedHeaders({ 'Idempotency-Key': idempotencyKey }),
      })
    },
    acceptDelivery(serviceOrderId, version, idempotencyKey) {
      return http.request<InspectionAcceptance>({
        url: `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}/acceptance`, method: 'POST', data: { confirmed: true, note: null },
        headers: authenticatedHeaders({ 'Idempotency-Key': idempotencyKey, 'If-Match': `"${version}"` }),
      })
    },
    confirmPlan(planId, version, idempotencyKey) {
      return http.request<InspectionServiceOrder>({
        url: `/api/v1/inspection/plans/${encodeURIComponent(planId)}/confirm`,
        method: 'POST',
        data: {
          agreementVersion: PLAN_AGREEMENT_VERSION,
          agreementAccepted: true,
        },
        headers: authenticatedHeaders({
          'Idempotency-Key': idempotencyKey,
          'If-Match': `"${version}"`,
        }),
      })
    },
  }
}
