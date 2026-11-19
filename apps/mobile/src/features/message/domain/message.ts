// Mirrors contracts/openapi-notification.yaml. The server decides who may read what; the recipient
// is always the signed-in account.

// 1.1.0 adds DEMAND_QUOTE (需求与报价) with the target DEMAND: id is the demand, subId is null.
export type MessageCategory = 'SERVICE_PROGRESS' | 'DEMAND_QUOTE'

export type MessageTargetType = 'INSPECTION_PLAN' | 'INSPECTION_FULFILLMENT' | 'INSPECTION_DELIVERY' | 'DEMAND'

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
