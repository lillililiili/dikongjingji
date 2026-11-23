import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import type { Page } from '@/core/ui/use-paged-list'

// 直播主办工作台（契约 contracts/openapi-live.yaml 1.1.0）：本机构的直播间（列表可按状态筛）、建直播间、改预告、
// 讲解清单和当前讲解项、回看链接、弹幕（公开的增量接口读）和隐藏弹幕、推流地址。只给直播主办（LIVE_HOST）。

export type LiveRoomStatus = 'SCHEDULED' | 'LIVE' | 'ENDED'
export type LiveItemType = 'PRODUCT' | 'PRO_SERVICE' | 'INSPECTION_SERVICE' | 'ACTIVITY' | 'VENUE'
export type LiveCommentSide = 'VIEWER' | 'HOST'

export const STATUS_OPTIONS: ReadonlyArray<{ value: LiveRoomStatus, label: string }> = [
  { value: 'SCHEDULED', label: '预告' },
  { value: 'LIVE', label: '直播中' },
  { value: 'ENDED', label: '已结束' },
]

export function statusLabel(status: LiveRoomStatus): string {
  return STATUS_OPTIONS.find((option) => option.value === status)?.label ?? status
}

/** 讲解项类型，以及 App 详情页分享链接里放这个对象 ID 的参数名（share-link：/pages/…?productId=…）。 */
export const ITEM_TYPES: ReadonlyArray<{ value: LiveItemType, label: string, linkParam: string }> = [
  { value: 'PRODUCT', label: '商品', linkParam: 'productId' },
  { value: 'PRO_SERVICE', label: '专业服务', linkParam: 'serviceId' },
  { value: 'INSPECTION_SERVICE', label: '巡检服务', linkParam: 'serviceId' },
  { value: 'ACTIVITY', label: '活动', linkParam: 'activityId' },
  { value: 'VENUE', label: '场馆', linkParam: 'venueId' },
]

export function itemTypeLabel(type: LiveItemType): string {
  return ITEM_TYPES.find((option) => option.value === type)?.label ?? type
}

export interface LiveItem {
  seq: number
  type: LiveItemType
  refId: string
  title: string
}

export interface LiveRoom {
  roomId: string
  title: string
  subtitle: string | null
  coverUrl: string | null
  host: { organizationId: string, name: string }
  cityCode: string | null
  status: LiveRoomStatus
  scheduledAt: string
  startedAt: string | null
  endedAt: string | null
  viewerCount: number
  likeCount: number
  speakerName: string | null
  speakerTitle: string | null
  topics: string[]
  items: LiveItem[]
  currentItemSeq: number | null
  replayUrl: string | null
  replayPlatformName: string | null
  playUrl: string | null
  streamInterrupted: boolean
}

export interface LiveHostRoom {
  room: LiveRoom
  pushUrl: string
}

export interface LiveRoomRequest {
  title: string
  subtitle: string | null
  coverUrl: string | null
  cityCode: string | null
  scheduledAt: string
  speakerName: string | null
  speakerTitle: string | null
  topics: string[] | null
}

export interface LiveItemRequest {
  type: LiveItemType
  refId: string
  title: string
}

export interface LiveComment {
  commentId: string
  seq: number
  side: LiveCommentSide
  authorName: string
  text: string
  sentAt: string
}

export interface LiveCommentPage {
  items: LiveComment[]
  cursor: number
  hiddenSeqs: number[]
}

export interface LiveRoomFilters {
  status: LiveRoomStatus | ''
}

/** 后端直播间列表每页最多 20 条。 */
export const HOST_PAGE_MAX = 20
/** 弹幕一次最多读 50 条。 */
export const COMMENT_PAGE_MAX = 50

registerErrorMessages({
  LIVE_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
  LIVE_ROOM_NOT_SCHEDULED: '直播已经开始或结束，预告不能再改',
  LIVE_ROOM_NOT_LIVE: '不在直播中',
  LIVE_ROOM_ENDED: '直播已结束，不能再改讲解清单',
  LIVE_COMMENT_BLOCKED: '弹幕没有通过关键词过滤',
  LIVE_COMMENT_TOO_FAST: '发得太快了，稍等几秒',
  LIVE_STREAM_UNAVAILABLE: '直播服务暂不可用，建不了直播间，请稍后再试',
})

const HOST = '/api/v1/live-host/rooms'

function room(roomId: string): string {
  return `${HOST}/${encodeURIComponent(roomId)}`
}

export function createLiveHostApi(http: ApiClient) {
  return {
    list(filters: LiveRoomFilters, page: number, size: number): Promise<Page<LiveHostRoom>> {
      return http.get<Page<LiveHostRoom>>(HOST, { status: filters.status, page, size: Math.min(size, HOST_PAGE_MAX) })
    },
    /** 建直播间：同一个幂等键重放返回第一次建的那个（200），新建是 201，两种都算成功。 */
    create(draft: LiveRoomRequest, key: string): Promise<LiveHostRoom> {
      return http.post<LiveHostRoom>(HOST, draft, { idempotencyKey: key })
    },
    get(roomId: string): Promise<LiveHostRoom> {
      return http.get<LiveHostRoom>(room(roomId))
    },
    update(roomId: string, draft: LiveRoomRequest): Promise<LiveHostRoom> {
      return http.put<LiveHostRoom>(room(roomId), draft)
    },
    setReplay(roomId: string, replayUrl: string | null, replayPlatformName: string | null): Promise<LiveHostRoom> {
      return http.put<LiveHostRoom>(`${room(roomId)}/replay`, { replayUrl, replayPlatformName })
    },
    setItems(roomId: string, items: LiveItemRequest[], currentSeq: number | null): Promise<LiveHostRoom> {
      return http.put<LiveHostRoom>(`${room(roomId)}/items`, { items, currentSeq })
    },
    setCurrentItem(roomId: string, seq: number | null): Promise<LiveHostRoom> {
      return http.put<LiveHostRoom>(`${room(roomId)}/current-item`, { seq })
    },
    /** 最近的弹幕（公开接口，不带 since 取最近 size 条）。 */
    comments(roomId: string, size = COMMENT_PAGE_MAX): Promise<LiveCommentPage> {
      return http.get<LiveCommentPage>(`/api/v1/live/rooms/${encodeURIComponent(roomId)}/comments`,
        { size: Math.min(size, COMMENT_PAGE_MAX) })
    },
    hideComment(roomId: string, commentId: string): Promise<unknown> {
      return http.post<unknown>(`${room(roomId)}/comments/${encodeURIComponent(commentId)}/hide`)
    },
  }
}

export type LiveHostApi = ReturnType<typeof createLiveHostApi>
