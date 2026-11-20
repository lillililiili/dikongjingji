import { acceptPlayUrl } from './preview/live-preview-covers'
import {
  LIVE_ITEM_TYPES,
  type LiveComment,
  type LiveCommentPage,
  type LiveHost,
  type LiveItem,
  type LiveItemType,
  type LiveRoom,
  type LiveRoomCard,
  type LiveRoomPage,
  type LiveRoomStatus,
  type LiveViewerState,
} from '../domain/live'

type LiveRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The DTOs as they arrive (contracts/openapi-live.yaml). Fields are read defensively: an item of a
// kind this version does not know is dropped rather than drawn wrong.
interface HostDto { organizationId?: string; name?: string }
interface ItemDto { seq?: number; type?: string; refId?: string; title?: string }
interface CardDto {
  roomId: string
  title?: string
  subtitle?: string | null
  coverUrl?: string | null
  host?: HostDto
  cityCode?: string | null
  status?: string
  scheduledAt?: string
  startedAt?: string | null
  endedAt?: string | null
  viewerCount?: number
  likeCount?: number
  currentItem?: ItemDto | null
  hasReplay?: boolean
}
interface RoomDto extends Omit<CardDto, 'currentItem' | 'hasReplay'> {
  speakerName?: string | null
  speakerTitle?: string | null
  topics?: string[]
  items?: ItemDto[]
  currentItemSeq?: number | null
  replayUrl?: string | null
  replayPlatformName?: string | null
  playUrl?: string | null
  streamInterrupted?: boolean
}
interface PageDto { hasMore?: boolean; total?: number; items?: CardDto[] }
interface CommentDto { commentId: string; seq?: number; side?: string; authorName?: string; text?: string; sentAt?: string }
interface CommentPageDto { items?: CommentDto[]; cursor?: number; hiddenSeqs?: number[] }
interface ViewerStateDto { reminded?: boolean; followingHost?: boolean }

export interface LiveApi {
  loadRooms(cityCode: string, status: LiveRoomStatus | null, page: number, size: number): Promise<LiveRoomPage>
  loadRoom(roomId: string, cityCode: string): Promise<LiveRoom>
  loadComments(roomId: string, since: number | null): Promise<LiveCommentPage>
  // Counts this device once while the room is live; answers the viewer count now.
  recordView(roomId: string, deviceId: string): Promise<number>
  loadViewerState(accessToken: string, roomId: string): Promise<LiveViewerState>
  setReminder(accessToken: string, roomId: string, on: boolean): Promise<LiveViewerState>
  sendComment(accessToken: string, roomId: string, idempotencyKey: string, text: string): Promise<LiveComment>
  // Several taps merged into one request; answers the room's like count now.
  like(accessToken: string, roomId: string, count: number): Promise<number>
  setFollow(accessToken: string, organizationId: string, on: boolean): Promise<boolean>
  report(accessToken: string, roomId: string, commentId: string): Promise<void>
}

const STATUSES: readonly LiveRoomStatus[] = ['SCHEDULED', 'LIVE', 'ENDED']

const toStatus = (value: unknown): LiveRoomStatus =>
  typeof value === 'string' && (STATUSES as readonly string[]).includes(value) ? value as LiveRoomStatus : 'ENDED'

const count = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0)

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value.trim() : null)

const httpsOnly = (value: unknown): string | null => {
  const url = text(value)
  return url && /^https:\/\/\S+$/i.test(url) ? url : null
}

function toHost(dto: HostDto | undefined): LiveHost {
  return { organizationId: dto?.organizationId ?? '', name: text(dto?.name) ?? '主办方' }
}

export function toLiveItem(dto: ItemDto | null | undefined): LiveItem | null {
  if (!dto || typeof dto.seq !== 'number' || !dto.refId) return null
  if (typeof dto.type !== 'string' || !(LIVE_ITEM_TYPES as readonly string[]).includes(dto.type)) return null
  return { seq: dto.seq, type: dto.type as LiveItemType, refId: dto.refId, title: text(dto.title) ?? '' }
}

export function toLiveRoomCard(dto: CardDto): LiveRoomCard {
  return {
    roomId: dto.roomId,
    title: text(dto.title) ?? '直播',
    subtitle: text(dto.subtitle),
    coverUrl: httpsOnly(dto.coverUrl),
    host: toHost(dto.host),
    cityCode: text(dto.cityCode),
    status: toStatus(dto.status),
    scheduledAt: dto.scheduledAt ?? '',
    startedAt: dto.startedAt ?? null,
    endedAt: dto.endedAt ?? null,
    viewerCount: count(dto.viewerCount),
    likeCount: count(dto.likeCount),
    currentItem: toLiveItem(dto.currentItem),
    hasReplay: dto.hasReplay === true,
  }
}

export function toLiveRoom(dto: RoomDto): LiveRoom {
  const status = toStatus(dto.status)
  const items = (dto.items ?? []).flatMap((item) => {
    const mapped = toLiveItem(item)
    return mapped ? [mapped] : []
  })
  const replayUrl = httpsOnly(dto.replayUrl)
  return {
    roomId: dto.roomId,
    title: text(dto.title) ?? '直播',
    subtitle: text(dto.subtitle),
    coverUrl: httpsOnly(dto.coverUrl),
    host: toHost(dto.host),
    cityCode: text(dto.cityCode),
    status,
    scheduledAt: dto.scheduledAt ?? '',
    startedAt: dto.startedAt ?? null,
    endedAt: dto.endedAt ?? null,
    viewerCount: count(dto.viewerCount),
    likeCount: count(dto.likeCount),
    speakerName: text(dto.speakerName),
    speakerTitle: text(dto.speakerTitle),
    topics: (dto.topics ?? []).flatMap((topic) => (text(topic) ? [text(topic) as string] : [])),
    items,
    currentItemSeq: items.some((item) => item.seq === dto.currentItemSeq) ? dto.currentItemSeq ?? null : null,
    replayUrl,
    replayPlatformName: replayUrl ? text(dto.replayPlatformName) : null,
    playUrl: status === 'LIVE' ? acceptPlayUrl(dto.playUrl) : null,
    streamInterrupted: status === 'LIVE' && dto.streamInterrupted === true,
  }
}

export function toLiveComment(dto: CommentDto): LiveComment | null {
  if (!dto.commentId || typeof dto.seq !== 'number' || typeof dto.text !== 'string') return null
  return {
    commentId: dto.commentId,
    seq: dto.seq,
    side: dto.side === 'HOST' ? 'HOST' : 'VIEWER',
    authorName: text(dto.authorName) ?? '观众',
    text: dto.text,
    sentAt: dto.sentAt ?? '',
  }
}

export function createLiveApi(request: LiveRequest): LiveApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const room = (roomId: string) => `/api/v1/live/rooms/${encodeURIComponent(roomId)}`
  return {
    async loadRooms(cityCode, status, page, size) {
      const query = [`cityCode=${encodeURIComponent(cityCode)}`, ...(status ? [`status=${status}`] : []),
        `page=${page}`, `size=${size}`].join('&')
      const dto = await request<PageDto>({ url: `/api/v1/live/rooms?${query}`, method: 'GET' })
      return {
        hasMore: dto.hasMore === true,
        total: count(dto.total),
        items: (dto.items ?? []).map(toLiveRoomCard),
      }
    },
    async loadRoom(roomId, cityCode) {
      return toLiveRoom(await request<RoomDto>({
        url: `${room(roomId)}?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
      }))
    },
    async loadComments(roomId, since) {
      const dto = await request<CommentPageDto>({
        url: `${room(roomId)}/comments${since === null ? '' : `?since=${since}`}`,
        method: 'GET',
      })
      return {
        items: (dto.items ?? []).flatMap((item) => {
          const mapped = toLiveComment(item)
          return mapped ? [mapped] : []
        }),
        cursor: count(dto.cursor),
        hiddenSeqs: (dto.hiddenSeqs ?? []).filter((seq) => typeof seq === 'number'),
      }
    },
    async recordView(roomId, deviceId) {
      const dto = await request<{ viewerCount?: number }>({
        url: `${room(roomId)}/views`,
        method: 'POST',
        data: { deviceId },
      })
      return count(dto.viewerCount)
    },
    async loadViewerState(accessToken, roomId) {
      const dto = await request<ViewerStateDto>({ url: `${room(roomId)}/me`, method: 'GET', headers: auth(accessToken) })
      return { reminded: dto.reminded === true, followingHost: dto.followingHost === true }
    },
    async setReminder(accessToken, roomId, on) {
      const dto = await request<ViewerStateDto>({
        url: `${room(roomId)}/reminder`,
        method: on ? 'PUT' : 'DELETE',
        headers: auth(accessToken),
      })
      return { reminded: dto.reminded === true, followingHost: dto.followingHost === true }
    },
    async sendComment(accessToken, roomId, idempotencyKey, body) {
      const dto = await request<CommentDto>({
        url: `${room(roomId)}/comments`,
        method: 'POST',
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
        data: { text: body },
      })
      const comment = toLiveComment(dto)
      if (!comment) throw new Error('弹幕没有发出去')
      return comment
    },
    async like(accessToken, roomId, taps) {
      const dto = await request<{ likeCount?: number }>({
        url: `${room(roomId)}/likes`,
        method: 'POST',
        headers: auth(accessToken),
        data: { count: taps },
      })
      return count(dto.likeCount)
    },
    async setFollow(accessToken, organizationId, on) {
      const dto = await request<{ following?: boolean }>({
        url: `/api/v1/live/hosts/${encodeURIComponent(organizationId)}/follow`,
        method: on ? 'PUT' : 'DELETE',
        headers: auth(accessToken),
      })
      return dto.following === true
    },
    async report(accessToken, roomId, commentId) {
      await request<unknown>({
        url: `${room(roomId)}/comments/${encodeURIComponent(commentId)}/report`,
        method: 'POST',
        headers: auth(accessToken),
      })
    },
  }
}
