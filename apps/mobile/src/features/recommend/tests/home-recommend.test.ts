import { describe, expect, it } from 'vitest'

import type { ActivityCard } from '../../activity/domain/activity'
import type { VenueCard, VenueProject, VenueSession } from '../../experience/domain/experience'
import type { MallProductCard } from '../../mall/domain/mall'
import {
  EMPTY_HOME_RECOMMEND,
  buildTodaySlots,
  createHomeRecommendLoader,
  formatTodaySeats,
  formatTodaySlotTime,
  pickNextOpenSession,
  type HomeRecommendContent,
  type HomeRecommendSources,
} from '../application/home-recommend'

// 2026-11-17 13:00 in Beijing.
const NOW = new Date('2026-11-17T05:00:00Z')

const session = (id: string, startsAt: string, overrides: Partial<VenueSession> = {}): VenueSession => ({
  sessionId: id,
  startsAt,
  endsAt: startsAt,
  capacity: 8,
  seatsLeft: 6,
  status: 'OPEN',
  ...overrides,
})

const project = (id: string, name: string): VenueProject => ({
  projectId: id,
  name,
  durationMinutes: 30,
  minAge: 6,
  capacity: 8,
  limitNote: null,
  priceAmountMinor: 3900,
})

const venue = (id: string, openSessionsToday: number): VenueCard => ({
  venueId: id,
  name: `场馆 ${id}`,
  coverImage: null,
  openingNote: '09:30–18:00',
  address: '东营区示例路 66 号',
  openSessionsToday,
})

const activity = (id: string) => ({ activityId: id }) as ActivityCard
const product = (id: string) => ({ productId: id }) as MallProductCard

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function mount(sources: Partial<HomeRecommendSources>) {
  let content: HomeRecommendContent = EMPTY_HOME_RECOMMEND
  const loader = createHomeRecommendLoader({
    loadActivities: async () => [],
    loadProducts: async () => [],
    loadVenues: async () => [],
    loadVenueProjects: async () => [],
    loadSessions: async () => [],
    ...sources,
  }, {
    now: () => NOW,
    getContent: () => content,
    setContent: (next) => { content = next },
  })
  return { loader, content: () => content }
}

describe('today slots', () => {
  it('takes the earliest session that is open, has seats and has not started', () => {
    const next = pickNextOpenSession([
      session('past', '2026-11-17T04:00:00Z'),
      session('full', '2026-11-17T06:00:00Z', { status: 'FULL', seatsLeft: 0 }),
      session('later', '2026-11-17T08:00:00Z'),
      session('soon', '2026-11-17T06:30:00Z', { seatsLeft: 2 }),
    ], NOW)

    expect(next?.sessionId).toBe('soon')
    expect(pickNextOpenSession([session('past', '2026-11-17T04:00:00Z')], NOW)).toBeNull()
  })

  it('gives one slot per project that still has a session, in project order', () => {
    const slots = buildTodaySlots([
      { project: project('p1', '机器人避障闯关'), sessions: [session('a', '2026-11-17T06:00:00Z', { seatsLeft: 3 })] },
      { project: project('p2', '无人机模拟飞行'), sessions: [session('b', '2026-11-17T03:00:00Z')] },
      { project: project('p3', '编程体验课'), sessions: [session('c', '2026-11-17T07:30:00Z', { seatsLeft: 1 })] },
    ], NOW)

    expect(slots.map((slot) => slot.projectName)).toEqual(['机器人避障闯关', '编程体验课'])
    expect(formatTodaySlotTime(slots[0])).toBe('14:00 场')
    expect(formatTodaySeats(slots[0])).toBe('余 3 位')
    expect(formatTodaySlotTime(slots[1])).toBe('15:30 场')
  })
})

describe('home recommend loader', () => {
  it('fills each block from its own source and asks for today in Beijing time', async () => {
    const asked: string[] = []
    const { loader, content } = mount({
      loadActivities: async (cityCode, size) => { asked.push(`activities ${cityCode} ${size}`); return [activity('a1')] },
      loadProducts: async (cityCode, size) => { asked.push(`products ${cityCode} ${size}`); return [product('p1')] },
      loadVenues: async (cityCode, size) => { asked.push(`venues ${cityCode} ${size}`); return [venue('closed', 0), venue('open', 2)] },
      loadVenueProjects: async (venueId) => { asked.push(`projects ${venueId}`); return [project('p1', '机器人避障闯关')] },
      loadSessions: async (input) => {
        asked.push(`sessions ${input.venueId} ${input.projectId} ${input.date}`)
        return [session('s1', '2026-11-17T06:00:00Z')]
      },
    })

    await loader.load('370500')

    expect(asked).toEqual(expect.arrayContaining([
      'activities 370500 3',
      'products 370500 4',
      'venues 370500 2',
      'projects open',
      'sessions open p1 2026-11-17',
    ]))
    expect(content().activities).toEqual([activity('a1')])
    expect(content().products).toEqual([product('p1')])
    expect(content().venues.map((item) => item.venueId)).toEqual(['closed', 'open'])
    expect(content().today?.venue.venueId).toBe('open')
    expect(content().today?.slots).toHaveLength(1)
  })

  it('leaves a failed block out and still shows the others', async () => {
    const { loader, content } = mount({
      loadActivities: async () => { throw new Error('offline') },
      loadProducts: async () => [product('p1')],
      loadVenues: async () => [venue('open', 1)],
      loadVenueProjects: async () => { throw new Error('offline') },
    })

    await loader.load('370500')

    expect(content().activities).toEqual([])
    expect(content().products).toEqual([product('p1')])
    expect(content().venues).toHaveLength(1)
    expect(content().today).toBeNull()
  })

  it('shows no today block when no venue has a session left or none can be booked', async () => {
    const noVenue = mount({ loadVenues: async () => [venue('closed', 0)] })
    await noVenue.loader.load('370500')
    expect(noVenue.content().today).toBeNull()

    const allPast = mount({
      loadVenues: async () => [venue('open', 1)],
      loadVenueProjects: async () => [project('p1', '机器人避障闯关')],
      loadSessions: async () => [session('past', '2026-11-17T04:00:00Z')],
    })
    await allPast.loader.load('370500')
    expect(allPast.content().today).toBeNull()
  })

  it('drops the answers of the previous city once another city is being loaded', async () => {
    const dongying = deferred<ActivityCard[]>()
    const { loader, content } = mount({
      loadActivities: (cityCode) =>
        (cityCode === '370500' ? dongying.promise : Promise.resolve([activity('jinan')])),
    })

    const first = loader.load('370500')
    await loader.load('370100')
    dongying.resolve([activity('dongying')])
    await first

    expect(content().activities).toEqual([activity('jinan')])
  })
})
