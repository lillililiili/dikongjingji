import { describe, expect, it } from 'vitest'

import {
  addMonths,
  buildCalendarMonth,
  calendarMonthRange,
  describeDaySessions,
  formatPickedDay,
} from '../application/calendar-view'
import { s53Session } from './result-change-fixtures'

// 10:00 on 3 November 2026 in Beijing; 1 November is a Sunday.
const NOW = new Date('2026-11-03T02:00:00Z')
const sessions = [
  s53Session('s-am', '2026-11-15T01:00:00Z', { label: '上午' }),
  s53Session('s-pm', '2026-11-15T06:00:00Z', { label: '下午', status: 'FULL', seatsLeft: 0 }),
  s53Session('s-full', '2026-11-21T01:00:00Z', { status: 'FULL', seatsLeft: 0 }),
  s53Session('s-closed', '2026-11-28T01:00:00Z', { status: 'CLOSED' }),
  s53Session('s-dec', '2026-12-06T01:00:00Z'),
]

describe('month calendar', () => {
  it('starts on Sunday and marks open, full, past and empty days', () => {
    const cells = buildCalendarMonth({ year: 2026, month: 11 }, sessions, NOW, 1, null)
    expect(cells).toHaveLength(30)
    const day = (n: number) => cells.find((cell) => cell.day?.day === n)!.day!
    expect(day(1).state).toBe('PAST')
    expect(day(3)).toMatchObject({ state: 'NONE', today: true, sub: '今天' })
    expect(day(15)).toMatchObject({ state: 'OPEN', sub: '' })
    expect(day(21)).toMatchObject({ state: 'FULL', sub: '已满' })
    expect(day(28)).toMatchObject({ state: 'FULL', sub: '已截止' })
  })

  it('leaves blanks before a month that does not start on Sunday', () => {
    const cells = buildCalendarMonth({ year: 2026, month: 12 }, sessions, NOW, 1, null)
    // 1 December 2026 is a Tuesday.
    expect(cells.slice(0, 2).every((cell) => cell.day === null)).toBe(true)
    expect(cells[2].day?.day).toBe(1)
  })

  it('does not offer the session a reschedule leaves, nor one too small for everyone', () => {
    const cells = buildCalendarMonth({ year: 2026, month: 11 }, sessions, NOW, 2, 's-am')
    expect(cells.find((cell) => cell.day?.day === 15)?.day?.state).toBe('FULL')
    expect(describeDaySessions('2026-11-15', sessions, 2, 's-am').map((option) => option.sessionId)).toEqual(['s-pm'])
  })

  it('runs from this month to the month of the last session', () => {
    expect(calendarMonthRange(sessions, NOW)).toEqual({ first: { year: 2026, month: 11 }, last: { year: 2026, month: 12 } })
    expect(addMonths({ year: 2026, month: 12 }, 1)).toEqual({ year: 2027, month: 1 })
  })

  it('lists the sessions of a day in order', () => {
    expect(describeDaySessions('2026-11-15', sessions, 1, null)).toEqual([
      { sessionId: 's-am', title: '上午 09:00', sub: '余 10', pickable: true },
      { sessionId: 's-pm', title: '下午 14:00', sub: '已满', pickable: false },
    ])
    expect(formatPickedDay('2026-11-22')).toBe('11月22日 周日')
  })
})
