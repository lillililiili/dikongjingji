import type { ApiClient } from '@/core/http/api-client'
import type {
  OpsAttachment,
  OpsOrganization,
  OpsQualification,
  ProviderProfileStatus,
  QualificationDraft,
} from '@/features/organization/organization-api'

// 契约 contracts/openapi-organization-signup.yaml 1.0.0 的工作台接口：本机构的入驻资料（资质、附件）。
// 机构只从令牌里取；审核只在运营端“机构与资质”做，这里只能看结果、改资料、传附件。

export interface OwnOrganization {
  organization: OpsOrganization
  qualification: OpsQualification | null
  attachments: OpsAttachment[]
  providerProfileStatus: ProviderProfileStatus | null
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

export function createOrganizationSelfApi(http: ApiClient) {
  const base = '/api/v1/provider/organization'
  return {
    read(): Promise<OwnOrganization> {
      return http.get<OwnOrganization>(base)
    },
    submitQualification(draft: QualificationDraft, version: number | null): Promise<OwnOrganization> {
      return http.put<OwnOrganization>(`${base}/qualification`, {
        subjectName: draft.subjectName.trim(),
        licenseNumber: draft.licenseNumber.trim().toUpperCase(),
        businessScope: blankToNull(draft.businessScope),
        qualificationNote: blankToNull(draft.qualificationNote),
        version,
      })
    },
    uploadAttachment(file: File): Promise<OwnOrganization> {
      const form = new FormData()
      form.append('file', file, file.name)
      return http.request<OwnOrganization>('POST', `${base}/attachments`, { form })
    },
    removeAttachment(attachmentId: string): Promise<OwnOrganization> {
      return http.delete<OwnOrganization>(`${base}/attachments/${encodeURIComponent(attachmentId)}`)
    },
    downloadAttachment(attachmentId: string) {
      return http.download(`${base}/attachments/${encodeURIComponent(attachmentId)}/content`)
    },
  }
}

export type OrganizationSelfApi = ReturnType<typeof createOrganizationSelfApi>
