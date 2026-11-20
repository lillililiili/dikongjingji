import { DAY_MS, formatBeijingTime, padTwo, toBeijingDate, toBeijingParts } from '../../../shared/time/beijing-time'

import type { LiveComment, LiveRoom, LiveRoomCard, LiveRoomStatus } from '../domain/live'

// What the live pages say, worked out from the server's facts. Texts and colours follow the boards
// Discover, LiveNotice and LiveRoom (2026-11-14); where the board was drawn for a partner platform
// (LiveNotice predates live in the App) the words say the room is watched here instead.

/** 12400 → "1.2万", as the boards write audience and like counts. */
export function formatLiveCount(value: number): string {
  return value >= 10000 ? `${(value / 10000).toFixed(1)}万` : String(Math.max(0, Math.floor(value)))
}

/** How long the room has been live: "42:17", or "1:02:03" past an hour. */
export function formatElapsed(startedAt: string | null, now: Date): string {
  const started = startedAt ? Date.parse(startedAt) : Number.NaN
  if (!Number.isFinite(started)) return '0:00'
  const seconds = Math.max(0, Math.floor((now.getTime() - started) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  return hours > 0 ? `${hours}:${padTwo(minutes)}:${padTwo(rest)}` : `${minutes}:${padTwo(rest)}`
}

/** "11/08 周日 19:30" in Beijing time (board LiveNotice). */
export function formatLiveDay(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${parts.weekday} ${formatBeijingTime(iso)}`
}

/** "11/08 19:30" (board Discover, 直播预告). */
export function formatLiveShort(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

/** "10/30" (board Discover, 往期回看). */
export function formatLiveDate(iso: string | null): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

/** How long a finished room ran: "52 分钟". */
export function formatLiveDuration(startedAt: string | null, endedAt: string | null): string | null {
  const started = startedAt ? Date.parse(startedAt) : Number.NaN
  const ended = endedAt ? Date.parse(endedAt) : Number.NaN
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) return null
  return `${Math.max(1, Math.round((ended - started) / 60000))} 分钟`
}

/** The chip next to the time on the preview page: "还有 5 天", "明天开播", "今天开播", "即将开播". */
export function scheduleChip(scheduledAt: string, now: Date): string {
  const at = new Date(scheduledAt)
  if (!Number.isFinite(at.getTime()) || at.getTime() <= now.getTime()) return '即将开播'
  const days = Math.round((Date.parse(toBeijingDate(at)) - Date.parse(toBeijingDate(now))) / DAY_MS)
  if (days <= 0) return '今天开播'
  if (days === 1) return '明天开播'
  return `还有 ${days} 天`
}

// ---- 直播预告 page (board LiveNotice) ----

export interface NoticeStep {
  n: number
  title: string
  note: string
  state: 'past' | 'now' | 'later'
}

export interface NoticeView {
  stage: 0 | 1 | 2
  tag: { text: string; background: string }
  chip: { text: string; background: string; color: string }
  watchNote: string
  // The main button besides 咨询: 提醒我 (a preview), 进直播间, 去合作平台回看, or none.
  action: 'REMIND' | 'ENTER' | 'REPLAY' | null
  actionLabel: string
  steps: NoticeStep[]
  // "[合作平台名称]" on the board: who plays the replay, only for a finished room that has one.
  platformName: string | null
}

export function noticeView(room: Pick<LiveRoom, 'status' | 'scheduledAt' | 'replayUrl' | 'replayPlatformName'>, now: Date): NoticeView {
  const stage = room.status === 'SCHEDULED' ? 0 : room.status === 'LIVE' ? 1 : 2
  const replay = Boolean(room.replayUrl)
  const steps: NoticeStep[] = [
    ['预告', '可设提醒'],
    ['直播', 'App 内观看'],
    ['回看', replay || stage < 2 ? '合作平台提供' : '本场没有回看'],
  ].map(([title, note], index) => ({
    n: index + 1,
    title,
    note,
    state: index < stage ? 'past' : index === stage ? 'now' : 'later',
  }))
  if (stage === 0) {
    return {
      stage,
      tag: { text: '直播预告', background: '#6a4fe0' },
      chip: { text: scheduleChip(room.scheduledAt, now), background: '#eee7ff', color: '#6a4fe0' },
      watchNote: '开播后在本 App 的直播间观看。设提醒后，开播时会在消息里通知你。',
      action: 'REMIND',
      actionLabel: '提醒我',
      steps,
      platformName: null,
    }
  }
  if (stage === 1) {
    return {
      stage,
      tag: { text: '直播中', background: '#d64f67' },
      chip: { text: '正在播出', background: '#fdecec', color: '#d64f67' },
      watchNote: '正在直播，进直播间可以看画面、发弹幕和看讲解清单；交易在平台内完成，直播间不收款。',
      action: 'ENTER',
      actionLabel: '进直播间',
      steps,
      platformName: null,
    }
  }
  return {
    stage,
    tag: { text: replay ? '已结束 · 可回看' : '已结束', background: 'rgba(0,0,0,0.55)' },
    chip: { text: '已结束', background: '#eef2f8', color: '#536178' },
    watchNote: replay
      ? '回看由合作平台提供，本 App 不保存画面，能看多久以对方的规定为准。'
      : '直播已结束，这场没有回看。',
    action: replay ? 'REPLAY' : null,
    actionLabel: replay ? '去合作平台回看' : '',
    steps,
    platformName: replay ? room.replayPlatformName : null,
  }
}

// ---- 发现 (board Discover) ----

export const LIVE_TAB = 'LIVE' as const
export const LIVE_TAB_LABEL = '直播'
export const LIVE_CHANNEL_NOTE = '平台内直播可互动、看讲解清单；交易在平台内完成，直播间不收款。'

/**
 * The discover tabs with 直播 in the board's place: after 视频 when the channel list has one,
 * otherwise right after 推荐 (board order 推荐 / 视频 / 直播 / 赛事 / 研学 / 案例 / 知识).
 */
export function withLiveTab<T extends string>(channels: readonly T[]): Array<T | typeof LIVE_TAB> {
  const list: Array<T | typeof LIVE_TAB> = [...channels]
  if (list.includes(LIVE_TAB)) return list
  const after = list.indexOf('VIDEO' as T) >= 0 ? list.indexOf('VIDEO' as T) : list.indexOf('RECOMMEND' as T)
  list.splice(after + 1, 0, LIVE_TAB)
  return list
}

// The board's avatar colours by the host's first character; others are violet.
const AVATAR_COLORS: Record<string, string> = { 示: '#ff6a00', 鹰: '#11804a', 东: '#1d63c4' }

export function hostInitial(name: string): string {
  return Array.from(name.trim())[0] ?? '播'
}

export function hostAvatarColor(name: string): string {
  return AVATAR_COLORS[hostInitial(name)] ?? '#6a4fe0'
}

export interface LiveChannelSections {
  live: LiveRoomCard[]
  scheduled: LiveRoomCard[]
  replays: LiveRoomCard[]
}

/** The 直播 channel: 正在直播, 直播预告, 往期回看, each in the server's order. */
export function splitLiveChannel(cards: readonly LiveRoomCard[]): LiveChannelSections {
  return {
    live: cards.filter((card) => card.status === 'LIVE'),
    scheduled: cards.filter((card) => card.status === 'SCHEDULED'),
    replays: cards.filter((card) => card.status === 'ENDED' && card.hasReplay),
  }
}

/** "鹰眼智能巡检 · 正在讲解 园区安防巡检" under the live bar's title. */
export function liveBarLine(card: Pick<LiveRoomCard, 'host' | 'currentItem'>): string {
  return card.currentItem ? `${card.host.name} · 正在讲解 ${card.currentItem.title}` : card.host.name
}

export function liveStatusLabel(status: LiveRoomStatus): string {
  return status === 'LIVE' ? '直播中' : status === 'SCHEDULED' ? '预告' : '回看'
}

// ---- 直播间 (board LiveRoom) ----

export interface ChatLine {
  key: string
  name: string
  text: string
  color: string
}

const WELCOME: ChatLine = {
  key: 'welcome',
  name: '系统',
  text: '欢迎来到直播间，文明互动；交易请在平台内下单',
  color: '#ffc53d',
}

/**
 * The lines over the picture: the welcome line, then the newest comments, five in all (board
 * LiveRoom). The host's lines read 主播, the viewer's own read 我.
 */
export function chatLines(comments: readonly LiveComment[], mine: ReadonlySet<string>, shown = 5): ChatLine[] {
  const lines = [WELCOME, ...comments.map((comment): ChatLine => {
    const own = mine.has(comment.commentId)
    return {
      key: comment.commentId,
      name: comment.side === 'HOST' ? '主播' : own ? '我' : comment.authorName,
      text: comment.text,
      color: comment.side === 'HOST' ? '#ff8a5b' : own ? '#7dd3fc' : '#9fe1cb',
    }
  })]
  return lines.slice(-shown)
}
