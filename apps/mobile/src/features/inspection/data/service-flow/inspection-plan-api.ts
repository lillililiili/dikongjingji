import { PLAN_AGREEMENT_VERSION } from '../../domain/service-flow/plan'
import type {
  AccessTokenPort,
  BookingHttpClient,
} from './inspection-booking-api'

export interface InspectionPlanItem {
  code: string
  name: string
  quantity: number
  unit: string
  unitPriceMinor: number
  lineAmountMinor: number
}

export interface InspectionProviderSummary {
  providerId: string
  name: string
  certificationLabel?: string | null
  // (service flow 2.2.0, R6-4) The platform reviewed the provider's qualification.
  qualificationReviewed?: boolean | null
}

export interface InspectionCustomerParty {
  organizationName?: string | null
  operatorName: string
}

export type InspectionPaymentNodeType = 'DOWN_PAYMENT' | 'PER_VISIT'

export interface InspectionPlanPaymentNode {
  nodeNo: number
  nodeType: InspectionPaymentNodeType
  visitNo?: number | null
  amountMinor: number
}

// (service flow 2.3.0, S4-1) status is the trade module's refund status; null when it cannot be read.
export interface InspectionNodeRefund {
  refundId: string
  amountMinor: number
  status?: 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL' | null
  requestedAt: string
}

export interface InspectionOrderPaymentNode extends InspectionPlanPaymentNode {
  payable: boolean
  paidAt?: string | null
  // (2.3.0) The trade order open or paid for this node: the cashier or the pay result reads it.
  tradeOrderId?: string | null
  refund?: InspectionNodeRefund | null
}

// (2.3.0) One payment attempt: one trade order.
export interface InspectionPaymentRecord {
  attemptId: string
  nodeNo: number
  nodeType: InspectionPaymentNodeType
  visitNo?: number | null
  tradeOrderId: string
  amountMinor: number
  status: 'OPEN' | 'PAID' | 'CLOSED'
  payBefore: string
  createdAt: string
  paidAt?: string | null
  closedAt?: string | null
}

export interface InspectionPaymentLaunch {
  nodeNo: number
  tradeOrderId: string
  payBefore?: string | null
  paid: boolean
}

export interface InspectionVisitReschedule {
  id: string
  visitId: string
  fromDate: string
  requestedDate: string
  reason: string
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED'
  decisionNote?: string | null
  requestedAt: string
  decidedAt?: string | null
}

export interface InspectionVisitRework {
  id: string
  visitId: string
  deliveryRevision: number
  reason: string
  requestedAt: string
  resolvedAt?: string | null
}

export type InspectionPlanStatus =
  | 'DRAFT'
  | 'PUBLISHED'
  | 'CONFIRMED'
  | 'EXPIRED'
  | 'WITHDRAWN'

export interface InspectionPlan {
  id: string
  planNo: string
  reservationId: string
  providerId: string
  provider?: InspectionProviderSummary | null
  customerParty?: InspectionCustomerParty | null
  status: InspectionPlanStatus
  planVersion: number
  validUntil: string
  serviceDate: string
  visits: Array<{ visitNo: number; plannedDate: string }>
  summary: string | null
  scopeSummary?: string | null
  serviceMethods?: Array<{ code: string; label: string }>
  deliveryTerms?: {
    reportDueHours?: number | null
    mediaRetentionDays?: number | null
  } | null
  changeNote?: string | null
  planItems: InspectionPlanItem[]
  deliverables: Array<{
    code: string
    label: string
    fileType?: InspectionDeliveryFileType | null
  }>
  totalAmountMinor: number
  currency: 'CNY'
  paymentNodes?: InspectionPlanPaymentNode[]
  publishedAt: string
  confirmedAt: string | null
  confirmedBy?: { subjectId: string; displayName: string } | null
  serviceOrderId: string | null
  version: number
}

export interface InspectionPlanVersionSummary {
  id: string
  planNo: string
  planVersion: number
  status: InspectionPlanStatus
  totalAmountMinor: number
  planItems: InspectionPlanItem[]
  changeNote?: string | null
  publishedAt: string
}

export interface InspectionProgressEvent {
  id: string
  eventCode: string
  occurredAt: string
  message: string
}

export type InspectionServiceVisitStatus =
  | 'PENDING_SCHEDULE'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  // (2.3.0, B2) The customer returned the delivery; the provider has not delivered again yet.
  | 'REWORK'
  | 'DELIVERED'
  | 'ACCEPTED'

export interface InspectionServiceVisit {
  id: string
  serviceOrderId: string
  visitNo: number
  plannedDate: string
  plannedStartAt?: string | null
  status: InspectionServiceVisitStatus
  progress: InspectionProgressEvent[]
  startedAt?: string | null
  deliveredAt?: string | null
  acceptedAt?: string | null
  version: number
  // (2.3.0, C3) The date before the first approved move; null when it never moved.
  originalPlannedDate?: string | null
  reschedulable?: boolean
  reschedule?: InspectionVisitReschedule | null
  rework?: InspectionVisitRework | null
}

// AWAITING_PAYMENT (2.3.0): confirmed, the down payment has not arrived. CANCELLED (2.3.0): cancelled
// before the first visit started.
export type InspectionServiceOrderStatus =
  | 'AWAITING_PAYMENT'
  | 'ARRANGING'
  | 'IN_PROGRESS'
  | 'DELIVERED'
  | 'ACCEPTED'
  | 'CANCELLED'

export interface InspectionServiceOrder {
  id: string
  serviceOrderNo: string
  reservationId: string
  planId: string
  customerId: string
  providerId: string
  status: InspectionServiceOrderStatus
  paymentMode: 'NONE' | 'NODES'
  paymentNodes?: InspectionOrderPaymentNode[]
  provider?: InspectionProviderSummary | null
  customerParty?: InspectionCustomerParty | null
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
  assignments: Array<{
    type: 'TEAM_MEMBER' | 'DEVICE'
    resourceId: string
    displayName: string
    roleLabel: string | null
    contactMobileMasked?: string | null
  }>
  progress: InspectionProgressEvent[]
  visits: InspectionServiceVisit[]
  createdAt: string
  deliveredAt: string | null
  acceptedAt: string | null
  version: number
  // (2.3.0) The customer may cancel online now (before the first visit starts).
  cancellable?: boolean
  cancellation?: { cancelledAt: string; reason?: string | null } | null
  paymentRecords?: InspectionPaymentRecord[]
}

export type InspectionDeliveryFileType =
  | 'IMAGE_ARCHIVE'
  | 'DEFECT_LIST'
  | 'POINT_KML'
  | 'INSPECTION_REPORT'

type InspectionDeliveryBase = {
  id: string
  serviceOrderId: string
  visitId: string
  towerCount?: number | null
  inspectedLengthMeters?: number | null
  metrics: Array<{ code: string; label: string; value: number; unit: string }>
  issueCount: number
  issueGroupBy: 'SEVERITY' | 'CATEGORY'
  issueSummary: Array<{ groupCode: string; groupName: string; count: number }>
  files: Array<{
    fileId: string
    type: InspectionDeliveryFileType
    displayName: string
    pointCount: number | null
    itemCount?: number | null
    contentType: string
    sizeBytes: number
    status: 'PENDING_UPLOAD' | 'UPLOADED' | 'SCANNING' | 'READY' | 'REJECTED' | 'DELETED'
  }>
  publishedAt: string
  version: number
}

export type InspectionDelivery = InspectionDeliveryBase & (
  | { status: 'PUBLISHED'; acceptedAt: null; acceptedByName?: null }
  // (2.3.0, B2) Returned for rework; the provider delivers again on the same delivery.
  | { status: 'RETURNED'; acceptedAt: null; acceptedByName?: null }
  | { status: 'ACCEPTED'; acceptedAt: string; acceptedByName?: string | null }
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
  visitId: string
  deliveryId: string
  acceptedBy: string
  acceptedByName?: string | null
  acceptedAt: string
  visitStatus: 'ACCEPTED'
  orderStatus: InspectionServiceOrderStatus
}

export interface InspectionPlanApi {
  getPlan(planId: string): Promise<InspectionPlan>
  listPlanVersions(reservationId: string): Promise<InspectionPlanVersionSummary[]>
  getServiceOrder(serviceOrderId: string): Promise<InspectionServiceOrder>
  getDelivery(serviceOrderId: string, visitId: string): Promise<InspectionDelivery>
  createDownloadIntent(
    serviceOrderId: string,
    visitId: string,
    fileId: string,
    idempotencyKey: string,
  ): Promise<InspectionDownloadIntent>
  createDownloadAllIntent(
    serviceOrderId: string,
    visitId: string,
    idempotencyKey: string,
  ): Promise<InspectionDownloadIntent>
  acceptDelivery(
    serviceOrderId: string,
    visitId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<InspectionAcceptance>
  confirmPlan(
    planId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<InspectionServiceOrder>
  // (2.3.0, S4-1) The trade order to pay for this node; the same one again while it is open.
  payNode(serviceOrderId: string, nodeNo: number): Promise<InspectionPaymentLaunch>
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

  const serviceOrderUrl = (serviceOrderId: string): string =>
    `/api/v1/inspection/service-orders/${encodeURIComponent(serviceOrderId)}`
  const visitUrl = (serviceOrderId: string, visitId: string): string =>
    `${serviceOrderUrl(serviceOrderId)}/visits/${encodeURIComponent(visitId)}`

  return {
    getPlan(planId) {
      return http.request<InspectionPlan>({
        url: `/api/v1/inspection/plans/${encodeURIComponent(planId)}`,
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    listPlanVersions(reservationId) {
      return http.request<InspectionPlanVersionSummary[]>({
        url: `/api/v1/inspection/reservations/${encodeURIComponent(reservationId)}/plans`,
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    getServiceOrder(serviceOrderId) {
      return http.request<InspectionServiceOrder>({
        url: serviceOrderUrl(serviceOrderId),
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    getDelivery(serviceOrderId, visitId) {
      return http.request<InspectionDelivery>({
        url: `${visitUrl(serviceOrderId, visitId)}/delivery`,
        method: 'GET',
        headers: authenticatedHeaders(),
      })
    },
    createDownloadIntent(serviceOrderId, visitId, fileId, idempotencyKey) {
      return http.request<InspectionDownloadIntent>({
        url: `${visitUrl(serviceOrderId, visitId)}/delivery-files/${encodeURIComponent(fileId)}/download-intent`,
        method: 'POST',
        headers: authenticatedHeaders({ 'Idempotency-Key': idempotencyKey }),
      })
    },
    createDownloadAllIntent(serviceOrderId, visitId, idempotencyKey) {
      return http.request<InspectionDownloadIntent>({
        url: `${visitUrl(serviceOrderId, visitId)}/delivery-files/download-all-intent`,
        method: 'POST',
        headers: authenticatedHeaders({ 'Idempotency-Key': idempotencyKey }),
      })
    },
    acceptDelivery(serviceOrderId, visitId, version, idempotencyKey) {
      return http.request<InspectionAcceptance>({
        url: `${visitUrl(serviceOrderId, visitId)}/acceptance`,
        method: 'POST',
        data: { confirmed: true, note: null },
        headers: authenticatedHeaders({
          'Idempotency-Key': idempotencyKey,
          'If-Match': `"${version}"`,
        }),
      })
    },
    payNode(serviceOrderId, nodeNo) {
      return http.request<InspectionPaymentLaunch>({
        url: `${serviceOrderUrl(serviceOrderId)}/payment-nodes/${encodeURIComponent(String(nodeNo))}/payment`,
        method: 'POST',
        headers: authenticatedHeaders(),
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
