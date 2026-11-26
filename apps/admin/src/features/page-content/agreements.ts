import { http } from '@/core/http'
import type { Page } from '@/core/ui/use-paged-list'

export const agreementTypes = {
  USER_AGREEMENT: '用户协议', PRIVACY_POLICY: '隐私政策', CHILD_PRIVACY_RULES: '儿童隐私保护规则',
  TRADE_AGREEMENT: '交易协议', ENROLLMENT_NOTICE: '报名须知',
} as const
export type AgreementType = keyof typeof agreementTypes
export const statusLabels = { DRAFT: '草稿', SCHEDULED: '待生效', EFFECTIVE: '已生效', ARCHIVED: '历史版本' } as const
export const actionLabels = { CREATE: '新建草稿', UPDATE: '保存草稿', PUBLISH: '发布版本' } as const
export interface AgreementHeader {
  id: string
  doc: AgreementType
  version: string
  title: string
  effectiveOn: string | null
  revision: number
  status: keyof typeof statusLabels
}
export interface Section { title: string; body: string }
export interface Draft {
  doc: AgreementType
  version: string
  title: string
  effectiveOn: string | null
  revision: number
  sections: Section[]
}
export interface Agreement {
  document: AgreementHeader
  sections: Section[]
  history: {revision: number; action: keyof typeof actionLabels; operatorName: string; changedAt: string}[]
}
const base = '/api/v1/ops/agreements'
export const agreements = {
  list: (doc: string, page: number, size: number) => http.get<Page<AgreementHeader>>(base, {doc, page, size}),
  detail: (id: string) => http.get<Agreement>(base + '/' + id),
  save: (id: string | undefined, draft: Draft) =>
    id ? http.put<Agreement>(base + '/' + id, draft) : http.post<Agreement>(base, draft),
  publish: (header: AgreementHeader) => http.post<Agreement>(base + '/' + header.id + '/publish', {revision: header.revision}),
}
