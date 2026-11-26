import { describe, expect, it, vi } from 'vitest'

import { createMessageApi, toMessageItem } from '../data/message-api'

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

  // 1.3.0 (R6-5): the two new categories and four targets come through; a later contract's category
  // or target type becomes a plain row that opens nothing instead of breaking the list.
  it('keeps the new categories and turns unknown ones into a plain row', () => {
    const dto = (category: string, type: string) => ({
      id: 'n1', category, title: '订单已发货', body: '示例无人机，示例商家已发出',
      target: { type, id: 'o1', subId: null }, occurredAt: '2026-11-20T02:00:00Z', read: false,
    })

    expect(toMessageItem(dto('TRADE_LOGISTICS', 'TRADE_ORDER'))).toMatchObject({
      category: 'TRADE_LOGISTICS', target: { type: 'TRADE_ORDER', id: 'o1', subId: null },
    })
    for (const type of ['ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING']) {
      expect(toMessageItem(dto('ACTIVITY_REMINDER', type))).toMatchObject({ category: 'ACTIVITY_REMINDER', target: { type } })
    }
    // 1.4.0 (M20): 直播提醒 opens the live room.
    expect(toMessageItem(dto('LIVE_REMINDER', 'LIVE_ROOM')))
      .toMatchObject({ category: 'LIVE_REMINDER', target: { type: 'LIVE_ROOM' } })
    // 1.5.0 (S5-9): 系统通知 opens the announcement.
    expect(toMessageItem(dto('SYSTEM_NOTICE', 'SYSTEM_NOTICE')))
      .toMatchObject({ category: 'SYSTEM_NOTICE', target: { type: 'SYSTEM_NOTICE', id: 'o1' } })
    expect(toMessageItem(dto('ORG_INVITE', 'NOTICE'))).toMatchObject({ category: 'OTHER', target: null })
    // A known category pointing at a page this version does not know still shows, but opens nothing.
    expect(toMessageItem(dto('TRADE_LOGISTICS', 'AFTER_SALES'))).toMatchObject({ category: 'TRADE_LOGISTICS', target: null })
    // An unknown category never opens a page, even with a known target.
    expect(toMessageItem(dto('ORG_INVITE', 'TRADE_ORDER')).target).toBeNull()
    expect(toMessageItem({ ...dto('TRADE_LOGISTICS', 'TRADE_ORDER'), target: null }).target).toBeNull()
  })

  // 1.5.0: only a build that shows 系统通知 asks for them, with the current city when there is one.
  it('asks for 系统通知 only when the build shows them', async () => {
    const urls: string[] = []
    const api = createMessageApi(async <T>(input: { url: string }) => {
      urls.push(input.url)
      return (input.url.includes('unread-count') ? { count: 0 } : { hasMore: false, unreadCount: 0, items: [] }) as T
    })

    await api.loadPage('t', 1, 20)
    await api.loadPage('t', 1, 20, { systemNotices: false, cityCode: '370500' })
    await api.loadPage('t', 2, 20, { systemNotices: true, cityCode: '370500' })
    await api.loadUnreadCount('t', { systemNotices: true, cityCode: null })
    await api.loadUnreadCount('t')
    await api.markAllRead('t', { systemNotices: false, cityCode: null })
    await api.markAllRead('t', { systemNotices: true, cityCode: null })

    expect(urls).toEqual([
      '/api/v1/notifications?page=1&size=20',
      '/api/v1/notifications?page=1&size=20',
      '/api/v1/notifications?page=2&size=20&systemNotices=true&cityCode=370500',
      '/api/v1/notifications/unread-count?systemNotices=true',
      '/api/v1/notifications/unread-count',
      '/api/v1/notifications/read-all?systemNotices=false',
      '/api/v1/notifications/read-all',
    ])
  })
})
