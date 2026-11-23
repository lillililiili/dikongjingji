// 商品售后，对应 contracts/openapi-trade-aftersale.yaml 1.0.0（S4-2，docs/redesign/09-商品售后与商家主页.md §1）。
// 只针对商城订单。平台只协助沟通、不裁决：同意、拒绝、验货都是卖出商品的商家做，钱经交易模块原路退回。
// 金额都是分。售后单的状态只来自服务端，页面不自己推进。

import type { TradeOrderLine } from './trade-order'

export const AFTERSALE_KINDS = ['REFUND_ONLY', 'RETURN_REFUND'] as const
export type AftersaleKind = (typeof AFTERSALE_KINDS)[number]

export const AFTERSALE_REASONS = ['NO_REASON_7D', 'NOT_AS_DESCRIBED', 'DAMAGED', 'NOT_WANTED'] as const
export type AftersaleReason = (typeof AFTERSALE_REASONS)[number]

export const AFTERSALE_STATUSES = [
  'PENDING_REVIEW',
  'AWAITING_RETURN',
  'RETURN_SHIPPED',
  'REFUNDING',
  'REFUNDED',
  'REFUND_FAILED',
  'REJECTED',
  'RECEIPT_REJECTED',
  'REVOKED',
  'CLOSED',
] as const
export type AftersaleStatus = (typeof AFTERSALE_STATUSES)[number]

// 列表三个页签：售后中、已完成、已关闭。
export const AFTERSALE_TABS = ['DOING', 'DONE', 'CLOSED'] as const
export type AftersaleTab = (typeof AFTERSALE_TABS)[number]

export const AFTERSALE_IMAGE_SLOTS = ['PRODUCT', 'PACKAGE', 'OTHER'] as const
export type AftersaleImageSlot = (typeof AFTERSALE_IMAGE_SLOTS)[number]

export const AFTERSALE_ACTIONS = [
  'SUBMIT',
  'AGREE',
  'AUTO_AGREE',
  'REJECT',
  'REVOKE',
  'SHIP_RETURN',
  'EDIT_RETURN',
  'CLOSE_OVERDUE',
  'CONFIRM_RECEIPT',
  'AUTO_REFUND',
  'REJECT_RECEIPT',
  'REFUND_SUCCEEDED',
  'REFUND_FAILED',
] as const
export type AftersaleAction = (typeof AFTERSALE_ACTIONS)[number]

export const AFTERSALE_UNAVAILABLE_REASONS = [
  'ORDER_NOT_ELIGIBLE',
  'ORDER_NOT_PAID',
  'ORDER_REFUNDED',
  'ALREADY_SHIPPED',
  'NOT_SHIPPED',
  'NO_REASON_NOT_SUPPORTED',
  'WINDOW_PASSED',
  'OPEN_AFTERSALE',
  'LIMIT_REACHED',
  'NOTHING_TO_REFUND',
] as const
export type AftersaleUnavailableReason = (typeof AFTERSALE_UNAVAILABLE_REASONS)[number]

// 每格最多 3 张，一共 9 张（设计假设 8）。
export const AFTERSALE_IMAGES_PER_SLOT = 3
export const AFTERSALE_DESCRIPTION_LIMIT = 200

// 没结束的售后单：订单页的“申请售后”这时变成“查看售后”。
export const OPEN_AFTERSALE_STATUSES: readonly AftersaleStatus[] = [
  'PENDING_REVIEW',
  'AWAITING_RETURN',
  'RETURN_SHIPPED',
  'REFUNDING',
  'REFUND_FAILED',
]

export interface AftersaleMerchant {
  organizationId: string
  // 处理方：卖出商品的商家（下单时的名称）。
  name: string
  // 下单时记的售后责任方；null 时就是商家自己。
  aftersaleParty: string | null
}

export interface AftersaleReturnAddress {
  contactName: string | null
  phone: string | null
  address: string
  note: string | null
}

export interface AftersaleWaybill {
  carrierName: string
  trackingNo: string
  shippedAt: string | null
}

export type AftersaleRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface AftersaleRefund {
  status: AftersaleRefundStatus
  amountMinor: number
  succeededAt: string | null
  // 原付款在微信的交易号，只显示。
  channelTransactionId: string | null
}

export interface AftersaleImage {
  imageId: string
  slot: AftersaleImageSlot
}

export interface AftersaleEvent {
  action: AftersaleAction
  status: AftersaleStatus
  occurredAt: string
}

// 买家这一方现在能做的事（服务端给出，页面只照着显示按钮）。
export interface AftersaleActions {
  canRevoke: boolean
  canSubmitReturn: boolean
  canEditReturn: boolean
  canReapply: boolean
}

export interface Aftersale {
  aftersaleId: string
  aftersaleNo: string
  orderId: string
  orderNo: string
  kind: AftersaleKind
  reason: AftersaleReason
  description: string | null
  status: AftersaleStatus
  merchant: AftersaleMerchant
  lines: TradeOrderLine[]
  paidMinor: number
  requestedMinor: number
  // 实际退款金额（商家确认收货时定）；还没发起退款时为 null。
  refundMinor: number | null
  returnAddress: AftersaleReturnAddress | null
  returnWaybill: AftersaleWaybill | null
  rejectReason: string | null
  merchantNote: string | null
  refund: AftersaleRefund | null
  images: AftersaleImage[]
  // 当前这一步的处理时限。
  dueAt: string | null
  submittedAt: string
  reviewedAt: string | null
  returnShippedAt: string | null
  receivedAt: string | null
  refundedAt: string | null
  closedAt: string | null
  // 详情里有，列表里为空。
  timeline: AftersaleEvent[]
  actions: AftersaleActions
  // 改售后单时放进 If-Match（带引号）。
  version: number
}

export interface AftersaleCounts {
  doing: number
  done: number
  closed: number
}

export interface AftersalePage {
  items: Aftersale[]
  hasMore: boolean
  total: number
  counts: AftersaleCounts
}

export interface AftersaleKindOption {
  kind: AftersaleKind
  available: boolean
  unavailableReason: AftersaleUnavailableReason | null
}

export interface AftersaleReasonOption {
  reason: AftersaleReason
  kind: AftersaleKind
  available: boolean
  unavailableReason: AftersaleUnavailableReason | null
}

export interface AftersaleEligibility {
  orderId: string
  orderNo: string
  orderStatus: string
  lines: TradeOrderLine[]
  paidMinor: number
  // 现在最多能申请多少（实付减已退减在路上的）。
  maxRefundMinor: number
  merchant: AftersaleMerchant
  kinds: AftersaleKindOption[]
  reasons: AftersaleReasonOption[]
  // 有没结束的售后单时是它：申请页直接进那张单。
  openAftersaleId: string | null
  shippedAt: string | null
  completedAt: string | null
}

export interface ApplyAftersaleInput {
  kind: AftersaleKind
  reason: AftersaleReason
  description: string
  // null：还能退的全部。
  requestedMinor: number | null
  imageIds: string[]
}

export interface ReturnShipmentInput {
  carrierName: string
  trackingNo: string
}

export interface AftersaleImageUpload {
  imageId: string
  slot: AftersaleImageSlot
}

export function isOpenAftersale(status: string | null): boolean {
  return status !== null && (OPEN_AFTERSALE_STATUSES as readonly string[]).includes(status)
}
