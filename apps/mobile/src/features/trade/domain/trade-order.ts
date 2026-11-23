// Mirrors contracts/openapi-trade.yaml (0.7.0). Amounts are in fen.

export type TradeOrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CLOSED'
  | 'REFUNDING'
  | 'REFUNDED'
  | 'REFUND_FAILED'

export type TradeOrderTab = 'ALL' | 'TO_PAY' | 'TO_SHIP' | 'TO_RECEIVE' | 'DONE' | 'REFUND'

export const TRADE_ORDER_TABS: readonly TradeOrderTab[] = [
  'ALL',
  'TO_PAY',
  'TO_SHIP',
  'TO_RECEIVE',
  'DONE',
  'REFUND',
]

export type TradeDeliveryMode = 'EXPRESS' | 'PICKUP'

// Where the order came from (0.2.0): a mall order, a paid activity enrolment whose page is the
// enrolment's ticket (sourceRef is the enrolment id), a venue booking (E10) whose page is the
// booking's code (sourceRef is the booking id), or an equipment rental (F3) whose page is the rental
// order (sourceRef is the rental order id).
// An inspection payment node (S4-1, contract 0.4.0): sourceRef is the payment attempt id; the line's
// productId is the inspection service order, productName "服务名 · 方案 vN", skuName the node.
// An online course (S5-5): sourceRef is the course enrollment.
export type TradeOrderSource = 'MALL' | 'ACTIVITY' | 'VENUE' | 'RENTAL' | 'INSPECTION' | 'COURSE'

export const TRADE_ORDER_SOURCES: readonly TradeOrderSource[] = ['MALL', 'ACTIVITY', 'VENUE', 'RENTAL', 'INSPECTION', 'COURSE']

export type TradeMerchantKind = 'FACTORY_DIRECT' | 'AUTHORIZED_DEALER' | 'LOCAL_STORE'

export type TradeRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

// How a CLOSED order closed: the buyer cancelled it, or the payment time ran out.
export type TradeCloseReason = 'BUYER_CANCEL' | 'TIMEOUT'

export const TRADE_CLOSE_REASONS: readonly TradeCloseReason[] = ['BUYER_CANCEL', 'TIMEOUT']

export interface TradeImage {
  url: string
  alt: string
}

export interface TradeMerchant {
  organizationId: string
  name: string
  // The mall's merchant type; null on business orders (the payee of an enrolment is its organiser).
  kind: TradeMerchantKind | null
}

export interface TradeOrderLine {
  lineId: string
  productId: string
  offerId: string
  productName: string
  skuName: string
  coverImage: TradeImage | null
  unitAmountMinor: number
  quantity: number
  subtotalMinor: number
}

export interface TradeDelivery {
  mode: TradeDeliveryMode
  recipientName: string | null
  phoneMasked: string | null
  addressLine: string | null
  pickupPoint: string | null
  // Six digits, only after payment and only on pickup orders.
  pickupCode: string | null
  carrierName: string | null
  trackingNo: string | null
  shippedAt: string | null
}

export interface TradeAmounts {
  goodsMinor: number
  shippingMinor: number
  payableMinor: number
  paidMinor: number
  refundedMinor: number
}

export interface TradeRefund {
  refundId: string
  amountMinor: number
  reason: string
  status: TradeRefundStatus
  requestedAt: string
  succeededAt: string | null
}

// (0.6.0) 交易主体 as recorded when the order was placed (board OrderGoods): where it ships from, who
// invoices and who answers for after-sales. Business orders carry none of them; a null row is not shown.
export interface TradeOrderParties {
  fulfilledFrom: string | null
  invoiceIssuer: string | null
  aftersaleParty: string | null
}

// (0.6.0) The after-sales entry of an order (contracts/openapi-trade-aftersale.yaml): the latest
// after-sales case, and whether one can be applied for now (only ever true on a mall order).
export interface TradeOrderAftersale {
  latestAftersaleId: string | null
  latestStatus: string | null
  applicable: boolean
}

export interface TradeOrder {
  orderId: string
  orderNo: string
  source: TradeOrderSource
  // The business record behind the order (the enrolment id of a paid enrolment, the booking id of a
  // venue booking); null on mall orders.
  sourceRef: string | null
  status: TradeOrderStatus
  merchant: TradeMerchant
  // A paid enrolment: productName is the activity, skuName the session, quantity the number of people.
  // A venue booking: productName is "venue project", skuName the session time, quantity the people.
  // A rental: the first line is the rent (productName the device, skuName the period, quantity the
  // units); further lines are the merchant's other fees.
  lines: TradeOrderLine[]
  // Null on business orders, which ship nothing.
  delivery: TradeDelivery | null
  amounts: TradeAmounts
  buyerNote: string | null
  createdAt: string
  payBefore: string | null
  paidAt: string | null
  completedAt: string | null
  closedAt: string | null
  // Only on CLOSED orders; null otherwise, or when an older server does not say.
  closeReason: TradeCloseReason | null
  refunds: TradeRefund[]
  // Whether the buyer may cancel now: unpaid, or paid and not yet shipped or picked up.
  cancellable: boolean
  // The order's version; commands that change the order send it back as If-Match.
  version: number
  parties: TradeOrderParties
  // (0.6.0) WeChat's transaction id of the successful payment, only once a verified notification or
  // the server's own query recorded it. For display only.
  channelTransactionId: string | null
  // (0.6.0) A paid sign-up's people, in sign-up order, joined with 、. For display only.
  participantNames: string | null
  aftersale: TradeOrderAftersale
  // Contract 0.10.0 (S5-7): who pays, as the buyer's identity was when the order was placed. Older
  // servers and orders say nothing, which is a person.
  payer?: TradePayer
}

export interface TradePayer {
  type: 'PERSONAL' | 'ENTERPRISE'
  name: string | null
}

export interface TradeOrderPage {
  items: TradeOrder[]
  hasMore: boolean
  total: number
}

// What the WeChat OpenSDK needs to open the payment sheet, signed by the server.
export interface WechatAppCall {
  appId: string
  partnerId: string
  prepayId: string
  packageValue: string
  nonceStr: string
  timeStamp: string
  sign: string
}

export interface PaymentLaunch {
  paymentId: string
  expiresAt: string
  appCall: WechatAppCall
}

export interface SubmitOrderInput {
  cityCode: string
  source:
    | { kind: 'CART'; cartItemIds: string[] }
    | { kind: 'OFFER'; offerId: string; quantity: number }
    // The chosen quote of a goods inquiry (contract 0.3.0): quantity and price are the quote's.
    | { kind: 'QUOTE'; inquiryQuoteId: string }
  deliveryMode: TradeDeliveryMode
  addressId: string | null
  buyerNote: string
  expectedPayableMinor: number
}

// Paid at some point: every status after PENDING_PAYMENT except CLOSED.
export function hasBeenPaid(order: TradeOrder): boolean {
  return order.status !== 'PENDING_PAYMENT' && order.status !== 'CLOSED'
}
