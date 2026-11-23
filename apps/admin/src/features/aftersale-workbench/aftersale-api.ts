import type { ApiClient } from '@/core/http/api-client'
import { ApiRequestError, registerErrorMessages } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-trade-aftersale.yaml 1.0.0 的商家接口：本机构卖出的商品的售后单（只针对商城订单）。
// 改售后单状态的操作带 If-Match（售后单版本，也就是详情里的 version），版本不对 412；金额都是分。
// 平台只协助沟通、不裁决：同意、拒绝、验货都由商家在这里做，钱经交易模块原路退回。

export type AftersaleKind = 'RETURN_REFUND' | 'REFUND_ONLY'
export type AftersaleReason = 'NO_REASON_7D' | 'NOT_AS_DESCRIBED' | 'DAMAGED' | 'NOT_WANTED'
export type AftersaleStatus =
  | 'PENDING_REVIEW'
  | 'AWAITING_RETURN'
  | 'RETURN_SHIPPED'
  | 'REFUNDING'
  | 'REFUNDED'
  | 'REFUND_FAILED'
  | 'REJECTED'
  | 'RECEIPT_REJECTED'
  | 'REVOKED'
  | 'CLOSED'
export type AftersaleImageSlot = 'PRODUCT' | 'PACKAGE' | 'OTHER'
export type AftersaleAction =
  | 'SUBMIT'
  | 'AGREE'
  | 'AUTO_AGREE'
  | 'REJECT'
  | 'REVOKE'
  | 'SHIP_RETURN'
  | 'EDIT_RETURN'
  | 'CLOSE_OVERDUE'
  | 'CONFIRM_RECEIPT'
  | 'AUTO_REFUND'
  | 'REJECT_RECEIPT'
  | 'REFUND_SUCCEEDED'
  | 'REFUND_FAILED'
export type AftersaleRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface AftersaleOrderLine {
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

export interface AftersaleMerchant {
  organizationId: string
  name: string
  /** 下单时记的售后责任方；为 null 时就是商家自己。 */
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

export interface AftersaleRefund {
  status: AftersaleRefundStatus
  amountMinor: number
  succeededAt: string | null
  /** 原付款在微信的交易号（只显示）。 */
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

export interface AftersaleActions {
  canRevoke: boolean
  canSubmitReturn: boolean
  canEditReturn: boolean
  canReapply: boolean
  canReview: boolean
  canConfirmReceipt: boolean
  canRejectReceipt: boolean
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
  lines: AftersaleOrderLine[]
  paidMinor: number
  requestedMinor: number
  refundMinor: number | null
  returnAddress: AftersaleReturnAddress | null
  returnWaybill: AftersaleWaybill | null
  rejectReason: string | null
  merchantNote: string | null
  refund: AftersaleRefund | null
  images: AftersaleImage[]
  dueAt: string | null
  submittedAt: string
  reviewedAt: string | null
  returnShippedAt: string | null
  receivedAt: string | null
  refundedAt: string | null
  closedAt: string | null
  /** 进度：详情里有，列表里为空。 */
  timeline: AftersaleEvent[]
  actions: AftersaleActions
  version: number
}

export interface AftersaleCounts {
  doing: number
  done: number
  closed: number
}

export interface AftersalePage extends Page<Aftersale> {
  counts: AftersaleCounts
}

export interface ReturnAddress {
  contactName: string
  phone: string
  address: string
  note: string | null
  version: number
  updatedAt: string
}

export interface ReturnAddressDraft {
  contactName: string
  phone: string
  address: string
  note: string
}

export interface ApproveDraft extends ReturnAddressDraft {
  saveAsDefault: boolean
}

// ---- 中文 ----

export const KIND_LABELS: Record<AftersaleKind, string> = {
  RETURN_REFUND: '退货退款',
  REFUND_ONLY: '仅退款',
}

export const REASON_LABELS: Record<AftersaleReason, string> = {
  NO_REASON_7D: '七天无理由（未拆封）',
  NOT_AS_DESCRIBED: '商品与描述不符',
  DAMAGED: '商品有损坏',
  NOT_WANTED: '不想要了（未发货）',
}

export const STATUS_LABELS: Record<AftersaleStatus, string> = {
  PENDING_REVIEW: '待处理',
  AWAITING_RETURN: '待买家寄回',
  RETURN_SHIPPED: '待收货',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  REFUND_FAILED: '退款异常',
  REJECTED: '已拒绝',
  RECEIPT_REJECTED: '验货不通过',
  REVOKED: '买家已撤销',
  CLOSED: '逾期未寄回，已关闭',
}

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/** 列表和详情里状态标签的颜色：等商家动手的用橙色，结束的灰色，退款成功绿色，异常红色。 */
export const STATUS_TAGS: Record<AftersaleStatus, TagType> = {
  PENDING_REVIEW: 'warning',
  AWAITING_RETURN: 'primary',
  RETURN_SHIPPED: 'warning',
  REFUNDING: 'primary',
  REFUNDED: 'success',
  REFUND_FAILED: 'danger',
  REJECTED: 'info',
  RECEIPT_REJECTED: 'info',
  REVOKED: 'info',
  CLOSED: 'info',
}

export const ACTION_LABELS: Record<AftersaleAction, string> = {
  SUBMIT: '买家申请售后',
  AGREE: '商家同意退货',
  AUTO_AGREE: '商家 48 小时未处理，自动同意',
  REJECT: '商家不同意',
  REVOKE: '买家撤销申请',
  SHIP_RETURN: '买家寄回商品，填了退货运单',
  EDIT_RETURN: '买家修改了退货运单',
  CLOSE_OVERDUE: '买家逾期未寄回，自动关闭',
  CONFIRM_RECEIPT: '商家确认收货，发起退款',
  AUTO_REFUND: '商家超时未验货，按申请金额自动退款',
  REJECT_RECEIPT: '商家验货不通过',
  REFUND_SUCCEEDED: '退款已到账',
  REFUND_FAILED: '退款异常，需要人工处理',
}

export const SLOT_LABELS: Record<AftersaleImageSlot, string> = {
  PRODUCT: '商品照片',
  PACKAGE: '包装照片',
  OTHER: '其他凭证',
}

export const REFUND_STATUS_LABELS: Record<AftersaleRefundStatus, string> = {
  REQUESTED: '待提交微信',
  PROCESSING: '微信处理中',
  SUCCEEDED: '已退回',
  CLOSED: '已关闭',
  ABNORMAL: '退款异常',
}

/** 处理时限说的是谁的时限、过了会怎样（时限本身是后端的设计假设：48 小时、7 天、10 天）。 */
export function dueHint(status: AftersaleStatus): string | null {
  if (status === 'PENDING_REVIEW') return '超时未处理自动同意'
  if (status === 'AWAITING_RETURN') return '买家逾期不寄回自动关闭'
  if (status === 'RETURN_SHIPPED') return '超时未验货按申请金额自动退款'
  return null
}

/** 列表页签：前四个各对应一个状态，“全部”不带 status。 */
export type AftersaleTabKey = 'PENDING_REVIEW' | 'AWAITING_RETURN' | 'RETURN_SHIPPED' | 'REFUNDING' | 'ALL'

export const AFTERSALE_TABS: ReadonlyArray<{ key: AftersaleTabKey, label: string, status?: AftersaleStatus }> = [
  { key: 'PENDING_REVIEW', label: '待处理', status: 'PENDING_REVIEW' },
  { key: 'AWAITING_RETURN', label: '待寄回', status: 'AWAITING_RETURN' },
  { key: 'RETURN_SHIPPED', label: '待收货', status: 'RETURN_SHIPPED' },
  { key: 'REFUNDING', label: '退款中', status: 'REFUNDING' },
  { key: 'ALL', label: '全部' },
]

export interface AftersaleFilters {
  tab: AftersaleTabKey
}

export function tabStatus(tab: AftersaleTabKey): AftersaleStatus | undefined {
  return AFTERSALE_TABS.find((candidate) => candidate.key === tab)?.status
}

// ---- 表单校验（长度、格式和契约一致，长度按码点算、去掉首尾空白） ----

export const LIMITS = {
  contactName: 40,
  address: 200,
  note: 200,
  rejectReason: 100,
} as const

export const PHONE_PATTERN = /^[0-9+-]{5,20}$/

export interface FieldErrors {
  contactName?: string
  phone?: string
  address?: string
  note?: string
}

/** 寄回地址（同意时、设默认寄回地址时）：收件人、电话、地址必填，说明可空。没有问题返回空对象。 */
export function returnAddressErrors(draft: ReturnAddressDraft): FieldErrors {
  const errors: FieldErrors = {}
  const contactName = draft.contactName.trim()
  const phone = draft.phone.trim()
  const address = draft.address.trim()
  if (!contactName) errors.contactName = '请填写寄回收件人'
  else if (charCount(contactName) > LIMITS.contactName) errors.contactName = `收件人最多 ${LIMITS.contactName} 个字`
  if (!phone) errors.phone = '请填写联系电话'
  else if (!PHONE_PATTERN.test(phone)) errors.phone = '电话是 5–20 位数字，可以带 + 和 -'
  if (!address) errors.address = '请填写寄回地址'
  else if (charCount(address) > LIMITS.address) errors.address = `地址最多 ${LIMITS.address} 个字`
  if (charCount(draft.note.trim()) > LIMITS.note) errors.note = `说明最多 ${LIMITS.note} 个字`
  return errors
}

export interface RejectErrors {
  reason?: string
  note?: string
}

/** 拒绝（审核时）：原因必填 ≤100，说明可空 ≤200。验货不通过：原因、说明都必填（说明写寄回的商品怎么处理）。 */
export function rejectErrors(reason: string, note: string, noteRequired: boolean): RejectErrors {
  const errors: RejectErrors = {}
  const reasonText = reason.trim()
  const noteText = note.trim()
  if (!reasonText) errors.reason = '请填写原因（买家能看到）'
  else if (charCount(reasonText) > LIMITS.rejectReason) errors.reason = `原因最多 ${LIMITS.rejectReason} 个字`
  if (noteRequired && !noteText) errors.note = '请写明寄回的商品怎么处理（比如原样寄回买家，运费谁出）'
  else if (charCount(noteText) > LIMITS.note) errors.note = `说明最多 ${LIMITS.note} 个字`
  return errors
}

/** 确认收货的退款金额：大于 0、不超过申请金额。amountMinor 为 null 表示输入格式不对。 */
export function receiptAmountError(amountMinor: number | null, requestedMinor: number): string | null {
  if (amountMinor === null) return '请输入金额，最多两位小数，比如 41 或 41.50'
  if (amountMinor <= 0) return '退款金额要大于 0'
  if (amountMinor > requestedMinor) return '退款金额不能超过买家的申请金额'
  return null
}

/** 少于申请金额时说明必填（比如少了配件）。 */
export function receiptNoteError(amountMinor: number | null, requestedMinor: number, note: string): string | null {
  const text = note.trim()
  if (amountMinor !== null && amountMinor < requestedMinor && !text) return '少退时要写明原因，比如缺少配件'
  if (charCount(text) > LIMITS.note) return `说明最多 ${LIMITS.note} 个字`
  return null
}

function blankToNull(value: string): string | null {
  const text = value.trim()
  return text === '' ? null : text
}

export function returnAddressBody(draft: ReturnAddressDraft) {
  return {
    contactName: draft.contactName.trim(),
    phone: draft.phone.trim(),
    address: draft.address.trim(),
    note: blankToNull(draft.note),
  }
}

export function approveBody(draft: ApproveDraft) {
  return { ...returnAddressBody(draft), saveAsDefault: draft.saveAsDefault }
}

/** 版本冲突或状态变了（412、409）：提示后页面要重新读一次详情。 */
export function needsRefresh(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 412 || error.status === 409)
}

registerErrorMessages({
  // 交易模块的渠道错误（openapi-trade.yaml 也有），确认收货退款时可能遇到
  TRADE_CHANNEL_UNAVAILABLE: '微信支付暂时连不上，请稍后再试',
  TRADE_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
})

export function createAftersaleApi(http: ApiClient) {
  const base = '/api/v1/provider/trade/aftersales'
  const one = (aftersaleId: string) => `${base}/${encodeURIComponent(aftersaleId)}`
  const returnAddressPath = '/api/v1/provider/trade/return-address'
  type Target = Pick<Aftersale, 'aftersaleId' | 'version'>
  return {
    list(filters: AftersaleFilters, page: number, size: number): Promise<AftersalePage> {
      return http.get<AftersalePage>(base, { status: tabStatus(filters.tab), page, size })
    },
    detail(aftersaleId: string): Promise<Aftersale> {
      return http.get<Aftersale>(one(aftersaleId))
    },
    approve(target: Target, draft: ApproveDraft): Promise<Aftersale> {
      return http.post<Aftersale>(`${one(target.aftersaleId)}/approval`, approveBody(draft), { ifMatch: target.version })
    },
    reject(target: Target, reason: string, note: string): Promise<Aftersale> {
      return http.post<Aftersale>(`${one(target.aftersaleId)}/rejection`,
        { reason: reason.trim(), note: blankToNull(note) }, { ifMatch: target.version })
    },
    confirmReceipt(target: Target, refundMinor: number, note: string): Promise<Aftersale> {
      return http.post<Aftersale>(`${one(target.aftersaleId)}/receipt`,
        { refundMinor, note: blankToNull(note) }, { ifMatch: target.version })
    },
    rejectReceipt(target: Target, reason: string, note: string): Promise<Aftersale> {
      return http.post<Aftersale>(`${one(target.aftersaleId)}/receipt-rejection`,
        { reason: reason.trim(), note: blankToNull(note) }, { ifMatch: target.version })
    },
    /** 凭证图片不公开：带登录令牌取回二进制，页面转成 blob 地址显示。 */
    image(aftersaleId: string, imageId: string): Promise<{ blob: Blob, fileName: string | null }> {
      return http.download(`${one(aftersaleId)}/images/${encodeURIComponent(imageId)}`)
    },
    /** 默认寄回地址；没设过时返回 null（后端 404）。 */
    async returnAddress(): Promise<ReturnAddress | null> {
      try {
        return await http.get<ReturnAddress>(returnAddressPath)
      } catch (failure) {
        if (failure instanceof ApiRequestError && failure.status === 404) return null
        throw failure
      }
    },
    saveReturnAddress(draft: ReturnAddressDraft): Promise<ReturnAddress> {
      return http.put<ReturnAddress>(returnAddressPath, returnAddressBody(draft))
    },
  }
}

export type AftersaleApi = ReturnType<typeof createAftersaleApi>
