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
  serviceOrderStatus?: 'ARRANGING' | 'IN_PROGRESS' | 'DELIVERED' | 'ACCEPTED'
  quoteAmountMinor?: number
  currency: 'CNY'
  visitCount?: number
  acceptedVisitCount?: number
  nextVisitDate?: string
  pendingAcceptanceVisitId?: string
  pendingAcceptanceVisitNo?: number
}

export interface InspectionOrderPage {
  items: InspectionOrderSummary[]
  page: number
  size: number
  total: number
  hasMore: boolean
  stageCounts: Record<Exclude<InspectionOrderStage, 'CANCELLED'>, number>
}
