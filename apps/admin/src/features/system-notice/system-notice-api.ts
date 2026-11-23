import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-notification.yaml 1.5.0 的运营部分（/api/v1/ops/notifications/system-notices）：只给平台运营。
// 公告登记一份；账号打开 App 的“消息”时补记成它的一条，发布时按收件范围推送到设备（本机模拟推送）。

export type AudienceScope = 'ALL' | 'CITY'
export type AgreementCode = 'PRIVACY_POLICY' | 'USER_AGREEMENT' | 'CHILD_PRIVACY_RULES' | 'TRADE_AGREEMENT' | 'ENROLLMENT_NOTICE'
export type LinkType =
  | 'AGREEMENT'
  | 'ACTIVITY_PARTICIPANTS'
  | 'PRIVACY_PERMISSIONS'
  | 'PERSONAL_DATA_EXPORT'
  | 'NOTIFICATION_SETTINGS'
  | 'PLATFORM_SUPPORT'

export const AGREEMENT_OPTIONS: ReadonlyArray<{ value: AgreementCode, label: string }> = [
  { value: 'PRIVACY_POLICY', label: '隐私政策' },
  { value: 'USER_AGREEMENT', label: '用户协议' },
  { value: 'CHILD_PRIVACY_RULES', label: '儿童个人信息保护规则' },
  { value: 'TRADE_AGREEMENT', label: '平台交易协议' },
  { value: 'ENROLLMENT_NOTICE', label: '报名须知与照片使用说明' },
]

export const LINK_OPTIONS: ReadonlyArray<{ value: LinkType, label: string }> = [
  { value: 'AGREEMENT', label: '协议正文' },
  { value: 'ACTIVITY_PARTICIPANTS', label: '参与人' },
  { value: 'PRIVACY_PERMISSIONS', label: '权限管理（App 未开通时提示即将开通）' },
  { value: 'PERSONAL_DATA_EXPORT', label: '个人信息查看与导出（App 未开通时提示即将开通）' },
  { value: 'NOTIFICATION_SETTINGS', label: '设置' },
  { value: 'PLATFORM_SUPPORT', label: '平台客服' },
]

// 和服务端 SystemNoticeService 的上限一致。
export const LIMITS = {
  title: 40,
  summary: 120,
  paragraphs: 10,
  paragraph: 500,
  facts: 4,
  factLabel: 8,
  factValue: 16,
  items: 6,
  itemTitle: 20,
  itemDescription: 60,
  footnote: 200,
  actionLabel: 12,
} as const

export interface NoticeLink {
  type: LinkType
  agreement: AgreementCode | null
}

export interface SystemNotice {
  id: string
  title: string
  summary: string
  paragraphs: string[]
  facts: Array<{ label: string, value: string }>
  items: Array<{ title: string, description: string, link: NoticeLink | null }>
  footnote: string | null
  action: { label: string, link: NoticeLink } | null
  aboutAgreement: AgreementCode | null
  audience: { scope: AudienceScope, cityCode: string | null }
  publishedAt: string
  expiresAt: string | null
  status: 'PUBLISHED' | 'WITHDRAWN'
}

/** 发布表单：段落按空行分开写在一个文本框里；链接类型为空表示这一条不带链接。 */
export interface NoticeForm {
  title: string
  summary: string
  paragraphsText: string
  facts: Array<{ label: string, value: string }>
  items: Array<{ title: string, description: string, linkType: LinkType | '', agreement: AgreementCode | '' }>
  footnote: string
  actionLabel: string
  actionLinkType: LinkType | ''
  actionAgreement: AgreementCode | ''
  aboutAgreement: AgreementCode | ''
  scope: AudienceScope
  cityCode: string
  expiresAt: string | null
}

export function emptyNoticeForm(): NoticeForm {
  return {
    title: '',
    summary: '',
    paragraphsText: '',
    facts: [],
    items: [],
    footnote: '',
    actionLabel: '',
    actionLinkType: '',
    actionAgreement: '',
    aboutAgreement: '',
    scope: 'ALL',
    cityCode: '',
    expiresAt: null,
  }
}

export function paragraphsOf(text: string): string[] {
  return text.split(/\n\s*\n/).map((paragraph) => paragraph.trim()).filter(Boolean)
}

function linkOf(type: LinkType | '', agreement: AgreementCode | ''): NoticeLink | null {
  if (!type) return null
  return { type, agreement: type === 'AGREEMENT' ? (agreement || null) : null }
}

/** 表单 → 契约的 SystemNoticeDraft。主按钮没填文字或没选去处时不带。 */
export function draftOf(form: NoticeForm) {
  const action = form.actionLabel.trim() && form.actionLinkType
    ? { label: form.actionLabel.trim(), link: linkOf(form.actionLinkType, form.actionAgreement) }
    : null
  return {
    title: form.title.trim(),
    summary: form.summary.trim(),
    paragraphs: paragraphsOf(form.paragraphsText),
    facts: form.facts.map((fact) => ({ label: fact.label.trim(), value: fact.value.trim() })),
    items: form.items.map((item) => ({
      title: item.title.trim(),
      description: item.description.trim(),
      link: linkOf(item.linkType, item.agreement),
    })),
    footnote: form.footnote.trim() || null,
    action,
    aboutAgreement: form.aboutAgreement || null,
    audience: { scope: form.scope, cityCode: form.scope === 'CITY' ? form.cityCode : null },
    expiresAt: form.expiresAt,
  }
}

/** 发布前在页面上就能说清的问题（服务端还会再校验一遍）；没有问题返回空数组。 */
export function draftProblems(form: NoticeForm): string[] {
  const problems: string[] = []
  const paragraphs = paragraphsOf(form.paragraphsText)
  if (paragraphs.length === 0) problems.push('正文至少写一段')
  if (paragraphs.length > LIMITS.paragraphs) problems.push(`正文最多 ${LIMITS.paragraphs} 段`)
  if (paragraphs.some((paragraph) => [...paragraph].length > LIMITS.paragraph)) {
    problems.push(`每段最多 ${LIMITS.paragraph} 字`)
  }
  if (form.scope === 'CITY' && !form.cityCode) problems.push('按城市发布要选城市')
  if (form.items.some((item) => item.linkType === 'AGREEMENT' && !item.agreement)) problems.push('“主要变化”的协议链接要选哪份协议')
  if (form.actionLinkType === 'AGREEMENT' && !form.actionAgreement) problems.push('主按钮的协议链接要选哪份协议')
  if (Boolean(form.actionLabel.trim()) !== Boolean(form.actionLinkType)) problems.push('主按钮的文字和去处要一起填')
  return problems
}

export function createSystemNoticeApi(http: ApiClient) {
  const base = '/api/v1/ops/notifications/system-notices'
  return {
    list(page: number, size: number): Promise<Page<SystemNotice>> {
      return http.get<Page<SystemNotice>>(base, { page, size })
    },
    publish(form: NoticeForm, idempotencyKey: string): Promise<SystemNotice> {
      return http.post<SystemNotice>(base, draftOf(form), { idempotencyKey })
    },
    withdraw(noticeId: string): Promise<SystemNotice> {
      return http.post<SystemNotice>(`${base}/${encodeURIComponent(noticeId)}/withdraw`)
    },
  }
}

export type SystemNoticeApi = ReturnType<typeof createSystemNoticeApi>
