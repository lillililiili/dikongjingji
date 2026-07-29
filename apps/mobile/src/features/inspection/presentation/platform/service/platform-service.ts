import type { PlatformWeatherPresentation } from '../../../../../shared/weather/platform-weather'
import type { InspectionPortalContent } from '../../../domain/inspection-portal-content'
import type { PlatformAvailability } from '../shared/platform-availability'

export type PlatformServiceAvailability = PlatformAvailability

export interface PlatformServiceChannel {
  code: string
  label: string
  availability: PlatformServiceAvailability
}

export interface PlatformServiceHero {
  imageUrl: string
  title: string
  highlight: string
  description: string
}

export interface PlatformRobotScenario {
  code: string
  title: string
  subtitle: string
  imageUrl: string
  availability: PlatformServiceAvailability
}

export interface PlatformServiceCapability {
  code: string
  title: string
  description: string
}

export interface PlatformRecommendedService {
  id: string
  title: string
  subtitle?: string
  imageUrl: string
  availability: PlatformServiceAvailability
  quoteLabel?: string
  rating?: number
  soldCountText?: string
  actionLabel: string
}

export interface PlatformServiceContent {
  cityName: string
  weather: PlatformWeatherPresentation
  unreadCount?: number
  searchPlaceholder?: string
  activeChannelCode: string
  channels: PlatformServiceChannel[]
  hero?: PlatformServiceHero
  scenarios: PlatformRobotScenario[]
  capabilities: PlatformServiceCapability[]
  recommendedServices: PlatformRecommendedService[]
}

export type PlatformServiceInput =
  | { status: 'loading' }
  | { status: 'ready'; content: PlatformServiceContent }
  | { status: 'error'; message?: string }

export type PlatformServiceViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; content: PlatformServiceContent }
  | { kind: 'empty' }
  | { kind: 'error'; message: string }

const hasContent = (content: PlatformServiceContent): boolean =>
  Boolean(
    content.hero
      || content.channels.length
      || content.scenarios.length
      || content.capabilities.length
      || content.recommendedServices.length,
  )

export const resolvePlatformServiceState = (
  input: PlatformServiceInput,
): PlatformServiceViewState => {
  if (input.status === 'loading') return { kind: 'loading' }
  if (input.status === 'error') {
    return {
      kind: 'error',
      message: input.message?.trim() || '服务频道加载失败，请稍后重试',
    }
  }

  return hasContent(input.content)
    ? { kind: 'ready', content: input.content }
    : { kind: 'empty' }
}

export function createPlatformServiceContentFromPortal(
  cityName: string,
  weather: PlatformWeatherPresentation,
  portal: InspectionPortalContent,
): PlatformServiceContent {
  return {
    cityName,
    weather,
    activeChannelCode: 'INSPECTION',
    channels: [{
      code: 'INSPECTION',
      label: '我要巡检',
      availability: 'AVAILABLE',
    }],
    scenarios: portal.categories.map((category) => ({
      code: category.code,
      title: category.name,
      subtitle: category.subtitle,
      imageUrl: category.imageUrl,
      availability: 'AVAILABLE',
    })),
    capabilities: portal.guarantees.map((guarantee) => ({
      code: guarantee.code,
      title: guarantee.title,
      description: guarantee.description,
    })),
    recommendedServices: portal.hotServices.map((service) => ({
      id: service.id,
      title: service.title,
      subtitle: service.tag,
      imageUrl: service.imageUrl
        ?? '/static/images/inspection/platform/industrial-inspection-robot-v2.png',
      availability: 'AVAILABLE',
      quoteLabel: service.priceLabel,
      rating: service.rating,
      soldCountText: service.servedCountLabel,
      actionLabel: '查看服务',
    })),
    ...(portal.hero ? {
      hero: {
        imageUrl: portal.hero.imageUrl,
        title: portal.hero.title,
        highlight: portal.hero.accent,
        description: portal.hero.description,
      },
    } : {}),
  }
}
