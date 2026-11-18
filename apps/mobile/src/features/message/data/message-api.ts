import type { MessageItem, MessagePage, MessageTarget } from '../domain/message'

interface MessageDto {
  id: string
  category: MessageItem['category']
  title: string
  body: string
  target: MessageTarget
  occurredAt: string
  read: boolean
}

interface MessagePageDto {
  hasMore: boolean
  unreadCount: number
  items: MessageDto[]
}

type MessageRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  headers: Record<string, string>
}) => Promise<T>

export interface MessageApi {
  loadPage(accessToken: string, page: number, size: number): Promise<MessagePage>
  loadUnreadCount(accessToken: string): Promise<number>
  markRead(accessToken: string, messageId: string): Promise<void>
  markAllRead(accessToken: string): Promise<void>
}

// Every call carries the access token it was given. The request helper is the plain one, so a
// rejected token is reported to the caller instead of sending the user to the login page: the
// message tab is a root page and decides for itself what to show.
export function createMessageApi(request: MessageRequest): MessageApi {
  const headers = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  return {
    async loadPage(accessToken, page, size) {
      const dto = await request<MessagePageDto>({
        url: `/api/v1/notifications?page=${page}&size=${size}`,
        method: 'GET',
        headers: headers(accessToken),
      })
      return {
        hasMore: dto.hasMore,
        unreadCount: dto.unreadCount,
        items: dto.items.map((item) => ({
          id: item.id,
          category: item.category,
          title: item.title,
          body: item.body,
          target: { type: item.target.type, id: item.target.id, subId: item.target.subId ?? null },
          occurredAt: item.occurredAt,
          read: item.read,
        })),
      }
    },
    async loadUnreadCount(accessToken) {
      const dto = await request<{ count: number }>({
        url: '/api/v1/notifications/unread-count',
        method: 'GET',
        headers: headers(accessToken),
      })
      return dto.count
    },
    async markRead(accessToken, messageId) {
      await request<void>({
        url: `/api/v1/notifications/${encodeURIComponent(messageId)}/read`,
        method: 'POST',
        headers: headers(accessToken),
      })
    },
    async markAllRead(accessToken) {
      await request<{ updated: number }>({
        url: '/api/v1/notifications/read-all',
        method: 'POST',
        headers: headers(accessToken),
      })
    },
  }
}
