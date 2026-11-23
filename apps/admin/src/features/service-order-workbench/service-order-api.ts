import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'
import type { QuoteExtras, QuoteLine } from '@/features/demand-quotes/quote-lines'

// 契约 contracts/openapi-service-order.yaml 1.0.0 的服务商接口（/api/v1/provider/service-orders…）：航拍、测绘、设备维护、
// 技术支持四类需求选定报价后生成的服务订单。机构只从令牌里取，别的机构的单一律 404。
// 改状态的写操作都带 If-Match（读订单时的 version，发出去是 "3"），版本不对 412 SERVICE_ORDER_VERSION_CONFLICT；
// 上传、删除未交付的文件不带。错误码的中文在 core/http/api-error.ts。

export type ServiceOrderStatus = 'AWAITING_PLAN' | 'PLAN_PENDING' | 'AWAITING_PAYMENT' | 'AWAITING_START'
  | 'IN_SERVICE' | 'DELIVERED' | 'REWORK' | 'COMPLETED' | 'CANCELLED' | 'TERMINATED'
export type ServiceOrderType = 'AERIAL' | 'SURVEY' | 'MAINTENANCE' | 'TECH_SUPPORT'
export type ServiceOrderParty = 'CUSTOMER' | 'PROVIDER'
export type ServiceOrderDeliveryStatus = 'SUBMITTED' | 'ACCEPTED' | 'RETURNED'
export type ServiceOrderRefundKind = 'CANCEL' | 'TERMINATE'
export type ServiceOrderRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'
export type ServiceOrderEventType = 'CREATED' | 'PLAN_ISSUED' | 'PLAN_REVISED' | 'PLAN_CONFIRMED' | 'PAID' | 'STARTED'
  | 'DELIVERED' | 'ACCEPTED' | 'RETURNED' | 'CANCELLED' | 'TERMINATED'

export interface ServiceOrderCustomer {
  kind: 'ORGANIZATION' | 'PERSONAL'
  /** 企业下单时的组织名（快照）。 */
  organizationName: string | null
  /** 经办人显示名（快照）；服务商看不到手机号和账号。 */
  contactName: string
}

export interface ServiceOrderProvider {
  providerId: string
  name: string
  qualificationReviewed: boolean
}

/** 选定那一版报价的快照。 */
export interface ServiceOrderQuote extends QuoteExtras {
  quoteId: string
  quoteVersion: number
  method: string
  schedule: string
  durationDays: number
  deliverables: string
}

export interface ServiceOrderPlan {
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
  status: ServiceOrderRefundStatus
  requestedAt: string
}

export interface ServiceOrderPayment {
  amountMinor: number
  collectable: boolean
  paidAt: string | null
  pendingTradeOrderId: string | null
  pendingPayBefore: string | null
  paidTradeOrderId: string | null
  /** 已经发起的退款合计（含还在处理的）。 */
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
  /** 退回整改的原因。 */
  reviewNote: string | null
  reviewedAt: string | null
  reviewedBy: string | null
}

export interface ServiceOrderEvent {
  type: ServiceOrderEventType
  occurredAt: string
  planVersion: number | null
  round: number | null
}

/** 当前登录的这一方现在能做的操作（服务商接口里客户的几项总是 false）。 */
export interface ServiceOrderActions {
  confirmPlan: boolean
  pay: boolean
  cancel: boolean
  accept: boolean
  rework: boolean
  issuePlan: boolean
  start: boolean
  deliver: boolean
  terminate: boolean
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
  lines: QuoteLine[]
  quote: ServiceOrderQuote
  plan: ServiceOrderPlan | null
  payment: ServiceOrderPayment
  deliveries: ServiceOrderDelivery[]
  /** 已上传、还没提交交付的文件。 */
  stagedFiles: ServiceOrderFile[]
  events: ServiceOrderEvent[]
  startedAt: string | null
  completedAt: string | null
  closedAt: string | null
  closedBy: ServiceOrderParty | null
  closeReason: string | null
  actions: ServiceOrderActions
  createdAt: string
  updatedAt: string
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

export interface PlanRequest {
  schedule: string
  terms: string[]
  note: string | null
}

export interface DeliveryRequest {
  note: string
  fileIds: string[]
}

export interface TerminateRequest {
  refundAmountMinor: number
  reason: string
}

export interface ServiceOrderFilters {
  status: ServiceOrderStatus | ''
}

export function createServiceOrderApi(http: ApiClient) {
  const base = '/api/v1/provider/service-orders'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  const file = (id: string, fileId: string) => `${one(id)}/files/${encodeURIComponent(fileId)}`
  return {
    list(filters: ServiceOrderFilters, page: number, size: number): Promise<Page<ServiceOrderSummary>> {
      return http.get<Page<ServiceOrderSummary>>(base, { status: filters.status, page, size })
    },
    detail(id: string): Promise<ServiceOrder> {
      return http.get<ServiceOrder>(one(id))
    },
    issuePlan(order: Pick<ServiceOrder, 'orderId' | 'version'>, request: PlanRequest): Promise<ServiceOrder> {
      return http.put<ServiceOrder>(`${one(order.orderId)}/plan`, request, { ifMatch: order.version })
    },
    start(order: Pick<ServiceOrder, 'orderId' | 'version'>): Promise<ServiceOrder> {
      return http.post<ServiceOrder>(`${one(order.orderId)}/start`, undefined, { ifMatch: order.version })
    },
    uploadFile(id: string, upload: File): Promise<ServiceOrderFile> {
      const form = new FormData()
      form.append('file', upload, upload.name)
      return http.request<ServiceOrderFile>('POST', `${one(id)}/files`, { form })
    },
    removeFile(id: string, fileId: string): Promise<void> {
      return http.delete<void>(file(id, fileId))
    },
    downloadFile(id: string, fileId: string) {
      return http.download(`${file(id, fileId)}/content`)
    },
    deliver(order: Pick<ServiceOrder, 'orderId' | 'version'>, request: DeliveryRequest): Promise<ServiceOrder> {
      return http.post<ServiceOrder>(`${one(order.orderId)}/delivery`, request, { ifMatch: order.version })
    },
    cancel(order: Pick<ServiceOrder, 'orderId' | 'version'>, reason: string): Promise<ServiceOrder> {
      return http.post<ServiceOrder>(`${one(order.orderId)}/cancel`, { reason }, { ifMatch: order.version })
    },
    terminate(order: Pick<ServiceOrder, 'orderId' | 'version'>, request: TerminateRequest): Promise<ServiceOrder> {
      return http.post<ServiceOrder>(`${one(order.orderId)}/termination`, request, { ifMatch: order.version })
    },
  }
}

export type ServiceOrderApi = ReturnType<typeof createServiceOrderApi>
