// Mirrors contracts/openapi-live.yaml 1.0.0 (M20). The server decides what is visible and what may
// be done; the App only draws it. Go-live, interruption and end come from the stream provider.

export type LiveRoomStatus = 'SCHEDULED' | 'LIVE' | 'ENDED'

// What an explainer item is: the same five kinds an article can mention, opened on their own pages.
export type LiveItemType = 'PRODUCT' | 'PRO_SERVICE' | 'INSPECTION_SERVICE' | 'ACTIVITY' | 'VENUE'

export const LIVE_ITEM_TYPES: readonly LiveItemType[] = ['PRODUCT', 'PRO_SERVICE', 'INSPECTION_SERVICE', 'ACTIVITY', 'VENUE']

export type LiveCommentSide = 'VIEWER' | 'HOST'

export interface LiveHost {
  organizationId: string
  name: string
}

export interface LiveItem {
  seq: number
  type: LiveItemType
  refId: string
  // The host's own words for the item; prices and pictures are read from the item's module.
  title: string
}

export interface LiveRoomCard {
  roomId: string
  title: string
  subtitle: string | null
  coverUrl: string | null
  host: LiveHost
  cityCode: string | null
  status: LiveRoomStatus
  scheduledAt: string
  startedAt: string | null
  endedAt: string | null
  viewerCount: number
  likeCount: number
  currentItem: LiveItem | null
  hasReplay: boolean
}

export interface LiveRoomPage {
  hasMore: boolean
  total: number
  items: LiveRoomCard[]
}

export interface LiveRoom {
  roomId: string
  title: string
  subtitle: string | null
  coverUrl: string | null
  host: LiveHost
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
  // Only while live; null when the address is not one this build may play.
  playUrl: string | null
  streamInterrupted: boolean
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

export interface LiveViewerState {
  reminded: boolean
  followingHost: boolean
}

export const LIVE_ROOM_PAGE_SIZE = 10
export const LIVE_COMMENT_LIMIT = 50
export const LIVE_LIKES_PER_REQUEST = 30
// The App asks for new comments this often while the room is in front (no WebSocket).
export const LIVE_COMMENT_POLL_MS = 2500
