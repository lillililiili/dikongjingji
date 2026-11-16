import type { InspectionPortalContent, InspectionPortalCategory, InspectionCategoryCode } from '../../domain/inspection-portal-content'
import cableImageUrl from '../../presentation/catalog/assets/service-cable.png'
import distributionImageUrl from '../../presentation/catalog/assets/service-distribution.png'
import substationImageUrl from '../../presentation/catalog/assets/service-substation.png'
import type {
  CatalogSort,
  InspectionCatalogContent,
  InspectionCatalogService,
} from '../../presentation/catalog/model/inspection-catalog'

export interface InspectionCatalogHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}

interface CatalogPageDto {
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

const CATALOG_CATEGORY_BY_API: Record<string, InspectionCatalogService['category']> = {
  POWER: 'TRANSMISSION',
  SUBSTATION: 'SUBSTATION',
  DISTRIBUTION: 'DISTRIBUTION',
  CABLE: 'CABLE',
  PHOTOVOLTAIC: 'PHOTOVOLTAIC',
}

const CATALOG_IMAGE_BY_API: Record<string, string> = {
  POWER: '/static/images/inspection/service-power-line.webp',
  SUBSTATION: substationImageUrl,
  DISTRIBUTION: distributionImageUrl,
  CABLE: cableImageUrl,
  PHOTOVOLTAIC: '/static/images/inspection/service-photovoltaic.webp',
}

type BusinessPortalCategory = InspectionPortalCategory & {
  code: Exclude<InspectionCategoryCode, 'MORE'>
}

function serviceImage(service: CatalogServiceDto): string {
  return service.coverImage?.url
    ?? CATALOG_IMAGE_BY_API[service.category.code]
    ?? '/static/images/inspection/service-power-line.webp'
}

function toCatalogService(service: CatalogServiceDto): InspectionCatalogService | null {
  const category = CATALOG_CATEGORY_BY_API[service.category.code]
  if (!category) return null

  const amountFen = service.price.startingAmountMinor ?? undefined
  return {
    id: service.serviceId,
    category,
    categoryLabel: service.category.name,
    title: service.name,
    description: service.summary,
    tags: service.features.map((feature) => feature.name),
    includesReport: service.features.some((feature) => feature.code === 'REPORT'),
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
  }
}

function toPortalCategory(service: CatalogServiceDto): BusinessPortalCategory | null {
  if (!PORTAL_CATEGORY_CODES.has(service.category.code as InspectionCategoryCode)) return null
  const code = service.category.code as Exclude<InspectionCategoryCode, 'MORE'>
  return {
    code,
    name: service.category.name,
    subtitle: service.summary,
    imageUrl: PORTAL_CATEGORY_IMAGES[code],
  }
}

function priceLabel(service: CatalogServiceDto): string {
  const amount = service.price.startingAmountMinor
  if (service.price.mode === 'PROJECT_QUOTE' || amount == null) return '按项目报价'
  return `¥${Math.round(amount / 100)}${service.price.unitLabel ? `/${service.price.unitLabel}` : ''}`
}

export function createInspectionCatalogApi(http: InspectionCatalogHttpClient) {
  async function loadPortalPage(cityCode: string): Promise<CatalogPageDto> {
    return http.request<CatalogPageDto>({
      url: `/api/v1/inspection/services?cityCode=${encodeURIComponent(cityCode)}&page=1&size=50`,
      method: 'GET',
    })
  }

  return {
    async loadCatalog(
      cityCode: string,
      sort: CatalogSort = 'COMPREHENSIVE',
    ): Promise<InspectionCatalogContent> {
      const page = await http.request<CatalogPageDto>({
        url: `/api/v1/inspection/services?cityCode=${encodeURIComponent(cityCode)}&categoryCode=POWER&sort=${encodeURIComponent(sort)}&page=1&size=50`,
        method: 'GET',
      })
      return {
        services: page.items
          .map(toCatalogService)
          .filter((service): service is InspectionCatalogService => service !== null),
      }
    },
    async loadPortal(cityCode: string): Promise<InspectionPortalContent> {
      const page = await loadPortalPage(cityCode)
      const categories = new Map<InspectionCategoryCode, InspectionPortalCategory>()
      for (const service of page.items) {
        const category = toPortalCategory(service)
        if (category && !categories.has(category.code)) categories.set(category.code, category)
      }

      return {
        hero: {
          title: '专业巡检',
          accent: '隐患早发现',
          description: '无人机与智能设备，为您提供安全高效的巡检服务',
          highlights: [],
          imageUrl: '/static/images/inspection/inspection-hero-v1.png',
        },
        categories: [...categories.values()],
        guarantees: [],
        hotServices: page.items.slice(0, 4).flatMap((service) => {
          const category = toPortalCategory(service)
          return category ? [{
            id: service.serviceId,
            categoryCode: category.code,
            title: service.name,
            tag: service.category.name,
            imageUrl: serviceImage(service),
            priceLabel: priceLabel(service),
          }] : []
        }),
        processSteps: [],
      }
    },
  }
}
