import {
  SUPPORT_CARD_TYPES,
  SUPPORT_COUNTERPART_KINDS,
  SUPPORT_FEEDBACK_TYPES,
  type SupportCard,
  type SupportCardType,
  type SupportConversation,
  type SupportConversationPage,
  type SupportCounterpartKind,
  type SupportFeedback,
  type SupportFeedbackType,
  type SupportLastMessage,
  type SupportMessage,
  type SupportMessageBody,
  type SupportMessagePage,
} from '../domain/support'

type SupportRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The DTOs as they arrive. Fields are read defensively: a row the page cannot draw is dropped
// rather than drawn wrong.
interface CounterpartDto { kind?: string; organizationId?: string | null; name?: string }
interface LastMessageDto { senderSide?: string; kind?: string; preview?: string; sentAt?: string }
interface ConversationDto {
  conversationId: string
  counterpart?: CounterpartDto
  lastMessage?: LastMessageDto | null
  unreadCount?: number
  createdAt?: string
}
interface CardDto { type?: string; refId?: string; title?: string; subtitle?: string | null }
interface MessageDto {
  messageId: string
  seq: number
  senderSide?: string
  mine?: boolean
  kind?: string
  text?: string | null
  card?: CardDto | null
  sentAt?: string
}
interface ConversationPageDto { hasMore?: boolean; total?: number; unreadCount?: number; items?: ConversationDto[] }
interface MessagePageDto { conversation: ConversationDto; items?: MessageDto[]; hasMore?: boolean; nextBefore?: number | null }
interface FeedbackDto { feedbackId: string; type?: string; text?: string; createdAt?: string }

export interface SupportApi {
  listConversations(accessToken: string, page: number, size: number): Promise<SupportConversationPage>
  // The one conversation with this counterpart; made when there is none yet.
  openConversation(accessToken: string, kind: SupportCounterpartKind, organizationId: string | null): Promise<SupportConversation>
  loadMessages(accessToken: string, conversationId: string, before: number | null, size: number): Promise<SupportMessagePage>
  sendMessage(accessToken: string, conversationId: string, idempotencyKey: string, body: SupportMessageBody): Promise<SupportMessage>
  markRead(accessToken: string, conversationId: string): Promise<SupportConversation>
  submitFeedback(accessToken: string, idempotencyKey: string, type: SupportFeedbackType, text: string): Promise<SupportFeedback>
}

const isCardType = (value: unknown): value is SupportCardType =>
  typeof value === 'string' && (SUPPORT_CARD_TYPES as readonly string[]).includes(value)

const toKind = (value: unknown): SupportCounterpartKind =>
  typeof value === 'string' && (SUPPORT_COUNTERPART_KINDS as readonly string[]).includes(value)
    ? value as SupportCounterpartKind
    : 'PLATFORM'

function toLastMessage(dto: LastMessageDto | null | undefined): SupportLastMessage | null {
  if (!dto || !dto.sentAt) return null
  return {
    senderSide: dto.senderSide === 'COUNTERPART' ? 'COUNTERPART' : 'CUSTOMER',
    kind: dto.kind === 'CARD' ? 'CARD' : 'TEXT',
    preview: dto.preview ?? '',
    sentAt: dto.sentAt,
  }
}

export function toSupportConversation(dto: ConversationDto): SupportConversation {
  const kind = toKind(dto.counterpart?.kind)
  return {
    conversationId: dto.conversationId,
    counterpart: {
      kind,
      organizationId: kind === 'PLATFORM' ? null : dto.counterpart?.organizationId ?? null,
      name: dto.counterpart?.name?.trim() || (kind === 'PLATFORM' ? '平台客服' : '对方'),
    },
    lastMessage: toLastMessage(dto.lastMessage),
    unreadCount: Math.max(0, dto.unreadCount ?? 0),
    createdAt: dto.createdAt ?? '',
  }
}

function toCard(dto: CardDto | null | undefined): SupportCard | null {
  if (!dto || !isCardType(dto.type) || !dto.refId) return null
  return { type: dto.type, refId: dto.refId, title: dto.title ?? '', subtitle: dto.subtitle?.trim() || null }
}

export function toSupportMessage(dto: MessageDto): SupportMessage | null {
  const card = toCard(dto.card)
  const kind = dto.kind === 'CARD' ? 'CARD' : 'TEXT'
  if (kind === 'CARD' && !card) return null
  if (kind === 'TEXT' && typeof dto.text !== 'string') return null
  return {
    messageId: dto.messageId,
    seq: dto.seq,
    senderSide: dto.senderSide === 'COUNTERPART' ? 'COUNTERPART' : 'CUSTOMER',
    mine: dto.mine === true,
    kind,
    text: kind === 'TEXT' ? dto.text ?? '' : null,
    card: kind === 'CARD' ? card : null,
    sentAt: dto.sentAt ?? '',
  }
}

function toFeedbackType(value: unknown): SupportFeedbackType {
  return typeof value === 'string' && (SUPPORT_FEEDBACK_TYPES as readonly string[]).includes(value)
    ? value as SupportFeedbackType
    : 'SUGGESTION'
}

// Every call carries the access token it was given; the caller decides what a 401 means.
export function createSupportApi(request: SupportRequest): SupportApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const path = (conversationId: string) => `/api/v1/conversations/${encodeURIComponent(conversationId)}`
  return {
    async listConversations(accessToken, page, size) {
      const dto = await request<ConversationPageDto>({
        url: `/api/v1/conversations?page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        hasMore: dto.hasMore === true,
        total: dto.total ?? 0,
        unreadCount: Math.max(0, dto.unreadCount ?? 0),
        items: (dto.items ?? []).map(toSupportConversation),
      }
    },
    async openConversation(accessToken, kind, organizationId) {
      const dto = await request<ConversationDto>({
        url: '/api/v1/conversations',
        method: 'POST',
        headers: auth(accessToken),
        data: kind === 'PLATFORM' ? { counterpartKind: kind } : { counterpartKind: kind, organizationId },
      })
      return toSupportConversation(dto)
    },
    async loadMessages(accessToken, conversationId, before, size) {
      const query = [`size=${size}`, ...(before === null ? [] : [`before=${before}`])].join('&')
      const dto = await request<MessagePageDto>({
        url: `${path(conversationId)}/messages?${query}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        conversation: toSupportConversation(dto.conversation),
        items: (dto.items ?? []).flatMap((item) => {
          const message = toSupportMessage(item)
          return message ? [message] : []
        }),
        hasMore: dto.hasMore === true,
        nextBefore: typeof dto.nextBefore === 'number' && dto.nextBefore > 0 ? dto.nextBefore : null,
      }
    },
    async sendMessage(accessToken, conversationId, idempotencyKey, body) {
      const data = 'text' in body
        ? { text: body.text }
        : { card: { type: body.card.type, refId: body.card.refId, title: body.card.title, subtitle: body.card.subtitle } }
      const dto = await request<MessageDto>({
        url: `${path(conversationId)}/messages`,
        method: 'POST',
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
        data,
      })
      const message = toSupportMessage(dto)
      if (!message) throw new Error('消息发送结果无效')
      return message
    },
    async markRead(accessToken, conversationId) {
      const dto = await request<ConversationDto>({
        url: `${path(conversationId)}/read`,
        method: 'POST',
        headers: auth(accessToken),
      })
      return toSupportConversation(dto)
    },
    async submitFeedback(accessToken, idempotencyKey, type, text) {
      const dto = await request<FeedbackDto>({
        url: '/api/v1/feedback',
        method: 'POST',
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
        data: { type, text },
      })
      return {
        feedbackId: dto.feedbackId,
        type: toFeedbackType(dto.type),
        text: dto.text ?? text,
        createdAt: dto.createdAt ?? '',
      }
    },
  }
}
