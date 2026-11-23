import type { ApiClient } from '@/core/http/api-client'
import { ApiRequestError } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'

// 契约 contracts/openapi-proservice.yaml 1.2.0 的 getMyProviderProfile、submitMyProviderProfile（/api/v1/provider/profile）：
// 本机构的服务商主页资料（经营主体、服务方式、授权有效期、可交付清单、本地团队标签，原型 ProviderPage）。
// 提交后去运营端“服务商资料审核”；通过前 App 主页仍显示上一份通过的。机构只从令牌里取；没有在用服务商资料的机构是 403。
// 提交带读到的 version（还没提交过是 0），不对时 409 PRO_PROVIDER_PROFILE_VERSION_CONFLICT（中文在 core/http/api-error.ts）。

export type ProviderProfileReviewStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface ProviderProfile {
  legalEntity: string
  serviceModes: string
  /** 授权有效期（到哪一天，yyyy-MM-dd）。 */
  authorizationUntil: string | null
  deliverables: string[]
  localTeamLabel: string | null
}

export interface ProviderProfileReview {
  providerId: string
  providerName: string
  status: ProviderProfileReviewStatus
  /** 主页上正在显示的（最近一次审核通过的）。 */
  published: ProviderProfile | null
  /** 最近提交的一份（PENDING、REJECTED 时有；APPROVED 时和 published 相同）。 */
  submitted: ProviderProfile | null
  rejectReason: string | null
  submittedAt: string | null
  reviewedAt: string | null
  version: number
}

export interface ProviderProfileRequest extends ProviderProfile {
  version: number
}

export const REVIEW_STATUS: Record<ProviderProfileReviewStatus, { label: string, tag: TagType }> = {
  NONE: { label: '还没提交过', tag: 'info' },
  PENDING: { label: '待审核', tag: 'warning' },
  APPROVED: { label: '已通过', tag: 'success' },
  REJECTED: { label: '被驳回', tag: 'danger' },
}

export const PROFILE_LIMITS = {
  legalEntity: 60,
  serviceModes: 40,
  deliverables: 8,
  deliverable: 12,
  localTeamLabel: 12,
} as const

/** 一份资料按页面顺序的几行（没填的显示“—”）；审核页并排对比也用这个顺序。 */
export const PROFILE_FIELDS: ReadonlyArray<{ key: keyof ProviderProfile, label: string }> = [
  { key: 'legalEntity', label: '经营主体' },
  { key: 'serviceModes', label: '服务方式' },
  { key: 'authorizationUntil', label: '授权有效期至' },
  { key: 'deliverables', label: '可交付' },
  { key: 'localTeamLabel', label: '本地团队标签' },
]

export function profileValue(profile: ProviderProfile | null, key: keyof ProviderProfile): string {
  if (!profile) return '—'
  const value = profile[key]
  if (Array.isArray(value)) return value.length > 0 ? value.join('、') : '—'
  return value ? String(value) : '—'
}

/** 这家机构没有在用的服务商资料（或不是服务商操作员）：读、写都是 403。 */
export function isNoProviderProfile(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 403
}

export interface ProfileForm {
  legalEntity: string
  serviceModes: string
  authorizationUntil: string | null
  deliverables: Array<{ label: string }>
  localTeamLabel: string
}

/** 用最近提交的一份预填（没有就用正在显示的）；都没有时是空的。 */
export function initialProfileForm(review: ProviderProfileReview | null): ProfileForm {
  const source = review?.submitted ?? review?.published ?? null
  return {
    legalEntity: source?.legalEntity ?? '',
    serviceModes: source?.serviceModes ?? '',
    authorizationUntil: source?.authorizationUntil ?? null,
    deliverables: (source?.deliverables ?? []).map((label) => ({ label })),
    localTeamLabel: source?.localTeamLabel ?? '',
  }
}

export type BuildResult<T> = { ok: true, request: T } | { ok: false, message: string }

function fail<T>(message: string): BuildResult<T> {
  return { ok: false, message }
}

/** 提交前按契约检查；today 是北京时间的今天（yyyy-MM-dd），授权有效期不能早于它。 */
export function buildProfileRequest(form: ProfileForm, version: number, today: string):
BuildResult<ProviderProfileRequest> {
  const legalEntity = form.legalEntity.trim()
  if (!legalEntity) return fail('请填写经营主体（营业执照上的名称）')
  if (charCount(legalEntity) > PROFILE_LIMITS.legalEntity) return fail(`经营主体最多 ${PROFILE_LIMITS.legalEntity} 个字`)
  const serviceModes = form.serviceModes.trim()
  if (!serviceModes) return fail('请填写服务方式')
  if (charCount(serviceModes) > PROFILE_LIMITS.serviceModes) return fail(`服务方式最多 ${PROFILE_LIMITS.serviceModes} 个字`)
  const authorizationUntil = form.authorizationUntil || null
  if (authorizationUntil !== null && authorizationUntil < today) return fail('授权有效期不能早于今天')
  if (form.deliverables.length > PROFILE_LIMITS.deliverables) return fail(`可交付最多 ${PROFILE_LIMITS.deliverables} 项`)
  const deliverables: string[] = []
  for (const item of form.deliverables) {
    const label = item.label.trim()
    if (!label) return fail('可交付有一项是空的')
    if (charCount(label) > PROFILE_LIMITS.deliverable) return fail(`可交付每项最多 ${PROFILE_LIMITS.deliverable} 个字`)
    if (deliverables.includes(label)) return fail(`可交付“${label}”重复了`)
    deliverables.push(label)
  }
  const localTeamLabel = form.localTeamLabel.trim()
  if (charCount(localTeamLabel) > PROFILE_LIMITS.localTeamLabel) {
    return fail(`本地团队标签最多 ${PROFILE_LIMITS.localTeamLabel} 个字`)
  }
  return {
    ok: true,
    request: {
      legalEntity,
      serviceModes,
      authorizationUntil,
      deliverables,
      localTeamLabel: localTeamLabel || null,
      version,
    },
  }
}

export function createProviderProfileApi(http: ApiClient) {
  const path = '/api/v1/provider/profile'
  return {
    get(): Promise<ProviderProfileReview> {
      return http.get<ProviderProfileReview>(path)
    },
    submit(request: ProviderProfileRequest): Promise<ProviderProfileReview> {
      return http.put<ProviderProfileReview>(path, request)
    },
  }
}

export type ProviderProfileApi = ReturnType<typeof createProviderProfileApi>
