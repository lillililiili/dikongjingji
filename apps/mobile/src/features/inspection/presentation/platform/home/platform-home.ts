import type { PlatformWeatherPresentation } from '../../../../../shared/weather/platform-weather'
import type { InspectionPortalContent } from '../../../domain/inspection-portal-content'
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
  highlights: readonly string[]
  actionLabel: string
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
  quoteLabel?: string
  rating?: number
  soldCountText?: string
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
  unreadCount?: number
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

const toFeaturedServices = (
  portal: InspectionPortalContent,
): PlatformHomeFeaturedService[] =>
  portal.hotServices.map((service) => ({
    id: service.id,
    title: service.title,
    subtitle: service.tag,
    imageUrl: service.imageUrl
      ?? '/static/images/inspection/platform/industrial-inspection-robot-v2.png',
    availability: 'AVAILABLE',
    quoteLabel: service.priceLabel,
    rating: service.rating,
    soldCountText: service.servedCountLabel,
  }))

export function createPlatformHomeContentFromPortal(
  cityName: string,
  weather: PlatformWeatherPresentation,
  portal: InspectionPortalContent,
): PlatformHomeContent {
  return {
    cityName,
    weather,
    serviceEntries: portal.categories.map((category) => ({
      code: category.code,
      title: category.name,
      subtitle: category.subtitle,
      imageUrl: category.imageUrl,
      availability: 'AVAILABLE',
    })),
    featuredServices: toFeaturedServices(portal),
    activities: [],
    ...(portal.hero ? {
      hero: {
        imageUrl: portal.hero.imageUrl,
        title: portal.hero.title,
        highlight: portal.hero.accent,
        description: portal.hero.description,
        highlights: portal.hero.highlights,
        actionLabel: '浏览精选服务',
      },
    } : {}),
  }
}

export function createPlatformHomePreviewContent(
  cityName: string,
  weather: PlatformWeatherPresentation,
  portal: InspectionPortalContent,
): PlatformHomeContent {
  const featuredFallbacks: PlatformHomeFeaturedService[] = [
    {
      id: 'preview-aerial-photo',
      title: '航拍跟拍',
      subtitle: '婚礼跟拍 · 活动拍摄',
      imageUrl: '/static/images/inspection/platform/multi-drone-v2.png',
      availability: 'UNAVAILABLE',
    },
    {
      id: 'preview-low-altitude-tour',
      title: '低空观光',
      subtitle: '空中游览 · 专业讲解',
      imageUrl: '/static/images/inspection/platform/home-low-altitude-tour-v2.png',
      availability: 'UNAVAILABLE',
    },
    {
      id: 'preview-robot-experience',
      title: '机器人体验',
      subtitle: '互动陪伴 · 科普讲解',
      imageUrl: '/static/images/inspection/platform/delivery-robot-v2.png',
      availability: 'UNAVAILABLE',
    },
    {
      id: 'preview-equipment-rental',
      title: '设备租赁',
      subtitle: '专业设备 · 灵活租用',
      imageUrl: '/static/images/inspection/platform/remote-controller-v2.png',
      availability: 'UNAVAILABLE',
    },
  ]
  const featuredServices = [
    ...toFeaturedServices(portal).slice(0, 4),
    ...featuredFallbacks,
  ].slice(0, 4)

  return {
    cityName,
    weather,
    searchPlaceholder: '搜索服务、活动或设备',
    hero: {
      imageUrl: '/static/images/inspection/platform/home-hero.png',
      title: '低空服务',
      highlight: '触手可及',
      description: '无人机 · 机器人，让生活更美好',
      highlights: ['多元服务', '专业保障', '便捷响应'],
      actionLabel: '浏览精选服务',
    },
    serviceEntries: [
      {
        code: 'AERIAL_PHOTO',
        title: '航拍摄影',
        subtitle: '记录美好瞬间',
        imageUrl: '/static/images/inspection/platform/home-aerial-photo-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'INSPECTION',
        title: '巡检服务',
        subtitle: '专业高效安全',
        imageUrl: '/static/images/inspection/platform/home-inspection-service-v2.png',
        availability: 'AVAILABLE',
      },
      {
        code: 'DRONE_FLIGHT',
        title: '飞无人机',
        subtitle: '一起飞上天空',
        imageUrl: '/static/images/inspection/platform/home-drone-flight-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'ROBOT',
        title: '机器人服务',
        subtitle: '智能便捷生活',
        imageUrl: '/static/images/inspection/platform/home-robot-service-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'LOW_ALTITUDE_TOUR',
        title: '低空旅游',
        subtitle: '空中观光体验',
        imageUrl: '/static/images/inspection/platform/home-low-altitude-tour-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'EQUIPMENT_RENTAL',
        title: '设备租赁',
        subtitle: '专业设备随租',
        imageUrl: '/static/images/inspection/platform/home-equipment-rental-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'ENTERPRISE',
        title: '企业服务',
        subtitle: '助力企业发展',
        imageUrl: '/static/images/inspection/platform/home-enterprise-service-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'CITY',
        title: '城市服务',
        subtitle: '智慧城市新体验',
        imageUrl: '/static/images/inspection/platform/home-city-service-v2.png',
        availability: 'UNAVAILABLE',
      },
    ],
    featuredServices,
    activities: [
      {
        id: 'preview-drone-football',
        title: '无人机足球赛',
        locationText: '城市体育中心',
        imageUrl: '/static/images/inspection/platform/multi-drone-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        id: 'preview-aerial-contest',
        title: '航拍摄影大赛',
        locationText: '城市湖景区',
        imageUrl: '/static/images/inspection/platform/home-aerial-photo-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        id: 'preview-low-altitude-festival',
        title: '低空观光节',
        locationText: '城市风景区',
        imageUrl: '/static/images/inspection/platform/home-low-altitude-tour-v2.png',
        availability: 'UNAVAILABLE',
      },
    ],
    enterpriseSolution: {
      title: '企业解决方案',
      description: '降本增效 · 安全可靠 · 定制服务',
      imageUrl: '/static/images/inspection/platform/enterprise-solution.png',
      availability: 'UNAVAILABLE',
      benefits: [
        {
          title: '行业解决方案',
          description: '多行业覆盖',
          iconType: 'map-filled',
          color: '#4e8cff',
        },
        {
          title: '数据智能分析',
          description: '洞察更精准',
          iconType: 'list',
          color: '#23b26f',
        },
        {
          title: '一站式服务',
          description: '省心更高效',
          iconType: 'wallet-filled',
          color: '#ff8a1f',
        },
      ],
    },
  }
}
