import type {
  InspectionCategoryCode,
  InspectionPortalCategory,
  InspectionPortalContent,
  InspectionPortalHero,
  InspectionPortalHotService,
} from '../../domain/inspection-portal-content'
import { inspectionServiceImage } from '../inspection-service-images'
import {
  priceModesForFilter,
  type CatalogFilterOptions,
  type CatalogFilterValue,
  type CatalogPosition,
  type CatalogPriceMode,
  type InspectionCatalogContent,
  type InspectionCatalogQuery,
  type InspectionCatalogService,
} from '../../presentation/catalog/model/inspection-catalog'

export interface InspectionCatalogHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}

export interface CatalogSearchInput {
  cityCode: string
  query: InspectionCatalogQuery
  filters: CatalogFilterValue
  /** Sent only with the distance sort; the server needs it for nothing else. */
  position: CatalogPosition | null
  page: number
  size: number
}

interface CatalogPageDto {
  hasMore: boolean
  items: CatalogServiceDto[]
}

interface CatalogServiceDto {
  serviceId: string
  category: { code: string; name: string }
  name: string
  summary: string
  coverImage: { url: string } | null
  features: Array<{ code: string; name: string }>
  price: {
    mode: string
    startingAmountMinor?: number | null
    unitLabel?: string | null
  }
  priceNote?: string | null
  completedCount?: number | null
  distanceMeters?: number | null
  /** Catalog contract 1.4.0: the provider organisation; null when it is no longer an active provider. */
  providerName?: string | null
}

interface CodeNameDto {
  code: string
  name: string
}

interface CategoryDto extends CodeNameDto {
  subtitle?: string | null
}

interface PortalInfoItemDto {
  code: string
  title: string
  subtitle?: string | null
}

interface PortalDto {
  hero: {
    title: string
    highlightedText?: string | null
    subtitle: string
    sellingPoints: string[]
    image?: { url: string } | null
  }
  categories: CategoryDto[]
  guarantees: PortalInfoItemDto[]
  hotServices: CatalogServiceDto[]
  processSteps: PortalInfoItemDto[]
}

interface FilterOptionsDto {
  categories: CategoryDto[]
  serviceMethods: CodeNameDto[]
  priceModes: CodeNameDto[]
  sorts: string[]
  supportsReportFilter?: boolean
}

const CITY_NOT_ENABLED_CODE = 'INS_CATALOG_CITY_NOT_ENABLED'
const DEFAULT_HERO_IMAGE = '/static/images/inspection/inspection-hero-v1.png'

/** Shown where the city has no inspection service yet, so the entry page keeps its headline. */
const CLOSED_CITY_HERO: InspectionPortalHero = {
  title: '专业巡检',
  accent: '隐患早发现',
  description: '无人机与智能设备，为您提供安全高效的巡检服务',
  highlights: [],
  imageUrl: DEFAULT_HERO_IMAGE,
}

const PORTAL_CATEGORY_CODES = new Set<InspectionCategoryCode>([
  'POWER', 'PHOTOVOLTAIC', 'OIL_GAS', 'THERMAL',
  'PARK', 'EQUIPMENT', 'PERIODIC',
])

const PORTAL_CATEGORY_IMAGES: Record<Exclude<InspectionCategoryCode, 'MORE'>, string> = {
  POWER: '/static/images/inspection/category-power.webp',
  PHOTOVOLTAIC: '/static/images/inspection/category-photovoltaic.webp',
  OIL_GAS: '/static/images/inspection/category-oil-gas.webp',
  THERMAL: '/static/images/inspection/category-thermal.webp',
  PARK: '/static/images/inspection/category-park.webp',
  EQUIPMENT: '/static/images/inspection/category-equipment.webp',
  PERIODIC: '/static/images/inspection/category-periodic.webp',
}

type BusinessPortalCategory = InspectionPortalCategory & {
  code: Exclude<InspectionCategoryCode, 'MORE'>
}

function serviceImage(service: CatalogServiceDto): string {
  return inspectionServiceImage({ coverUrl: service.coverImage?.url, categoryCode: service.category.code })
}

function toCatalogService(service: CatalogServiceDto): InspectionCatalogService {
  const amountFen = service.price.startingAmountMinor ?? undefined
  return {
    id: service.serviceId,
    category: service.category.code,
    categoryLabel: service.category.name,
    title: service.name,
    description: service.summary,
    tags: service.features.map((feature) => feature.name),
    imageUrl: serviceImage(service),
    ...(typeof service.completedCount === 'number'
      ? { completedCount: service.completedCount }
      : {}),
    price: {
      mode: service.price.mode === 'PROJECT_QUOTE'
        ? 'PROJECT_QUOTE'
        : 'STARTING_PRICE',
      ...(amountFen === undefined ? {} : { amountFen }),
      ...(service.price.unitLabel ? { unitLabel: service.price.unitLabel } : {}),
    },
    ...(service.priceNote ? { priceNote: service.priceNote } : {}),
    ...(typeof service.distanceMeters === 'number'
      ? { distanceMeters: service.distanceMeters }
      : {}),
  }
}

function toPortalCategory(category: CategoryDto): BusinessPortalCategory | null {
  if (!PORTAL_CATEGORY_CODES.has(category.code as InspectionCategoryCode)) return null
  const code = category.code as Exclude<InspectionCategoryCode, 'MORE'>
  return {
    code,
    name: category.name,
    subtitle: category.subtitle ?? '',
    imageUrl: PORTAL_CATEGORY_IMAGES[code],
  }
}

function priceLabel(service: CatalogServiceDto): string {
  const amount = service.price.startingAmountMinor
  if (service.price.mode === 'PROJECT_QUOTE' || amount == null) return '按项目报价'
  return `¥${Math.round(amount / 100)}${service.price.unitLabel ? `/${service.price.unitLabel}` : ''}`
}

function toHotService(service: CatalogServiceDto): InspectionPortalHotService {
  return {
    id: service.serviceId,
    categoryCode: service.category.code,
    title: service.name,
    tag: service.category.name,
    ...(service.providerName ? { providerName: service.providerName } : {}),
    imageUrl: serviceImage(service),
    priceLabel: priceLabel(service),
  }
}

function isCityNotEnabled(error: unknown): boolean {
  return error instanceof Error
    && (error as { code?: unknown }).code === CITY_NOT_ENABLED_CODE
}

function searchUrl(input: CatalogSearchInput): string {
  const { query, filters, position } = input
  const parameters: Array<[string, string]> = [['cityCode', input.cityCode]]
  if (query.keyword) parameters.push(['keyword', query.keyword])
  if (query.category !== 'ALL') parameters.push(['categoryCode', query.category])
  if (filters.method !== 'ALL') parameters.push(['serviceMethods', filters.method])
  for (const mode of priceModesForFilter(filters.priceMode)) {
    parameters.push(['priceModes', mode])
  }
  if (filters.includesReport) parameters.push(['includesReport', 'true'])
  parameters.push(['sort', query.sort])
  if (query.sort === 'DISTANCE_ASC' && position) {
    parameters.push(['latitude', String(position.latitude)])
    parameters.push(['longitude', String(position.longitude)])
  }
  parameters.push(['page', String(input.page)], ['size', String(input.size)])
  return `/api/v1/inspection/services?${parameters
    .map(([name, value]) => `${name}=${encodeURIComponent(value)}`)
    .join('&')}`
}

export function createInspectionCatalogApi(http: InspectionCatalogHttpClient) {
  return {
    /** One page of the city's services matching the customer's conditions, in the server's order. */
    async searchServices(input: CatalogSearchInput): Promise<InspectionCatalogContent> {
      try {
        const page = await http.request<CatalogPageDto>({
          url: searchUrl(input),
          method: 'GET',
        })
        return {
          services: page.items.map(toCatalogService),
          hasMore: page.hasMore,
          cityClosed: false,
        }
      } catch (error) {
        // "This city has no inspection service" is an answer, not a failure: the page shows
        // its own state for it instead of the network error.
        if (isCityNotEnabled(error)) {
          return { services: [], hasMore: false, cityClosed: true }
        }
        throw error
      }
    },

    async loadFilterOptions(cityCode: string): Promise<CatalogFilterOptions> {
      const options = await http.request<FilterOptionsDto>({
        url: `/api/v1/inspection/filters?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
      })
      return {
        categories: options.categories.map(({ code, name }) => ({ code, label: name })),
        serviceMethods: options.serviceMethods.map(({ code, name }) => ({ code, label: name })),
        priceModes: options.priceModes.map(({ code }) => code as CatalogPriceMode),
        supportsDistanceSort: options.sorts.includes('DISTANCE_ASC'),
        supportsReportFilter: options.supportsReportFilter === true,
      }
    },

    async loadPortal(cityCode: string): Promise<InspectionPortalContent> {
      let portal: PortalDto
      try {
        portal = await http.request<PortalDto>({
          url: `/api/v1/inspection/portal?cityCode=${encodeURIComponent(cityCode)}`,
          method: 'GET',
        })
      } catch (error) {
        if (!isCityNotEnabled(error)) throw error
        // A city that is not open is not a failure: the page keeps its headline and has
        // nothing to list, as it did before the portal had its own endpoint.
        return {
          hero: CLOSED_CITY_HERO,
          categories: [],
          guarantees: [],
          hotServices: [],
          processSteps: [],
        }
      }

      return {
        hero: {
          title: portal.hero.title,
          accent: portal.hero.highlightedText ?? '',
          description: portal.hero.subtitle,
          highlights: portal.hero.sellingPoints,
          imageUrl: portal.hero.image?.url ?? DEFAULT_HERO_IMAGE,
        },
        categories: portal.categories.flatMap((category) => {
          const mapped = toPortalCategory(category)
          return mapped ? [mapped] : []
        }),
        guarantees: portal.guarantees.map((item) => ({
          code: item.code,
          title: item.title,
          description: item.subtitle ?? '',
          icon: '',
          tone: 'green',
        })),
        hotServices: portal.hotServices.map(toHotService),
        processSteps: portal.processSteps.map((step) => ({
          code: step.code,
          title: step.title,
          icon: '',
        })),
      }
    },
  }
}
