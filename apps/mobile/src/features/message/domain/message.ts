// Mirrors contracts/openapi-notification.yaml. The server decides who may read what; the recipient
// is always the signed-in account.

export type MessageCategory = 'SERVICE_PROGRESS'

export type MessageTargetType = 'INSPECTION_PLAN' | 'INSPECTION_FULFILLMENT' | 'INSPECTION_DELIVERY'

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
  target: MessageTarget
  occurredAt: string
  read: boolean
}

export interface MessagePage {
  hasMore: boolean
  unreadCount: number
  items: MessageItem[]
}

export const MESSAGE_PAGE_SIZE = 20
