export type InspectionCategoryCode =
  | 'POWER'
  | 'PHOTOVOLTAIC'
  | 'OIL_GAS'
  | 'THERMAL'
  | 'PARK'
  | 'EQUIPMENT'
  | 'PERIODIC'
  | 'MORE'

export type InspectionVisualTone =
  | 'blue'
  | 'cyan'
  | 'orange'
  | 'red'
  | 'indigo'
  | 'teal'
  | 'green'
  | 'slate'

export interface InspectionPortalHero {
  title: string
  accent: string
  description: string
  highlights: readonly string[]
  imageUrl: string
}

export interface InspectionPortalCategory {
  code: InspectionCategoryCode
  name: string
  subtitle: string
  imageUrl: string
}

export interface InspectionPortalGuarantee {
  code: string
  title: string
  description: string
  icon: string
  tone: InspectionVisualTone
}

export interface InspectionPortalHotService {
  id: string
  /** The service's own category code; a child such as SUBSTATION for a 电力 service. */
  categoryCode: string
  title: string
  tag: string
  /** Board HomeSvc's second line; left out when the server has no active provider for the service. */
  providerName?: string
  imageUrl?: string
  priceLabel?: string
  rating?: number
  servedCountLabel?: string
}

export interface InspectionPortalProcessStep {
  code: string
  title: string
  icon: string
}

export interface InspectionPortalContent {
  hero: InspectionPortalHero | null
  categories: readonly InspectionPortalCategory[]
  guarantees: readonly InspectionPortalGuarantee[]
  hotServices: readonly InspectionPortalHotService[]
  processSteps: readonly InspectionPortalProcessStep[]
}
