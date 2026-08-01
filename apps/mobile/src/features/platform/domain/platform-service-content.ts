import type { PlatformChannelCode } from './platform-channel'

export interface PlatformServiceHero {
  imageUrl: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel: string
}
export interface PlatformServiceScenario {
  code: string
  title: string
  subtitle: string
  imageUrl: string
}

export interface PlatformServiceCapability {
  code: string
  title: string
  description: string
}

export interface PlatformServiceRecommendation {
  id: string
  title: string
  subtitle: string
  imageUrl: string
  priceLabel?: string
  rating?: number
  salesLabel?: string
  actionLabel: string
}

export interface PlatformServiceChannelContent {
  code: Exclude<PlatformChannelCode, 'INSPECTION' | 'MORE'>
  searchPlaceholder: string
  hero: PlatformServiceHero
  scenarios: PlatformServiceScenario[]
  capabilityTitle: string
  capabilitySubtitle: string
  capabilities: PlatformServiceCapability[]
  recommendationTitle: string
  recommendationSubtitle: string
  recommendations: PlatformServiceRecommendation[]
}
