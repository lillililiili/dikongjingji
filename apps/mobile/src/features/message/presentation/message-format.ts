import {
  DAY_MS,
  formatBeijingTime,
  toBeijingDate,
  toBeijingParts,
} from '../../../shared/time/beijing-time'

import type { IconTileTone } from '../../../shared/ui/icon-tile-tones'

import type { MessageCategory, MessageItem } from '../domain/message'

export const MESSAGE_CATEGORY_LABELS: Record<MessageCategory, string> = {
  SERVICE_PROGRESS: '服务进度',
  DEMAND_QUOTE: '需求与报价',
  CUSTOMER_SERVICE: '客服消息',
  TRADE_LOGISTICS: '交易物流',
  ACTIVITY_REMINDER: '活动提醒',
  LIVE_REMINDER: '直播提醒',
  OTHER: '通知',
}

// Icons from the board Messages (`icon` in renderVals); 需求与报价 has no square on the board.
export const MESSAGE_ICONS = {
  truck: 'M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a1.5 1.5 0 1 0 0-.1M17 19a1.5 1.5 0 1 0 0-.1',
  progress: 'M9 4h6v3H9zM7 5H5v16h14V5h-2M9 14l2 2 4-4',
  flag: 'M6 21V4M6 4h11l-2 4 2 4H6',
  bell: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  service: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  calendar: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4',
  demand: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  check: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8 12l3 3 5-6',
  chat: 'M4 5h16v11H9l-5 4zM8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01',
  // 直播提醒 (M20) has no row on the board Messages: a screen with a play mark, in the violet of the
  // board Discover's 预告 tag.
  live: 'M3 6h18v12H3zM10 9.5v5l4.5-2.5z',
} as const

export interface MessageLook {
  label: string
  d: string
  tone: IconTileTone
  // The board's grey (系统通知) is not one of the tile tones; it overrides the tone's colours.
  bg?: string
  ink?: string
}

/**
 * How a notification row looks, as on the board: 交易物流 orange truck, 服务进度 blue, 活动提醒 green flag
 * (a venue booking reads 体验预约 with a calendar, like the board's 到馆提醒), 客服 orange; a category
 * this version does not know is a grey 通知 bell.
 */
export function messageLook(message: Pick<MessageItem, 'category' | 'target'>): MessageLook {
  switch (message.category) {
    case 'TRADE_LOGISTICS': return { label: '交易物流', d: MESSAGE_ICONS.truck, tone: 'orange' }
    case 'SERVICE_PROGRESS': return { label: '服务进度', d: MESSAGE_ICONS.progress, tone: 'blue' }
    case 'ACTIVITY_REMINDER':
      return message.target?.type === 'VENUE_BOOKING'
        ? { label: '体验预约', d: MESSAGE_ICONS.calendar, tone: 'green' }
        : { label: '活动提醒', d: MESSAGE_ICONS.flag, tone: 'green' }
    case 'DEMAND_QUOTE': return { label: '需求与报价', d: MESSAGE_ICONS.demand, tone: 'violet' }
    case 'CUSTOMER_SERVICE': return { label: '客服消息', d: MESSAGE_ICONS.service, tone: 'orange' }
    case 'LIVE_REMINDER': return { label: '直播提醒', d: MESSAGE_ICONS.live, tone: 'violet' }
    default: return { label: '通知', d: MESSAGE_ICONS.bell, tone: 'blue', bg: '#eef2f8', ink: '#536178' }
  }
}

// The category squares of the board, in its order (系统通知 is not built). Each filters the list.
export type MessageSquare = 'TRADE_LOGISTICS' | 'SERVICE_PROGRESS' | 'ACTIVITY_REMINDER'

export const MESSAGE_SQUARES: Record<MessageSquare, { label: string; d: string; tone: IconTileTone; testId: string }> = {
  TRADE_LOGISTICS: { label: '交易物流', d: MESSAGE_ICONS.truck, tone: 'orange', testId: 'message-cat-trade' },
  SERVICE_PROGRESS: { label: '服务进度', d: MESSAGE_ICONS.progress, tone: 'blue', testId: 'message-cat-progress' },
  ACTIVITY_REMINDER: { label: '活动提醒', d: MESSAGE_ICONS.flag, tone: 'green', testId: 'message-cat-activity' },
}

export const MESSAGE_SQUARE_ORDER: readonly MessageSquare[] = ['TRADE_LOGISTICS', 'SERVICE_PROGRESS', 'ACTIVITY_REMINDER']

/**
 * A square's badge counts its unread notifications. The count is only known once every page is
 * loaded, so while more pages wait the square shows no number rather than a partial one.
 */
export function squareUnread(items: MessageItem[], hasMore: boolean, square: MessageSquare): number {
  if (hasMore) return 0
  return items.filter((item) => item.category === square && !item.read).length
}

export function emptyCopy(filter: MessageSquare | null, squares: readonly MessageSquare[]): { title: string; copy: string } {
  switch (filter) {
    case 'TRADE_LOGISTICS': return { title: '没有交易物流消息', copy: '商品订单发货、可以自提和退款到账会通知到这里' }
    case 'SERVICE_PROGRESS': return { title: '没有服务进度消息', copy: '巡检方案、排期、报告交付等进度会通知到这里' }
    case 'ACTIVITY_REMINDER': return { title: '没有活动提醒', copy: '报名成功、退款和到馆预约、改期会通知到这里' }
    default:
      return {
        title: '还没有消息',
        copy: squares.length > 1
          ? '订单物流、服务进度、报名和预约的通知都在这里'
          : '巡检方案、排期、报告交付等进度会通知到这里',
      }
  }
}

// "今天 14:05", "昨天 09:30", otherwise "11/3 16:20". Days are Beijing days.
export function formatMessageTime(iso: string, now: Date): string {
  const at = new Date(iso)
  const time = formatBeijingTime(iso)
  const day = toBeijingDate(at)
  if (day === toBeijingDate(now)) return `今天 ${time}`
  if (day === toBeijingDate(new Date(now.getTime() - DAY_MS))) return `昨天 ${time}`
  const parts = toBeijingParts(at)
  return `${parts.month}/${parts.day} ${time}`
}

// A 客服会话 row (features/support/application/chat-view.ts ConversationRow), passed in by the page
// host so this feature does not read conversations itself.
export interface MessageConversationRow {
  conversationId: string
  title: string
  preview: string
  time: string
  // When the last message was sent (ISO); rows are placed among the notifications by it.
  at: string | null
  unreadCount: number
  // First character of the merchant or provider; null draws the platform support's headset.
  initial: string | null
}

// Board Messages: one list, newest first, with the 客服 conversations among the notifications.
export type MessageRow =
  | { kind: 'notice'; key: string; message: MessageItem }
  | { kind: 'conversation'; key: string; row: MessageConversationRow }

const timeOf = (iso: string | null): number => {
  const value = iso ? new Date(iso).getTime() : Number.NaN
  return Number.isFinite(value) ? value : 0
}

/**
 * Notifications come a page at a time. While more pages wait, a conversation older than the last
 * loaded notification is held back, so it does not sit above notifications that are still to come.
 */
export function mergeMessageRows(
  notices: MessageItem[],
  conversations: MessageConversationRow[],
  hasMore: boolean,
): MessageRow[] {
  const oldestNotice = notices.length ? Math.min(...notices.map((item) => timeOf(item.occurredAt))) : 0
  const rows: Array<MessageRow & { at: number }> = [
    ...notices.map((message) => ({ kind: 'notice' as const, key: `n-${message.id}`, message, at: timeOf(message.occurredAt) })),
    ...conversations
      .filter((row) => !hasMore || timeOf(row.at) >= oldestNotice)
      .map((row) => ({ kind: 'conversation' as const, key: `c-${row.conversationId}`, row, at: timeOf(row.at) })),
  ]
  // A stable sort keeps the server's order among items of the same time.
  return rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => b.row.at - a.row.at || a.index - b.index)
    .map(({ row }): MessageRow => (row.kind === 'notice'
      ? { kind: 'notice', key: row.key, message: row.message }
      : { kind: 'conversation', key: row.key, row: row.row }))
}
