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
  | 'SALES_DESC'
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
  /** 价格下一行的说明，来自服务端 priceNote；没有就不显示 */
  priceNote?: string
  rating?: number
  completedCount?: number
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
  'SALES_DESC',
  'PRICE_ASC',
  'PRICE_DESC',
]

const CATEGORY_RANK: Record<
  Exclude<CatalogCategory, 'ALL'>,
  number
> = {
  TRANSMISSION: 10,
  SUBSTATION: 20,
  DISTRIBUTION: 30,
  CABLE: 40,
  PHOTOVOLTAIC: 50,
}

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
): InspectionCatalogQuery {
  return {
    ...query,
    sort,
    page: 1,
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
    if (query.sort === 'COMPREHENSIVE') {
      return (
        CATEGORY_RANK[left.category] - CATEGORY_RANK[right.category] ||
        completedCountForSorting(right) - completedCountForSorting(left) ||
        compareById(left, right)
      )
    }

    if (query.sort === 'SALES_DESC') {
      return (
        completedCountForSorting(right) - completedCountForSorting(left) ||
        compareById(left, right)
      )
    }

    if (query.sort === 'PRICE_ASC') {
      return compareByPrice(left, right, 1)
    }

    if (query.sort === 'PRICE_DESC') {
      return compareByPrice(left, right, -1)
    }

    return compareById(left, right)
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

export function formatCompletedCount(count: number): string {
  return `已服务 ${Math.max(0, Math.trunc(count)).toLocaleString('zh-CN')} 次`
}

function normalizeKeyword(keyword: string): string {
  return keyword.trim().slice(0, 50)
}

function completedCountForSorting(
  service: InspectionCatalogService,
): number {
  return service.completedCount ?? 0
}

function compareById(
  left: InspectionCatalogService,
  right: InspectionCatalogService,
): number {
  return left.id.localeCompare(right.id)
}

function compareByPrice(
  left: InspectionCatalogService,
  right: InspectionCatalogService,
  direction: 1 | -1,
): number {
  const leftAmount = priceForSorting(left)
  const rightAmount = priceForSorting(right)
  const leftHasPrice = Number.isFinite(leftAmount)
  const rightHasPrice = Number.isFinite(rightAmount)

  if (leftHasPrice !== rightHasPrice) return leftHasPrice ? -1 : 1
  if (leftHasPrice && rightHasPrice && leftAmount !== rightAmount) {
    return (leftAmount - rightAmount) * direction
  }
  return compareById(left, right)
}

function priceForSorting(service: InspectionCatalogService): number {
  if (service.price.mode === 'PROJECT_QUOTE') {
    return Number.POSITIVE_INFINITY
  }
  return service.price.amountFen ?? Number.POSITIVE_INFINITY
}
