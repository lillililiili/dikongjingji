// Mirrors contracts/openapi-service-order.yaml 1.0.0 (S4-3, docs/redesign/03-App实现任务计划.md §28).
// A general service order: an aerial, survey, maintenance or tech-support demand after a quote was
// chosen. One visit, paid at once after the plan is confirmed; the provider may deliver more than
// once (第 N 次交付) when the customer sends the work back. The server is the source of truth: the
// status, what this account may do (actions) and every amount come from it.

export const SERVICE_ORDER_STATUSES = [
  'AWAITING_PLAN',
  'PLAN_PENDING',
  'AWAITING_PAYMENT',
  'AWAITING_START',
  'IN_SERVICE',
  'DELIVERED',
  'REWORK',
  'COMPLETED',
  'CANCELLED',
  'TERMINATED',
] as const
export type ServiceOrderStatus = (typeof SERVICE_ORDER_STATUSES)[number]

export const SERVICE_ORDER_TYPES = ['AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT'] as const
export type ServiceOrderType = (typeof SERVICE_ORDER_TYPES)[number]

export type ServiceOrderCustomerKind = 'ORGANIZATION' | 'PERSONAL'
export type ServiceOrderParty = 'CUSTOMER' | 'PROVIDER'
export type ServiceOrderDeliveryStatus = 'SUBMITTED' | 'ACCEPTED' | 'RETURNED'
export type ServiceOrderRefundKind = 'CANCEL' | 'TERMINATE'
export type ServiceOrderRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export const SERVICE_ORDER_PAGE_SIZE = 20

export interface ServiceOrderCustomer {
  kind: ServiceOrderCustomerKind
  organizationName: string | null
  contactName: string
}

export interface ServiceOrderProvider {
  providerId: string
  name: string
  qualificationReviewed: boolean
}

// One line of the quote (as openapi-demand.yaml QuoteLine); a quote without lines has one 服务费用.
export interface ServiceOrderLine {
  name: string
  quantity: number | null
  unit: string | null
  unitAmountMinor: number | null
  amountMinor: number
}

// The chosen quote as it was chosen.
export interface ServiceOrderQuote {
  quoteId: string
  quoteVersion: number
  method: string
  schedule: string
  durationDays: number
  deliverables: string
  team: string | null
  nightWork: string | null
  reportHours: number | null
  archiveDays: number | null
  firstVisitDate: string | null
}

export interface ServiceOrderPlan {
  // 方案 vN; sent back as planVersion when confirming.
  version: number
  amountMinor: number
  schedule: string
  terms: string[]
  note: string | null
  issuedAt: string
  confirmedAt: string | null
  confirmedBy: string | null
}

export interface ServiceOrderRefund {
  refundId: string
  kind: ServiceOrderRefundKind
  amountMinor: number
  reason: string
  // The trade module's refund state (only from the channel's receipt or a query).
  status: ServiceOrderRefundStatus
  requestedAt: string
}

export interface ServiceOrderPayment {
  amountMinor: number
  // The provider can take the payment online.
  collectable: boolean
  paidAt: string | null
  pendingTradeOrderId: string | null
  pendingPayBefore: string | null
  paidTradeOrderId: string | null
  refundedMinor: number
  refunds: ServiceOrderRefund[]
}

export interface ServiceOrderFile {
  fileId: string
  name: string
  contentType: string
  sizeBytes: number
  uploadedAt: string
}

export interface ServiceOrderDelivery {
  round: number
  status: ServiceOrderDeliveryStatus
  note: string
  files: ServiceOrderFile[]
  deliveredAt: string
  // Why it was sent back.
  reviewNote: string | null
  reviewedAt: string | null
  reviewedBy: string | null
}

// What this account may do now (the customer routes only ever set the customer's ones).
export interface ServiceOrderActions {
  confirmPlan: boolean
  pay: boolean
  cancel: boolean
  accept: boolean
  rework: boolean
}

export interface ServiceOrder {
  orderId: string
  orderNo: string
  status: ServiceOrderStatus
  type: ServiceOrderType
  title: string
  demandId: string
  customer: ServiceOrderCustomer
  provider: ServiceOrderProvider
  cityCode: string
  cityName: string
  address: string
  scope: string
  dueDate: string | null
  amountMinor: number
  lines: ServiceOrderLine[]
  quote: ServiceOrderQuote
  // The latest plan; null until the provider issues one.
  plan: ServiceOrderPlan | null
  payment: ServiceOrderPayment
  // In round order.
  deliveries: ServiceOrderDelivery[]
  startedAt: string | null
  completedAt: string | null
  closedAt: string | null
  closedBy: ServiceOrderParty | null
  closeReason: string | null
  actions: ServiceOrderActions
  createdAt: string
  updatedAt: string
  // The ETag in quotes; sent back as If-Match.
  version: number
}

export interface ServiceOrderSummary {
  orderId: string
  orderNo: string
  status: ServiceOrderStatus
  type: ServiceOrderType
  title: string
  scope: string
  cityName: string
  customer: ServiceOrderCustomer
  provider: ServiceOrderProvider
  amountMinor: number
  planVersion: number | null
  paidAt: string | null
  latestDeliveryAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ServiceOrderPage {
  total: number
  hasMore: boolean
  items: ServiceOrderSummary[]
}

export interface ServiceOrderPaymentStart {
  orderId: string
  tradeOrderId: string
  amountMinor: number
  payBefore: string
}

export interface ServiceOrderDownloadIntent {
  // Relative: /api/v1/service-order-files/{token}, opened after the API address.
  url: string
  expiresAt: string
}
