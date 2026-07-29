import type { PlatformWeatherPresentation } from '../../../../../shared/weather/platform-weather'
import type { PlatformAvailability } from '../shared/platform-availability'

export {
  resolvePlatformAvailabilityAction,
} from '../shared/platform-availability'
export type { PlatformAvailability } from '../shared/platform-availability'

export interface PlatformHomeHero {
  imageUrl: string
  title: string
  highlight: string
  description: string
}

export interface PlatformHomeServiceEntry {
  code: string
  title: string
  subtitle: string
  imageUrl: string
  availability: PlatformAvailability
}

export interface PlatformHomeFeaturedService {
  id: string
  title: string
  subtitle?: string
  imageUrl: string
  availability: PlatformAvailability
  quoteLabel: string
  rating: number
  soldCountText: string
}

export interface PlatformHomeActivity {
  id: string
  title: string
  locationText?: string
  distanceText?: string
  imageUrl: string
  availability: PlatformAvailability
}

export interface PlatformHomeEnterpriseSolution {
  title: string
  description: string
  imageUrl: string
  availability: PlatformAvailability
  benefits: PlatformHomeEnterpriseBenefit[]
}

export interface PlatformHomeEnterpriseBenefit {
  title: string
  description: string
  iconType: string
  color: string
}

export interface PlatformHomeContent {
  cityName: string
  weather: PlatformWeatherPresentation
  unreadCount: number
  searchPlaceholder?: string
  hero?: PlatformHomeHero
  serviceEntries: PlatformHomeServiceEntry[]
  featuredServices: PlatformHomeFeaturedService[]
  activities: PlatformHomeActivity[]
  enterpriseSolution?: PlatformHomeEnterpriseSolution
}

export type PlatformHomeInput =
  | { status: 'loading' }
  | { status: 'ready'; content: PlatformHomeContent }
  | { status: 'error'; message?: string }

export type PlatformHomeViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; content: PlatformHomeContent }
  | { kind: 'empty' }
  | { kind: 'error'; message: string }

const hasVisibleContent = (content: PlatformHomeContent): boolean =>
  Boolean(
    content.hero
      || content.serviceEntries.length
      || content.featuredServices.length
      || content.activities.length
      || content.enterpriseSolution,
  )

export const resolvePlatformHomeState = (input: PlatformHomeInput): PlatformHomeViewState => {
  if (input.status === 'loading') return { kind: 'loading' }
  if (input.status === 'error') {
    return { kind: 'error', message: input.message?.trim() || '页面加载失败，请稍后重试' }
  }

  return hasVisibleContent(input.content)
    ? { kind: 'ready', content: input.content }
    : { kind: 'empty' }
}
