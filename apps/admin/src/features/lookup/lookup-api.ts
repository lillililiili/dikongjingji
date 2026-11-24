import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-lookup.yaml 1.0.0：订单查询、用户查询。平台运营和平台客服都能用，只读。
// 手机号、收货人由后端打码；打开详情时后端记一条查看记录。

export type OrderSource = 'MALL' | 'RENTAL' | 'ACTIVITY' | 'VENUE' | 'INSPECTION' | 'PRO_SERVICE' | 'COURSE'
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CLOSED' | 'REFUNDING' | 'REFUNDED'
  | 'REFUND_FAILED'
export type UserStatus = 'ACTIVE' | 'CLOSING' | 'LOCKED' | 'DISABLED' | 'CLOSED'

export const SOURCE_LABELS: Record<OrderSource, string> = {
  MALL: '商城',
  RENTAL: '设备租赁',
  ACTIVITY: '活动报名',
  VENUE: '到馆预约',
  INSPECTION: '巡检付款',
  PRO_SERVICE: '专业服务',
  COURSE: '课程',
}

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  REFUNDING: '退款中',
  REFUNDED: '已全额退款',
  REFUND_FAILED: '退款异常',
}

export const USER_STATUS_LABELS: Record<UserStatus, string> = {
  ACTIVE: '正常',
  CLOSING: '注销冷静期',
  LOCKED: '已锁定',
  DISABLED: '已停用',
  CLOSED: '已注销',
}

export const PAYMENT_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  PREPAID: '待付款',
  SUCCEEDED: '已付款',
  CLOSED: '已关闭',
  MISMATCH: '金额不符',
}

export const REFUND_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '待提交微信',
  PROCESSING: '微信处理中',
  SUCCEEDED: '已退回',
  CLOSED: '已关闭',
  ABNORMAL: '退款异常',
}

export const REFUND_INITIATOR_LABELS: Record<string, string> = {
  BUYER_CANCEL: '买家取消',
  MERCHANT: '商家退款',
  SOURCE: '业务退款',
}

export const AFTERSALE_KIND_LABELS: Record<string, string> = {
  RETURN_REFUND: '退货退款',
  REFUND_ONLY: '仅退款',
}

export const AFTERSALE_REASON_LABELS: Record<string, string> = {
  NO_REASON_7D: '七天无理由（未拆封）',
  NOT_AS_DESCRIBED: '商品与描述不符',
  DAMAGED: '商品有损坏',
  NOT_WANTED: '不想要了（仅退款）',
}

export const AFTERSALE_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待商家处理',
  AWAITING_RETURN: '待买家寄回',
  RETURN_SHIPPED: '买家已寄回',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  REFUND_FAILED: '退款异常',
  REJECTED: '商家已拒绝',
  RECEIPT_REJECTED: '商家拒收',
  REVOKED: '买家已撤销',
  CLOSED: '已关闭',
}

export const CAUSE_LABELS: Record<string, string> = {
  SUBMIT: '下单',
  PAYMENT_NOTIFIED: '付款成功（微信通知）',
  PAYMENT_QUERIED: '付款成功（主动查单）',
  BUYER_CANCEL: '买家取消',
  TIMEOUT: '超时未付款，自动关闭',
  SHIPMENT: '商家发货',
  PICKUP: '自提核销',
  RECEIPT: '买家确认收货',
  AUTO_RECEIPT: '自动确认收货',
  REFUND_NOTIFIED: '退款到账（微信通知）',
  REFUND_QUERIED: '退款到账（主动查询）',
  MERCHANT_REFUND: '商家退款',
  REFUND_REJECTED: '退款被拒',
  SOURCE_REFUND: '业务退款',
}

export const ROLE_CODE_LABELS: Record<string, string> = {
  CUSTOMER: '客户',
  PROVIDER_OPERATOR: '机构操作员',
  PLATFORM_OPERATOR: '平台运营',
  PLATFORM_SUPPORT: '平台客服',
  LIVE_HOST: '直播主办',
  SYSTEM: '系统',
}

export const ORGANIZATION_TYPE_LABELS: Record<string, string> = {
  CUSTOMER: '客户单位',
  PROVIDER: '经营机构',
  PLATFORM: '平台',
}

/** 未知编码原样显示，不显示空白。 */
export function label(map: Record<string, string>, code: string | null | undefined): string {
  if (!code) return '—'
  return map[code] ?? code
}

export interface Buyer {
  userId: string
  displayName: string | null
  maskedPhone: string | null
}

export interface OrderSummary {
  id: string
  orderNo: string
  source: OrderSource
  status: OrderStatus
  cityCode: string
  merchantName: string
  buyer: Buyer
  payerName: string | null
  title: string
  itemCount: number
  payableMinor: number
  paidMinor: number
  refundedMinor: number
  createdAt: string
  paidAt: string | null
}

export interface OrderDetail extends OrderSummary {
  sourceRef: string | null
  deliveryMode: string | null
  goodsMinor: number
  shippingMinor: number
  buyerNote: string | null
  payBefore: string | null
  shippedAt: string | null
  completedAt: string | null
  closedAt: string | null
  lines: Array<{ productName: string, skuName: string | null, unitAmountMinor: number, quantity: number,
    subtotalMinor: number }>
  delivery: {
    mode: 'EXPRESS' | 'PICKUP'
    recipientMasked: string | null
    phoneMasked: string | null
    cityName: string | null
    pickupPoint: string | null
    carrierName: string | null
    trackingNo: string | null
  } | null
  payments: Array<{ outTradeNo: string, transactionId: string | null, amountMinor: number, status: string,
    createdAt: string, succeededAt: string | null, closedAt: string | null }>
  refunds: Array<{ outRefundNo: string, refundId: string | null, amountMinor: number, reason: string | null,
    initiator: string, status: string, createdAt: string, acceptedAt: string | null, succeededAt: string | null }>
  aftersales: Array<{ id: string, aftersaleNo: string, kind: string, status: string, reason: string,
    requestedMinor: number, refundMinor: number | null, submittedAt: string | null }>
  history: Array<{ fromStatus: string | null, toStatus: string, cause: string, occurredAt: string }>
}

export interface UserSummary {
  id: string
  displayName: string
  loginName: string
  maskedPhone: string | null
  status: UserStatus
  registeredVia: string | null
  roles: string[]
  createdAt: string
}

export interface UserDetail extends UserSummary {
  closingDueAt: string | null
  closedAt: string | null
  organizations: Array<{ id: string, name: string, type: string }>
  enterprises: Array<{ id: string, name: string, memberRole: string, memberStatus: string }>
  activeDeviceCount: number
  lastActiveAt: string | null
  orderCount: number
}

export interface OrderFilters {
  q: string
  buyerId: string
  source: OrderSource | ''
  status: OrderStatus | ''
  /** [from, to]，北京时间日期（2026-11-23），两头都含。 */
  dates: [string, string] | null
}

export interface UserFilters {
  q: string
}

/** 和后端一致：q 最长 64（订单）、40（用户）个字。 */
export const ORDER_QUERY_MAX = 64
export const USER_QUERY_MAX = 40

export function createLookupApi(http: ApiClient) {
  return {
    orders(filters: OrderFilters, page: number, size: number): Promise<Page<OrderSummary>> {
      return http.get<Page<OrderSummary>>('/api/v1/ops/orders', {
        q: filters.q.trim(),
        buyerId: filters.buyerId,
        source: filters.source,
        status: filters.status,
        from: filters.dates?.[0] ?? '',
        to: filters.dates?.[1] ?? '',
        page,
        size,
      })
    },
    order(orderId: string): Promise<OrderDetail> {
      return http.get<OrderDetail>(`/api/v1/ops/orders/${encodeURIComponent(orderId)}`)
    },
    users(filters: UserFilters, page: number, size: number): Promise<Page<UserSummary>> {
      return http.get<Page<UserSummary>>('/api/v1/ops/users', { q: filters.q.trim(), page, size })
    },
    user(userId: string): Promise<UserDetail> {
      return http.get<UserDetail>(`/api/v1/ops/users/${encodeURIComponent(userId)}`)
    },
  }
}

export type LookupApi = ReturnType<typeof createLookupApi>
