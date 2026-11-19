import { describe, expect, it, vi } from 'vitest'

import { createChatSession, mergeSupportMessages, sendableSupportText, type ChatState } from '../application/chat-session'
import type { SupportApi } from '../data/support-api'
import type { SupportConversation, SupportMessage, SupportMessagePage } from '../domain/support'

const ID = '6f1b2c3d-1111-4222-8333-444455556666'

const conversation = (unreadCount = 0): SupportConversation => ({
  conversationId: ID,
  counterpart: { kind: 'MERCHANT', organizationId: 'org-1', name: '示例机器人商家' },
  lastMessage: null,
  unreadCount,
  createdAt: '2026-11-19T02:00:00Z',
})

const message = (seq: number, mine = true, text = `第 ${seq} 条`): SupportMessage => ({
  messageId: `m-${seq}`,
  seq,
  senderSide: mine ? 'CUSTOMER' : 'COUNTERPART',
  mine,
  kind: 'TEXT',
  text,
  card: null,
  sentAt: new Date(Date.UTC(2026, 10, 19, 2, seq)).toISOString(),
})

const page = (items: SupportMessage[], more: { hasMore?: boolean; nextBefore?: number | null; unread?: number } = {}): SupportMessagePage => ({
  conversation: conversation(more.unread ?? 0),
  items,
  hasMore: more.hasMore ?? false,
  nextBefore: more.nextBefore ?? null,
})

function fakeApi(overrides: Partial<SupportApi> = {}): SupportApi {
  return {
    listConversations: vi.fn(),
    openConversation: vi.fn().mockResolvedValue(conversation()),
    loadMessages: vi.fn().mockResolvedValue(page([])),
    sendMessage: vi.fn(),
    markRead: vi.fn().mockResolvedValue(conversation(0)),
    submitFeedback: vi.fn(),
    ...overrides,
  }
}

function harness(api: SupportApi, token: string | null = 'token') {
  let state: ChatState = { kind: 'loading' }
  let keys = 0
  const onRead = vi.fn()
  const session = createChatSession({
    api,
    getAccessToken: () => token,
    getState: () => state,
    setState: (next) => { state = next },
    createKey: () => `key-${++keys}`,
    onRead,
  })
  return { session, onRead, state: () => state }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('chat helpers', () => {
  it('merges pages one message per seq, oldest first', () => {
    expect(mergeSupportMessages([message(2), message(3)], [message(1), message(3, true, '新')]).map((m) => `${m.seq}${m.text}`))
      .toEqual(['1第 1 条', '2第 2 条', '3新'])
  })

  it('sends trimmed text of 1–500 characters only', () => {
    expect(sendableSupportText('  你好 ')).toBe('你好')
    expect(sendableSupportText('   ')).toBeNull()
    expect(sendableSupportText('字'.repeat(501))).toBeNull()
  })
})

describe('chat session', () => {
  it('loads the newest page and marks the conversation read', async () => {
    const api = fakeApi({ loadMessages: vi.fn().mockResolvedValue(page([message(4), message(5, false)], { hasMore: true, nextBefore: 4, unread: 1 })) })
    const { session, state, onRead } = harness(api)
    await session.load(ID)
    const ready = state()
    expect(ready.kind).toBe('ready')
    if (ready.kind !== 'ready') return
    expect(ready.messages.map((m) => m.seq)).toEqual([4, 5])
    expect(ready.hasOlder).toBe(true)
    expect(api.markRead).toHaveBeenCalledWith('token', ID)
    expect(ready.conversation.unreadCount).toBe(0)
    expect(onRead).toHaveBeenCalled()
  })

  it('asks for a login without a token or when the server refuses it', async () => {
    const without = harness(fakeApi(), null)
    await without.session.load(ID)
    expect(without.state().kind).toBe('login')
    const refused = harness(fakeApi({ loadMessages: vi.fn().mockRejectedValue({ status: 401 }) }))
    await refused.session.load(ID)
    expect(refused.state().kind).toBe('login')
    const missing = harness(fakeApi({ loadMessages: vi.fn().mockRejectedValue({ status: 404 }) }))
    await missing.session.load(ID)
    expect(missing.state().kind).toBe('missing')
  })

  it('says the counterpart is unavailable when it cannot be opened', async () => {
    const { session, state } = harness(fakeApi({ openConversation: vi.fn().mockRejectedValue({ status: 409, code: 'SUPPORT_COUNTERPART_UNAVAILABLE' }) }))
    expect(await session.open({ kind: 'MERCHANT', organizationId: 'org-x' })).toBeNull()
    expect(state().kind).toBe('unavailable')
    const ok = harness(fakeApi())
    expect(await ok.session.open({ kind: 'PLATFORM' })).toBe(ID)
  })

  it('loads older pages in front, once at a time', async () => {
    const loadMessages = vi.fn()
      .mockResolvedValueOnce(page([message(21), message(22)], { hasMore: true, nextBefore: 21 }))
      .mockResolvedValueOnce(page([message(19), message(20)], { hasMore: false, nextBefore: null }))
    const { session, state } = harness(fakeApi({ loadMessages }))
    await session.load(ID)
    await session.loadOlder()
    expect(loadMessages).toHaveBeenLastCalledWith('token', ID, 21, 20)
    const ready = state()
    if (ready.kind !== 'ready') throw new Error('not ready')
    expect(ready.messages.map((m) => m.seq)).toEqual([19, 20, 21, 22])
    expect(ready.hasOlder).toBe(false)
    await session.loadOlder()
    expect(loadMessages).toHaveBeenCalledTimes(2)
  })

  it('shows a sent text at once and keeps its key for a retry after a failure', async () => {
    const sendMessage = vi.fn()
      .mockRejectedValueOnce({ status: 503 })
      .mockResolvedValueOnce(message(1, true, '你好'))
    const { session, state } = harness(fakeApi({ sendMessage }))
    await session.load(ID)
    expect(session.sendText('  你好  ')).toBe(true)
    await flush()
    let ready = state()
    if (ready.kind !== 'ready') throw new Error('not ready')
    expect(ready.outgoing).toHaveLength(1)
    expect(ready.outgoing[0].status).toBe('FAILED')
    session.retry(ready.outgoing[0].localId)
    await flush()
    expect(sendMessage.mock.calls.map((call) => call[2])).toEqual(['key-1', 'key-1'])
    expect(sendMessage).toHaveBeenLastCalledWith('token', ID, 'key-1', { text: '你好' })
    ready = state()
    if (ready.kind !== 'ready') throw new Error('not ready')
    expect(ready.outgoing).toEqual([])
    expect(ready.messages.map((m) => m.text)).toEqual(['你好'])
    expect(session.sendText('   ')).toBe(false)
  })

  it('sends the offered card once', async () => {
    const card = { type: 'PRODUCT' as const, refId: 'c2000000-0000-4000-8000-000000000101', title: '套装', subtitle: null }
    const sendMessage = vi.fn().mockResolvedValue({ ...message(1), kind: 'CARD', text: null, card })
    const { session, state } = harness(fakeApi({ sendMessage }))
    await session.load(ID)
    session.sendCard(card)
    session.sendCard(card)
    await flush()
    expect(sendMessage).toHaveBeenCalledTimes(1)
    expect(sendMessage).toHaveBeenCalledWith('token', ID, 'key-1', { card })
    const ready = state()
    expect(ready.kind === 'ready' && ready.cardSent).toBe(true)
  })

  it('polls: merges a reply and marks it read; restarts from the newest page after a gap', async () => {
    const markRead = vi.fn().mockResolvedValue(conversation(0))
    const loadMessages = vi.fn()
      .mockResolvedValueOnce(page([message(1), message(2)]))
      .mockResolvedValueOnce(page([message(1), message(2), message(3, false, '已发货')], { unread: 1 }))
      .mockResolvedValueOnce(page([message(40, false), message(41, false)], { hasMore: true, nextBefore: 40, unread: 2 }))
    const { session, state } = harness(fakeApi({ loadMessages, markRead }))
    await session.load(ID)
    expect(markRead).toHaveBeenCalledTimes(1)
    await session.refresh()
    let ready = state()
    if (ready.kind !== 'ready') throw new Error('not ready')
    expect(ready.messages.map((m) => m.seq)).toEqual([1, 2, 3])
    expect(markRead).toHaveBeenCalledTimes(2)
    await session.refresh()
    ready = state()
    if (ready.kind !== 'ready') throw new Error('not ready')
    expect(ready.messages.map((m) => m.seq)).toEqual([40, 41])
    expect(ready.nextBefore).toBe(40)
    expect(ready.hasOlder).toBe(true)
  })
})
