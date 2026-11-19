import type { ActivityDetail, ActivityEnrollmentStatus } from '../domain/activity'

// Entries that are not built yet. They go through this typed gate instead of ad-hoc toasts.
// ENROLL here means signing up for an activity that quotes a price; free and paid-per-person ones
// have their own pages (see resolveEnrollmentPayMode).
export type ActivityIntent =
  | { type: 'ENROLL'; activityId: string; sessionId: string }
  | { type: 'JOIN_WAITLIST'; activityId: string; sessionId: string }
  | { type: 'TEAM_ENROLL'; activityId: string }
  | { type: 'CONSULT'; activityId: string }
  | { type: 'READ_AGREEMENT'; document: 'ENROLLMENT_NOTICE' | 'CHILD_PRIVACY_RULES' }

export type ActivityIntentResolution =
  | { action: 'SHOW_NOTICE'; message: string }
  | { action: 'HIDE' }

const NOTICES: Record<ActivityIntent['type'], string> = {
  ENROLL: '询价活动暂不支持在线报名',
  JOIN_WAITLIST: '候补功能即将开放',
  TEAM_ENROLL: '团队报名即将开放',
  CONSULT: '咨询功能即将开放',
  READ_AGREEMENT: '协议正文待提供',
}

/**
 * How the sign-up form works for an activity (plan §20.1):
 * FREE signs up; ONLINE_PAYMENT signs up and goes on to pay the organiser; PAYMENT_UNAVAILABLE shows
 * the form but cannot submit (the organiser has no online payment); UNSUPPORTED (a quoted price, or a
 * per-person price without an amount) has no form, only the typed gate.
 */
export type EnrollmentPayMode = 'FREE' | 'ONLINE_PAYMENT' | 'PAYMENT_UNAVAILABLE' | 'UNSUPPORTED'

export function resolveEnrollmentPayMode(
  activity: Pick<ActivityDetail, 'price' | 'onlinePayment'>,
): EnrollmentPayMode {
  if (activity.price.mode === 'FREE') return 'FREE'
  if (activity.price.mode !== 'PER_PERSON' || !activity.price.amountMinor || activity.price.amountMinor <= 0) {
    return 'UNSUPPORTED'
  }
  return activity.onlinePayment ? 'ONLINE_PAYMENT' : 'PAYMENT_UNAVAILABLE'
}

export const PAYMENT_UNAVAILABLE_TEXT = '主办方暂未开通在线支付'

// What the form sends as expectedAmountMinor and shows as the total: unit price × people.
export function enrollmentTotalMinor(unitAmountMinor: number, people: number): number {
  return unitAmountMinor * people
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
