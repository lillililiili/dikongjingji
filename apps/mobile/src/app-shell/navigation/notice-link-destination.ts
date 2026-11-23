import { ACTIVITY_PARTICIPANTS_PATH } from '../../features/activity/navigation/activity-routes'
import type { SystemNoticeLink } from '../../features/message/domain/system-notice'
import { SETTINGS_PATH, buildAgreementUrl } from '../../features/settings/navigation/settings-routes'
import { buildSupportOpenUrl } from '../../features/support/navigation/support-routes'

// Where a 系统通知's item or action leads (S5-9). The notice only carries a stable code; the shell turns
// it into a page so the message feature imports no other module. A page this App does not have yet is
// answered with what to tell the person instead of a dead link (the typed gateway of AGENTS.md §3).
export type NoticeLinkDestination =
  | { kind: 'page'; url: string }
  | { kind: 'unavailable'; message: string }

export function noticeLinkDestination(link: SystemNoticeLink): NoticeLinkDestination {
  switch (link.type) {
    case 'AGREEMENT': return { kind: 'page', url: buildAgreementUrl(link.agreement) }
    case 'ACTIVITY_PARTICIPANTS': return { kind: 'page', url: ACTIVITY_PARTICIPANTS_PATH }
    case 'NOTIFICATION_SETTINGS': return { kind: 'page', url: SETTINGS_PATH }
    case 'PLATFORM_SUPPORT': return { kind: 'page', url: buildSupportOpenUrl({ kind: 'PLATFORM' }) }
    // 权限管理 and 个人信息查看与导出 come with S5-8.
    case 'PRIVACY_PERMISSIONS': return { kind: 'unavailable', message: '权限管理即将开通' }
    case 'PERSONAL_DATA_EXPORT': return { kind: 'unavailable', message: '个人信息查看与导出即将开通' }
  }
}
