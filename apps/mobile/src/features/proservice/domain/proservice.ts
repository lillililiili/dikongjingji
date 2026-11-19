// Mirrors contracts/openapi-proservice.yaml 1.0.0 (step F2, docs/redesign/03-App实现任务计划.md §23).
// Aerial, survey, maintenance and tech support services: a catalog, a service page and a provider
// page, all public reads. None of them is booked; the main action everywhere is 发布需求 (F1).

export type ProServiceType = 'AERIAL' | 'SURVEY' | 'MAINTENANCE' | 'TECH_SUPPORT'

export type ProServiceFacet =
  | 'VIDEO_FOOTAGE'
  | 'PHOTOS'
  | 'ORTHOPHOTO'
  | 'MODEL_3D'
  | 'ON_SITE'
  | 'MAIL_IN'
  | 'REMOTE'
  | 'STATIONED'

export type ProServiceCategory =
  | 'ENGINEERING'
  | 'ENERGY'
  | 'TOURISM'
  | 'EVENT'
  | 'MODELING'
  | 'PARK'
  | 'TERRAIN'
  | 'CORRIDOR'
  | 'GENERAL'
  | 'DRONE'
  | 'ROBOT'
  | 'ROBOT_DOG'
  | 'FLIGHT'
  | 'SOFTWARE'

export type ProServicePriceMode = 'PRICE_FROM' | 'PROJECT'
export type ProServiceSort = 'DEFAULT' | 'PRICE_ASC' | 'PRICE_DESC'

export const PRO_SERVICE_TYPES: readonly ProServiceType[] = ['AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT']
export const PRO_SERVICE_FACETS: readonly ProServiceFacet[] = [
  'VIDEO_FOOTAGE', 'PHOTOS', 'ORTHOPHOTO', 'MODEL_3D', 'ON_SITE', 'MAIL_IN', 'REMOTE', 'STATIONED',
]
export const PRO_SERVICE_PRICE_MODES: readonly ProServicePriceMode[] = ['PRICE_FROM', 'PROJECT']
export const PRO_SERVICE_SORTS: readonly ProServiceSort[] = ['DEFAULT', 'PRICE_ASC', 'PRICE_DESC']
export const PRO_SERVICE_PAGE_SIZE = 20

export function isProServiceType(value: unknown): value is ProServiceType {
  return typeof value === 'string' && (PRO_SERVICE_TYPES as readonly string[]).includes(value)
}

export function isProServiceFacet(value: unknown): value is ProServiceFacet {
  return typeof value === 'string' && (PRO_SERVICE_FACETS as readonly string[]).includes(value)
}

export function isProServicePriceMode(value: unknown): value is ProServicePriceMode {
  return typeof value === 'string' && (PRO_SERVICE_PRICE_MODES as readonly string[]).includes(value)
}

export interface ProServicePrice {
  mode: ProServicePriceMode
  // Fen; null for 按项目报价.
  amountMinor: number | null
  unit: string | null
  // What the starting price covers, or 方案确认后定价.
  note: string | null
}

export interface ProServiceCategoryView {
  code: ProServiceCategory
  label: string
}

export interface ProServiceCard {
  serviceId: string
  type: ProServiceType
  category: ProServiceCategoryView
  title: string
  summary: string
  tags: string[]
  price: ProServicePrice
  // HTTPS only; null draws the category icon.
  coverUrl: string | null
  sample: boolean
}

export interface ProServiceFacetGroup {
  label: string
  options: Array<{ code: ProServiceFacet; label: string }>
}

export interface ProServiceQuery {
  type: ProServiceType
  cityCode: string
  keyword: string
  facet: ProServiceFacet | null
  priceMode: ProServicePriceMode | null
  sort: ProServiceSort
}

export interface ProServicePage {
  type: ProServiceType
  cityCode: string
  cityName: string | null
  // Whether the city has any service of this type, whatever the keyword and filters.
  cityOpen: boolean
  facetGroup: ProServiceFacetGroup
  hasMore: boolean
  total: number
  items: ProServiceCard[]
}

export interface ProServiceLine {
  label: string
  detail: string
}

export interface ProServiceCity {
  cityCode: string
  cityName: string
}

export interface ProServiceProvider {
  providerId: string
  name: string
  certificationLabel: string | null
  description: string | null
}

export interface ProServiceDetail extends Omit<ProServiceCard, 'tags'> {
  capabilities: string[]
  priceRows: ProServiceLine[]
  scope: string[]
  outputs: ProServiceLine[]
  serviceModes: ProServiceLine[]
  terms: ProServiceLine[]
  steps: string[]
  guarantees: string[]
  provider: ProServiceProvider | null
  cities: ProServiceCity[]
}

export interface ProviderInspectionService {
  serviceId: string
  title: string
  summary: string
  categoryName: string
  price: ProServicePrice
}

export interface ProviderPage {
  providerId: string
  name: string
  certificationLabel: string | null
  description: string | null
  cityCode: string
  inspectionServices: ProviderInspectionService[]
  proServices: ProServiceCard[]
  cities: ProServiceCity[]
}
