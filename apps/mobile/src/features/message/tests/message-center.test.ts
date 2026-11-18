import { describe, expect, it, vi } from 'vitest'

import { createMessageCenter, type MessageCenterState } from '../application/message-center'
import type { MessageApi } from '../data/message-api'
import type { MessageItem, MessagePage } from '../domain/message'

const item = (id: string, read = false): MessageItem => ({
  id,
  category: 'SERVICE_PROGRESS',
  title: '巡检报告已交付',
  body: '光伏电站巡检服务第 1 次报告已交付，请核对后验收。',
  target: { type: 'INSPECTION_DELIVERY', id: 'order', subId: 'visit' },
  occurredAt: '2026-11-17T03:00:00Z',
  read,
})

function setup(overrides: Partial<MessageApi> = {}, token: string | null = 'token') {
  let state: MessageCenterState = { kind: 'loading' }
  const unread: number[] = []
  const api: MessageApi = {
    loadPage: vi.fn(async (): Promise<MessagePage> => ({ hasMore: false, unreadCount: 0, items: [] })),
    loadUnreadCount: vi.fn(async () => 0),
    markRead: vi.fn(async () => undefined),
    markAllRead: vi.fn(async () => undefined),
    ...overrides,
  }
  let currentToken = token
  const center = createMessageCenter({
    api,
    getAccessToken: () => currentToken,
    getState: () => state,
    setState: (next) => { state = next },
    onUnreadCount: (count) => { unread.push(count) },
  })
  return {
    api,
    center,
    unread,
    state: () => state,
    signOut: () => { currentToken = null },
  }
}

describe('message center', () => {
  it('asks nothing of the server for a guest and shows no badge', async () => {
    const { api, center, state, unread } = setup({}, null)

    await center.load()

    expect(state()).toEqual({ kind: 'guest' })
    expect(api.loadPage).not.toHaveBeenCalled()
    expect(unread).toEqual([0])
  })

  it('shows the first page and reports the unread count', async () => {
    const { center, state, unread, api } = setup({
      loadPage: vi.fn(async () => ({ hasMore: true, unreadCount: 3, items: [item('a'), item('b', true)] })),
    })

    await center.load()

    expect(api.loadPage).toHaveBeenCalledWith('token', 1, 20)
    expect(state()).toMatchObject({ kind: 'ready', unreadCount: 3, hasMore: true })
    expect(unread.at(-1)).toBe(3)
  })

  it('tells a rejected login apart from a failed request', async () => {
    const rejected = setup({ loadPage: vi.fn(async () => { throw Object.assign(new Error('401'), { status: 401 }) }) })
    await rejected.center.load()
    expect(rejected.state()).toEqual({ kind: 'expired' })
    expect(rejected.unread.at(-1)).toBe(0)

    const offline = setup({ loadPage: vi.fn(async () => { throw Object.assign(new Error('offline'), { status: 0 }) }) })
    await offline.center.load()
    expect(offline.state()).toEqual({ kind: 'error' })
  })

  it('appends the next page without repeating a notification', async () => {
    const loadPage = vi.fn(async (_token: string, page: number): Promise<MessagePage> => (page === 1
      ? { hasMore: true, unreadCount: 2, items: [item('a'), item('b')] }
      : { hasMore: false, unreadCount: 2, items: [item('b'), item('c')] }))
    const { center, state } = setup({ loadPage })

    await center.load()
    await center.loadMore()
    await center.loadMore()

    const ready = state()
    expect(ready.kind === 'ready' && ready.items.map((entry) => entry.id)).toEqual(['a', 'b', 'c'])
    expect(ready.kind === 'ready' && ready.hasMore).toBe(false)
    expect(loadPage).toHaveBeenCalledTimes(2)
  })

  it('keeps the list and offers a retry when the next page fails', async () => {
    let fail = true
    const loadPage = vi.fn(async (_token: string, page: number): Promise<MessagePage> => {
      if (page === 2 && fail) throw Object.assign(new Error('offline'), { status: 0 })
      return page === 1
        ? { hasMore: true, unreadCount: 0, items: [item('a', true)] }
        : { hasMore: false, unreadCount: 0, items: [item('b', true)] }
    })
    const { center, state } = setup({ loadPage })

    await center.load()
    await center.loadMore()
    expect(state()).toMatchObject({ kind: 'ready', loadMoreFailed: true, hasMore: true })

    fail = false
    await center.loadMore()
    const ready = state()
    expect(ready.kind === 'ready' && ready.items.map((entry) => entry.id)).toEqual(['a', 'b'])
  })

  it('marks one notification as read at once, once, and tells the server', async () => {
    const { api, center, state, unread } = setup({
      loadPage: vi.fn(async () => ({ hasMore: false, unreadCount: 2, items: [item('a'), item('b')] })),
    })
    await center.load()

    await center.markRead('a')
    await center.markRead('a')
    await center.markRead('missing')

    const ready = state()
    expect(ready.kind === 'ready' && ready.items.map((entry) => entry.read)).toEqual([true, false])
    expect(ready.kind === 'ready' && ready.unreadCount).toBe(1)
    expect(unread.at(-1)).toBe(1)
    expect(api.markRead).toHaveBeenCalledTimes(1)
    expect(api.markRead).toHaveBeenCalledWith('token', 'a')
  })

  it('marks everything as read and does nothing when nothing is unread', async () => {
    const { api, center, state } = setup({
      loadPage: vi.fn(async () => ({ hasMore: false, unreadCount: 2, items: [item('a'), item('b')] })),
    })
    await center.load()

    await center.markAllRead()
    await center.markAllRead()

    const ready = state()
    expect(ready.kind === 'ready' && ready.items.every((entry) => entry.read)).toBe(true)
    expect(ready.kind === 'ready' && ready.unreadCount).toBe(0)
    expect(api.markAllRead).toHaveBeenCalledTimes(1)
  })

  it('drops the answer of an older load once a newer one has started', async () => {
    const releases: Array<() => void> = []
    const loadPage = vi.fn((): Promise<MessagePage> => new Promise((resolve) => {
      const call = releases.length
      releases.push(() => resolve({ hasMore: false, unreadCount: call, items: [item(`load-${call}`)] }))
    }))
    const { center, state } = setup({ loadPage })

    const first = center.load()
    const second = center.load()
    releases[1]()
    await second
    releases[0]()
    await first

    const ready = state()
    expect(ready.kind === 'ready' && ready.items[0].id).toBe('load-1')
  })

  it('goes back to the guest state after signing out', async () => {
    const { center, state, signOut } = setup({
      loadPage: vi.fn(async () => ({ hasMore: false, unreadCount: 1, items: [item('a')] })),
    })
    await center.load()
    signOut()

    await center.load()

    expect(state()).toEqual({ kind: 'guest' })
  })
})
