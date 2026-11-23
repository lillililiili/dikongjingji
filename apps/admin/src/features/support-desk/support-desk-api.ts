import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-support.yaml 1.0.0 的 /api/v1/provider/conversations…（SupportDeskController 的商家、服务商一侧）：
// 本机构作为商家或服务商的客服会话。客户只给显示名，不交换手机号；客户也看不到是哪个操作员回的。

registerErrorMessages({
  SUPPORT_IDEMPOTENCY_KEY_REUSED: '这条回复和刚才提交的内容不一致，请重新发送',
  SUPPORT_COUNTERPART_UNAVAILABLE: '这个会话现在不能回复',
})

export type CounterpartKind = 'PLATFORM' | 'MERCHANT' | 'PROVIDER'
export type SenderSide = 'CUSTOMER' | 'COUNTERPART'
export type MessageKind = 'TEXT' | 'CARD'
export type CardType = 'PRODUCT' | 'MALL_ORDER' | 'MALL_INQUIRY' | 'INSPECTION_SERVICE' | 'INSPECTION_ORDER'
  | 'PRO_SERVICE' | 'ACTIVITY' | 'ACTIVITY_ENROLLMENT' | 'VENUE' | 'VENUE_BOOKING' | 'RENTAL_INQUIRY'
  | 'RENTAL_ORDER' | 'DEMAND'

export interface LastMessage {
  senderSide: SenderSide
  kind: MessageKind
  preview: string
  sentAt: string
}

export interface DeskConversation {
  conversationId: string
  counterpartKind: CounterpartKind
  customer: { name: string }
  lastMessage: LastMessage | null
  unreadCount: number
  createdAt: string
}

export interface DeskConversationPage extends Page<DeskConversation> {
  /** 本机构所有会话的未读合计。 */
  unreadCount: number
}

export interface SupportCard {
  type: CardType
  refId: string
  title: string
  subtitle: string | null
}

export interface SupportMessage {
  messageId: string
  seq: number
  senderSide: SenderSide
  mine: boolean
  kind: MessageKind
  text: string | null
  card: SupportCard | null
  sentAt: string
}

export interface DeskMessagePage {
  conversation: DeskConversation
  /** 按时间先后。 */
  items: SupportMessage[]
  hasMore: boolean
  /** 还有更早的消息时，下一页的 before。 */
  nextBefore: number | null
}

export const REPLY_MAX = 500
export const MESSAGE_PAGE_SIZE = 30

export const CARD_TYPE_LABELS: Record<CardType, string> = {
  PRODUCT: '商品',
  MALL_ORDER: '商城订单',
  MALL_INQUIRY: '商品询价单',
  INSPECTION_SERVICE: '巡检服务',
  INSPECTION_ORDER: '巡检服务单',
  PRO_SERVICE: '专业服务',
  ACTIVITY: '活动',
  ACTIVITY_ENROLLMENT: '活动报名',
  VENUE: '场馆',
  VENUE_BOOKING: '到馆预约',
  RENTAL_INQUIRY: '租赁询价单',
  RENTAL_ORDER: '租赁订单',
  DEMAND: '需求',
}

/** 客户找的是本机构的哪一面：商家（商城、租赁、询价）还是服务商（巡检、专业服务）。 */
export const COUNTERPART_LABELS: Record<CounterpartKind, string> = {
  PLATFORM: '平台客服',
  MERCHANT: '找商家',
  PROVIDER: '找服务商',
}

/** 列表里最后一条：谁说的 + 前 60 个字（卡片是标题）。 */
export function lastMessageLabel(message: LastMessage | null): string {
  if (!message) return '—'
  const who = message.senderSide === 'CUSTOMER' ? '客户' : '我方'
  const body = message.kind === 'CARD' ? `[卡片] ${message.preview}` : message.preview
  return `${who}：${body}`
}

// 11 位手机号（前后不是数字）。平台不交换手机号，回复里也不要留。
const MOBILE = /(^|\D)1[3-9]\d{9}(?!\d)/
// 换行、回车、制表符以外的控制字符，后端也拒绝。
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/

/** 回复前检查：1–500 字（按码点、去掉首尾空白）、不含控制字符、不留手机号。返回第一条错误或 null。 */
export function replyError(text: string): string | null {
  const value = text.trim()
  if (!value) return '请先写回复内容'
  if (charCount(value) > REPLY_MAX) return `回复最多 ${REPLY_MAX} 个字`
  if (CONTROL.test(value)) return '回复里有不能显示的字符，请删掉后再发'
  if (MOBILE.test(value)) return '平台不交换手机号，回复里请不要留手机号，有事在这里沟通'
  return null
}

export function createSupportDeskApi(http: ApiClient) {
  const base = '/api/v1/provider/conversations'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    conversations(_filters: object, page: number, size: number): Promise<DeskConversationPage> {
      return http.get<DeskConversationPage>(base, { page, size })
    },
    /** 不带 before 是最新一页；往前翻带上一页的 nextBefore。不改未读，读过要调 read。 */
    messages(id: string, before: number | null = null, size = MESSAGE_PAGE_SIZE): Promise<DeskMessagePage> {
      return http.get<DeskMessagePage>(`${one(id)}/messages`, { before, size })
    },
    /** 同一个幂等键带同样的内容重复提交，返回第一次那条。 */
    reply(id: string, text: string, idempotencyKey: string): Promise<SupportMessage> {
      return http.post<SupportMessage>(`${one(id)}/messages`, { text: text.trim() }, { idempotencyKey })
    },
    read(id: string): Promise<DeskConversation> {
      return http.post<DeskConversation>(`${one(id)}/read`)
    },
  }
}

export type SupportDeskApi = ReturnType<typeof createSupportDeskApi>

/** 往前翻页拿到的更早消息接在前面，按 seq 去重、排序（同一条不会出现两次）。 */
export function mergeMessages(current: SupportMessage[], incoming: SupportMessage[]): SupportMessage[] {
  const bySeq = new Map<number, SupportMessage>()
  for (const message of [...current, ...incoming]) bySeq.set(message.seq, message)
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq)
}
