import type { FormItemRule, FormRules } from 'element-plus'

import type { ApiClient } from '@/core/http/api-client'
import { charCount } from '@/core/ui/format'
import { maxChars, required } from '@/core/ui/form-rules'

// 契约 contracts/openapi-mall-merchant.yaml 1.0.0 的工作台接口：本机构的商家资料（readMyMerchantProfile、
// submitMyMerchantProfile）。商家填、提交，平台运营审核（openapi-ops-merchant-profile.yaml）；对外只展示审核通过的那一版，
// 待审核时再交一版，上一版作废（SUPERSEDED）。机构只从令牌里取。

export type MerchantSubmissionStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'

/** 审核通过、对外展示的资料。 */
export interface MallMerchantProfile {
  businessEntity: string
  intro: string | null
  saleScopeNote: string | null
  /** 授权（合作）有效期，日期 YYYY-MM-DD。 */
  authorizationUntil: string | null
  categoryNote: string | null
  invoiceIssuer: string | null
  aftersaleParty: string | null
  approvedAt: string
}

export interface MallMerchantDraft {
  businessEntity: string
  intro: string | null
  saleScopeNote: string | null
  authorizationUntil: string | null
  categoryNote: string | null
  invoiceIssuer: string | null
  aftersaleParty: string | null
}

export interface MallMerchantSubmission {
  submissionId: string
  organizationId: string
  organizationName: string
  draft: MallMerchantDraft
  status: MerchantSubmissionStatus
  submittedByName: string
  submittedAt: string
  reviewedByName: string | null
  reviewedAt: string | null
  rejectReason: string | null
  version: number
}

export interface MallMerchantProfileState {
  organizationId: string
  organizationName: string
  /** 对外的资料；没通过过时为 null。 */
  published: MallMerchantProfile | null
  /** 最近一次提交；没交过时为 null。 */
  latest: MallMerchantSubmission | null
}

/** 表单里的一版（全是字符串，空串表示不填；授权有效期是 YYYY-MM-DD 或空串）。 */
export interface MerchantProfileForm {
  businessEntity: string
  intro: string
  saleScopeNote: string
  authorizationUntil: string
  categoryNote: string
  invoiceIssuer: string
  aftersaleParty: string
}

export const SUBMISSION_STATUS_LABELS: Record<MerchantSubmissionStatus, string> = {
  SUBMITTED: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SUPERSEDED: '已被新的一版替换',
}

export const SUBMISSION_STATUS_TAGS: Record<MerchantSubmissionStatus, 'warning' | 'success' | 'danger' | 'info'> = {
  SUBMITTED: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  SUPERSEDED: 'info',
}

/** 各项的中文名（详情对照、表单标签共用）。 */
export const PROFILE_FIELD_LABELS: Record<keyof MallMerchantDraft, string> = {
  businessEntity: '经营主体',
  intro: '简介',
  saleScopeNote: '可售说明',
  authorizationUntil: '授权有效期',
  categoryNote: '品类',
  invoiceIssuer: '默认开票方',
  aftersaleParty: '默认售后责任方',
}

export const PROFILE_FIELDS = Object.keys(PROFILE_FIELD_LABELS) as Array<keyof MallMerchantDraft>

export const PROFILE_LIMITS = {
  businessEntityMin: 2,
  businessEntity: 160,
  intro: 300,
  saleScopeNote: 40,
  categoryNote: 40,
  invoiceIssuer: 60,
  aftersaleParty: 60,
} as const

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

function minChars(label: string, min: number): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? value.trim() : ''
      callback(text !== '' && charCount(text) < min ? new Error(`${label}至少 ${min} 个字`) : undefined)
    },
  }
}

const dateRule: FormItemRule = {
  trigger: 'change',
  validator: (_rule, value: unknown, callback) => {
    const text = typeof value === 'string' ? value : ''
    callback(text !== '' && !DATE_PATTERN.test(text) ? new Error('授权有效期要是一个日期') : undefined)
  },
}

export const merchantProfileRules: FormRules = {
  businessEntity: [required('经营主体'), minChars('经营主体', PROFILE_LIMITS.businessEntityMin),
    maxChars('经营主体', PROFILE_LIMITS.businessEntity)],
  intro: [maxChars('简介', PROFILE_LIMITS.intro)],
  saleScopeNote: [maxChars('可售说明', PROFILE_LIMITS.saleScopeNote)],
  authorizationUntil: [dateRule],
  categoryNote: [maxChars('品类', PROFILE_LIMITS.categoryNote)],
  invoiceIssuer: [maxChars('默认开票方', PROFILE_LIMITS.invoiceIssuer)],
  aftersaleParty: [maxChars('默认售后责任方', PROFILE_LIMITS.aftersaleParty)],
}

/** 表单的初值：最近一次提交（待审核或被驳回的要接着改）→ 对外的资料 → 只有机构名称。 */
export function formFrom(state: MallMerchantProfileState): MerchantProfileForm {
  const source: MallMerchantDraft | null = state.latest?.draft ?? state.published ?? null
  return {
    businessEntity: source?.businessEntity ?? state.organizationName,
    intro: source?.intro ?? '',
    saleScopeNote: source?.saleScopeNote ?? '',
    authorizationUntil: source?.authorizationUntil ?? '',
    categoryNote: source?.categoryNote ?? '',
    invoiceIssuer: source?.invoiceIssuer ?? '',
    aftersaleParty: source?.aftersaleParty ?? '',
  }
}

function blankToNull(value: string): string | null {
  const text = value.trim()
  return text === '' ? null : text
}

/** 提交的请求体（MerchantProfileRequest）：去掉首尾空白，空的写 null。 */
export function profileRequestBody(form: MerchantProfileForm) {
  return {
    businessEntity: form.businessEntity.trim(),
    intro: blankToNull(form.intro),
    saleScopeNote: blankToNull(form.saleScopeNote),
    authorizationUntil: blankToNull(form.authorizationUntil),
    categoryNote: blankToNull(form.categoryNote),
    invoiceIssuer: blankToNull(form.invoiceIssuer),
    aftersaleParty: blankToNull(form.aftersaleParty),
  }
}

/** 详情、对照里一项的显示文字：空的写“未填”。 */
export function fieldText(value: string | null | undefined): string {
  return value === null || value === undefined || value === '' ? '未填' : value
}

export function createMerchantProfileApi(http: ApiClient) {
  const path = '/api/v1/provider/mall/merchant-profile'
  return {
    read(): Promise<MallMerchantProfileState> {
      return http.get<MallMerchantProfileState>(path)
    },
    submit(form: MerchantProfileForm): Promise<MallMerchantProfileState> {
      return http.put<MallMerchantProfileState>(path, profileRequestBody(form))
    },
  }
}

export type MerchantProfileApi = ReturnType<typeof createMerchantProfileApi>
