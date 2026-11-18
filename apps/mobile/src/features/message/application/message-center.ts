import type { MessageApi } from '../data/message-api'
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
}

const isRejectedLogin = (error: unknown): boolean =>
  typeof error === 'object' && error !== null && (error as { status?: number }).status === 401

/**
 * The message tab's behaviour: load the first page, load more, mark one or all as read.
 * A newer load makes the answers of an older one be dropped. Marking as read shows at once and
 * is then sent to the server; if sending fails the next load shows the server's truth again.
 */
export function createMessageCenter(options: MessageCenterOptions) {
  let revision = 0
  let nextPage = 2

  const ready = () => {
    const state = options.getState()
    return state.kind === 'ready' ? state : null
  }

  function publish(state: MessageCenterState): void {
    options.setState(state)
    if (state.kind === 'ready') options.onUnreadCount?.(state.unreadCount)
    if (state.kind === 'guest' || state.kind === 'expired') options.onUnreadCount?.(0)
  }

  return {
    async load(): Promise<void> {
      const requested = ++revision
      const token = options.getAccessToken()
      if (!token) {
        publish({ kind: 'guest' })
        return
      }
      // Coming back to the tab keeps the list on screen while it is read again.
      if (!ready()) publish({ kind: 'loading' })
      try {
        const page = await options.api.loadPage(token, 1, MESSAGE_PAGE_SIZE)
        if (requested !== revision) return
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
        publish({ kind: isRejectedLogin(error) ? 'expired' : 'error' })
      }
    },

    async loadMore(): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      if (!current || !token || !current.hasMore || current.loadingMore) return
      const requested = revision
      publish({ ...current, loadingMore: true, loadMoreFailed: false })
      try {
        const page = await options.api.loadPage(token, nextPage, MESSAGE_PAGE_SIZE)
        const latest = ready()
        if (requested !== revision || !latest) return
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
        publish(isRejectedLogin(error)
          ? { kind: 'expired' }
          : { ...latest, loadingMore: false, loadMoreFailed: true })
      }
    },

    async markRead(messageId: string): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      const target = current?.items.find((item) => item.id === messageId)
      if (!current || !token || !target || target.read) return
      publish({
        ...current,
        items: current.items.map((item) => (item.id === messageId ? { ...item, read: true } : item)),
        unreadCount: Math.max(0, current.unreadCount - 1),
      })
      try {
        await options.api.markRead(token, messageId)
      } catch {
        // The next load shows what the server holds.
      }
    },

    async markAllRead(): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      if (!current || !token || current.unreadCount === 0) return
      publish({
        ...current,
        items: current.items.map((item) => ({ ...item, read: true })),
        unreadCount: 0,
      })
      try {
        await options.api.markAllRead(token)
      } catch {
        // The next load shows what the server holds.
      }
    },
  }
}
