import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-organization.yaml 1.1.0（S6-01 加了开通服务商资料）。

export type OrganizationType = 'PROVIDER' | 'CUSTOMER'
export type QualificationStatus = 'NOT_SUBMITTED' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'
export type ReviewDecision = 'APPROVE' | 'REJECT'
export type GrantableRole = 'PROVIDER_OPERATOR' | 'LIVE_HOST' | 'CUSTOMER'
export type OrganizationAction =
  | 'CREATE' | 'QUALIFICATION_SUBMIT' | 'QUALIFICATION_APPROVE' | 'QUALIFICATION_REJECT'
  | 'ATTACHMENT_ADD' | 'ATTACHMENT_REMOVE' | 'ACCOUNT_CREATE' | 'ROLES_CHANGE' | 'PROVIDER_PROFILE_OPEN'
/** 服务商资料（巡检模块的 inspection_provider_profile）：未开通 / 已开通 / 已停用。企业客户没有这一项。 */
export type ProviderProfileStatus = 'NOT_OPENED' | 'ACTIVE' | 'DISABLED'

export const ORGANIZATION_TYPE_OPTIONS: ReadonlyArray<{ value: OrganizationType, label: string, hint: string }> = [
  { value: 'PROVIDER', label: '经营机构', hint: '商家、服务商、活动主办、场馆运营' },
  { value: 'CUSTOMER', label: '企业客户', hint: '以企业身份下单、预约的客户' },
]

export const QUALIFICATION_STATUS_OPTIONS: ReadonlyArray<{
  value: QualificationStatus
  label: string
  tag: 'info' | 'warning' | 'success' | 'danger'
}> = [
  { value: 'NOT_SUBMITTED', label: '未提交', tag: 'info' },
  { value: 'SUBMITTED', label: '待审核', tag: 'warning' },
  { value: 'APPROVED', label: '已通过', tag: 'success' },
  { value: 'REJECTED', label: '已驳回', tag: 'danger' },
]

export const ACTION_LABELS: Record<OrganizationAction, string> = {
  CREATE: '新建机构',
  QUALIFICATION_SUBMIT: '提交资料',
  QUALIFICATION_APPROVE: '审核通过',
  QUALIFICATION_REJECT: '审核驳回',
  ATTACHMENT_ADD: '上传附件',
  ATTACHMENT_REMOVE: '移除附件',
  ACCOUNT_CREATE: '新建账号',
  ROLES_CHANGE: '修改角色',
  PROVIDER_PROFILE_OPEN: '开通服务商资料',
}

export const PROVIDER_PROFILE_STATUS_LABELS: Record<ProviderProfileStatus, { label: string, tag: 'info' | 'success' | 'warning' }> = {
  NOT_OPENED: { label: '未开通', tag: 'info' },
  ACTIVE: { label: '已开通', tag: 'success' },
  DISABLED: { label: '已停用', tag: 'warning' },
}

/**
 * “开通服务商资料”按钮能不能点、为什么不能点（和后端 OrganizationOnboardingService.openProviderProfile 的规则一致）：
 * 只有在用的经营机构，资质审核通过（有审核通过时间）后才能开通；已开通的不用再开。
 */
export function providerProfileOpening(detail: OpsOrganizationDetail):
{ visible: boolean, canOpen: boolean, reason: string | null } {
  const organization = detail.organization
  if (organization.type !== 'PROVIDER' || !detail.providerProfileStatus) {
    return { visible: false, canOpen: false, reason: null }
  }
  if (detail.providerProfileStatus === 'ACTIVE') {
    return { visible: true, canOpen: false, reason: '已开通' }
  }
  if (organization.status !== 'ACTIVE') {
    return { visible: true, canOpen: false, reason: '机构已停用，不能开通' }
  }
  if (!organization.qualificationReviewedAt) {
    return { visible: true, canOpen: false, reason: '资质审核通过后才能开通' }
  }
  return { visible: true, canOpen: true, reason: null }
}

export const GRANTABLE_ROLE_LABELS: Record<GrantableRole, { label: string, hint: string }> = {
  PROVIDER_OPERATOR: { label: '服务商操作员', hint: '报价、发货、核销、回复客服等日常操作' },
  LIVE_HOST: { label: '直播主办', hint: '开通直播：建直播间、改预告、隐藏本机构弹幕' },
  CUSTOMER: { label: '客户', hint: '企业客户的下单、预约账号' },
}

export function organizationTypeLabel(type: OrganizationType): string {
  return ORGANIZATION_TYPE_OPTIONS.find((option) => option.value === type)?.label ?? type
}

export function qualificationStatus(status: QualificationStatus) {
  return QUALIFICATION_STATUS_OPTIONS.find((option) => option.value === status) ?? QUALIFICATION_STATUS_OPTIONS[0]!
}

export interface OpsOrganization {
  organizationId: string
  organizationCode: string
  name: string
  type: OrganizationType
  cityCode: string | null
  cityName: string | null
  status: string
  qualificationStatus: QualificationStatus
  qualificationReviewedAt: string | null
  createdAt: string
}

export interface OpsQualification {
  subjectName: string
  licenseNumber: string
  businessScope: string | null
  qualificationNote: string | null
  status: QualificationStatus
  submittedAt: string
  reviewedAt: string | null
  rejectReason: string | null
  version: number
}

export interface OpsAttachment {
  attachmentId: string
  fileName: string
  contentType: string
  sizeBytes: number
  uploadedAt: string
}

export interface OpsAccount {
  accountId: string
  loginName: string
  displayName: string
  status: string
  roles: string[]
  createdAt: string
}

export interface OpsOperation {
  operationId: string
  action: OrganizationAction
  detail: string
  operatorName: string
  occurredAt: string
}

export interface OpsOrganizationDetail {
  organization: OpsOrganization
  qualification: OpsQualification | null
  attachments: OpsAttachment[]
  accounts: OpsAccount[]
  grantableRoles: GrantableRole[]
  /** 服务商资料的状态；企业客户为 null（1.1.0）。 */
  providerProfileStatus: ProviderProfileStatus | null
  operations: OpsOperation[]
}

export interface OpsAccountCreated {
  account: OpsAccount
  initialPassword: string
}

export interface OrganizationFilters {
  keyword: string
  type: OrganizationType | ''
  qualificationStatus: QualificationStatus | ''
}

export interface QualificationDraft {
  subjectName: string
  licenseNumber: string
  businessScope: string
  qualificationNote: string
}

/** 平台城市目录（公开接口 GET /api/v1/platform/cities，openapi-platform-city.yaml）里选城市用的部分。 */
export interface DirectoryCity {
  code: string
  name: string
  provinceName: string
  serviceStatus?: 'OPEN' | 'PREPARING' | 'CLOSED'
}

interface CityDirectoryResponse {
  provinces: Array<{ cities: DirectoryCity[] }>
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

export function createOrganizationApi(http: ApiClient) {
  const base = '/api/v1/ops/organizations'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    list(filters: OrganizationFilters, page: number, size: number): Promise<Page<OpsOrganization>> {
      return http.get<Page<OpsOrganization>>(base, {
        keyword: filters.keyword.trim(),
        type: filters.type,
        qualificationStatus: filters.qualificationStatus,
        page,
        size,
      })
    },
    create(idempotencyKey: string, type: OrganizationType, name: string, cityCode: string):
    Promise<OpsOrganizationDetail> {
      return http.post<OpsOrganizationDetail>(base, { type, name: name.trim(), cityCode }, { idempotencyKey })
    },
    detail(id: string): Promise<OpsOrganizationDetail> {
      return http.get<OpsOrganizationDetail>(one(id))
    },
    submitQualification(id: string, draft: QualificationDraft, version: number | null): Promise<OpsOrganizationDetail> {
      return http.put<OpsOrganizationDetail>(`${one(id)}/qualification`, {
        subjectName: draft.subjectName.trim(),
        licenseNumber: draft.licenseNumber.trim().toUpperCase(),
        businessScope: blankToNull(draft.businessScope),
        qualificationNote: blankToNull(draft.qualificationNote),
        version,
      })
    },
    review(id: string, decision: ReviewDecision, reason: string, version: number): Promise<OpsOrganizationDetail> {
      return http.post<OpsOrganizationDetail>(`${one(id)}/qualification/review`,
        { decision, reason: blankToNull(reason), version })
    },
    uploadAttachment(id: string, file: File): Promise<OpsOrganizationDetail> {
      const form = new FormData()
      form.append('file', file, file.name)
      return http.request<OpsOrganizationDetail>('POST', `${one(id)}/attachments`, { form })
    },
    removeAttachment(id: string, attachmentId: string): Promise<OpsOrganizationDetail> {
      return http.delete<OpsOrganizationDetail>(`${one(id)}/attachments/${encodeURIComponent(attachmentId)}`)
    },
    downloadAttachment(id: string, attachmentId: string) {
      return http.download(`${one(id)}/attachments/${encodeURIComponent(attachmentId)}/content`)
    },
    createAccount(id: string, loginName: string, displayName: string, roles: GrantableRole[]):
    Promise<OpsAccountCreated> {
      return http.post<OpsAccountCreated>(`${one(id)}/accounts`,
        { loginName: loginName.trim(), displayName: displayName.trim(), roles })
    },
    /** 开通服务商资料（幂等，没有请求体）。 */
    openProviderProfile(id: string): Promise<OpsOrganizationDetail> {
      return http.post<OpsOrganizationDetail>(`${one(id)}/provider-profile`)
    },
    replaceRoles(id: string, accountId: string, roles: GrantableRole[]): Promise<OpsOrganizationDetail> {
      return http.put<OpsOrganizationDetail>(`${one(id)}/accounts/${encodeURIComponent(accountId)}/roles`, { roles })
    },
    async cities(): Promise<DirectoryCity[]> {
      const directory = await http.get<CityDirectoryResponse>('/api/v1/platform/cities')
      return directory.provinces.flatMap((province) => province.cities)
    },
  }
}

export type OrganizationApi = ReturnType<typeof createOrganizationApi>
