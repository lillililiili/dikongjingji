// Mirrors contracts/openapi-notification.yaml. The server decides who may read what; the recipient
// is always the signed-in account.

// 1.1.0 adds DEMAND_QUOTE (需求与报价) with the target DEMAND: id is the demand, subId is null.
// 1.2.0 adds CUSTOMER_SERVICE (客服消息, a reply in a conversation) with the target CONVERSATION: id
// is the conversation, subId is null.
// 1.3.0 (R6-5) adds TRADE_LOGISTICS (交易物流) with the target TRADE_ORDER, and ACTIVITY_REMINDER
// (活动提醒) with ACTIVITY_ENROLLMENT (the sign-up's tickets), ACTIVITY_ORDER (a paid sign-up's
// order) and VENUE_BOOKING; all four ids are the business object, subId is null.
export type KnownMessageCategory =
  | 'SERVICE_PROGRESS'
  | 'DEMAND_QUOTE'
  | 'CUSTOMER_SERVICE'
  | 'TRADE_LOGISTICS'
  | 'ACTIVITY_REMINDER'

// A category a later contract adds is read as OTHER: shown as a plain 通知 row that opens nothing.
export type MessageCategory = KnownMessageCategory | 'OTHER'

export type MessageTargetType =
  | 'INSPECTION_PLAN'
  | 'INSPECTION_FULFILLMENT'
  | 'INSPECTION_DELIVERY'
  | 'DEMAND'
  | 'CONVERSATION'
  | 'TRADE_ORDER'
  | 'ACTIVITY_ENROLLMENT'
  | 'ACTIVITY_ORDER'
  | 'VENUE_BOOKING'

export interface MessageTarget {
  type: MessageTargetType
  id: string
  subId: string | null
}

export interface MessageItem {
  id: string
  category: MessageCategory
  title: string
  body: string
  // null when the server names a page this version does not know: the row opens nothing.
  target: MessageTarget | null
  occurredAt: string
  read: boolean
}

export interface MessagePage {
  hasMore: boolean
  unreadCount: number
  items: MessageItem[]
}

export const MESSAGE_PAGE_SIZE = 20

const KNOWN_CATEGORIES: readonly KnownMessageCategory[] = [
  'SERVICE_PROGRESS',
  'DEMAND_QUOTE',
  'CUSTOMER_SERVICE',
  'TRADE_LOGISTICS',
  'ACTIVITY_REMINDER',
]

const KNOWN_TARGET_TYPES: readonly MessageTargetType[] = [
  'INSPECTION_PLAN',
  'INSPECTION_FULFILLMENT',
  'INSPECTION_DELIVERY',
  'DEMAND',
  'CONVERSATION',
  'TRADE_ORDER',
  'ACTIVITY_ENROLLMENT',
  'ACTIVITY_ORDER',
  'VENUE_BOOKING',
]

export function toMessageCategory(value: string): MessageCategory {
  return (KNOWN_CATEGORIES as readonly string[]).includes(value) ? value as KnownMessageCategory : 'OTHER'
}

export function isMessageTargetType(value: string): value is MessageTargetType {
  return (KNOWN_TARGET_TYPES as readonly string[]).includes(value)
}
