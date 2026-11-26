import type { InboxScope, MessageApi } from '../data/message-api'
import { MESSAGE_PAGE_SIZE, type MessageItem } from '../domain/message'

export type MessageCenterState =
  // Not signed in: nothing is asked of the server.
  | { kind: 'guest' }
  | { kind: 'loading' }
  | { kind: 'error' }
  // The server no longer accepts the login.
  | { kind: 'expired' }
  | {
      kind: 'ready'
      items: MessageItem[]
      unreadCount: number
      hasMore: boolean
      loadingMore: boolean
      loadMoreFailed: boolean
    }

export interface MessageCenterOptions {
  api: MessageApi
  getAccessToken(): string | null
  getState(): MessageCenterState
  setState(state: MessageCenterState): void
  /** Told every time the unread count is known, so the tab badge can follow. */
  onUnreadCount?(count: number): void
  getAccountKey?(): string | null
  onReadError?(): void
  /** 系统通知 (S5-9): whether this build shows them, and the App's current city. */
  getInboxScope?(): InboxScope
}

const isRejectedLogin = (error: unknown): boolean =>
  typeof error === 'object' && error !== null && (error as { status?: number }).status === 401

/**
 * The message tab's behaviour: load the first page, load more, mark one or all as read.
 * Unread indicators follow confirmed writes. A read that overlaps a confirmed write is fetched again.
 */
export function createMessageCenter(options: MessageCenterOptions) {
  let revision = 0
  let nextPage = 2
  let readVersion = 0
  let displayedInbox: string | null = null
  let refreshing: number | null = null
  const pending = new Set<string>()
  const accountKey = () => options.getAccountKey?.() ?? options.getAccessToken()
  function inbox() {
    const account = accountKey()
    const scope = options.getInboxScope?.()
    // Changing cities can deliver new announcements into the same account's inbox.
    const key = JSON.stringify([account, scope?.systemNotices, scope?.cityCode])
    return { account, scope, key }
  }

  const ready = () => {
    const state = options.getState()
    return state.kind === 'ready' ? state : null
  }

  function publish(state: MessageCenterState): void {
    options.setState(state)
    if (state.kind === 'ready') options.onUnreadCount?.(state.unreadCount)
    if (state.kind === 'guest' || state.kind === 'expired') options.onUnreadCount?.(0)
  }

  async function mark(messageId: string | null): Promise<void> {
    const current = ready()
    const token = options.getAccessToken()
    const source = inbox()
    if (!current || !token || !source.account || current.unreadCount === 0) return
    if (displayedInbox !== source.key) return center.load()
    if (messageId !== null && !current.items.some(item => item.id === messageId && !item.read)) return
    const all = source.key + ':*'
    const key = messageId === null ? all : source.key + ':' + messageId
    const requested = revision
    if (pending.has(key) || pending.has(all)) return
    pending.add(key)
    try {
      if (messageId === null) await options.api.markAllRead(token, source.scope)
      else await options.api.markRead(token, messageId)
      if (source.account !== accountKey()) {
        if (displayedInbox === source.key) await center.load()
        return
      }
      readVersion += 1
      // The bulk receipt only counts affected rows. Messages delivered after that write can still
      // be unread, including announcements delivered by the next read in a different city.
      if (messageId === null || source.key !== inbox().key || requested !== revision) {
        await center.load()
        return
      }
      const latest = ready()
      if (!latest) return
      if (!latest.items.some(item => item.id === messageId && !item.read)) return
      publish({
        ...latest,
        items: latest.items.map(item => item.id === messageId ? { ...item, read: true } : item),
        unreadCount: Math.max(0, latest.unreadCount - 1),
      })
    } catch (error) {
      if (source.key !== inbox().key) {
        if (displayedInbox === source.key) await center.load()
        return
      }
      if (isRejectedLogin(error)) publish({ kind: 'expired' })
      else options.onReadError?.()
    } finally {
      pending.delete(key)
    }
  }

  const center = {
    async load(): Promise<void> {
      const requested = ++revision
      const readsAtStart = readVersion
      const token = options.getAccessToken()
      const source = inbox()
      const changed = source.key !== displayedInbox
      displayedInbox = source.key
      if (!token) {
        publish({ kind: 'guest' })
        return
      }
      // Coming back to the tab keeps the list on screen while it is read again.
      if (changed || !ready()) publish({ kind: 'loading' })
      if (changed) options.onUnreadCount?.(0)
      refreshing = requested
      try {
        const page = await options.api.loadPage(token, 1, MESSAGE_PAGE_SIZE, source.scope)
        if (requested !== revision) return
        if (source.key !== inbox().key) return center.load()
        if (readsAtStart !== readVersion) return center.load()
        nextPage = 2
        publish({
          kind: 'ready',
          items: page.items,
          unreadCount: page.unreadCount,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        })
      } catch (error) {
        if (requested !== revision) return
        if (source.key !== inbox().key) return center.load()
        publish({ kind: isRejectedLogin(error) ? 'expired' : 'error' })
      } finally {
        if (refreshing === requested) refreshing = null
      }
    },

    async loadMore(): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      if (!current || !token || !current.hasMore || current.loadingMore || refreshing === revision) return
      const source = inbox()
      if (displayedInbox !== source.key) return center.load()
      const requested = revision
      const readsAtStart = readVersion
      publish({ ...current, loadingMore: true, loadMoreFailed: false })
      try {
        const page = await options.api.loadPage(token, nextPage, MESSAGE_PAGE_SIZE, source.scope)
        const latest = ready()
        if (requested !== revision || !latest) return
        if (source.key !== inbox().key) return center.load()
        if (readsAtStart !== readVersion) {
          publish({ ...latest, loadingMore: false })
          return center.loadMore()
        }
        nextPage += 1
        const known = new Set(latest.items.map((item) => item.id))
        publish({
          ...latest,
          items: [...latest.items, ...page.items.filter((item) => !known.has(item.id))],
          unreadCount: page.unreadCount,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        })
      } catch (error) {
        const latest = ready()
        if (requested !== revision || !latest) return
        if (source.key !== inbox().key) return center.load()
        publish(isRejectedLogin(error)
          ? { kind: 'expired' }
          : { ...latest, loadingMore: false, loadMoreFailed: true })
      }
    },

    markRead: (messageId: string) => mark(messageId),
    markAllRead: () => mark(null),
  }
  return center
}
