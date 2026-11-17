import type { ActivityCard } from '../../activity/domain/activity'
import type { VenueCard, VenueProject, VenueSession } from '../../experience/domain/experience'
import type { MallProductCard } from '../../mall/domain/mall'
import { formatBeijingTime, toBeijingDate } from '../../../shared/time/beijing-time'

// What the home page's 推荐 channel shows under the entries: today's bookable sessions of one
// venue, the next activities, a few products on sale in the city and the venues nearby.
// Every block comes from the read-only APIs of its own feature; a block with nothing to show,
// or whose request failed, is left out. Nothing here is sample content.

export interface HomeTodaySlot {
  projectId: string
  projectName: string
  startsAt: string
  seatsLeft: number
}

export interface HomeTodayBlock {
  venue: VenueCard
  slots: HomeTodaySlot[]
}

export interface HomeRecommendContent {
  today: HomeTodayBlock | null
  activities: ActivityCard[]
  products: MallProductCard[]
  venues: VenueCard[]
}

export const EMPTY_HOME_RECOMMEND: HomeRecommendContent = {
  today: null,
  activities: [],
  products: [],
  venues: [],
}

export const HOME_RECOMMEND_LIMITS = {
  activities: 3,
  products: 4,
  venues: 2,
  /** Projects of the venue whose sessions are asked for; one request each. */
  todayProjects: 3,
} as const

export interface HomeRecommendSources {
  loadActivities(cityCode: string, size: number): Promise<ActivityCard[]>
  loadProducts(cityCode: string, size: number): Promise<MallProductCard[]>
  loadVenues(cityCode: string, size: number): Promise<VenueCard[]>
  loadVenueProjects(venueId: string, cityCode: string): Promise<VenueProject[]>
  loadSessions(input: {
    venueId: string
    cityCode: string
    projectId: string
    date: string
  }): Promise<VenueSession[]>
}

/** The earliest session that can still be booked and has not started. */
export function pickNextOpenSession(
  sessions: readonly VenueSession[],
  now: Date,
): VenueSession | null {
  const open = sessions
    .filter((session) =>
      session.status === 'OPEN'
      && session.seatsLeft > 0
      && new Date(session.startsAt).getTime() > now.getTime())
    .sort((left, right) =>
      new Date(left.startsAt).getTime() - new Date(right.startsAt).getTime())
  return open[0] ?? null
}

/** One slot per project that still has a session today, in the venue's project order. */
export function buildTodaySlots(
  projects: ReadonlyArray<{ project: VenueProject; sessions: readonly VenueSession[] }>,
  now: Date,
): HomeTodaySlot[] {
  return projects.flatMap(({ project, sessions }) => {
    const next = pickNextOpenSession(sessions, now)
    return next
      ? [{
          projectId: project.projectId,
          projectName: project.name,
          startsAt: next.startsAt,
          seatsLeft: next.seatsLeft,
        }]
      : []
  })
}

// "14:00 场"
export function formatTodaySlotTime(slot: HomeTodaySlot): string {
  return `${formatBeijingTime(slot.startsAt)} 场`
}

// "余 6 位"
export function formatTodaySeats(slot: HomeTodaySlot): string {
  return `余 ${slot.seatsLeft} 位`
}

/**
 * Loads the blocks for a city. The three lists are asked for at once and each fills in when it
 * arrives; a list that fails stays empty. A newer load makes every answer of an older one be
 * dropped, so a slow answer for the previous city cannot show up under the new one.
 */
export function createHomeRecommendLoader(
  sources: HomeRecommendSources,
  options: {
    now(): Date
    getContent(): HomeRecommendContent
    setContent(content: HomeRecommendContent): void
  },
): { load(cityCode: string): Promise<void> } {
  let revision = 0

  return {
    async load(cityCode) {
      const requested = ++revision
      const latest = (): boolean => requested === revision
      const merge = (part: Partial<HomeRecommendContent>): void => {
        if (latest()) options.setContent({ ...options.getContent(), ...part })
      }
      options.setContent(EMPTY_HOME_RECOMMEND)

      const loadToday = async (venues: VenueCard[]): Promise<void> => {
        const venue = venues.find((candidate) => candidate.openSessionsToday > 0)
        if (!venue) return
        const projects = (await sources.loadVenueProjects(venue.venueId, cityCode))
          .slice(0, HOME_RECOMMEND_LIMITS.todayProjects)
        const now = options.now()
        const date = toBeijingDate(now)
        const answers = await Promise.allSettled(projects.map(async (project) => ({
          project,
          sessions: await sources.loadSessions({
            venueId: venue.venueId,
            cityCode,
            projectId: project.projectId,
            date,
          }),
        })))
        const slots = buildTodaySlots(
          answers.flatMap((answer) => (answer.status === 'fulfilled' ? [answer.value] : [])),
          now,
        )
        if (slots.length) merge({ today: { venue, slots } })
      }

      await Promise.allSettled([
        sources.loadActivities(cityCode, HOME_RECOMMEND_LIMITS.activities)
          .then((activities) => merge({ activities })),
        sources.loadProducts(cityCode, HOME_RECOMMEND_LIMITS.products)
          .then((products) => merge({ products })),
        sources.loadVenues(cityCode, HOME_RECOMMEND_LIMITS.venues)
          .then(async (venues) => {
            merge({ venues })
            if (latest()) await loadToday(venues)
          }),
      ])
    },
  }
}
