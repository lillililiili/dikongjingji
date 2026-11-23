import type { FormRules } from 'element-plus'

import { matches, maxChars, required } from '@/core/ui/form-rules'

// 和后端 OrganizationOnboardingService 的校验一致（契约 openapi-ops-organization.yaml）。

/** 统一社会信用代码：18 位数字或大写字母（不含 I、O、Z、S、V）。只校验格式。 */
export const LICENSE_NUMBER = /^[0-9A-HJ-NPQRTUWXY]{18}$/
/** 登录名：小写字母开头，4–32 位小写字母、数字、_、-。 */
export const LOGIN_NAME = /^[a-z][a-z0-9_-]{3,31}$/

export const LIMITS = {
  name: 60,
  subjectName: 160,
  businessScope: 500,
  qualificationNote: 500,
  rejectReason: 200,
  displayName: 40,
  fileBytes: 10 * 1024 * 1024,
  attachments: 10,
} as const

export const ATTACHMENT_TYPES = ['application/pdf', 'image/jpeg', 'image/png'] as const

export const createOrganizationRules: FormRules = {
  type: [required('机构类型', 'change')],
  name: [required('机构名称'), maxChars('机构名称', LIMITS.name)],
  cityCode: [required('所在城市', 'change')],
}

export const qualificationRules: FormRules = {
  subjectName: [required('主体名称'), maxChars('主体名称', LIMITS.subjectName)],
  licenseNumber: [
    required('统一社会信用代码'),
    matches(LICENSE_NUMBER, '统一社会信用代码是 18 位数字或大写字母（不含 I、O、Z、S、V）',
      (value) => value.trim().toUpperCase()),
  ],
  businessScope: [maxChars('经营范围', LIMITS.businessScope)],
  qualificationNote: [maxChars('行业资质说明', LIMITS.qualificationNote)],
}

export const rejectRules: FormRules = {
  reason: [required('驳回原因'), maxChars('驳回原因', LIMITS.rejectReason)],
}

export const accountRules: FormRules = {
  loginName: [required('登录名'), matches(LOGIN_NAME, '登录名要以小写字母开头，4–32 位小写字母、数字、_ 或 -')],
  displayName: [required('显示名'), maxChars('显示名', LIMITS.displayName)],
  roles: [required('角色', 'change')],
}

/** 上传前在页面上先挡一次（后端按文件头再判断一次）。返回错误说明，没问题返回 null。 */
export function attachmentProblem(file: { size: number, type: string }, existing: number): string | null {
  if (existing >= LIMITS.attachments) return `每家最多 ${LIMITS.attachments} 份附件`
  if (file.size <= 0) return '文件是空的'
  if (file.size > LIMITS.fileBytes) return '单个附件不能超过 10 MB'
  if (!(ATTACHMENT_TYPES as readonly string[]).includes(file.type)) return '只能上传 PDF、JPEG 或 PNG'
  return null
}
