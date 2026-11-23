import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-mall-inquiry.yaml 1.1.0 的商家接口：发给本机构的询价单、报价 / 改价、拒绝。
// 商家只看到商品、配置说明、数量、城市和日期，看不到客户的身份和联系方式。

export type MallInquiryStatus = 'OPEN' | 'QUOTED' | 'CHOSEN' | 'CLOSED'
export type InvitationStatus = 'INVITED' | 'QUOTED' | 'DECLINED'
export type QuoteStatus = 'ACTIVE' | 'EXPIRED' | 'WITHDRAWN' | 'CHOSEN' | 'LAPSED'

export interface MallMerchantQuote {
  quoteId: string
  version: number
  status: QuoteStatus
  unitAmountMinor: number
  quantity: number
  totalMinor: number
  model: string
  leadDays: number
  includes: string | null
  validUntil: string
  quotedAt: string
}

export interface MallMerchantInquiry {
  inquiryId: string
  status: MallInquiryStatus
  product: { productId: string, name: string, coverImage: { url: string, alt: string } | null }
  offerId: string
  requirement: string
  quantity: number
  cityCode: string
  expectedDate: string | null
  submittedAt: string
  quoteDeadline: string
  /** 本机构现在还能报价、改价（询价单待报价或报价中、没过截止时间、本机构没拒绝）。 */
  quotingOpen: boolean
  invitationStatus: InvitationStatus
  declineReason: string | null
  myQuote: MallMerchantQuote | null
}

export type MallMerchantInquiryPage = Page<MallMerchantInquiry>

export interface InquiryFilters {
  status: MallInquiryStatus | ''
}

export const INQUIRY_STATUS_OPTIONS: ReadonlyArray<{ value: MallInquiryStatus, label: string }> = [
  { value: 'OPEN', label: '待报价' },
  { value: 'QUOTED', label: '报价中' },
  { value: 'CHOSEN', label: '已选定' },
  { value: 'CLOSED', label: '已关闭' },
]

export function inquiryStatusLabel(status: MallInquiryStatus): string {
  return INQUIRY_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? '状态未知'
}

export const INVITATION_STATUS_LABELS: Record<InvitationStatus, string> = {
  INVITED: '待我报价',
  QUOTED: '已报价',
  DECLINED: '已拒绝',
}

export const QUOTE_STATUS_LABELS: Record<QuoteStatus, string> = {
  ACTIVE: '有效',
  EXPIRED: '已过有效期',
  WITHDRAWN: '已撤回',
  CHOSEN: '客户已选中',
  LAPSED: '未被选中（已失效）',
}

// ---- 能做什么（和后端 MallInquiryService 一致，后端会再判断） ----

/** 报价、改价：看服务端算好的 quotingOpen。第一次是“报价”，有报价后是“改价”。 */
export function quoteAction(inquiry: Pick<MallMerchantInquiry, 'quotingOpen' | 'myQuote'>): '报价' | '改价' | null {
  if (!inquiry.quotingOpen) return null
  return inquiry.myQuote ? '改价' : '报价'
}

/** 拒绝：询价单还在待报价或报价中、本机构还没拒绝（截止之后也能拒绝，报过的随之撤回）。 */
export function canDecline(inquiry: Pick<MallMerchantInquiry, 'status' | 'invitationStatus'>): boolean {
  return (inquiry.status === 'OPEN' || inquiry.status === 'QUOTED') && inquiry.invitationStatus !== 'DECLINED'
}

// ---- 报价表单校验（限制和契约 MallInquiryQuoteRequest 一致） ----

export const MODEL_MAX = 60
export const INCLUDES_MAX = 120
export const DECLINE_REASON_MAX = 80
export const LEAD_DAYS_MAX = 365
export const UNIT_AMOUNT_MAX_MINOR = 10_000_000_000
export const QUOTE_VALIDITY_MAX_MS = 7 * 24 * 60 * 60 * 1000

export interface QuoteDraft {
  /** 单价（分）；输入格式不对时为 null。 */
  unitAmountMinor: number | null
  model: string
  leadDays: number | null
  includes: string
  /** 有效期截止时间（ISO 字符串）。 */
  validUntil: string | null
}

export type QuoteErrors = Partial<Record<'unitAmount' | 'model' | 'leadDays' | 'includes' | 'validUntil', string>>

export function quoteErrors(draft: QuoteDraft, now: Date = new Date()): QuoteErrors {
  const errors: QuoteErrors = {}
  if (draft.unitAmountMinor === null) errors.unitAmount = '请输入单价，最多两位小数'
  else if (draft.unitAmountMinor < 1) errors.unitAmount = '单价要大于 0'
  else if (draft.unitAmountMinor > UNIT_AMOUNT_MAX_MINOR) errors.unitAmount = '单价超过上限'
  const model = draft.model.trim()
  if (!model) errors.model = '请填写型号配置'
  else if (charCount(model) > MODEL_MAX) errors.model = `型号配置最多 ${MODEL_MAX} 个字`
  if (draft.leadDays === null || !Number.isInteger(draft.leadDays) || draft.leadDays < 1
    || draft.leadDays > LEAD_DAYS_MAX) {
    errors.leadDays = `交期写 1–${LEAD_DAYS_MAX} 天`
  }
  if (charCount(draft.includes.trim()) > INCLUDES_MAX) errors.includes = `包含内容最多 ${INCLUDES_MAX} 个字`
  const until = draft.validUntil ? new Date(draft.validUntil).getTime() : Number.NaN
  if (Number.isNaN(until)) errors.validUntil = '请选择报价有效期'
  else if (until <= now.getTime()) errors.validUntil = '有效期要晚于现在'
  else if (until > now.getTime() + QUOTE_VALIDITY_MAX_MS) errors.validUntil = '有效期最长 7 天'
  return errors
}

export function declineReasonError(reason: string): string | null {
  const value = reason.trim()
  if (!value) return '请写明拒绝原因'
  if (charCount(value) > DECLINE_REASON_MAX) return `拒绝原因最多 ${DECLINE_REASON_MAX} 个字`
  return null
}

registerErrorMessages({
  MALL_INQUIRY_STATE_INVALID: '询价单已选定或已关闭，或者本机构已经拒绝过，不能再报价',
  MALL_INQUIRY_QUOTING_CLOSED: '这张询价单已过报价截止时间',
  MALL_INQUIRY_VERSION_CONFLICT: '询价单刚刚有变化，请刷新后重试',
  MALL_INQUIRY_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
})

export function createMallInquiryApi(http: ApiClient) {
  const base = '/api/v1/provider/mall/inquiries'
  const one = (inquiryId: string) => `${base}/${encodeURIComponent(inquiryId)}`
  return {
    list(filters: InquiryFilters, page: number, size: number): Promise<MallMerchantInquiryPage> {
      return http.get<MallMerchantInquiryPage>(base, { status: filters.status, page, size })
    },
    detail(inquiryId: string): Promise<MallMerchantInquiry> {
      return http.get<MallMerchantInquiry>(one(inquiryId))
    },
    /** 报价或改价（每次是新的一版；和最新一版一样时没有变化）。只在 quoteErrors 没有错误时调。 */
    quote(inquiryId: string, draft: QuoteDraft): Promise<MallMerchantInquiry> {
      const includes = draft.includes.trim()
      return http.put<MallMerchantInquiry>(`${one(inquiryId)}/quote`, {
        unitAmountMinor: draft.unitAmountMinor,
        model: draft.model.trim(),
        leadDays: draft.leadDays,
        includes: includes === '' ? null : includes,
        validUntil: draft.validUntil,
      })
    },
    decline(inquiryId: string, reason: string): Promise<MallMerchantInquiry> {
      return http.post<MallMerchantInquiry>(`${one(inquiryId)}/decline`, { reason: reason.trim() })
    },
  }
}

export type MallInquiryApi = ReturnType<typeof createMallInquiryApi>
