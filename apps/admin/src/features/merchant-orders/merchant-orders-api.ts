import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-trade.yaml 0.5.0：商家操作员的商城订单（listMerchantOrders 加了 deliveryMode、readMerchantOrder），
// 发货、自提核销、退款沿用 0.1.0 起的 shipOrder、completePickup、refundOrder。只看、只改本机构卖出的商城订单。

export type TradeOrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CLOSED'
  | 'REFUNDING'
  | 'REFUNDED'
  | 'REFUND_FAILED'
export type DeliveryMode = 'EXPRESS' | 'PICKUP'
export type RefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'
export type MerchantKind = 'FACTORY_DIRECT' | 'AUTHORIZED_DEALER' | 'LOCAL_STORE'

export interface TradeOrderLine {
  lineId: string
  productId: string
  offerId: string
  productName: string
  skuName: string
  coverImage: { url: string, alt: string } | null
  unitAmountMinor: number
  quantity: number
  subtotalMinor: number
}

export interface TradeDelivery {
  mode: DeliveryMode
  recipientName: string | null
  phoneMasked: string | null
  /** 商家接口只在付款之后给完整手机号（发货要用），待付款、已关闭时为 null。 */
  recipientPhone: string | null
  /** 商家接口只在付款之后给收货地址；自提单为 null。 */
  addressLine: string | null
  pickupPoint: string | null
  /** 取货码只给买家，商家接口总是 null。 */
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
  /** 已经退到账的合计（退款成功才算）。 */
  refundedMinor: number
  currency: 'CNY'
}

export interface TradeRefund {
  refundId: string
  amountMinor: number
  reason: string
  status: RefundStatus
  requestedAt: string
  succeededAt: string | null
}

export interface TradeOrder {
  orderId: string
  orderNo: string
  source: 'MALL' | 'ACTIVITY' | 'VENUE' | 'RENTAL'
  sourceRef: string | null
  status: TradeOrderStatus
  merchant: { organizationId: string, name: string, kind: MerchantKind | null }
  lines: TradeOrderLine[]
  delivery: TradeDelivery | null
  amounts: TradeAmounts
  buyerNote: string | null
  createdAt: string
  payBefore: string | null
  paidAt: string | null
  completedAt: string | null
  closedAt: string | null
  closeReason: 'BUYER_CANCEL' | 'TIMEOUT' | null
  refunds: TradeRefund[]
  cancellable: boolean
  version: number
}

export type TradeOrderPage = Page<TradeOrder>

export const ORDER_STATUS_LABELS: Record<TradeOrderStatus, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  REFUNDING: '退款中',
  REFUNDED: '已全额退款',
  REFUND_FAILED: '退款异常',
}

/** 列表里的状态：已付款的按配送方式说“待发货”或“待自提”，商家一眼知道要做什么。 */
export function orderStatusLabel(order: Pick<TradeOrder, 'status' | 'delivery'>): string {
  if (order.status === 'PAID') return order.delivery?.mode === 'PICKUP' ? '待自提' : '待发货'
  return ORDER_STATUS_LABELS[order.status] ?? '状态未知'
}

export const DELIVERY_MODE_LABELS: Record<DeliveryMode, string> = {
  EXPRESS: '快递',
  PICKUP: '到店自提',
}

export const REFUND_STATUS_LABELS: Record<RefundStatus, string> = {
  REQUESTED: '待提交微信',
  PROCESSING: '微信处理中',
  SUCCEEDED: '已退回',
  CLOSED: '已关闭',
  ABNORMAL: '退款异常',
}

export const CLOSE_REASON_LABELS: Record<'BUYER_CANCEL' | 'TIMEOUT', string> = {
  BUYER_CANCEL: '买家取消',
  TIMEOUT: '超时未付款',
}

/** 列表页签：每个页签对应一组查询条件。 */
export type OrderTabKey = 'TO_SHIP' | 'TO_PICKUP' | 'SHIPPED' | 'REFUNDING' | 'ALL'

export interface OrderFilters {
  tab: OrderTabKey
}

export const ORDER_TABS: ReadonlyArray<{
  key: OrderTabKey
  label: string
  status?: TradeOrderStatus
  deliveryMode?: DeliveryMode
}> = [
  { key: 'TO_SHIP', label: '待发货', status: 'PAID', deliveryMode: 'EXPRESS' },
  { key: 'TO_PICKUP', label: '待自提', status: 'PAID', deliveryMode: 'PICKUP' },
  { key: 'SHIPPED', label: '已发货', status: 'SHIPPED' },
  { key: 'REFUNDING', label: '退款中', status: 'REFUNDING' },
  { key: 'ALL', label: '全部' },
]

export function tabQuery(tab: OrderTabKey): { status?: TradeOrderStatus, deliveryMode?: DeliveryMode } {
  const found = ORDER_TABS.find((candidate) => candidate.key === tab)
  return { status: found?.status, deliveryMode: found?.deliveryMode }
}

// ---- 按状态决定能做什么（和后端 TradeOrderService、TradeRefundService 的规则一致；后端仍会再判断一次） ----

export function canShip(order: Pick<TradeOrder, 'status' | 'delivery'>): boolean {
  return order.status === 'PAID' && order.delivery?.mode === 'EXPRESS'
}

export function canCompletePickup(order: Pick<TradeOrder, 'status' | 'delivery'>): boolean {
  return order.status === 'PAID' && order.delivery?.mode === 'PICKUP'
}

const REFUNDABLE_STATUSES: readonly TradeOrderStatus[] = ['PAID', 'SHIPPED', 'COMPLETED', 'REFUND_FAILED']

/** 还占着钱的退款：在路上的（已建、处理中）和异常的（人工处理后仍可能退成功），算可退金额时都要扣掉。 */
const RESERVED_REFUND_STATUSES: readonly RefundStatus[] = ['REQUESTED', 'PROCESSING', 'ABNORMAL']

/** 还能退多少（分）= 实付 − 已退到账 − 还占着钱的退款。不会小于 0。 */
export function refundableMinor(order: Pick<TradeOrder, 'amounts' | 'refunds'>): number {
  const reserved = order.refunds
    .filter((refund) => RESERVED_REFUND_STATUSES.includes(refund.status))
    .reduce((sum, refund) => sum + refund.amountMinor, 0)
  return Math.max(0, order.amounts.paidMinor - order.amounts.refundedMinor - reserved)
}

export function canRefund(order: Pick<TradeOrder, 'status' | 'amounts' | 'refunds'>): boolean {
  return REFUNDABLE_STATUSES.includes(order.status) && refundableMinor(order) > 0
}

// ---- 表单校验（长度、格式和契约一致，长度按码点算） ----

export const CARRIER_MAX = 40
export const TRACKING_MAX = 40
export const REFUND_REASON_MAX = 80
export const PICKUP_CODE_PATTERN = /^[0-9]{6}$/

/** 物流公司、运单号：去掉首尾空白后 1–40 个字。返回错误说明，没问题返回 null。 */
export function shipmentError(carrierName: string, trackingNo: string): string | null {
  const carrier = carrierName.trim()
  const tracking = trackingNo.trim()
  if (!carrier) return '请填写物流公司'
  if (charCount(carrier) > CARRIER_MAX) return `物流公司最多 ${CARRIER_MAX} 个字`
  if (!tracking) return '请填写运单号'
  if (charCount(tracking) > TRACKING_MAX) return `运单号最多 ${TRACKING_MAX} 个字`
  return null
}

export function pickupCodeError(code: string): string | null {
  const value = code.trim()
  if (!value) return '请输入买家出示的 6 位取货码'
  if (!PICKUP_CODE_PATTERN.test(value)) return '取货码是 6 位数字'
  return null
}

/** 退款金额：大于 0、不超过可退金额。amountMinor 为 null 表示输入的格式不对。 */
export function refundAmountError(amountMinor: number | null, refundable: number): string | null {
  if (amountMinor === null) return '请输入金额，最多两位小数，比如 41 或 41.50'
  if (amountMinor <= 0) return '退款金额要大于 0'
  if (amountMinor > refundable) return '退款金额不能超过可退金额'
  return null
}

export function refundReasonError(reason: string): string | null {
  const value = reason.trim()
  if (!value) return '请填写退款原因（买家能看到）'
  if (charCount(value) > REFUND_REASON_MAX) return `退款原因最多 ${REFUND_REASON_MAX} 个字`
  return null
}

registerErrorMessages({
  TRADE_ORDER_STATE_INVALID: '订单状态已经变了，不能做这个操作，请刷新后再看',
  TRADE_VERSION_CONFLICT: '订单刚刚有变化（买家取消、退款到账等），请刷新后重试',
  TRADE_PICKUP_CODE_INVALID: '取货码不对，请让买家重新出示。连续输错 10 次后这张单不能再核销，请买家联系平台客服',
  TRADE_REFUND_EXCEEDS_PAID: '退款金额超过了可退金额（实付减去已退和正在退的），请刷新后重新填写',
  TRADE_REFUND_LIMIT_REACHED: '这笔订单已不能再退款（付款超过一年，或已退满 50 次）',
  TRADE_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
  TRADE_CHANNEL_UNAVAILABLE: '微信支付暂时连不上，请稍后再试',
})

export function createMerchantOrdersApi(http: ApiClient) {
  const base = '/api/v1/provider/trade/orders'
  const one = (orderId: string) => `${base}/${encodeURIComponent(orderId)}`
  return {
    list(filters: OrderFilters, page: number, size: number): Promise<TradeOrderPage> {
      const { status, deliveryMode } = tabQuery(filters.tab)
      return http.get<TradeOrderPage>(base, { status, deliveryMode, page, size })
    },
    detail(orderId: string): Promise<TradeOrder> {
      return http.get<TradeOrder>(one(orderId))
    },
    ship(order: Pick<TradeOrder, 'orderId' | 'version'>, carrierName: string, trackingNo: string,
      idempotencyKey: string): Promise<TradeOrder> {
      return http.post<TradeOrder>(`${one(order.orderId)}/shipment`,
        { carrierName: carrierName.trim(), trackingNo: trackingNo.trim() },
        { idempotencyKey, ifMatch: order.version })
    },
    completePickup(order: Pick<TradeOrder, 'orderId' | 'version'>, pickupCode: string,
      idempotencyKey: string): Promise<TradeOrder> {
      return http.post<TradeOrder>(`${one(order.orderId)}/pickup`, { pickupCode: pickupCode.trim() },
        { idempotencyKey, ifMatch: order.version })
    },
    /** 退款不带 If-Match：重复靠幂等键（同一个键、同样的金额和原因返回同一笔退款）。 */
    refund(orderId: string, amountMinor: number, reason: string, idempotencyKey: string): Promise<TradeRefund> {
      return http.post<TradeRefund>(`${one(orderId)}/refunds`, { amountMinor, reason: reason.trim() },
        { idempotencyKey })
    },
  }
}

export type MerchantOrdersApi = ReturnType<typeof createMerchantOrdersApi>
