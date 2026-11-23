import type { ApiClient } from '@/core/http/api-client'
import { isHttpsUrl } from '@/core/ops/media-api'
import type { OpsChange } from '@/core/ops/ops-common'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-live.yaml 1.0.0：运营端“直播间”。开通直播主办（授 LIVE_HOST）走机构与资质的
// PUT /api/v1/ops/organizations/{id}/accounts/{accountId}/roles（openapi-ops-organization.yaml）。

export type LiveRoomStatus = 'SCHEDULED' | 'LIVE' | 'ENDED'
export type LiveEndReason = 'STREAM' | 'PLATFORM'

export const LIVE_STATUS_OPTIONS: ReadonlyArray<{ value: LiveRoomStatus, label: string, tag: 'warning' | 'danger' | 'info' }> = [
  { value: 'LIVE', label: '直播中', tag: 'danger' },
  { value: 'SCHEDULED', label: '预告', tag: 'warning' },
  { value: 'ENDED', label: '已结束', tag: 'info' },
]

export const END_REASON_LABELS: Record<LiveEndReason, string> = {
  STREAM: '主播结束（服务商回调）',
  PLATFORM: '平台强制结束',
}

export const LIVE_ACTION_LABELS: Readonly<Record<string, string>> = {
  FORCE_END: '强制结束',
  SET_REPLAY: '设回看',
  CLEAR_REPLAY: '去掉回看',
}

export const LIVE_SUBJECT_LABELS: Readonly<Record<string, string>> = { LIVE_ROOM: '直播间' }

export const LIVE_LIMITS = { note: 200, platformName: 20 } as const

export function liveStatusLabel(status: LiveRoomStatus): string {
  return LIVE_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? status
}

export function liveStatusTag(status: LiveRoomStatus): 'warning' | 'danger' | 'info' {
  return LIVE_STATUS_OPTIONS.find((option) => option.value === status)?.tag ?? 'info'
}

export interface OpsLiveHost {
  organizationId: string
  organizationName: string
  cityCode: string | null
  hostAccountCount: number
  roomCount: number
  liveRoomCount: number
  scheduledRoomCount: number
}

export interface OpsLiveRoom {
  roomId: string
  hostOrganizationId: string
  hostName: string
  title: string
  subtitle: string | null
  coverUrl: string | null
  cityCode: string | null
  scheduledAt: string
  speakerName: string | null
  status: LiveRoomStatus
  startedAt: string | null
  endedAt: string | null
  endReason: LiveEndReason | null
  streamInterrupted: boolean
  replayUrl: string | null
  replayPlatformName: string | null
  viewerCount: number
  likeCount: number
  commentCount: number
  version: number
  createdAt: string
}

export interface LiveRoomFilters {
  keyword: string
  status: LiveRoomStatus | ''
  hostOrganizationId: string
}

export interface ReplayDraft {
  replayUrl: string
  replayPlatformName: string
}

/** 回看两项一起填或一起空（去掉回看）；地址只收 https，平台名称 1–20 字。返回问题说明，没问题返回 null。 */
export function replayProblem(draft: ReplayDraft): string | null {
  const url = draft.replayUrl.trim()
  const name = draft.replayPlatformName.trim()
  if (!url && !name) return null
  if (!url || !name) return '回看地址和平台名称要一起填；都清空表示去掉回看'
  if (!isHttpsUrl(url)) return '回看地址只能是带主机名的 https 地址'
  if (charCount(name) > LIVE_LIMITS.platformName) return `平台名称最多 ${LIVE_LIMITS.platformName} 个字`
  return null
}

export function createLiveApi(http: ApiClient) {
  return {
    hosts(): Promise<OpsLiveHost[]> {
      return http.get<OpsLiveHost[]>('/api/v1/ops/live/hosts')
    },
    rooms(filters: LiveRoomFilters, page: number, size: number): Promise<Page<OpsLiveRoom>> {
      return http.get<Page<OpsLiveRoom>>('/api/v1/ops/live/rooms', {
        keyword: filters.keyword.trim(),
        status: filters.status,
        hostOrganizationId: filters.hostOrganizationId,
        page,
        size,
      })
    },
    room(roomId: string): Promise<OpsLiveRoom> {
      return http.get<OpsLiveRoom>(`/api/v1/ops/live/rooms/${encodeURIComponent(roomId)}`)
    },
    end(roomId: string, note: string, version: number): Promise<OpsLiveRoom> {
      return http.post<OpsLiveRoom>(`/api/v1/ops/live/rooms/${encodeURIComponent(roomId)}/end`,
        { note: note.trim(), version })
    },
    replay(roomId: string, draft: ReplayDraft, version: number): Promise<OpsLiveRoom> {
      const url = draft.replayUrl.trim()
      const name = draft.replayPlatformName.trim()
      return http.put<OpsLiveRoom>(`/api/v1/ops/live/rooms/${encodeURIComponent(roomId)}/replay`, {
        replayUrl: url || null,
        replayPlatformName: name || null,
        version,
      })
    },
    changes(roomId: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`/api/v1/ops/live/rooms/${encodeURIComponent(roomId)}/changes`)
    },
  }
}

export type LiveApi = ReturnType<typeof createLiveApi>
