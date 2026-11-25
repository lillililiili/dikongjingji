import type { HomeEntryTarget } from './home-channels'

export type HomeCarouselTone = 'SKY' | 'SUN' | 'VIOLET' | 'MINT'

export interface HomeCarouselSlide {
  id: string
  tag: string
  titleLines: readonly [string, string]
  descriptionLines: readonly [string, string]
  actionLabel: string
  imageUrl: string
  imageAlt: string
  tone: HomeCarouselTone
  target: HomeEntryTarget
}

export const HOME_CAROUSEL_INTERVAL_MS = 5500
