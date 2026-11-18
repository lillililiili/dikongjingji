import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import { toCard as toActivityCard, type ActivityCardDto } from '../../activity/data/activity-api'
import { toVenueCard, type VenueCardDto } from '../../experience/data/experience-api'
import { toMallProductCard, type ProductCardDto } from '../../mall/data/mall-api'
import type { HomeRecommendContent } from '../application/home-recommend'

// Mirrors contracts/openapi-home.yaml: one request for the four blocks, each item shaped like
// the list endpoint of its own feature.
interface HomeRecommendDto {
  today: {
    venue: VenueCardDto
    slots: { projectId: string; projectName: string; startsAt: string; seatsLeft: number }[]
  } | null
  activities: ActivityCardDto[]
  products: ProductCardDto[]
  venues: VenueCardDto[]
}

export interface HomeRecommendApi {
  load(cityCode: string): Promise<HomeRecommendContent>
}

export function createHomeRecommendApi(http: PublicApiHttpClient): HomeRecommendApi {
  return {
    async load(cityCode) {
      const dto = await http.request<HomeRecommendDto>({
        method: 'GET',
        url: `/api/v1/home/recommend?cityCode=${encodeURIComponent(cityCode)}`,
      })
      return {
        today: dto.today && dto.today.slots.length
          ? {
              venue: toVenueCard(dto.today.venue),
              slots: dto.today.slots.map((slot) => ({ ...slot })),
            }
          : null,
        activities: dto.activities.map(toActivityCard),
        products: dto.products.map(toMallProductCard),
        venues: dto.venues.map(toVenueCard),
      }
    },
  }
}
