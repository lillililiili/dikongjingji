import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-feedback.yaml 1.0.0；隐藏评论、弹幕调内容（openapi-content.yaml）和直播（openapi-live.yaml）
// 已有的受权接口，平台客服本来就能调。

export type FeedbackType = 'FUNCTION' | 'DISPLAY' | 'CONTENT' | 'SUGGESTION'
export type FeedbackSource = 'USER' | 'VIDEO_COMMENT_REPORT' | 'LIVE_COMMENT_REPORT'
export type HandleStatus = 'PENDING' | 'HANDLED'

export const FEEDBACK_TYPE_LABELS: Record<FeedbackType, string> = {
  FUNCTION: '功能问题',
  DISPLAY: '页面显示',
  CONTENT: '内容有误',
  SUGGESTION: '其他建议',
}

export const SOURCE_OPTIONS: ReadonlyArray<{ value: FeedbackSource, label: string }> = [
  { value: 'USER', label: '用户反馈' },
  { value: 'VIDEO_COMMENT_REPORT', label: '举报视频评论' },
  { value: 'LIVE_COMMENT_REPORT', label: '举报直播弹幕' },
]

export function sourceLabel(source: FeedbackSource): string {
  return SOURCE_OPTIONS.find((option) => option.value === source)?.label ?? source
}

export interface OpsFeedback {
  feedbackId: string
  type: FeedbackType
  source: FeedbackSource
  text: string
  target: { id: string, parentId: string, visibility: 'VISIBLE' | 'HIDDEN' | 'MISSING', text: string | null } | null
  reporter: { displayName: string | null }
  handleStatus: HandleStatus
  handling: { note: string, handlerName: string, handledAt: string } | null
  version: number
  createdAt: string
}

export interface OpsFeedbackPage extends Page<OpsFeedback> {
  pendingCount: number
}

export interface FeedbackFilters {
  handleStatus: HandleStatus | ''
  source: FeedbackSource | ''
}

export const HANDLE_NOTE_MAX = 200

/** 隐藏举报对象调哪个已有接口：视频评论按评论 ID；直播弹幕按直播间 + 弹幕 ID。用户反馈没有对象。 */
export function hidePath(feedback: { source: FeedbackSource, target: { id: string, parentId: string } | null }): string | null {
  if (!feedback.target) return null
  const id = encodeURIComponent(feedback.target.id)
  if (feedback.source === 'VIDEO_COMMENT_REPORT') return `/api/v1/content/comments/${id}/hide`
  if (feedback.source === 'LIVE_COMMENT_REPORT') {
    return `/api/v1/platform/live/rooms/${encodeURIComponent(feedback.target.parentId)}/comments/${id}/hide`
  }
  return null
}

export function hideLabel(source: FeedbackSource): string {
  return source === 'LIVE_COMMENT_REPORT' ? '隐藏弹幕' : '隐藏评论'
}

export function createFeedbackApi(http: ApiClient) {
  return {
    list(filters: FeedbackFilters, page: number, size: number): Promise<OpsFeedbackPage> {
      return http.get<OpsFeedbackPage>('/api/v1/ops/feedback', {
        handleStatus: filters.handleStatus,
        source: filters.source,
        page,
        size,
      })
    },
    handle(feedbackId: string, note: string, version: number): Promise<OpsFeedback> {
      return http.post<OpsFeedback>(`/api/v1/ops/feedback/${encodeURIComponent(feedbackId)}/handle`,
        { note: note.trim(), version })
    },
    /** 隐藏被举报的评论或弹幕（两个接口都幂等，已经隐藏的再调一次也只是返回原来的结果）。 */
    async hideTarget(feedback: OpsFeedback): Promise<void> {
      const path = hidePath(feedback)
      if (!path) throw new Error('这条反馈没有可以隐藏的内容')
      await http.post<unknown>(path)
    },
  }
}

export type FeedbackApi = ReturnType<typeof createFeedbackApi>
