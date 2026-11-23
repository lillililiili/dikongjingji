export type InspectionOrderStage =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'
  | 'CANCELLED'

export type InspectionOrderFilter =
  | 'ALL'
  | Exclude<InspectionOrderStage, 'CANCELLED'>

export interface InspectionOrderSummary {
  reservationId: string
  reservationNo: string
  serviceId: string
  serviceTitle: string
  serviceCoverUrl?: string
  cityCode: string
  locationSummary: string
  preferredServiceDate: string
  submittedAt: string
  stage: InspectionOrderStage
  reservationStatus: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  currentPlanId?: string
  serviceOrderId?: string
  serviceOrderStatus?: 'AWAITING_PAYMENT' | 'ARRANGING' | 'IN_PROGRESS' | 'DELIVERED' | 'ACCEPTED' | 'CANCELLED'
  quoteAmountMinor?: number
  currency: 'CNY'
  visitCount?: number
  acceptedVisitCount?: number
  nextVisitDate?: string
  pendingAcceptanceVisitId?: string
  pendingAcceptanceVisitNo?: number
  // (service flow 2.3.0, S4-1) The first node due now; absent when nothing is to be paid.
  paymentDueNodeNo?: number
  paymentDueNodeType?: 'DOWN_PAYMENT' | 'PER_VISIT'
  paymentDueVisitNo?: number
  paymentDueAmountMinor?: number
}

export interface InspectionOrderPage {
  items: InspectionOrderSummary[]
  page: number
  size: number
  total: number
  hasMore: boolean
  stageCounts: Record<Exclude<InspectionOrderStage, 'CANCELLED'>, number>
}
