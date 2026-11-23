import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type { SystemNotice, SystemNoticeLink } from '../domain/system-notice'

export type NoticeDetailState =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'error' }
  | { kind: 'ready'; notice: SystemNotice }

// "11/01 09:00", Beijing time, as on the board NoticeDetail.
export function formatNoticeTime(iso: string): string {
  const at = new Date(iso)
  if (!Number.isFinite(at.getTime())) return ''
  const parts = toBeijingParts(at)
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

// Icons of the board NoticeDetail's 主要变化 rows, by where they lead.
export const NOTICE_ICONS = {
  bell: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  participants: 'M9 11a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM3 20c.8-3 3-5 6-5s5.2 2 6 5M17 11a2.5 2.5 0 1 0 0-5M21 19c-.4-2-1.6-3.4-3.5-4',
  permissions: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4',
  exportData: 'M10 11a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM3 21c.8-3.5 3.4-6 7-6c1.2 0 2.3.3 3.2.8M18 14v7M15 18l3 3 3-3',
  document: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  settings: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  service: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  chevron: 'M9 5l7 7-7 7',
} as const

export function noticeItemIcon(link: SystemNoticeLink | null): string {
  switch (link?.type) {
    case 'ACTIVITY_PARTICIPANTS': return NOTICE_ICONS.participants
    case 'PRIVACY_PERMISSIONS': return NOTICE_ICONS.permissions
    case 'PERSONAL_DATA_EXPORT': return NOTICE_ICONS.exportData
    case 'NOTIFICATION_SETTINGS': return NOTICE_ICONS.settings
    case 'PLATFORM_SUPPORT': return NOTICE_ICONS.service
    default: return NOTICE_ICONS.document
  }
}
