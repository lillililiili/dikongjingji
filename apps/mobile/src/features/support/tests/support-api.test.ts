import { describe, expect, it, vi } from 'vitest'

import { buildChatLines, describeChatPeer, toConversationRow } from '../application/chat-view'
import { HELP_CATEGORIES } from '../application/help-faq'
import { createSupportApi } from '../data/support-api'

const ID = '6f1b2c3d-1111-4222-8333-444455556666'
const conversationDto = {
  conversationId: ID,
  counterpart: { kind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001', name: '示例机器人商家' },
  lastMessage: { senderSide: 'COUNTERPART', kind: 'TEXT', preview: '今天已发货', sentAt: '2026-11-19T02:24:00Z' },
  unreadCount: 2,
  createdAt: '2026-11-19T02:00:00Z',
}

describe('support api', () => {
  it('lists conversations with the total unread', async () => {
    const request = vi.fn().mockResolvedValue({ page: 1, size: 20, total: 1, hasMore: false, unreadCount: 2, items: [conversationDto] })
    const page = await createSupportApi(request).listConversations('t', 1, 20)
    expect(request).toHaveBeenCalledWith({ url: '/api/v1/conversations?page=1&size=20', method: 'GET', headers: { Authorization: 'Bearer t' } })
    expect(page.unreadCount).toBe(2)
    expect(page.items[0].counterpart).toEqual({ kind: 'MERCHANT', organizationId: conversationDto.counterpart.organizationId, name: '示例机器人商家' })
  })

  it('opens the platform support without an organization', async () => {
    const request = vi.fn().mockResolvedValue({ ...conversationDto, counterpart: { kind: 'PLATFORM', organizationId: null, name: '平台客服' } })
    const opened = await createSupportApi(request).openConversation('t', 'PLATFORM', null)
    expect(request.mock.calls[0][0]).toMatchObject({ url: '/api/v1/conversations', method: 'POST', data: { counterpartKind: 'PLATFORM' } })
    expect(opened.counterpart.organizationId).toBeNull()
  })

  it('reads a page of messages and drops what it cannot draw', async () => {
    const request = vi.fn().mockResolvedValue({
      conversation: conversationDto,
      items: [
        { messageId: 'a', seq: 1, senderSide: 'CUSTOMER', mine: true, kind: 'CARD', text: null, card: { type: 'PRODUCT', refId: 'r', title: '套装', subtitle: null }, sentAt: '2026-11-19T02:00:00Z' },
        { messageId: 'b', seq: 2, senderSide: 'COUNTERPART', mine: false, kind: 'CARD', text: null, card: { type: 'COUPON', refId: 'r', title: 'x' }, sentAt: '2026-11-19T02:01:00Z' },
        { messageId: 'c', seq: 3, senderSide: 'COUNTERPART', mine: false, kind: 'TEXT', text: '你好', card: null, sentAt: '2026-11-19T02:02:00Z' },
      ],
      hasMore: true,
      nextBefore: 1,
    })
    const result = await createSupportApi(request).loadMessages('t', ID, 9, 20)
    expect(request.mock.calls[0][0].url).toBe(`/api/v1/conversations/${ID}/messages?size=20&before=9`)
    expect(result.items.map((item) => item.messageId)).toEqual(['a', 'c'])
    expect(result.nextBefore).toBe(1)
  })

  it('sends a message and feedback with the idempotency key', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ messageId: 'a', seq: 1, senderSide: 'CUSTOMER', mine: true, kind: 'TEXT', text: '你好', card: null, sentAt: '2026-11-19T02:00:00Z' })
      .mockResolvedValueOnce({ feedbackId: 'f', type: 'DISPLAY', text: '挡住了', createdAt: '2026-11-19T02:00:00Z' })
    const api = createSupportApi(request)
    await api.sendMessage('t', ID, 'k1', { text: '你好' })
    expect(request.mock.calls[0][0]).toMatchObject({ method: 'POST', headers: { 'Idempotency-Key': 'k1' }, data: { text: '你好' } })
    const feedback = await api.submitFeedback('t', 'k2', 'DISPLAY', '挡住了')
    expect(request.mock.calls[1][0]).toMatchObject({ url: '/api/v1/feedback', headers: { 'Idempotency-Key': 'k2' }, data: { type: 'DISPLAY', text: '挡住了' } })
    expect(feedback.type).toBe('DISPLAY')
  })
})

describe('chat view', () => {
  const now = new Date('2026-11-19T06:00:00Z')

  it('opens the thread and every quiet five minutes with a time line', () => {
    const at = (minute: number) => new Date(Date.UTC(2026, 10, 19, 2, minute)).toISOString()
    const make = (seq: number, minute: number) => ({ messageId: `m${seq}`, seq, senderSide: 'CUSTOMER' as const, mine: true, kind: 'TEXT' as const, text: 'x', card: null, sentAt: at(minute) })
    const lines = buildChatLines([make(1, 0), make(2, 3), make(3, 10)], [], now)
    expect(lines.map((line) => line.kind)).toEqual(['TIME', 'MESSAGE', 'MESSAGE', 'TIME', 'MESSAGE'])
    expect(lines[0]).toMatchObject({ text: '今天 10:00' })
  })

  it('describes each kind of counterpart without phone numbers', () => {
    expect(describeChatPeer({ kind: 'PLATFORM', organizationId: null, name: '平台客服' })).toMatchObject({ initial: null, action: { kind: 'HELP' } })
    expect(describeChatPeer({ kind: 'MERCHANT', organizationId: 'o', name: '示例机器人商家' })).toMatchObject({ initial: '示', action: null, placeholder: '问问商家' })
    expect(describeChatPeer({ kind: 'PROVIDER', organizationId: 'o', name: '云翼科技' })).toMatchObject({ initial: '云', action: { kind: 'PROVIDER' } })
    expect(describeChatPeer({ kind: 'PLATFORM', organizationId: null, name: '平台客服' }).note).toContain('平台只协助沟通，不裁决')
  })

  it('writes a conversation row with who spoke last', () => {
    const row = toConversationRow({
      conversationId: ID,
      counterpart: { kind: 'MERCHANT', organizationId: 'o', name: '示例机器人商家' },
      lastMessage: { senderSide: 'CUSTOMER', kind: 'CARD', preview: '套装', sentAt: '2026-11-19T02:24:00Z' },
      unreadCount: 0,
      createdAt: '2026-11-19T02:00:00Z',
    }, now)
    expect(row).toEqual({ conversationId: ID, title: '示例机器人商家', preview: '我：[卡片] 套装', time: '今天 10:24', at: '2026-11-19T02:24:00Z', unreadCount: 0, initial: '示' })
  })
})

describe('help questions', () => {
  it('has four categories of short, unique questions and no deadline placeholders', () => {
    expect(HELP_CATEGORIES.map((category) => category.label)).toEqual(['订单与支付', '活动与到馆', '需求与报价', '设备租赁'])
    const ids = HELP_CATEGORIES.flatMap((category) => category.questions.map((question) => question.id))
    expect(new Set(ids).size).toBe(ids.length)
    for (const question of HELP_CATEGORIES.flatMap((category) => category.questions)) {
      expect(question.answer.length).toBeLessThanOrEqual(60)
      expect(question.answer).not.toContain('协议约定时间')
    }
  })
})
