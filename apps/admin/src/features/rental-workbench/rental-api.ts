import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount, minorToYuanText, yuanToMinor } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-rental.yaml 1.1.0 的出租方接口（RentalMerchantController）：询价单报价、改价、拒绝；
// 租赁订单录入交接、归还，交接前取消并整单原路退款。这些接口不带 Idempotency-Key 和 If-Match：
// 重复提交同样的内容没有变化，状态不对的回 409 RENTAL_STATE_INVALID。机构只从令牌里取。

registerErrorMessages({
  RENTAL_STATE_INVALID: '这份询价单或订单的状态已经变了，请刷新后再看',
  RENTAL_REFUND_NOT_ALLOWED: '这张订单现在不能退款（交易订单的状态不允许），请刷新后再看',
  RENTAL_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请刷新后重新填写',
  RENTAL_VERSION_CONFLICT: '数据刚被修改过，请刷新后重试',
  RENTAL_QUOTE_EXPIRED: '报价已过有效期',
  RENTAL_QUOTE_CHANGED: '报价已经改过，请刷新后再看',
  RENTAL_SCHEDULE_UNAVAILABLE: '这段时间档期不可用',
  RENTAL_PAYMENT_UNAVAILABLE: '本机构还没开通在线收款',
})

export type RentalInquiryStatus = 'PENDING' | 'QUOTED' | 'EXPIRED' | 'CONFIRMED' | 'DECLINED' | 'CLOSED'
export type RentalOrderStatus = 'PENDING_PAYMENT' | 'CLOSED' | 'AWAITING_HANDOVER' | 'HANDOVER_RECORDED' | 'IN_USE'
  | 'RETURN_RECORDED' | 'ENDED' | 'CANCELLED'
export type RentalHandoverMode = 'DELIVERY' | 'PICKUP'
export type RentalReturnMode = 'COLLECT' | 'DROP_OFF'
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface RentalLessee {
  kind: 'ORGANIZATION' | 'PERSONAL'
  organizationName: string | null
  contactName: string
}

export interface RentalMerchant {
  merchantId: string
  name: string
  qualificationReviewed: boolean
}

export interface RentalItem {
  offerId: string
  productId: string
  productName: string
  skuName: string
  coverUrl: string | null
  referenceAmountMinor: number | null
  referenceUnit: 'DAY' | 'HALF_DAY' | 'ITEM'
}

export interface RentalPlace {
  addressId: string | null
  recipientName: string
  /** 客户付款之前是打码的。 */
  phone: string
  provinceName: string
  cityCode: string
  cityName: string
  detail: string
}

export interface RentalFee {
  name: string
  note: string | null
  amountMinor: number
}

export interface RentalQuote {
  version: number
  dailyRentMinor: number
  days: number
  quantity: number
  rentMinor: number
  fees: RentalFee[]
  totalMinor: number
  scheduleAvailable: boolean
  validUntil: string
  expired: boolean
  terms: string
  quotedAt: string
}

export interface RentalInquiry {
  inquiryId: string
  status: RentalInquiryStatus
  closeReason: 'CUSTOMER' | 'NO_QUOTE' | null
  declineReason: string | null
  lessee: RentalLessee
  merchant: RentalMerchant
  item: RentalItem
  quantity: number
  startDate: string
  endDate: string
  days: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  place: RentalPlace
  note: string | null
  submittedAt: string
  quoteDeadline: string
  quote: RentalQuote | null
  confirmable: boolean
  closable: boolean
  order: { rentalOrderId: string, status: RentalOrderStatus, tradeOrderId: string } | null
  updatedAt: string
  version: number
}

export interface RentalContract {
  contractNo: string
  confirmedAt: string
  quoteVersion: number
  lessee: RentalLessee
  lessor: RentalMerchant
  productName: string
  skuName: string
  quantity: number
  startDate: string
  endDate: string
  days: number
  dailyRentMinor: number
  rentMinor: number
  fees: RentalFee[]
  totalMinor: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  place: RentalPlace
  terms: string
}

export interface RentalRecord {
  occurredAt: string
  note: string
  recordedAt: string
  /** 客户确认的时间；null 表示等客户在 App 里确认。 */
  confirmedAt: string | null
}

export type RentalRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface RentalOrder {
  rentalOrderId: string
  inquiryId: string
  status: RentalOrderStatus
  contract: RentalContract
  payment: { tradeOrderId: string, amountMinor: number, payBefore: string, paidAt: string | null }
  handoverRecord: RentalRecord | null
  returnRecord: RentalRecord | null
  rentStartedAt: string | null
  endedAt: string | null
  closedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  refund: { refundId: string, amountMinor: number, status: RentalRefundStatus } | null
  handoverConfirmable: boolean
  returnConfirmable: boolean
  createdAt: string
  updatedAt: string
  version: number
}

export interface RentalQuoteRequest {
  dailyRentMinor: number
  days: number
  quantity: number
  fees: RentalFee[]
  scheduleAvailable: boolean
  validUntil: string
  terms: string
}

export interface RentalRecordRequest {
  occurredAt: string | null
  note: string
}

export interface InquiryFilters {
  status: RentalInquiryStatus | ''
}

export interface OrderFilters {
  status: RentalOrderStatus | ''
}

// ---- 中文说明 ----

export const INQUIRY_STATUS: Record<RentalInquiryStatus, { label: string, tag: TagType }> = {
  PENDING: { label: '待报价', tag: 'warning' },
  QUOTED: { label: '已报价，等客户确认', tag: 'primary' },
  EXPIRED: { label: '报价已过期', tag: 'info' },
  CONFIRMED: { label: '客户已确认合同', tag: 'success' },
  DECLINED: { label: '已拒绝', tag: 'info' },
  CLOSED: { label: '已关闭', tag: 'info' },
}

export const INQUIRY_STATUS_OPTIONS = (Object.keys(INQUIRY_STATUS) as RentalInquiryStatus[])
  .map((value) => ({ value, label: INQUIRY_STATUS[value].label }))

export const ORDER_STATUS: Record<RentalOrderStatus, { label: string, tag: TagType }> = {
  PENDING_PAYMENT: { label: '等客户付款', tag: 'warning' },
  CLOSED: { label: '未付款已关闭', tag: 'info' },
  AWAITING_HANDOVER: { label: '待交接', tag: 'warning' },
  HANDOVER_RECORDED: { label: '已录交接，等客户确认', tag: 'primary' },
  IN_USE: { label: '使用中', tag: 'success' },
  RETURN_RECORDED: { label: '已录归还，等客户确认', tag: 'primary' },
  ENDED: { label: '已结束', tag: 'success' },
  CANCELLED: { label: '已取消，租金原路退回', tag: 'info' },
}

export const ORDER_STATUS_OPTIONS = (Object.keys(ORDER_STATUS) as RentalOrderStatus[])
  .map((value) => ({ value, label: ORDER_STATUS[value].label }))

export const HANDOVER_MODE_LABELS: Record<RentalHandoverMode, string> = {
  DELIVERY: '商家送达',
  PICKUP: '到店自提',
}

export const RETURN_MODE_LABELS: Record<RentalReturnMode, string> = {
  COLLECT: '商家上门取回',
  DROP_OFF: '送回门店',
}

export const REFERENCE_UNIT_LABELS: Record<RentalItem['referenceUnit'], string> = {
  DAY: '/天',
  HALF_DAY: '/半天',
  ITEM: '/件',
}

export const CLOSE_REASON_LABELS: Record<'CUSTOMER' | 'NO_QUOTE', string> = {
  CUSTOMER: '客户关闭了询价',
  NO_QUOTE: '提交 7 天内没有报价，自动关闭',
}

export const REFUND_STATUS_LABELS: Record<RentalRefundStatus, string> = {
  REQUESTED: '已提交，等渠道受理',
  PROCESSING: '退款中',
  SUCCEEDED: '已原路退回',
  CLOSED: '退款已关闭',
  ABNORMAL: '退款异常，请联系平台',
}

export function lesseeLabel(lessee: RentalLessee): string {
  return lessee.kind === 'ORGANIZATION' && lessee.organizationName
    ? `${lessee.organizationName}（经办人 ${lessee.contactName}）`
    : `${lessee.contactName}（个人）`
}

export function placeLabel(place: RentalPlace): string {
  return `${place.provinceName}${place.cityName}${place.detail}`
}

// ---- 什么时候能做什么（和服务端的状态规则一致，服务端仍然会再判断） ----

/** 待报价、待确认、报价已过期的询价单可以报价、改价，也可以拒绝。 */
export function canQuote(inquiry: Pick<RentalInquiry, 'status'>): boolean {
  return inquiry.status === 'PENDING' || inquiry.status === 'QUOTED' || inquiry.status === 'EXPIRED'
}

export const canDecline = canQuote

/** 待交接时录入交接；客户确认之前可以再录（覆盖）。 */
export function canRecordHandover(order: Pick<RentalOrder, 'status'>): boolean {
  return order.status === 'AWAITING_HANDOVER' || order.status === 'HANDOVER_RECORDED'
}

/** 使用中录入归还；客户确认之前可以再录。 */
export function canRecordReturn(order: Pick<RentalOrder, 'status'>): boolean {
  return order.status === 'IN_USE' || order.status === 'RETURN_RECORDED'
}

/** 交接之前（含已录交接、客户还没确认）可以取消，租金整单原路退回。 */
export function canCancel(order: Pick<RentalOrder, 'status'>): boolean {
  return order.status === 'AWAITING_HANDOVER' || order.status === 'HANDOVER_RECORDED'
}

// ---- 报价表单 ----

export const QUOTE_LIMITS = {
  days: 90,
  quantity: 10,
  fees: 5,
  feeName: 20,
  feeNote: 40,
  terms: 2000,
  reason: 80,
  recordNote: 200,
  validDays: 7,
  maxDailyRentMinor: 10_000_000_000,
} as const

export interface FeeForm {
  /** 只给 v-for 用的本地编号。 */
  key: number
  name: string
  note: string
  amountYuan: string
}

export interface QuoteForm {
  dailyRentYuan: string
  days: number
  quantity: number
  fees: FeeForm[]
  scheduleAvailable: boolean
  validUntil: Date | null
  terms: string
}

const DAY_MS = 86_400_000

/** 改价时用最新一版预填；第一次报价用询价的天数、台数，有效期默认 3 天。 */
export function initialQuoteForm(inquiry: RentalInquiry, now: Date = new Date()): QuoteForm {
  const quote = inquiry.quote
  let key = 0
  return {
    dailyRentYuan: quote ? minorToYuanText(quote.dailyRentMinor) : '',
    days: quote?.days ?? inquiry.days,
    quantity: quote?.quantity ?? inquiry.quantity,
    fees: (quote?.fees ?? []).map((fee) => ({
      key: ++key,
      name: fee.name,
      note: fee.note ?? '',
      amountYuan: minorToYuanText(fee.amountMinor),
    })),
    scheduleAvailable: quote?.scheduleAvailable ?? true,
    validUntil: new Date(now.getTime() + 3 * DAY_MS),
    terms: quote?.terms ?? '',
  }
}

/** 页面上随手算的合计（分）：日租金 × 天数 × 台数 + 其他费用；有一项填得不对时为 null。 */
export function quoteTotals(form: QuoteForm): { rentMinor: number | null, feesMinor: number | null, totalMinor: number | null } {
  const daily = yuanToMinor(form.dailyRentYuan)
  const rentMinor = daily !== null && Number.isInteger(form.days) && Number.isInteger(form.quantity)
    ? daily * form.days * form.quantity
    : null
  let feesMinor: number | null = 0
  for (const fee of form.fees) {
    const amount = yuanToMinor(fee.amountYuan)
    if (amount === null) {
      feesMinor = null
      break
    }
    feesMinor += amount
  }
  return {
    rentMinor,
    feesMinor,
    totalMinor: rentMinor !== null && feesMinor !== null ? rentMinor + feesMinor : null,
  }
}

export type BuildResult<T> = { ok: true, request: T } | { ok: false, message: string }

/** 提交前按契约的限制检查一遍，返回请求体或第一条错误（服务端还会再校验）。 */
export function buildQuoteRequest(form: QuoteForm, now: Date = new Date()): BuildResult<RentalQuoteRequest> {
  const daily = yuanToMinor(form.dailyRentYuan)
  if (daily === null || daily < 1) return fail('日租金要填大于 0 的金额，最多两位小数')
  if (daily > QUOTE_LIMITS.maxDailyRentMinor) return fail('日租金超过上限')
  if (!Number.isInteger(form.days) || form.days < 1 || form.days > QUOTE_LIMITS.days) {
    return fail(`天数要在 1–${QUOTE_LIMITS.days} 天`)
  }
  if (!Number.isInteger(form.quantity) || form.quantity < 1 || form.quantity > QUOTE_LIMITS.quantity) {
    return fail(`台数要在 1–${QUOTE_LIMITS.quantity} 台`)
  }
  if (form.fees.length > QUOTE_LIMITS.fees) return fail(`其他费用最多 ${QUOTE_LIMITS.fees} 行`)
  const fees: RentalFee[] = []
  const names = new Set<string>()
  for (const fee of form.fees) {
    const name = fee.name.trim()
    if (!name) return fail('其他费用要写名称')
    if (charCount(name) > QUOTE_LIMITS.feeName) return fail(`费用名称最多 ${QUOTE_LIMITS.feeName} 个字`)
    if (name === '租金') return fail('其他费用不能叫“租金”')
    if (names.has(name)) return fail(`其他费用“${name}”重复了`)
    names.add(name)
    const note = fee.note.trim()
    if (charCount(note) > QUOTE_LIMITS.feeNote) return fail(`费用说明最多 ${QUOTE_LIMITS.feeNote} 个字`)
    const amount = yuanToMinor(fee.amountYuan)
    if (amount === null) return fail(`“${name}”的金额格式不对（可以填 0）`)
    fees.push({ name, note: note || null, amountMinor: amount })
  }
  if (!form.validUntil || Number.isNaN(form.validUntil.getTime())) return fail('请选报价有效期')
  if (form.validUntil.getTime() <= now.getTime()) return fail('报价有效期要晚于现在')
  if (form.validUntil.getTime() > now.getTime() + QUOTE_LIMITS.validDays * DAY_MS) {
    return fail(`报价有效期最长 ${QUOTE_LIMITS.validDays} 天`)
  }
  const terms = form.terms.trim()
  if (!terms) return fail('请填写合同条款')
  if (charCount(terms) > QUOTE_LIMITS.terms) return fail(`合同条款最多 ${QUOTE_LIMITS.terms} 个字`)
  return {
    ok: true,
    request: {
      dailyRentMinor: daily,
      days: form.days,
      quantity: form.quantity,
      fees,
      scheduleAvailable: form.scheduleAvailable,
      validUntil: form.validUntil.toISOString(),
      terms,
    },
  }
}

/** 拒绝、取消的原因：1–80 字。 */
export function reasonError(reason: string): string | null {
  const text = reason.trim()
  if (!text) return '请填写原因'
  if (charCount(text) > QUOTE_LIMITS.reason) return `原因最多 ${QUOTE_LIMITS.reason} 个字`
  return null
}

/** 交接、归还记录：说明 1–200 字；时间不填是现在，不能晚于现在。 */
export function buildRecordRequest(note: string, occurredAt: Date | null, now: Date = new Date()):
BuildResult<RentalRecordRequest> {
  const text = note.trim()
  if (!text) return fail('请写设备编号、配件、外观等说明')
  if (charCount(text) > QUOTE_LIMITS.recordNote) return fail(`说明最多 ${QUOTE_LIMITS.recordNote} 个字`)
  if (occurredAt && occurredAt.getTime() > now.getTime()) return fail('时间不能晚于现在')
  return { ok: true, request: { occurredAt: occurredAt ? occurredAt.toISOString() : null, note: text } }
}

function fail<T>(message: string): BuildResult<T> {
  return { ok: false, message }
}

// ---- 接口 ----

export function createRentalWorkbenchApi(http: ApiClient) {
  const inquiry = (id: string) => `/api/v1/provider/rental-inquiries/${encodeURIComponent(id)}`
  const order = (id: string) => `/api/v1/provider/rental-orders/${encodeURIComponent(id)}`
  return {
    inquiries(filters: InquiryFilters, page: number, size: number): Promise<Page<RentalInquiry>> {
      return http.get<Page<RentalInquiry>>('/api/v1/provider/rental-inquiries', { status: filters.status, page, size })
    },
    inquiry(id: string): Promise<RentalInquiry> {
      return http.get<RentalInquiry>(inquiry(id))
    },
    quote(id: string, request: RentalQuoteRequest): Promise<RentalInquiry> {
      return http.put<RentalInquiry>(`${inquiry(id)}/quote`, request)
    },
    decline(id: string, reason: string): Promise<RentalInquiry> {
      return http.post<RentalInquiry>(`${inquiry(id)}/decline`, { reason: reason.trim() })
    },
    orders(filters: OrderFilters, page: number, size: number): Promise<Page<RentalOrder>> {
      return http.get<Page<RentalOrder>>('/api/v1/provider/rental-orders', { status: filters.status, page, size })
    },
    order(id: string): Promise<RentalOrder> {
      return http.get<RentalOrder>(order(id))
    },
    recordHandover(id: string, request: RentalRecordRequest): Promise<RentalOrder> {
      return http.post<RentalOrder>(`${order(id)}/handover`, request)
    },
    recordReturn(id: string, request: RentalRecordRequest): Promise<RentalOrder> {
      return http.post<RentalOrder>(`${order(id)}/return`, request)
    },
    cancel(id: string, reason: string): Promise<RentalOrder> {
      return http.post<RentalOrder>(`${order(id)}/cancel`, { reason: reason.trim() })
    },
  }
}

export type RentalWorkbenchApi = ReturnType<typeof createRentalWorkbenchApi>
