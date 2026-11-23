import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'

import { activityApi } from '../../data/activity-api-client'

// 联系主办方 on the result and refund-progress pages: the sign-up does not carry the organiser's id, so the
// activity is read once to find it; when it cannot be read, the platform support takes the conversation.
export async function contactOrganizer(activity: { activityId: string; cityCode: string; title: string }): Promise<void> {
  if (!SUPPORT_PREVIEW_ENABLED) {
    uni.showToast({ title: '客服功能即将开放', icon: 'none' })
    return
  }
  const detail = await activityApi.loadDetail(activity.activityId, activity.cityCode).catch(() => null)
  const card = { type: 'ACTIVITY' as const, refId: activity.activityId, title: activity.title, subtitle: null }
  openSupportChat(
    detail ? { kind: 'PROVIDER', organizationId: detail.organizer.organizationId } : { kind: 'PLATFORM' },
    card,
  )
}
