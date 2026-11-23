import type { ApiClient } from '@/core/http/api-client'
import type { OpsChange } from '@/core/ops/ops-common'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'
import type { ProviderProfile, TagType } from '@/features/provider-profile/provider-profile-api'

// 契约 contracts/openapi-ops-proservice.yaml 1.1.0 的“服务商资料审核”（/api/v1/ops/provider-profiles…，只给平台运营）：
// 服务商在工作台提交主页资料，这里看待审核的、通过或驳回（驳回写原因）。通过后 App 服务商主页显示这一份；驳回后主页
// 仍显示上一份通过的。审核带详情里读到的 version，不对 409 OPS_VERSION_CONFLICT（服务商又提交了一份）；没有待审核的
// 409 OPS_STATUS_UNCHANGED。每次审核记一条改动记录。

export type OpsProviderProfileStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type ReviewDecision = 'APPROVE' | 'REJECT'

export interface OpsProviderProfileRow {
  providerId: string
  providerName: string
  status: OpsProviderProfileStatus
  submittedAt: string
  reviewedAt: string | null
}

export interface OpsProviderProfile {
  providerId: string
  providerName: string
  status: OpsProviderProfileStatus
  /** App 主页上正在显示的一份。 */
  published: ProviderProfile | null
  /** 最近提交的一份。 */
  submitted: ProviderProfile | null
  rejectReason: string | null
  submittedAt: string
  reviewedAt: string | null
  version: number
  changes: OpsChange[]
}

export interface ProviderProfileFilters {
  status: OpsProviderProfileStatus | ''
}

export const OPS_REVIEW_STATUS: ReadonlyArray<{ value: OpsProviderProfileStatus, label: string, tag: TagType }> = [
  { value: 'PENDING', label: '待审核', tag: 'warning' },
  { value: 'APPROVED', label: '已通过', tag: 'success' },
  { value: 'REJECTED', label: '已驳回', tag: 'danger' },
]

export function reviewStatus(status: OpsProviderProfileStatus) {
  return OPS_REVIEW_STATUS.find((option) => option.value === status) ?? OPS_REVIEW_STATUS[0]!
}

/** 改动记录的对象和动作（契约的 OpsChange 枚举里还没有这两样，后端给什么编码就按这里翻，没登记的原样显示）。 */
export const CHANGE_SUBJECT_LABELS: Readonly<Record<string, string>> = {
  PROVIDER_PROFILE: '服务商资料',
  PROFILE: '服务商资料',
}
export const CHANGE_ACTION_LABELS: Readonly<Record<string, string>> = {
  APPROVE: '通过',
  REJECT: '驳回',
  SUBMIT: '提交',
}

export const REJECT_REASON_MAX = 200

export function rejectReasonError(reason: string): string | null {
  const text = reason.trim()
  if (!text) return '请写驳回原因（服务商在工作台能看到）'
  if (charCount(text) > REJECT_REASON_MAX) return `驳回原因最多 ${REJECT_REASON_MAX} 个字`
  return null
}

/** 能审核：有一份待审核的。 */
export function canReview(profile: Pick<OpsProviderProfile, 'status' | 'submitted'>): boolean {
  return profile.status === 'PENDING' && profile.submitted !== null
}

export function createProviderProfileReviewApi(http: ApiClient) {
  const base = '/api/v1/ops/provider-profiles'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    list(filters: ProviderProfileFilters, page: number, size: number): Promise<Page<OpsProviderProfileRow>> {
      return http.get<Page<OpsProviderProfileRow>>(base, { status: filters.status, page, size })
    },
    detail(id: string): Promise<OpsProviderProfile> {
      return http.get<OpsProviderProfile>(one(id))
    },
    review(id: string, decision: ReviewDecision, reason: string, version: number): Promise<OpsProviderProfile> {
      const text = reason.trim()
      return http.post<OpsProviderProfile>(`${one(id)}/review`, {
        decision,
        reason: decision === 'REJECT' && text ? text : null,
        version,
      })
    },
  }
}

export type ProviderProfileReviewApi = ReturnType<typeof createProviderProfileReviewApi>
