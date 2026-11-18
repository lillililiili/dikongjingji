import { describe, expect, it, vi } from 'vitest'

import { createMessageApi } from '../data/message-api'

describe('message api', () => {
  it('sends the access token with every call and never names a recipient', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({
        page: 2,
        size: 20,
        total: 21,
        hasMore: false,
        unreadCount: 4,
        items: [{
          id: 'n1',
          category: 'SERVICE_PROGRESS',
          eventCode: 'INSPECTION_PLAN_PUBLISHED',
          title: '方案已出',
          body: '光伏电站巡检服务的方案已发布，请查看并确认。',
          target: { type: 'INSPECTION_PLAN', id: 'plan-1', subId: null },
          occurredAt: '2026-11-17T03:00:00Z',
          read: false,
        }],
      })
      .mockResolvedValueOnce({ count: 4 })
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({ updated: 4 })
    const api = createMessageApi(request)

    const page = await api.loadPage('token-a', 2, 20)
    const count = await api.loadUnreadCount('token-a')
    await api.markRead('token-a', 'n 1/x')
    await api.markAllRead('token-a')

    expect(page).toEqual({
      hasMore: false,
      unreadCount: 4,
      items: [{
        id: 'n1',
        category: 'SERVICE_PROGRESS',
        title: '方案已出',
        body: '光伏电站巡检服务的方案已发布，请查看并确认。',
        target: { type: 'INSPECTION_PLAN', id: 'plan-1', subId: null },
        occurredAt: '2026-11-17T03:00:00Z',
        read: false,
      }],
    })
    expect(count).toBe(4)
    expect(request.mock.calls.map(([input]) => `${input.method} ${input.url}`)).toEqual([
      'GET /api/v1/notifications?page=2&size=20',
      'GET /api/v1/notifications/unread-count',
      'POST /api/v1/notifications/n%201%2Fx/read',
      'POST /api/v1/notifications/read-all',
    ])
    for (const [input] of request.mock.calls) {
      expect(input.headers).toEqual({ Authorization: 'Bearer token-a' })
    }
  })
})
