import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type { ContentAuthorKind, ContentCommentReportReason, ContentRefType } from '../domain/content'
import type { ContentRefView } from './content-refs'

// What the video pages print (boards Discover, VideoFeed, ContentVideo).

/** "3:12", "0:58", "1:02:05". */
export function formatVideoDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const rest = total % 60
  return hours > 0 ? `${hours}:${padTwo(minutes)}:${padTwo(rest)}` : `${minutes}:${padTwo(rest)}`
}

/** "00:46" for the detail player's clock (board ContentVideo). */
export function formatPlayerClock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  return `${padTwo(Math.floor(total / 60))}:${padTwo(total % 60)}`
}

/** "2380", "1.2万" (board VideoFeed). */
export function formatVideoCount(value: number): string {
  if (value >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`
  return String(Math.max(0, Math.floor(value)))
}

/** The badge after @author in the stream: 商家 / 服务商 / 官方 (board VideoFeed). */
export const VIDEO_AUTHOR_BADGES: Record<ContentAuthorKind, string> = {
  MERCHANT: '商家',
  PROVIDER: '服务商',
  PLATFORM: '官方',
}

/** The round avatar's colour: the board's orange for everyone (VideoFeed). */
export const VIDEO_AVATAR_COLOR = '#ff6a00'

export const REPORT_REASON_LABELS: Record<ContentCommentReportReason, string> = {
  AD_SPAM: '广告引流',
  ABUSE: '辱骂攻击',
  ILLEGAL: '违法违规',
  OTHER: '其他',
}

// The entry card above the caption (board VideoFeed): what kind of thing the video is tied to,
// with the board's icons.
const ENTRY_KINDS: Record<ContentRefType, { label: string; icon: string }> = {
  PRODUCT: { label: '关联商品', icon: 'M5 8h14l-1 12H6zM9 8V6a3 3 0 0 1 6 0v2' },
  INSPECTION_SERVICE: { label: '关联服务', icon: 'M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6zM9 12l2 2 4-4' },
  PRO_SERVICE: { label: '关联服务', icon: 'M4 6l5-2 6 2 5-2v14l-5 2-6-2-5 2zM9 4v14M15 6v14' },
  ACTIVITY: { label: '关联活动', icon: 'M6 21V4M6 4h11l-2 4 2 4H6' },
  VENUE: { label: '关联场馆', icon: 'M12 21s-7-6.5-7-12a7 7 0 0 1 14 0c0 5.5-7 12-7 12zM12 11.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z' },
}

export interface VideoEntryView {
  kind: string
  icon: string
  // "编程机器人探索套装 ¥1,299 起", "园区综合巡检服务 · 按项目报价".
  text: string
  cta: string
  url: string
}

/** The stream's entry card, made from the first mention as its module reads it now. */
export function videoEntry(view: ContentRefView | null | undefined): VideoEntryView | null {
  if (!view) return null
  const kind = ENTRY_KINDS[view.type]
  let price = ''
  if (view.price) {
    const amount = `${view.price.amount}${view.price.unit}`
    price = /^¥/.test(view.price.amount) ? ` ${amount}` : ` · ${amount}`
  }
  return { kind: kind.label, icon: kind.icon, text: `${view.title}${price}`, cta: view.actionLabel, url: view.url }
}

/** "刚刚", "12 分钟前", "3 小时前", "11/02" — Beijing time. */
export function formatCommentTime(iso: string, now: Date = new Date()): string {
  const at = new Date(iso)
  const minutes = Math.floor((now.getTime() - at.getTime()) / 60000)
  if (Number.isNaN(minutes)) return ''
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} 小时前`
  const parts = toBeijingParts(at)
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

/** The first character of a name for the round avatar. */
export function nameInitial(name: string): string {
  return Array.from(name.trim())[0] ?? '·'
}

/** "#编程机器人 #少儿编程". */
export function formatVideoTags(tags: readonly string[]): string {
  return tags.map((tag) => `#${tag}`).join(' ')
}
