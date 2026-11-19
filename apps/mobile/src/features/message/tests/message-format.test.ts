import { describe, expect, it } from 'vitest'

import { MESSAGE_CATEGORY_LABELS, formatMessageTime } from '../presentation/message-format'

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
  it('labels service progress and demand quotes', () => {
    expect(MESSAGE_CATEGORY_LABELS).toEqual({ SERVICE_PROGRESS: '服务进度', DEMAND_QUOTE: '需求与报价', CUSTOMER_SERVICE: '客服消息' })
  })
})
