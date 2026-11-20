import type { LiveRoomCard, LiveRoomStatus } from '../domain/live'

export const LIVE_NOTICE_PATH = '/pages/live/notice'
export const LIVE_ROOM_PATH = '/pages/live/room'

export interface LiveTarget {
  roomId: string
  // Empty when opened from a notification: the page then reads the App's current city.
  cityCode: string
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function build(path: string, roomId: string, cityCode: string | null | undefined): string {
  const parts = [`roomId=${encodeURIComponent(roomId)}`]
  const city = cityCode?.trim()
  if (city) parts.push(`cityCode=${encodeURIComponent(city)}`)
  return `${path}?${parts.join('&')}`
}

// Only the stable id and the city travel; the page reads the room again.
export function buildLiveNoticeUrl(roomId: string, cityCode?: string | null): string {
  return build(LIVE_NOTICE_PATH, roomId, cityCode)
}

export function buildLiveRoomUrl(roomId: string, cityCode?: string | null): string {
  return build(LIVE_ROOM_PATH, roomId, cityCode)
}

/** A live room opens the live room; a preview or a replay opens the preview page (board Discover). */
export function liveCardUrl(card: Pick<LiveRoomCard, 'roomId' | 'status'>, cityCode: string): string {
  return card.status === 'LIVE' ? buildLiveRoomUrl(card.roomId, cityCode) : buildLiveNoticeUrl(card.roomId, cityCode)
}

export function liveStatusUrl(roomId: string, status: LiveRoomStatus, cityCode: string): string {
  return liveCardUrl({ roomId, status }, cityCode)
}

function read(query: Record<string, unknown> | undefined, key: string): string {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  // H5 hands the query over decoded; an App deep link may still carry %-escapes.
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

export function parseLiveTarget(query: Record<string, unknown> | undefined): LiveTarget | null {
  const roomId = read(query, 'roomId')
  const cityCode = read(query, 'cityCode')
  if (!UUID.test(roomId)) return null
  return { roomId, cityCode: /^\d{6}$/.test(cityCode) ? cityCode : '' }
}
