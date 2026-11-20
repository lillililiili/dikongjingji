import { formatMessageTime } from '../../message/presentation/message-format'

import type {
  SupportConversation,
  SupportCounterpart,
  SupportFeedbackType,
  SupportMessage,
} from '../domain/support'
import type { ChatOutgoing } from './chat-session'

// A random UUID (version 4 layout) for the Idempotency-Key header.
export function createSupportKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

export type ChatLine =
  | { key: string; kind: 'TIME'; text: string }
  | { key: string; kind: 'MESSAGE'; message: SupportMessage }
  | { key: string; kind: 'OUTGOING'; outgoing: ChatOutgoing }

// A time line opens the thread and every stretch after five quiet minutes, as on the Chat board.
const TIME_GAP_MS = 5 * 60 * 1000

export function buildChatLines(messages: SupportMessage[], outgoing: ChatOutgoing[], now: Date): ChatLine[] {
  const lines: ChatLine[] = []
  let previous: number | null = null
  for (const message of messages) {
    const at = new Date(message.sentAt).getTime()
    if (Number.isFinite(at) && (previous === null || at - previous >= TIME_GAP_MS)) {
      lines.push({ key: `t-${message.messageId}`, kind: 'TIME', text: formatMessageTime(message.sentAt, now) })
    }
    if (Number.isFinite(at)) previous = at
    lines.push({ key: `m-${message.messageId}`, kind: 'MESSAGE', message })
  }
  for (const item of outgoing) lines.push({ key: `o-${item.localId}`, kind: 'OUTGOING', outgoing: item })
  return lines
}

export interface ChatPeer {
  title: string
  sub: string
  placeholder: string
  // The first character of the organization; null draws the headset of the platform support.
  initial: string | null
  // The button on the right of the header: 帮助 for the platform support, the provider's 主页.
  action: { kind: 'HELP' | 'PROVIDER'; label: string } | null
  // The grey line on top of the thread.
  note: string
}

// The platform helps both sides talk; it does not decide who is right (design assumption 20).
export function describeChatPeer(counterpart: SupportCounterpart): ChatPeer {
  if (counterpart.kind === 'PLATFORM') {
    return {
      title: counterpart.name || '平台客服',
      sub: '订单、售后和投诉都可以在这里说',
      placeholder: '说说你遇到的问题',
      initial: null,
      action: { kind: 'HELP', label: '帮助' },
      note: '平台只协助沟通，不裁决；请勿在会话外付款或透露验证码',
    }
  }
  const merchant = counterpart.kind === 'MERCHANT'
  return {
    title: counterpart.name,
    sub: merchant ? '商品与售后问题由商家回复' : '项目与排期问题由服务商回复',
    placeholder: merchant ? '问问商家' : '问问服务商',
    initial: Array.from(counterpart.name.trim())[0] ?? '?',
    action: merchant ? null : { kind: 'PROVIDER', label: '主页' },
    note: '会话会保存，请勿在会话外付款或透露验证码',
  }
}

// The greeting of the platform support in a conversation with no messages yet (board Chat). It is a
// constant of the page, never stored or sent, and disappears once the thread has messages.
export const PLATFORM_WELCOME_TEXT = '你好，这里是平台客服。订单、售后和投诉都可以在这里说，我们协助你和商家、服务商沟通。'

export const SUPPORT_KIND_LABELS = { PLATFORM: '平台客服', MERCHANT: '商家', PROVIDER: '服务商' } as const

// The 客服会话 rows of the message tab: newest first as the server sends them.
export interface ConversationRow {
  conversationId: string
  title: string
  preview: string
  time: string
  // When the last message was sent, so the message tab can place the row among notifications.
  at: string | null
  unreadCount: number
  initial: string | null
}

export function toConversationRow(conversation: SupportConversation, now: Date): ConversationRow {
  const last = conversation.lastMessage
  const mine = last?.senderSide === 'CUSTOMER'
  return {
    conversationId: conversation.conversationId,
    title: conversation.counterpart.name,
    preview: last ? `${mine ? '我：' : ''}${last.kind === 'CARD' ? `[卡片] ${last.preview}` : last.preview}` : '',
    time: last ? formatMessageTime(last.sentAt, now) : '',
    at: last?.sentAt ?? conversation.createdAt,
    unreadCount: conversation.unreadCount,
    initial: conversation.counterpart.kind === 'PLATFORM' ? null : Array.from(conversation.counterpart.name.trim())[0] ?? '?',
  }
}

export const formatUnreadBadge = (count: number): string => (count > 99 ? '99+' : String(count))

export const SUPPORT_FEEDBACK_LABELS: Record<SupportFeedbackType, string> = {
  FUNCTION: '功能问题',
  DISPLAY: '页面显示',
  CONTENT: '内容有误',
  SUGGESTION: '其他建议',
}
