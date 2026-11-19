// Mirrors contracts/openapi-support.yaml 1.0.0 (step G1, plan §26). The server decides who may read
// what: the customer is always the account of the access token, and a counterpart is only ever
// shown by kind and name (no phone numbers, no operator names).

export const SUPPORT_COUNTERPART_KINDS = ['PLATFORM', 'MERCHANT', 'PROVIDER'] as const
export type SupportCounterpartKind = (typeof SUPPORT_COUNTERPART_KINDS)[number]

export type SupportSenderSide = 'CUSTOMER' | 'COUNTERPART'
export type SupportMessageKind = 'TEXT' | 'CARD'

// What a "关于什么" card is about; refId is that thing's stable id.
export const SUPPORT_CARD_TYPES = [
  'PRODUCT',
  'MALL_ORDER',
  'MALL_INQUIRY',
  'INSPECTION_SERVICE',
  'INSPECTION_ORDER',
  'PRO_SERVICE',
  'ACTIVITY',
  'ACTIVITY_ENROLLMENT',
  'VENUE',
  'VENUE_BOOKING',
  'RENTAL_INQUIRY',
  'RENTAL_ORDER',
  'DEMAND',
] as const
export type SupportCardType = (typeof SUPPORT_CARD_TYPES)[number]

export const SUPPORT_FEEDBACK_TYPES = ['FUNCTION', 'DISPLAY', 'CONTENT', 'SUGGESTION'] as const
export type SupportFeedbackType = (typeof SUPPORT_FEEDBACK_TYPES)[number]

export interface SupportCounterpart {
  kind: SupportCounterpartKind
  // The merchant's or provider's organization; null for the platform support.
  organizationId: string | null
  name: string
}

export interface SupportLastMessage {
  senderSide: SupportSenderSide
  kind: SupportMessageKind
  preview: string
  sentAt: string
}

export interface SupportConversation {
  conversationId: string
  counterpart: SupportCounterpart
  lastMessage: SupportLastMessage | null
  // Counterpart messages this customer has not read.
  unreadCount: number
  createdAt: string
}

export interface SupportConversationPage {
  hasMore: boolean
  total: number
  // All conversations together.
  unreadCount: number
  items: SupportConversation[]
}

export interface SupportCard {
  type: SupportCardType
  refId: string
  title: string
  subtitle: string | null
}

export interface SupportMessage {
  messageId: string
  // Position in the conversation, from 1.
  seq: number
  senderSide: SupportSenderSide
  // Sent by this customer.
  mine: boolean
  kind: SupportMessageKind
  text: string | null
  card: SupportCard | null
  sentAt: string
}

export interface SupportMessagePage {
  conversation: SupportConversation
  // Oldest first.
  items: SupportMessage[]
  hasMore: boolean
  // The `before` of the next older page; null when there is none.
  nextBefore: number | null
}

export interface SupportFeedback {
  feedbackId: string
  type: SupportFeedbackType
  text: string
  createdAt: string
}

export type SupportMessageBody = { text: string } | { card: SupportCard }

export const SUPPORT_TEXT_LIMIT = 500
export const SUPPORT_CARD_TEXT_LIMIT = 60
export const SUPPORT_FEEDBACK_LIMIT = 200
export const SUPPORT_MESSAGE_PAGE_SIZE = 20
