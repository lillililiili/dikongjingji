import type { ActivityEnrollmentStatus } from '../domain/activity'

// Entries that the read-only step does not build yet. They go through this typed gate instead of ad-hoc toasts.
export type ActivityIntent =
  | { type: 'ENROLL'; activityId: string; sessionId: string }
  | { type: 'JOIN_WAITLIST'; activityId: string; sessionId: string }
  | { type: 'TEAM_ENROLL'; activityId: string }
  | { type: 'CONSULT'; activityId: string }

export type ActivityIntentResolution =
  | { action: 'SHOW_NOTICE'; message: string }
  | { action: 'HIDE' }

const NOTICES: Record<ActivityIntent['type'], string> = {
  ENROLL: '报名功能即将开放',
  JOIN_WAITLIST: '候补功能即将开放',
  TEAM_ENROLL: '团队报名即将开放',
  CONSULT: '咨询功能即将开放',
}

// Preview builds explain that the entry is coming; public builds do not show it at all.
export function resolveActivityIntent(
  intent: ActivityIntent,
  previewEnabled: boolean,
): ActivityIntentResolution {
  return previewEnabled
    ? { action: 'SHOW_NOTICE', message: NOTICES[intent.type] }
    : { action: 'HIDE' }
}

export type ActivityPrimaryAction =
  | { kind: 'CHOOSE_SESSION'; label: '选择场次'; enabled: false }
  | { kind: 'ENROLL'; label: '立即报名'; enabled: true }
  | { kind: 'JOIN_WAITLIST'; label: '加入候补'; enabled: true }
  | { kind: 'UNAVAILABLE'; label: string; enabled: false }

/**
 * The main button of the detail page. Once the whole activity is closed or over there is nothing to choose;
 * otherwise it follows the selected session.
 */
export function resolveActivityPrimaryAction(
  activityStatus: ActivityEnrollmentStatus,
  selectedSessionStatus: ActivityEnrollmentStatus | null,
): ActivityPrimaryAction {
  if (activityStatus === 'ENDED') return { kind: 'UNAVAILABLE', label: '活动已结束', enabled: false }
  if (activityStatus === 'CLOSED') return { kind: 'UNAVAILABLE', label: '报名已截止', enabled: false }
  if (selectedSessionStatus === null) return { kind: 'CHOOSE_SESSION', label: '选择场次', enabled: false }

  switch (selectedSessionStatus) {
    case 'OPEN':
      return { kind: 'ENROLL', label: '立即报名', enabled: true }
    case 'FULL_WAITLIST':
      return { kind: 'JOIN_WAITLIST', label: '加入候补', enabled: true }
    case 'FULL':
      return { kind: 'UNAVAILABLE', label: '本场已满', enabled: false }
    case 'CLOSED':
      return { kind: 'UNAVAILABLE', label: '本场报名已截止', enabled: false }
    case 'ENDED':
      return { kind: 'UNAVAILABLE', label: '本场已结束', enabled: false }
  }
}

export function isActivitySessionSelectable(status: ActivityEnrollmentStatus): boolean {
  return status === 'OPEN' || status === 'FULL_WAITLIST'
}
