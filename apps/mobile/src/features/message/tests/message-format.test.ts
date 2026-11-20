import { describe, expect, it } from 'vitest'

import type { MessageItem } from '../domain/message'
import {
  MESSAGE_CATEGORY_LABELS,
  MESSAGE_SQUARE_ORDER,
  MESSAGE_SQUARES,
  emptyCopy,
  formatMessageTime,
  mergeMessageRows,
  messageLook,
  squareUnread,
} from '../presentation/message-format'

describe('message time', () => {
  // 2026-11-17 20:00 in Beijing.
  const now = new Date('2026-11-17T12:00:00Z')

  it('says today and yesterday by the Beijing day, then the date', () => {
    expect(formatMessageTime('2026-11-17T03:05:00Z', now)).toBe('今天 11:05')
    // 00:30 Beijing on the 17th is still the 16th in UTC.
    expect(formatMessageTime('2026-11-16T16:30:00Z', now)).toBe('今天 00:30')
    expect(formatMessageTime('2026-11-16T15:30:00Z', now)).toBe('昨天 23:30')
    expect(formatMessageTime('2026-11-03T08:20:00Z', now)).toBe('11/3 16:20')
  })
})

describe('message categories', () => {
  // Notification contract 1.1.0 (F1) adds quotes on the account's demands.
  // 1.3.0 (R6-5) adds 交易物流 and 活动提醒; a category this version does not know reads 通知.
  it('labels every category, and an unknown one as 通知', () => {
    expect(MESSAGE_CATEGORY_LABELS).toEqual({
      SERVICE_PROGRESS: '服务进度',
      DEMAND_QUOTE: '需求与报价',
      CUSTOMER_SERVICE: '客服消息',
      TRADE_LOGISTICS: '交易物流',
      ACTIVITY_REMINDER: '活动提醒',
      LIVE_REMINDER: '直播提醒',
      OTHER: '通知',
    })
  })

  // 1.4.0 (M20): 直播提醒 has no row on the board Messages; it reads 直播提醒 in violet with a screen.
  it('gives 直播提醒 its own label, tone and icon', () => {
    expect(messageLook({ category: 'LIVE_REMINDER', target: { type: 'LIVE_ROOM', id: 'r', subId: null } }))
      .toEqual({ label: '直播提醒', d: 'M3 6h18v12H3zM10 9.5v5l4.5-2.5z', tone: 'violet' })
  })

  // Board Messages: 交易物流 orange truck, 服务进度 blue, 活动提醒 green flag, 体验预约 (a venue
  // booking) green calendar, 系统通知-like grey bell for what this version does not know.
  it('gives each row the board colour and icon of its category', () => {
    const look = (category: MessageItem['category'], type: string | null = null) =>
      messageLook({ category, target: type ? { type: type as never, id: 'x', subId: null } : null })

    expect(look('TRADE_LOGISTICS', 'TRADE_ORDER')).toMatchObject({ label: '交易物流', tone: 'orange' })
    expect(look('TRADE_LOGISTICS').d).toContain('M3 7h11v9H3z')
    expect(look('SERVICE_PROGRESS')).toMatchObject({ label: '服务进度', tone: 'blue' })
    expect(look('ACTIVITY_REMINDER', 'ACTIVITY_ENROLLMENT')).toMatchObject({ label: '活动提醒', tone: 'green' })
    expect(look('ACTIVITY_REMINDER', 'ACTIVITY_ENROLLMENT').d).toBe('M6 21V4M6 4h11l-2 4 2 4H6')
    expect(look('ACTIVITY_REMINDER', 'VENUE_BOOKING')).toMatchObject({ label: '体验预约', tone: 'green' })
    expect(look('ACTIVITY_REMINDER', 'VENUE_BOOKING').d).toBe('M5 5h14v15H5zM5 9h14M9 3v4M15 3v4')
    expect(look('CUSTOMER_SERVICE')).toMatchObject({ label: '客服消息', tone: 'orange' })
    expect(look('OTHER')).toMatchObject({ label: '通知', bg: '#eef2f8', ink: '#536178' })
  })
})

describe('category squares', () => {
  const item = (id: string, category: MessageItem['category'], read: boolean): MessageItem => ({
    id, category, title: 't', body: 'b', target: null, occurredAt: '2026-11-17T03:00:00Z', read,
  })
  const items = [
    item('a', 'TRADE_LOGISTICS', false),
    item('b', 'TRADE_LOGISTICS', true),
    item('c', 'ACTIVITY_REMINDER', false),
    item('d', 'ACTIVITY_REMINDER', false),
    item('e', 'SERVICE_PROGRESS', true),
    item('f', 'OTHER', false),
  ]

  it('keeps the board order without 系统通知', () => {
    expect(MESSAGE_SQUARE_ORDER.map((square) => MESSAGE_SQUARES[square].label)).toEqual(['交易物流', '服务进度', '活动提醒'])
    expect(MESSAGE_SQUARE_ORDER.map((square) => MESSAGE_SQUARES[square].tone)).toEqual(['orange', 'blue', 'green'])
  })

  it('counts unread per square only once every page is loaded', () => {
    expect(squareUnread(items, false, 'TRADE_LOGISTICS')).toBe(1)
    expect(squareUnread(items, false, 'ACTIVITY_REMINDER')).toBe(2)
    expect(squareUnread(items, false, 'SERVICE_PROGRESS')).toBe(0)
    expect(squareUnread(items, true, 'ACTIVITY_REMINDER')).toBe(0)
  })

  it('says what each filter would hold when it is empty', () => {
    expect(emptyCopy('TRADE_LOGISTICS', MESSAGE_SQUARE_ORDER).title).toBe('没有交易物流消息')
    expect(emptyCopy('ACTIVITY_REMINDER', MESSAGE_SQUARE_ORDER).title).toBe('没有活动提醒')
    expect(emptyCopy('SERVICE_PROGRESS', ['SERVICE_PROGRESS']).title).toBe('没有服务进度消息')
    // Public builds only have 服务进度: the copy keeps talking about inspections.
    expect(emptyCopy(null, ['SERVICE_PROGRESS'])).toEqual({
      title: '还没有消息',
      copy: '巡检方案、排期、报告交付等进度会通知到这里',
    })
    expect(emptyCopy(null, MESSAGE_SQUARE_ORDER).copy).toBe('订单物流、服务进度、报名和预约的通知都在这里')
  })
})

describe('one list of notifications and conversations', () => {
  const notice = (id: string, occurredAt: string) => ({
    id,
    category: 'SERVICE_PROGRESS' as const,
    title: 't',
    body: 'b',
    target: { type: 'INSPECTION_PLAN' as const, id: 'p', subId: null },
    occurredAt,
    read: false,
  })
  const conversation = (conversationId: string, at: string) => ({
    conversationId, title: '平台客服', preview: '', time: '', at, unreadCount: 0, initial: null,
  })

  it('places conversations among notifications by time, newest first', () => {
    const rows = mergeMessageRows(
      [notice('a', '2026-11-17T03:00:00Z'), notice('b', '2026-11-16T03:00:00Z')],
      [conversation('c', '2026-11-16T09:00:00Z'), conversation('d', '2026-11-18T09:00:00Z')],
      false,
    )
    expect(rows.map((row) => row.key)).toEqual(['c-d', 'n-a', 'c-c', 'n-b'])
  })

  it('holds back a conversation older than the loaded notifications while more pages wait', () => {
    const rows = mergeMessageRows(
      [notice('a', '2026-11-17T03:00:00Z')],
      [conversation('c', '2026-11-10T09:00:00Z')],
      true,
    )
    expect(rows.map((row) => row.key)).toEqual(['n-a'])
  })
})
