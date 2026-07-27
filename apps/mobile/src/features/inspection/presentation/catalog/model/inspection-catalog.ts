export const CATALOG_PAGE_SIZE = 5

export const CATALOG_CATEGORIES = [
  { code: 'ALL', label: '全部' },
  { code: 'TRANSMISSION', label: '输电线路' },
  { code: 'SUBSTATION', label: '变电站' },
  { code: 'DISTRIBUTION', label: '配电网' },
  { code: 'CABLE', label: '电缆' },
  { code: 'PHOTOVOLTAIC', label: '光伏' },
] as const

export type CatalogCategory =
  (typeof CATALOG_CATEGORIES)[number]['code']

export type CatalogSort =
  | 'COMPREHENSIVE'
  | 'DISTANCE_ASC'
  | 'PRICE_ASC'
  | 'PRICE_DESC'

export interface InspectionCatalogQuery {
  keyword: string
  category: CatalogCategory
  sort: CatalogSort
  page: number
  size: number
}

export interface InspectionCatalogPrice {
  mode:
    | 'STARTING_PRICE'
    | 'PER_DISTANCE'
    | 'PER_VISIT'
    | 'PROJECT_QUOTE'
  amountFen?: number
  unitLabel?: string
}

export interface InspectionCatalogService {
  id: string
  category: Exclude<CatalogCategory, 'ALL'>
  categoryLabel: string
  badgeLabel?: string
  title: string
  description: string
  tags: string[]
  serviceMethods?: Array<'UAV' | 'ROBOT'>
  includesReport?: boolean
  imageUrl: string
  imagePosition?: string
  price: InspectionCatalogPrice
  rating?: number
  servedCountLabel?: string
  distanceKm?: number
}

export interface InspectionCatalogContent {
  services: InspectionCatalogService[]
}

export interface OpenServiceDetailIntent {
  type: 'OPEN_SERVICE_DETAIL'
  serviceId: string
}

export const EMPTY_CATALOG_CONTENT: InspectionCatalogContent = {
  services: [],
}

export const DEFAULT_CATALOG_QUERY: InspectionCatalogQuery = {
  keyword: '',
  category: 'ALL',
  sort: 'COMPREHENSIVE',
  page: 1,
  size: CATALOG_PAGE_SIZE,
}

const CATALOG_SORTS: readonly CatalogSort[] = [
  'COMPREHENSIVE',
  'DISTANCE_ASC',
  'PRICE_ASC',
  'PRICE_DESC',
]

const CATALOG_CATEGORY_CODES = new Set<string>(
  CATALOG_CATEGORIES.map((category) => category.code),
)

export function normalizeCatalogEntry(
  categoryCode: string | undefined,
): { accepted: boolean; category: CatalogCategory } {
  if (!categoryCode || categoryCode === 'POWER') {
    return {
      accepted: true,
      category: 'ALL',
    }
  }

  return {
    accepted: false,
    category: 'ALL',
  }
}

export function normalizeCatalogQuery(input: {
  keyword?: string
  category?: string
  sort?: string
}): InspectionCatalogQuery {
  return {
    ...DEFAULT_CATALOG_QUERY,
    keyword: normalizeKeyword(input.keyword ?? ''),
    category: CATALOG_CATEGORY_CODES.has(input.category ?? '')
      ? (input.category as CatalogCategory)
      : 'ALL',
    sort: CATALOG_SORTS.includes(input.sort as CatalogSort)
      ? (input.sort as CatalogSort)
      : 'COMPREHENSIVE',
  }
}

export function applyCatalogSearch(
  query: InspectionCatalogQuery,
  keyword: string,
): InspectionCatalogQuery {
  return {
    ...query,
    keyword: normalizeKeyword(keyword),
    page: 1,
  }
}

export function applyCatalogCategory(
  query: InspectionCatalogQuery,
  category: CatalogCategory,
): InspectionCatalogQuery {
  return {
    ...query,
    category,
    page: 1,
  }
}

export function applyCatalogSort(
  query: InspectionCatalogQuery,
  sort: CatalogSort,
  hasValidLocation: boolean,
): {
  query: InspectionCatalogQuery
  fallbackReason?: string
} {
  if (sort === 'DISTANCE_ASC' && !hasValidLocation) {
    return {
      query: {
        ...query,
        sort: 'COMPREHENSIVE',
        page: 1,
      },
      fallbackReason: '需要位置权限才能按距离排序',
    }
  }

  return {
    query: {
      ...query,
      sort,
      page: 1,
    },
  }
}

export function selectCatalogServices(
  services: readonly InspectionCatalogService[],
  query: InspectionCatalogQuery,
): InspectionCatalogService[] {
  const normalizedKeyword = query.keyword.toLocaleLowerCase()
  const filtered = services.filter((service) => {
    const categoryMatches =
      query.category === 'ALL' || service.category === query.category
    const searchableText = [
      service.title,
      service.description,
      service.categoryLabel,
      ...service.tags,
    ]
      .join(' ')
      .toLocaleLowerCase()

    return (
      categoryMatches &&
      (!normalizedKeyword || searchableText.includes(normalizedKeyword))
    )
  })

  return [...filtered].sort((left, right) => {
    if (query.sort === 'DISTANCE_ASC') {
      return (
        (left.distanceKm ?? Number.POSITIVE_INFINITY) -
        (right.distanceKm ?? Number.POSITIVE_INFINITY)
      )
    }

    if (query.sort === 'PRICE_ASC') {
      return priceForSorting(left) - priceForSorting(right)
    }

    if (query.sort === 'PRICE_DESC') {
      return priceForSorting(right) - priceForSorting(left)
    }

    return 0
  })
}

export function mergeCatalogPage(
  current: readonly InspectionCatalogService[],
  incoming: readonly InspectionCatalogService[],
  hasMore: boolean,
): {
  services: InspectionCatalogService[]
  hasMore: boolean
} {
  const servicesById = new Map(
    [...current, ...incoming].map((service) => [service.id, service]),
  )

  return {
    services: [...servicesById.values()],
    hasMore,
  }
}

export function resolveCatalogContent(
  environment: string,
  developmentContent: InspectionCatalogContent,
): InspectionCatalogContent {
  return environment === 'development'
    ? developmentContent
    : EMPTY_CATALOG_CONTENT
}

export function createOpenServiceDetailIntent(
  serviceId: string,
): OpenServiceDetailIntent {
  const normalizedServiceId = serviceId.trim().slice(0, 64)

  if (!normalizedServiceId) {
    throw new Error('serviceId is required')
  }

  return {
    type: 'OPEN_SERVICE_DETAIL',
    serviceId: normalizedServiceId,
  }
}

export function formatCatalogPrice(
  price: InspectionCatalogPrice,
): { amountLabel: string; unitLabel: string } {
  if (
    price.mode === 'PROJECT_QUOTE' ||
    typeof price.amountFen !== 'number'
  ) {
    return {
      amountLabel: '按项目报价',
      unitLabel: '',
    }
  }

  return {
    amountLabel: `¥${Math.round(price.amountFen / 100)}`,
    unitLabel: price.unitLabel ?? '',
  }
}

function normalizeKeyword(keyword: string): string {
  return keyword.trim().slice(0, 50)
}

function priceForSorting(service: InspectionCatalogService): number {
  return service.price.mode === 'PROJECT_QUOTE'
    ? Number.POSITIVE_INFINITY
    : (service.price.amountFen ?? Number.POSITIVE_INFINITY)
}
