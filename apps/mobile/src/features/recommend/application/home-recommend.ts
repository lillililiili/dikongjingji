import type { ActivityCard } from '../../activity/domain/activity'
import type { VenueCard } from '../../experience/domain/experience'
import type { MallProductCard } from '../../mall/domain/mall'
import { formatBeijingTime } from '../../../shared/time/beijing-time'

// What the home page's 推荐 channel shows under the entries: today's bookable sessions of one
// venue, the next activities, a few products on sale in the city and the venues nearby.
// The server joins the four blocks (GET /api/v1/home/recommend) and works out which sessions can
// still be booked today; a block with nothing to show is left out. Nothing here is sample content.

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

// "14:00 场"
export function formatTodaySlotTime(slot: HomeTodaySlot): string {
  return `${formatBeijingTime(slot.startsAt)} 场`
}

// "余 6 位"
export function formatTodaySeats(slot: HomeTodaySlot): string {
  return `余 ${slot.seatsLeft} 位`
}

/**
 * Loads the blocks for a city. A newer load makes the answer of an older one be dropped, so a
 * slow answer for the previous city cannot show up under the new one. A failed request leaves
 * the blocks out; the entries above them still work.
 */
export function createHomeRecommendLoader(
  source: { load(cityCode: string): Promise<HomeRecommendContent> },
  options: { setContent(content: HomeRecommendContent): void; setFailed?(failed: boolean): void },
): { load(cityCode: string): Promise<void> } {
  let revision = 0

  return {
    async load(cityCode) {
      const requested = ++revision
      options.setContent(EMPTY_HOME_RECOMMEND)
      options.setFailed?.(false)
      try {
        const content = await source.load(cityCode)
        if (requested === revision) options.setContent(content)
      } catch {
        if (requested === revision) options.setFailed?.(true)
      }
    },
  }
}
