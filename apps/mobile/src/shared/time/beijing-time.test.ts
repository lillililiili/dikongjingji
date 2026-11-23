import { describe, expect, it } from 'vitest'

import { formatActivityDateTime, formatActivityTime } from '../../features/activity/presentation/shared/activity-format'
import { formatContentDay } from '../../features/content/application/content-view'
import { formatCommentTime } from '../../features/content/application/video-view'
import { formatClock, formatMonthDayTime } from '../../features/inspection/presentation/shared/service-flow-format'
import { scheduleChip } from '../../features/live/application/live-view'
import { formatMessageTime } from '../../features/message/presentation/message-format'
import { formatRentalInstant } from '../../features/rental/application/rental-format'
import { formatTradeDateTime, formatTradeTime } from '../../features/trade/application/order-view'
import { buildBeijingDayOptions, formatBeijingTime, toBeijingDate, toBeijingParts } from './beijing-time'

// vitest.config.mts runs the suite in America/New_York. 2026-11-21 16:30 UTC is Saturday 11/21
// 11:30 on the device and Sunday 11/22 00:30 in Beijing; every formatter has to say the latter.
const INSTANT = '2026-11-21T16:30:00Z'

describe('Beijing time on a phone in New York', () => {
  it('runs in a device zone 13 hours away from Beijing', () => {
    // Eastern Standard Time, five hours behind UTC.
    expect(new Date(INSTANT).getTimezoneOffset()).toBe(300)
    expect(new Date(INSTANT).getHours()).toBe(11)
    expect(new Date(INSTANT).getDate()).toBe(21)
  })

  it('reads the Beijing calendar and clock', () => {
    expect(toBeijingParts(new Date(INSTANT))).toEqual({
      year: 2026, month: 11, day: 22, weekday: '周日', hour: 0, minute: 30,
    })
    expect(toBeijingDate(new Date(INSTANT))).toBe('2026-11-22')
    expect(formatBeijingTime(INSTANT)).toBe('00:30')
  })

  it('formats orders, enrolments, rentals and inspection times in Beijing time', () => {
    expect(formatTradeTime(INSTANT)).toBe('11/22 00:30')
    expect(formatTradeDateTime(INSTANT)).toBe('2026-11-22 00:30')
    expect(formatActivityDateTime(INSTANT)).toBe('11/22 周日 00:30')
    expect(formatActivityTime(INSTANT)).toBe('00:30')
    expect(formatRentalInstant(INSTANT)).toBe('11/22 00:30')
    expect(formatMonthDayTime(INSTANT)).toBe('11/22 00:30')
    expect(formatClock(INSTANT)).toBe('00:30')
    expect(formatContentDay(INSTANT)).toBe('11/22')
  })

  it('says 今天 and 昨天 by the Beijing day', () => {
    // 01:00 UTC on 11/22 is 09:00 in Beijing on 11/22 but still 11/21 evening in New York.
    const now = new Date('2026-11-22T01:00:00Z')
    expect(formatMessageTime(INSTANT, now)).toBe('今天 00:30')
    expect(formatMessageTime('2026-11-21T15:50:00Z', now)).toBe('昨天 23:50')
    expect(formatMessageTime('2026-11-20T15:50:00Z', now)).toBe('11/20 23:50')
  })

  it('starts the day strips on the Beijing day', () => {
    const options = buildBeijingDayOptions(new Date(INSTANT), 3)
    expect(options.map((option) => option.date)).toEqual(['2026-11-22', '2026-11-23', '2026-11-24'])
    expect(options.map((option) => option.title)).toEqual(['今天', '明天', '周二'])
  })

  it('counts days to a live show and the date of an old comment by the Beijing day', () => {
    // 11/22 23:00 Beijing is 15:00 UTC on 11/22; now is 00:30 Beijing on 11/22: the same day.
    expect(scheduleChip('2026-11-22T15:00:00Z', new Date(INSTANT))).toBe('今天开播')
    expect(scheduleChip('2026-11-22T16:30:00Z', new Date(INSTANT))).toBe('明天开播')
    expect(formatCommentTime('2026-11-19T16:30:00Z', new Date(INSTANT))).toBe('11/20')
  })
})
