import {
  SERVICE_ORDER_STATUSES,
  SERVICE_ORDER_TYPES,
  type ServiceOrder,
  type ServiceOrderCustomer,
  type ServiceOrderDelivery,
  type ServiceOrderDownloadIntent,
  type ServiceOrderFile,
  type ServiceOrderLine,
  type ServiceOrderPage,
  type ServiceOrderPaymentStart,
  type ServiceOrderPlan,
  type ServiceOrderProvider,
  type ServiceOrderRefund,
  type ServiceOrderStatus,
  type ServiceOrderSummary,
  type ServiceOrderType,
} from '../domain/service-order'

type ServiceOrderRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The server sends every field; a missing one reads the same as null.
type Loose<T> = { [K in keyof T]?: T[K] | null }

interface ServiceOrderDto {
  orderId: string
  orderNo?: string | null
  status: string
  type?: string | null
  title?: string | null
  demandId?: string | null
  customer?: Loose<ServiceOrderCustomer> | null
  provider?: Loose<ServiceOrderProvider> | null
  cityCode?: string | null
  cityName?: string | null
  address?: string | null
  scope?: string | null
  dueDate?: string | null
  amountMinor?: number | null
  lines?: Array<Loose<ServiceOrderLine>> | null
  quote?: Loose<ServiceOrder['quote']> | null
  plan?: (Loose<ServiceOrderPlan> & { terms?: string[] | null }) | null
  payment?: (Loose<ServiceOrder['payment']> & { refunds?: Array<Loose<ServiceOrderRefund>> | null }) | null
  deliveries?: Array<Loose<ServiceOrderDelivery> & { files?: Array<Loose<ServiceOrderFile>> | null }> | null
  startedAt?: string | null
  completedAt?: string | null
  closedAt?: string | null
  closedBy?: string | null
  closeReason?: string | null
  actions?: Record<string, boolean | null | undefined> | null
  createdAt?: string | null
  updatedAt?: string | null
  version: number
}

type ServiceOrderSummaryDto = Loose<Omit<ServiceOrderSummary, 'customer' | 'provider'>> & {
  orderId: string
  status: string
  customer?: Loose<ServiceOrderCustomer> | null
  provider?: Loose<ServiceOrderProvider> | null
}

interface PageDto<T> {
  total: number
  hasMore: boolean
  items: T[]
}

export interface ServiceOrderApi {
  list(accessToken: string, status: ServiceOrderStatus | null, page: number, size: number): Promise<ServiceOrderPage>
  read(accessToken: string, orderId: string): Promise<ServiceOrder>
  // `version` is the order version the page read (If-Match); `planVersion` the plan on screen.
  confirmPlan(accessToken: string, orderId: string, version: number, planVersion: number): Promise<ServiceOrder>
  startPayment(accessToken: string, orderId: string): Promise<ServiceOrderPaymentStart>
  cancel(accessToken: string, orderId: string, version: number, reason: string | null): Promise<ServiceOrder>
  accept(accessToken: string, orderId: string, version: number, round: number): Promise<ServiceOrder>
  rework(accessToken: string, orderId: string, version: number, round: number, reason: string): Promise<ServiceOrder>
  createDownload(accessToken: string, orderId: string, fileId: string): Promise<ServiceOrderDownloadIntent>
}

// Every call carries the access token it was given; a rejected token reaches the page as an error
// (status 401) and the page decides where the user goes.
export function createServiceOrderApi(request: ServiceOrderRequest): ServiceOrderApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  // The ETag of an order is its version in quotes.
  const ifMatch = (accessToken: string, version: number) => ({ ...auth(accessToken), 'If-Match': `"${version}"` })
  const orderUrl = (orderId: string) => `/api/v1/service-orders/${encodeURIComponent(orderId)}`

  const command = async (accessToken: string, url: string, version: number, data: unknown) =>
    toServiceOrder(await request<ServiceOrderDto>({ url, method: 'POST', data, headers: ifMatch(accessToken, version) }))

  return {
    async list(accessToken, status, page, size) {
      const filter = status ? `status=${status}&` : ''
      const dto = await request<PageDto<ServiceOrderSummaryDto>>({
        url: `/api/v1/service-orders?${filter}page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toServiceOrderSummary) }
    },
    async read(accessToken, orderId) {
      return toServiceOrder(await request<ServiceOrderDto>({ url: orderUrl(orderId), method: 'GET', headers: auth(accessToken) }))
    },
    confirmPlan(accessToken, orderId, version, planVersion) {
      return command(accessToken, `${orderUrl(orderId)}/plan-confirmation`, version, { planVersion, agreed: true })
    },
    async startPayment(accessToken, orderId) {
      const dto = await request<Loose<ServiceOrderPaymentStart>>({
        url: `${orderUrl(orderId)}/payments`,
        method: 'POST',
        headers: auth(accessToken),
      })
      return {
        orderId: dto.orderId ?? orderId,
        tradeOrderId: dto.tradeOrderId ?? '',
        amountMinor: dto.amountMinor ?? 0,
        payBefore: dto.payBefore ?? '',
      }
    },
    cancel(accessToken, orderId, version, reason) {
      const text = reason?.trim()
      return command(accessToken, `${orderUrl(orderId)}/cancel`, version, { reason: text ? text : null })
    },
    accept(accessToken, orderId, version, round) {
      return command(accessToken, `${orderUrl(orderId)}/acceptance`, version, { round })
    },
    rework(accessToken, orderId, version, round, reason) {
      return command(accessToken, `${orderUrl(orderId)}/rework`, version, { round, reason: reason.trim() })
    },
    async createDownload(accessToken, orderId, fileId) {
      const dto = await request<Loose<ServiceOrderDownloadIntent>>({
        url: `${orderUrl(orderId)}/files/${encodeURIComponent(fileId)}/download-intents`,
        method: 'POST',
        headers: auth(accessToken),
      })
      return { url: dto.url ?? '', expiresAt: dto.expiresAt ?? '' }
    },
  }
}

// An unknown status reads as cancelled: nothing can be done on it, and the page says so.
function toStatus(value: string | null | undefined): ServiceOrderStatus {
  return value && (SERVICE_ORDER_STATUSES as readonly string[]).includes(value) ? value as ServiceOrderStatus : 'CANCELLED'
}

function toType(value: string | null | undefined): ServiceOrderType {
  return value && (SERVICE_ORDER_TYPES as readonly string[]).includes(value) ? value as ServiceOrderType : 'AERIAL'
}

function toCustomer(dto: Loose<ServiceOrderCustomer> | null | undefined): ServiceOrderCustomer {
  return {
    kind: dto?.kind === 'ORGANIZATION' ? 'ORGANIZATION' : 'PERSONAL',
    organizationName: dto?.organizationName?.trim() || null,
    contactName: dto?.contactName ?? '',
  }
}

// Only an explicit true from the server shows 资质已审核.
function toProvider(dto: Loose<ServiceOrderProvider> | null | undefined): ServiceOrderProvider {
  return {
    providerId: dto?.providerId ?? '',
    name: dto?.name ?? '',
    qualificationReviewed: dto?.qualificationReviewed === true,
  }
}

export function toServiceOrderLine(dto: Loose<ServiceOrderLine>): ServiceOrderLine {
  return {
    name: dto.name ?? '',
    quantity: dto.quantity ?? null,
    unit: dto.unit ?? null,
    unitAmountMinor: dto.unitAmountMinor ?? null,
    amountMinor: dto.amountMinor ?? 0,
  }
}

function toFile(dto: Loose<ServiceOrderFile>): ServiceOrderFile {
  return {
    fileId: dto.fileId ?? '',
    name: dto.name ?? '',
    contentType: dto.contentType ?? '',
    sizeBytes: dto.sizeBytes ?? 0,
    uploadedAt: dto.uploadedAt ?? '',
  }
}

function toRefund(dto: Loose<ServiceOrderRefund>): ServiceOrderRefund {
  const status = dto.status
  return {
    refundId: dto.refundId ?? '',
    kind: dto.kind === 'TERMINATE' ? 'TERMINATE' : 'CANCEL',
    amountMinor: dto.amountMinor ?? 0,
    reason: dto.reason ?? '',
    status: status === 'PROCESSING' || status === 'SUCCEEDED' || status === 'CLOSED' || status === 'ABNORMAL'
      ? status
      : 'REQUESTED',
    requestedAt: dto.requestedAt ?? '',
  }
}

function toDeliveryStatus(value: string | null | undefined): ServiceOrderDelivery['status'] {
  return value === 'ACCEPTED' || value === 'RETURNED' ? value : 'SUBMITTED'
}

export function toServiceOrder(dto: ServiceOrderDto): ServiceOrder {
  const quote = dto.quote
  const plan = dto.plan
  const payment = dto.payment
  const actions = dto.actions ?? {}
  return {
    orderId: dto.orderId,
    orderNo: dto.orderNo ?? '',
    status: toStatus(dto.status),
    type: toType(dto.type),
    title: dto.title ?? '',
    demandId: dto.demandId ?? '',
    customer: toCustomer(dto.customer),
    provider: toProvider(dto.provider),
    cityCode: dto.cityCode ?? '',
    cityName: dto.cityName ?? '',
    address: dto.address ?? '',
    scope: dto.scope ?? '',
    dueDate: dto.dueDate ?? null,
    amountMinor: dto.amountMinor ?? 0,
    lines: (dto.lines ?? []).map(toServiceOrderLine),
    quote: {
      quoteId: quote?.quoteId ?? '',
      quoteVersion: quote?.quoteVersion ?? 1,
      method: quote?.method ?? '',
      schedule: quote?.schedule ?? '',
      durationDays: quote?.durationDays ?? 0,
      deliverables: quote?.deliverables ?? '',
      team: quote?.team?.trim() || null,
      nightWork: quote?.nightWork?.trim() || null,
      reportHours: quote?.reportHours ?? null,
      archiveDays: quote?.archiveDays ?? null,
      firstVisitDate: quote?.firstVisitDate ?? null,
    },
    plan: plan?.version
      ? {
          version: plan.version,
          amountMinor: plan.amountMinor ?? dto.amountMinor ?? 0,
          schedule: plan.schedule ?? '',
          terms: (plan.terms ?? []).filter((term) => term.trim()),
          note: plan.note?.trim() || null,
          issuedAt: plan.issuedAt ?? '',
          confirmedAt: plan.confirmedAt ?? null,
          confirmedBy: plan.confirmedBy ?? null,
        }
      : null,
    payment: {
      amountMinor: payment?.amountMinor ?? dto.amountMinor ?? 0,
      collectable: payment?.collectable === true,
      paidAt: payment?.paidAt ?? null,
      pendingTradeOrderId: payment?.pendingTradeOrderId ?? null,
      pendingPayBefore: payment?.pendingPayBefore ?? null,
      paidTradeOrderId: payment?.paidTradeOrderId ?? null,
      refundedMinor: payment?.refundedMinor ?? 0,
      refunds: (payment?.refunds ?? []).map(toRefund),
    },
    deliveries: (dto.deliveries ?? [])
      .map((delivery) => ({
        round: delivery.round ?? 1,
        status: toDeliveryStatus(delivery.status),
        note: delivery.note ?? '',
        files: (delivery.files ?? []).map(toFile),
        deliveredAt: delivery.deliveredAt ?? '',
        reviewNote: delivery.reviewNote?.trim() || null,
        reviewedAt: delivery.reviewedAt ?? null,
        reviewedBy: delivery.reviewedBy ?? null,
      }))
      .sort((left, right) => left.round - right.round),
    startedAt: dto.startedAt ?? null,
    completedAt: dto.completedAt ?? null,
    closedAt: dto.closedAt ?? null,
    closedBy: dto.closedBy === 'CUSTOMER' || dto.closedBy === 'PROVIDER' ? dto.closedBy : null,
    closeReason: dto.closeReason?.trim() || null,
    // Only an explicit true allows an action; a missing flag never does.
    actions: {
      confirmPlan: actions.confirmPlan === true,
      pay: actions.pay === true,
      cancel: actions.cancel === true,
      accept: actions.accept === true,
      rework: actions.rework === true,
    },
    createdAt: dto.createdAt ?? '',
    updatedAt: dto.updatedAt ?? dto.createdAt ?? '',
    version: dto.version,
  }
}

export function toServiceOrderSummary(dto: ServiceOrderSummaryDto): ServiceOrderSummary {
  return {
    orderId: dto.orderId,
    orderNo: dto.orderNo ?? '',
    status: toStatus(dto.status),
    type: toType(dto.type),
    title: dto.title ?? '',
    scope: dto.scope ?? '',
    cityName: dto.cityName ?? '',
    customer: toCustomer(dto.customer),
    provider: toProvider(dto.provider),
    amountMinor: dto.amountMinor ?? 0,
    planVersion: dto.planVersion ?? null,
    paidAt: dto.paidAt ?? null,
    latestDeliveryAt: dto.latestDeliveryAt ?? null,
    createdAt: dto.createdAt ?? '',
    updatedAt: dto.updatedAt ?? dto.createdAt ?? '',
  }
}
