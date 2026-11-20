import {
  DAY_MS,
  formatBeijingTime,
  toBeijingDate,
  toBeijingParts,
} from '../../../shared/time/beijing-time'

import type { MessageCategory, MessageItem } from '../domain/message'

export const MESSAGE_CATEGORY_LABELS: Record<MessageCategory, string> = {
  SERVICE_PROGRESS: '服务进度',
  DEMAND_QUOTE: '需求与报价',
  CUSTOMER_SERVICE: '客服消息',
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
