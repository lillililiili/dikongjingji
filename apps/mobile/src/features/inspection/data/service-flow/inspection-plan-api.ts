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
  version: number
}

export interface InspectionPlanApi {
  getPlan(planId: string): Promise<InspectionPlan>
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
