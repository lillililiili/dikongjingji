import type { FormRules } from 'element-plus'

import type { ApiClient } from '@/core/http/api-client'
import { isApiError } from '@/core/http/api-error'
import { maxChars, required } from '@/core/ui/form-rules'
import type { Page } from '@/core/ui/use-paged-list'
import type {
  MallMerchantProfile,
  MallMerchantSubmission,
  MerchantSubmissionStatus,
} from '@/features/merchant-profile/merchant-profile-api'

// 契约 contracts/openapi-ops-merchant-profile.yaml 1.0.0 的商家资料审核（只给平台运营）。提交单、资料的形状和
// openapi-mall-merchant.yaml 相同，类型从工作台的商家资料那块拿。只能审核还在待审核、版本对得上的那一版：
// 已经处理过的 409 OPS_MERCHANT_PROFILE_REVIEW_CLOSED，版本不对 409 OPS_VERSION_CONFLICT。审核记录在提交单上。

export type {
  MallMerchantDraft,
  MallMerchantProfile,
  MallMerchantSubmission,
  MerchantSubmissionStatus,
} from '@/features/merchant-profile/merchant-profile-api'

export type MallMerchantSubmissionPage = Page<MallMerchantSubmission>

export interface MallMerchantSubmissionDetail {
  submission: MallMerchantSubmission
  /** 现在对外的资料；没通过过时为 null。 */
  published: MallMerchantProfile | null
  /** 机构资质审核过没有（机构与资质页审核，和这里的商家资料是两件事）。 */
  qualificationReviewed: boolean
  /** 这家最近 20 次提交。 */
  history: MallMerchantSubmission[]
}

export interface ReviewFilters {
  status: MerchantSubmissionStatus | ''
  keyword: string
}

export const REVIEW_STATUS_OPTIONS: ReadonlyArray<{ value: MerchantSubmissionStatus, label: string }> = [
  { value: 'SUBMITTED', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'SUPERSEDED', label: '已被新的一版替换' },
]

export const REVIEW_LIMITS = {
  keyword: 50,
  rejectReason: 200,
} as const

export const rejectReviewRules: FormRules = {
  reason: [required('驳回原因'), maxChars('驳回原因', REVIEW_LIMITS.rejectReason)],
}

/** 审核时“这一版已经被处理过 / 版本不对”：提示后要重新读一次详情。 */
export function reviewIsStale(error: unknown): boolean {
  return isApiError(error, 'OPS_MERCHANT_PROFILE_REVIEW_CLOSED') || isApiError(error, 'OPS_VERSION_CONFLICT')
}

export function createMerchantProfileReviewApi(http: ApiClient) {
  const base = '/api/v1/ops/mall/merchant-profiles'
  const one = (submissionId: string) => `${base}/${encodeURIComponent(submissionId)}`
  type Target = Pick<MallMerchantSubmission, 'submissionId' | 'version'>
  return {
    list(filters: ReviewFilters, page: number, size: number): Promise<MallMerchantSubmissionPage> {
      return http.get<MallMerchantSubmissionPage>(base, {
        status: filters.status || undefined,
        keyword: filters.keyword.trim() || undefined,
        page,
        size,
      })
    },
    detail(submissionId: string): Promise<MallMerchantSubmissionDetail> {
      return http.get<MallMerchantSubmissionDetail>(one(submissionId))
    },
    approve(target: Target): Promise<MallMerchantSubmissionDetail> {
      return http.post<MallMerchantSubmissionDetail>(`${one(target.submissionId)}/approval`,
        { version: target.version })
    },
    reject(target: Target, reason: string): Promise<MallMerchantSubmissionDetail> {
      return http.post<MallMerchantSubmissionDetail>(`${one(target.submissionId)}/rejection`,
        { version: target.version, reason: reason.trim() })
    },
  }
}

export type MerchantProfileReviewApi = ReturnType<typeof createMerchantProfileReviewApi>
