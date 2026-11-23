import { describe, expect, it } from 'vitest'

import activityFlagSource from '../../activity/application/activity-preview.ts?raw'
import experienceFlagSource from '../application/experience-preview.ts?raw'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import { resolveExperienceIntent } from '../application/experience-intent'
import type { VenueCard, VenueProject, VenueSession } from '../domain/experience'
import {
  formatVenueCardNote,
  formatVenuePrice,
  formatVenueProjectMeta,
  formatVenueSessionSeats,
  formatVenueSessionTime,
  toDialNumber,
} from '../presentation/shared/experience-format'
import { buildBeijingDayOptions } from '../../../shared/time/beijing-time'

const experienceSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

const venue: VenueCard = {
  venueId: 'v',
  name: '东营低空与机器人体验馆',
  coverImage: null,
  openingNote: '09:30–18:00 · 周一闭馆',
  address: '东营区示例路 66 号',
  openSessionsToday: 3,
}

const project: VenueProject = {
  projectId: 'p',
  name: '机器人避障闯关',
  durationMinutes: 30,
  minAge: 6,
  capacity: 8,
  limitNote: null,
  priceAmountMinor: 3900,
  onlinePayment: true,
}

const session: VenueSession = {
  sessionId: 's',
  startsAt: '2026-11-17T02:00:00Z',
  endsAt: '2026-11-17T02:30:00Z',
  capacity: 8,
  seatsLeft: 5,
  status: 'OPEN',
}

describe('venue formatting', () => {
  it('adds the bookable count to the opening hours only when there is one', () => {
    expect(formatVenueCardNote(venue)).toBe('09:30–18:00 · 周一闭馆 · 今日 3 场可约')
    expect(formatVenueCardNote({ ...venue, openSessionsToday: 0 })).toBe('09:30–18:00 · 周一闭馆')
    expect(formatVenueCardNote({ ...venue, openingNote: null, openSessionsToday: 0 })).toBe('')
  })

  it('describes a project by length, age and seats', () => {
    expect(formatVenueProjectMeta(project)).toBe('30 分钟 · 6 岁以上 · 每场 8 人')
    expect(formatVenueProjectMeta({ ...project, minAge: null })).toBe('30 分钟 · 每场 8 人')
  })

  it('formats prices from the minor unit', () => {
    expect(formatVenuePrice(3900)).toBe('¥39')
    expect(formatVenuePrice(3950)).toBe('¥39.50')
    expect(formatVenuePrice(0)).toBe('免费')
  })

  it('shows a session in Beijing time with seats or its status', () => {
    expect(formatVenueSessionTime(session)).toBe('10:00')
    expect(formatVenueSessionSeats(session)).toBe('余 5')
    expect(formatVenueSessionSeats({ ...session, status: 'FULL', seatsLeft: 0 })).toBe('已满')
    expect(formatVenueSessionSeats({ ...session, status: 'ENDED' })).toBe('已结束')
  })

  it('turns a printed phone number into one the dialer accepts', () => {
    expect(toDialNumber('0546-8000 0000')).toBe('054680000000')
    expect(toDialNumber('+86 546 8000 0000')).toBe('+8654680000000')
  })

  it('builds a week of day tabs starting today in Beijing time', () => {
    const days = buildBeijingDayOptions(new Date('2026-11-16T09:30:00Z'), 7)
    expect(days.map((day) => day.title)).toEqual(['今天', '明天', '周三', '周四', '周五', '周六', '周日'])
    expect(days[0].date).toBe('2026-11-16')
    expect(days[6]).toMatchObject({ date: '2026-11-22', subtitle: '11/22' })
  })
})

describe('experience intents', () => {
  it('opens the built pages, explains the recorded demo in preview builds and hides in public builds', () => {
    const open = { type: 'OPEN_EXPERIENCE', experienceId: 'e', experienceType: 'SIMULATION' } as const

    expect(resolveExperienceIntent(open, true))
      .toEqual({ action: 'NAVIGATE', url: '/pages/experience/simulation?experienceId=e' })
    expect(resolveExperienceIntent({ ...open, experienceType: 'SOFTWARE_TRIAL' }, true))
      .toEqual({ action: 'NAVIGATE', url: '/pages/experience/software-trial?experienceId=e' })
    expect(resolveExperienceIntent({ ...open, experienceType: 'COURSE_TRIAL' }, true))
      .toEqual({ action: 'NAVIGATE', url: '/pages/experience/course-trial?experienceId=e' })
    expect(resolveExperienceIntent({ ...open, experienceType: 'RECORDED_DEMO' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '录播演示即将开放' })
    expect(resolveExperienceIntent(open, false)).toEqual({ action: 'HIDE' })
  })
})

describe('experience preview flag', () => {
  const expression = (source: string, name: string): string => {
    const match = new RegExp(`export const ${name} = (.+)`).exec(source)
    if (!match) throw new Error(`${name} is not declared`)
    return match[1].trim()
  }

  it('uses exactly the expression of the shared preview flag', () => {
    const shared = expression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED')
    expect(expression(experienceFlagSource, 'EXPERIENCE_PREVIEW_ENABLED')).toBe(shared)
    expect(expression(activityFlagSource, 'ACTIVITY_PREVIEW_ENABLED')).toBe(shared)
  })

  it('keeps the experience feature from importing the shared flag module', () => {
    const offenders = Object.entries(experienceSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)

    expect(offenders).toEqual([])
  })
})
