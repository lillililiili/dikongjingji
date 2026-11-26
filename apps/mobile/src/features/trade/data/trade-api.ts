import { businessImage } from '../../../shared/http/business-media'
import {
  TRADE_CLOSE_REASONS,
  TRADE_ORDER_SOURCES,
  type PaymentLaunch,
  type SubmitOrderInput,
  type TradeCloseReason,
  type TradeDelivery,
  type TradeOrder,
  type TradeOrderLine,
  type TradeOrderPage,
  type TradeOrderSource,
  type TradeOrderAftersale,
  type TradeOrderParties,
  type TradeOrderTab,
  type TradeRefund,
} from '../domain/trade-order'

type TradeRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// Commands that change the order carry its version (If-Match) and a fresh idempotency key.
export interface TradeCommand {
  idempotencyKey: string
  version: number
}

export interface TradeApi {
  submit(accessToken: string, input: SubmitOrderInput, idempotencyKey: string): Promise<TradeOrder>
  list(accessToken: string, tab: TradeOrderTab, page: number, size: number): Promise<TradeOrderPage>
  read(accessToken: string, orderId: string): Promise<TradeOrder>
  launchPayment(accessToken: string, orderId: string, idempotencyKey: string): Promise<PaymentLaunch>
  checkPayment(accessToken: string, orderId: string): Promise<TradeOrder>
  cancel(accessToken: string, orderId: string, command: TradeCommand): Promise<TradeOrder>
  confirmReceipt(accessToken: string, orderId: string, command: TradeCommand): Promise<TradeOrder>
}

// The DTO as it arrives: the server sends null for empty fields, older fixtures may omit them.
type TradeOrderDto = Omit<
  TradeOrder,
  'lines' | 'delivery' | 'refunds' | 'closeReason' | 'source' | 'sourceRef' | 'merchant'
  | 'parties' | 'channelTransactionId' | 'participantNames' | 'aftersale' | 'payer'
> & {
  payer?: { type?: string | null; name?: string | null } | null
  source?: string | null
  sourceRef?: string | null
  merchant: { organizationId: string; name: string; kind?: TradeOrder['merchant']['kind'] }
  closeReason?: string | null
  lines: Array<Partial<TradeOrderLine> & Omit<TradeOrderLine, 'coverImage'>>
  delivery?: (Partial<TradeDelivery> & Pick<TradeDelivery, 'mode'>) | null
  refunds?: Array<Partial<TradeRefund> & Omit<TradeRefund, 'succeededAt'>>
  // 0.6.0; absent on an older server.
  parties?: Partial<TradeOrderParties> | null
  channelTransactionId?: string | null
  participantNames?: string | null
  aftersale?: Partial<TradeOrderAftersale> | null
}

// 只由演示收银分支引用，公开构建可连同开发接口地址一起移除。
export function requestSimulatedPayment(
  request: (input: Parameters<TradeRequest>[0]) => Promise<void>, accessToken: string, orderId: string,
): Promise<void> {
  return request({
    url: `/api/v1/dev/trade/orders/${encodeURIComponent(orderId)}/simulated-payment`,
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}` },
  })
}

export function createTradeApi(request: TradeRequest): TradeApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const orderUrl = (orderId: string) => `/api/v1/trade/orders/${encodeURIComponent(orderId)}`
  const commandHeaders = (accessToken: string, command: TradeCommand) => ({
    ...auth(accessToken),
    'Idempotency-Key': command.idempotencyKey,
    // The ETag of the order is its version in quotes.
    'If-Match': `"${command.version}"`,
  })
  return {
    async submit(accessToken, input, idempotencyKey) {
      const body: Record<string, unknown> = {
        cityCode: input.cityCode,
        deliveryMode: input.deliveryMode,
        expectedPayableMinor: input.expectedPayableMinor,
        agreementAccepted: true,
      }
      if (input.source.kind === 'CART') {
        body.cartItemIds = input.source.cartItemIds
      } else if (input.source.kind === 'QUOTE') {
        body.inquiryQuoteId = input.source.inquiryQuoteId
      } else {
        body.offerId = input.source.offerId
        body.quantity = input.source.quantity
      }
      if (input.deliveryMode === 'EXPRESS' && input.addressId) body.addressId = input.addressId
      const note = input.buyerNote.trim()
      if (note) body.buyerNote = note
      return toTradeOrder(await request<TradeOrderDto>({
        url: '/api/v1/trade/orders',
        method: 'POST',
        data: body,
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async list(accessToken, tab, page, size) {
      const dto = await request<{ items: TradeOrderDto[]; hasMore: boolean; total: number }>({
        url: `/api/v1/trade/orders?tab=${tab}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { items: dto.items.map(toTradeOrder), hasMore: Boolean(dto.hasMore), total: dto.total }
    },
    async read(accessToken, orderId) {
      return toTradeOrder(await request<TradeOrderDto>({
        url: orderUrl(orderId),
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async launchPayment(accessToken, orderId, idempotencyKey) {
      const dto = await request<PaymentLaunch>({
        url: `${orderUrl(orderId)}/payments`,
        method: 'POST',
        data: { channel: 'WECHAT_APP' },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      })
      return {
        paymentId: dto.paymentId,
        expiresAt: dto.expiresAt,
        appCall: {
          appId: dto.appCall.appId,
          partnerId: dto.appCall.partnerId,
          prepayId: dto.appCall.prepayId,
          packageValue: dto.appCall.packageValue,
          nonceStr: dto.appCall.nonceStr,
          timeStamp: dto.appCall.timeStamp,
          sign: dto.appCall.sign,
        },
      }
    },
    async checkPayment(accessToken, orderId) {
      return toTradeOrder(await request<TradeOrderDto>({
        url: `${orderUrl(orderId)}/payment-check`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async cancel(accessToken, orderId, command) {
      return toTradeOrder(await request<TradeOrderDto>({
        url: `${orderUrl(orderId)}/cancel`,
        method: 'POST',
        headers: commandHeaders(accessToken, command),
      }))
    },
    async confirmReceipt(accessToken, orderId, command) {
      return toTradeOrder(await request<TradeOrderDto>({
        url: `${orderUrl(orderId)}/receipt`,
        method: 'POST',
        headers: commandHeaders(accessToken, command),
      }))
    },
  }
}

export function toTradeOrder(dto: TradeOrderDto): TradeOrder {
  const source = toSource(dto.source)
  return {
    orderId: dto.orderId,
    orderNo: dto.orderNo,
    source,
    sourceRef: source === 'MALL' ? null : dto.sourceRef ?? null,
    status: dto.status,
    merchant: { organizationId: dto.merchant.organizationId, name: dto.merchant.name, kind: dto.merchant.kind ?? null },
    lines: dto.lines.map((line) => ({
      lineId: line.lineId,
      productId: line.productId,
      offerId: line.offerId,
      productName: line.productName,
      skuName: line.skuName,
      coverImage: businessImage(line.coverImage),
      unitAmountMinor: line.unitAmountMinor,
      quantity: line.quantity,
      subtotalMinor: line.subtotalMinor,
    })),
    // The buyer's copy never carries the full phone number; it is not kept even if sent.
    delivery: dto.delivery ? toDelivery(dto.delivery) : null,
    amounts: {
      goodsMinor: dto.amounts.goodsMinor,
      shippingMinor: dto.amounts.shippingMinor,
      payableMinor: dto.amounts.payableMinor,
      paidMinor: dto.amounts.paidMinor ?? 0,
      refundedMinor: dto.amounts.refundedMinor ?? 0,
    },
    buyerNote: dto.buyerNote ?? null,
    createdAt: dto.createdAt,
    payBefore: dto.payBefore ?? null,
    paidAt: dto.paidAt ?? null,
    completedAt: dto.completedAt ?? null,
    closedAt: dto.closedAt ?? null,
    closeReason: toCloseReason(dto.status, dto.closeReason),
    refunds: (dto.refunds ?? []).map((refund) => ({
      refundId: refund.refundId,
      amountMinor: refund.amountMinor,
      reason: refund.reason,
      status: refund.status,
      requestedAt: refund.requestedAt,
      succeededAt: refund.succeededAt ?? null,
    })),
    cancellable: Boolean(dto.cancellable),
    version: dto.version,
    parties: {
      fulfilledFrom: textOrNull(dto.parties?.fulfilledFrom),
      invoiceIssuer: textOrNull(dto.parties?.invoiceIssuer),
      aftersaleParty: textOrNull(dto.parties?.aftersaleParty),
    },
    channelTransactionId: textOrNull(dto.channelTransactionId),
    participantNames: textOrNull(dto.participantNames),
    aftersale: {
      latestAftersaleId: dto.aftersale?.latestAftersaleId ?? null,
      latestStatus: dto.aftersale?.latestStatus ?? null,
      // Only an explicit true opens 申请售后; an older server that does not say keeps it closed.
      applicable: dto.aftersale?.applicable === true,
    },
    payer: dto.payer?.type === 'ENTERPRISE' && dto.payer.name
      ? { type: 'ENTERPRISE', name: dto.payer.name }
      : { type: 'PERSONAL', name: null },
  }
}

// An empty string reads as "not known", so the page leaves the row out rather than showing a blank.
function textOrNull(value: string | null | undefined): string | null {
  const text = value?.trim() ?? ''
  return text === '' ? null : text
}

function toDelivery(delivery: Partial<TradeDelivery> & Pick<TradeDelivery, 'mode'>): TradeDelivery {
  return {
    mode: delivery.mode,
    recipientName: delivery.recipientName ?? null,
    phoneMasked: delivery.phoneMasked ?? null,
    addressLine: delivery.addressLine ?? null,
    pickupPoint: delivery.pickupPoint ?? null,
    pickupCode: delivery.pickupCode ?? null,
    carrierName: delivery.carrierName ?? null,
    trackingNo: delivery.trackingNo ?? null,
    shippedAt: delivery.shippedAt ?? null,
  }
}

// A server before 0.2.0 sends no source: every order was a mall order then.
function toSource(source: string | null | undefined): TradeOrderSource {
  return source && (TRADE_ORDER_SOURCES as readonly string[]).includes(source)
    ? (source as TradeOrderSource)
    : 'MALL'
}

// Only a closed order has a reason, and only the two the contract names; anything else reads as unknown.
function toCloseReason(status: TradeOrder['status'], reason: string | null | undefined): TradeCloseReason | null {
  if (status !== 'CLOSED' || !reason) return null
  return (TRADE_CLOSE_REASONS as readonly string[]).includes(reason) ? (reason as TradeCloseReason) : null
}
