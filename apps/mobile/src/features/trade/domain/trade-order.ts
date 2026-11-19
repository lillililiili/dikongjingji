// Mirrors contracts/openapi-trade.yaml (0.2.0). Amounts are in fen.

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
// enrolment's ticket (sourceRef is the enrolment id), or a venue booking (E10) whose page is the
// booking's code (sourceRef is the booking id).
export type TradeOrderSource = 'MALL' | 'ACTIVITY' | 'VENUE'

export const TRADE_ORDER_SOURCES: readonly TradeOrderSource[] = ['MALL', 'ACTIVITY', 'VENUE']

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
  source: { kind: 'CART'; cartItemIds: string[] } | { kind: 'OFFER'; offerId: string; quantity: number }
  deliveryMode: TradeDeliveryMode
  addressId: string | null
  buyerNote: string
  expectedPayableMinor: number
}

// Paid at some point: every status after PENDING_PAYMENT except CLOSED.
export function hasBeenPaid(order: TradeOrder): boolean {
  return order.status !== 'PENDING_PAYMENT' && order.status !== 'CLOSED'
}
