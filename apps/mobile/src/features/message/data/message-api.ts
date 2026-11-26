import {
  isMessageTargetType,
  toMessageCategory,
  type MessageItem,
  type MessagePage,
  type MessageTarget,
} from '../domain/message'

// Categories and target types stay strings here: a newer server may send ones this version does
// not know, and the mapper turns those into a plain row instead of failing.
interface MessageDto {
  id: string
  category: string
  title: string
  body: string
  target: { type: string; id: string; subId?: string | null } | null
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

// Contract 1.5.0: a build that shows 系统通知 asks for them (systemNotices), with the App's current
// city so that city's announcements reach the inbox too. Without it the server copies none.
export interface InboxScope {
  systemNotices: boolean
  cityCode: string | null
}

export interface MessageApi {
  loadPage(accessToken: string, page: number, size: number, scope?: InboxScope): Promise<MessagePage>
  loadUnreadCount(accessToken: string, scope?: InboxScope): Promise<number>
  markRead(accessToken: string, messageId: string): Promise<void>
  markAllRead(accessToken: string, scope?: InboxScope): Promise<void>
}

// Every call carries the access token it was given. The request helper is the plain one, so a
// rejected token is reported to the caller instead of sending the user to the login page: the
// message tab is a root page and decides for itself what to show.
export function createMessageApi(request: MessageRequest): MessageApi {
  const headers = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const inbox = (scope?: InboxScope): string => {
    if (!scope?.systemNotices) return ''
    return scope.cityCode ? `systemNotices=true&cityCode=${encodeURIComponent(scope.cityCode)}` : 'systemNotices=true'
  }
  return {
    async loadPage(accessToken, page, size, scope) {
      const extra = inbox(scope)
      const dto = await request<MessagePageDto>({
        url: `/api/v1/notifications?page=${page}&size=${size}${extra ? `&${extra}` : ''}`,
        method: 'GET',
        headers: headers(accessToken),
      })
      return {
        hasMore: dto.hasMore,
        unreadCount: dto.unreadCount,
        items: dto.items.map(toMessageItem),
      }
    },
    async loadUnreadCount(accessToken, scope) {
      const extra = inbox(scope)
      const dto = await request<{ count: number }>({
        url: `/api/v1/notifications/unread-count${extra ? `?${extra}` : ''}`,
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
    async markAllRead(accessToken, scope) {
      await request<{ updated: number }>({
        url: '/api/v1/notifications/read-all' + (scope?.systemNotices === false ? '?systemNotices=false' : ''),
        method: 'POST',
        headers: headers(accessToken),
      })
    },
  }
}

function toMessageTarget(target: MessageDto['target']): MessageTarget | null {
  if (!target || !isMessageTargetType(target.type) || !target.id) return null
  return { type: target.type, id: target.id, subId: target.subId ?? null }
}

export function toMessageItem(item: MessageDto): MessageItem {
  const category = toMessageCategory(item.category)
  return {
    id: item.id,
    category,
    title: item.title,
    body: item.body,
    // An unknown category opens nothing even if its target type happens to be known.
    target: category === 'OTHER' ? null : toMessageTarget(item.target),
    occurredAt: item.occurredAt,
    read: item.read,
  }
}
