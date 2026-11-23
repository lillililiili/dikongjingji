// Mirrors the 系统通知 part of contracts/openapi-notification.yaml (1.5.0): an announcement the platform
// published to everyone or to one city. Links are stable codes; the shell turns them into pages.

export const SYSTEM_NOTICE_AGREEMENTS = [
  'PRIVACY_POLICY',
  'USER_AGREEMENT',
  'CHILD_PRIVACY_RULES',
  'TRADE_AGREEMENT',
  'ENROLLMENT_NOTICE',
] as const
export type SystemNoticeAgreement = (typeof SYSTEM_NOTICE_AGREEMENTS)[number]

export type SystemNoticeLink =
  | { type: 'AGREEMENT'; agreement: SystemNoticeAgreement }
  | { type: 'ACTIVITY_PARTICIPANTS' | 'PRIVACY_PERMISSIONS' | 'PERSONAL_DATA_EXPORT' | 'NOTIFICATION_SETTINGS' | 'PLATFORM_SUPPORT' }

export interface SystemNoticeFact {
  label: string
  value: string
}

export interface SystemNoticeItem {
  title: string
  description: string
  // null: the item opens nothing (no link, or one this version does not know).
  link: SystemNoticeLink | null
}

export interface SystemNotice {
  id: string
  title: string
  summary: string
  paragraphs: string[]
  facts: SystemNoticeFact[]
  items: SystemNoticeItem[]
  footnote: string | null
  action: { label: string; link: SystemNoticeLink } | null
  publishedAt: string
}

export interface AgreementUpdate {
  noticeId: string
  title: string
  publishedAt: string
}

const SIMPLE_LINKS = [
  'ACTIVITY_PARTICIPANTS',
  'PRIVACY_PERMISSIONS',
  'PERSONAL_DATA_EXPORT',
  'NOTIFICATION_SETTINGS',
  'PLATFORM_SUPPORT',
] as const

export function toSystemNoticeLink(value: { type?: string; agreement?: string | null } | null | undefined): SystemNoticeLink | null {
  if (!value?.type) return null
  if (value.type === 'AGREEMENT') {
    return (SYSTEM_NOTICE_AGREEMENTS as readonly string[]).includes(value.agreement ?? '')
      ? { type: 'AGREEMENT', agreement: value.agreement as SystemNoticeAgreement }
      : null
  }
  return (SIMPLE_LINKS as readonly string[]).includes(value.type)
    ? { type: value.type as (typeof SIMPLE_LINKS)[number] }
    : null
}
