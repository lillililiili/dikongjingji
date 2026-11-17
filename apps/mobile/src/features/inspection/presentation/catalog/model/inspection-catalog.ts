export const CATALOG_PAGE_SIZE = 5

/** "全部" is not a server category; it means no category condition. */
export const ALL_CATALOG_CATEGORY = 'ALL'

export interface CatalogCategoryOption {
  code: string
  label: string
}

export const ALL_CATALOG_CATEGORY_OPTION: CatalogCategoryOption = {
  code: ALL_CATALOG_CATEGORY,
  label: '全部',
}

export type CatalogSort =
  | 'COMPREHENSIVE'
  | 'DISTANCE_ASC'
  | 'PRICE_ASC'
  | 'PRICE_DESC'

export type CatalogPriceMode =
  | 'STARTING_PRICE'
  | 'PER_DISTANCE'
  | 'PER_VISIT'
  | 'PROJECT_QUOTE'

/** What the customer typed, picked and sorted by. The server does the matching and the paging. */
export interface InspectionCatalogQuery {
  keyword: string
  /** A category code from the server, or ALL_CATALOG_CATEGORY. */
  category: string
  sort: CatalogSort
}

/** The filter sheet: 不限 is ALL; 明确价格 (FIXED) is every price mode except 按项目报价. */
export interface CatalogFilterValue {
  method: string
  priceMode: 'ALL' | 'FIXED' | 'PROJECT_QUOTE'
  includesReport: boolean
}

export interface CatalogPosition {
  latitude: number
  longitude: number
}

/** The categories and filters a city's catalog offers, from GET /api/v1/inspection/filters. */
export interface CatalogFilterOptions {
  categories: CatalogCategoryOption[]
  serviceMethods: CatalogCategoryOption[]
  priceModes: CatalogPriceMode[]
  supportsDistanceSort: boolean
  supportsReportFilter: boolean
}

export interface InspectionCatalogPrice {
  mode: CatalogPriceMode
  amountFen?: number
  unitLabel?: string
}

export interface InspectionCatalogService {
  id: string
  /** The service's own category code; a child code such as SUBSTATION for a 电力 service. */
  category: string
  categoryLabel: string
  badgeLabel?: string
  title: string
  description: string
  tags: string[]
  imageUrl: string
  imagePosition?: string
  price: InspectionCatalogPrice
  /** 价格下一行的说明，来自服务端 priceNote；没有就不显示 */
  priceNote?: string
  rating?: number
  completedCount?: number
  /** Metres from the customer to the service's centre point; only with a position and a centre. */
  distanceMeters?: number
}

/** The cards loaded so far for the current conditions. */
export interface InspectionCatalogContent {
  services: InspectionCatalogService[]
  hasMore: boolean
  /** True when the server says the city has no inspection service at all. */
  cityClosed: boolean
}

export const EMPTY_CATALOG_CONTENT: InspectionCatalogContent = {
  services: [],
  hasMore: false,
  cityClosed: false,
}

export interface OpenServiceDetailIntent {
  type: 'OPEN_SERVICE_DETAIL'
  serviceId: string
}

export const DEFAULT_CATALOG_QUERY: InspectionCatalogQuery = {
  keyword: '',
  category: ALL_CATALOG_CATEGORY,
  sort: 'COMPREHENSIVE',
}

export const DEFAULT_CATALOG_FILTERS: CatalogFilterValue = {
  method: 'ALL',
  priceMode: 'ALL',
  includesReport: false,
}

export const EMPTY_CATALOG_FILTER_OPTIONS: CatalogFilterOptions = {
  categories: [],
  serviceMethods: [],
  priceModes: [],
  supportsDistanceSort: false,
  supportsReportFilter: false,
}

/** Sorts a link may ask for. Distance is left out: it needs a position the link cannot carry. */
const ROUTE_SORTS: readonly CatalogSort[] = [
  'COMPREHENSIVE',
  'PRICE_ASC',
  'PRICE_DESC',
]

const LISTED_PRICE_MODES: readonly CatalogPriceMode[] = [
  'STARTING_PRICE',
  'PER_DISTANCE',
  'PER_VISIT',
]

const CATEGORY_CODE_PATTERN = /^[A-Z0-9_]{2,64}$/

/** A category code from a link. Anything that is not a well-formed code falls back to 全部. */
export function normalizeCatalogCategory(categoryCode: string | undefined): string {
  const code = categoryCode?.trim() ?? ''
  return CATEGORY_CODE_PATTERN.test(code) ? code : ALL_CATALOG_CATEGORY
}

export function normalizeCatalogQuery(input: {
  keyword?: string
  category?: string
  sort?: string
}): InspectionCatalogQuery {
  return {
    keyword: normalizeKeyword(input.keyword ?? ''),
    category: normalizeCatalogCategory(input.category),
    sort: ROUTE_SORTS.includes(input.sort as CatalogSort)
      ? (input.sort as CatalogSort)
      : 'COMPREHENSIVE',
  }
}

export function applyCatalogSearch(
  query: InspectionCatalogQuery,
  keyword: string,
): InspectionCatalogQuery {
  return { ...query, keyword: normalizeKeyword(keyword) }
}

export function nextPriceSort(sort: CatalogSort): CatalogSort {
  return sort === 'PRICE_ASC' ? 'PRICE_DESC' : 'PRICE_ASC'
}

/** The chips above the list: 全部 first, then what the server offers in its own order. */
export function catalogCategoryChips(
  options: readonly CatalogCategoryOption[],
  selected: string,
): CatalogCategoryOption[] {
  const chips = [ALL_CATALOG_CATEGORY_OPTION, ...options]
  // A link can open a category the server did not list (a child such as 变电站); show it as
  // selected instead of leaving every chip grey.
  return chips.some((chip) => chip.code === selected)
    ? chips
    : [...chips, { code: selected, label: selected }]
}

/** The price modes the server is asked for; empty means no price condition. */
export function priceModesForFilter(
  priceMode: CatalogFilterValue['priceMode'],
): CatalogPriceMode[] {
  if (priceMode === 'PROJECT_QUOTE') return ['PROJECT_QUOTE']
  return priceMode === 'FIXED' ? [...LISTED_PRICE_MODES] : []
}

/** Adds the next page to what is shown; a card that moved between pages is kept once. */
export function appendCatalogPage(
  current: InspectionCatalogContent,
  next: InspectionCatalogContent,
): InspectionCatalogContent {
  const known = new Set(current.services.map((service) => service.id))
  return {
    services: [
      ...current.services,
      ...next.services.filter((service) => !known.has(service.id)),
    ],
    hasMore: next.hasMore,
    cityClosed: false,
  }
}

export function hasCatalogFilters(filters: CatalogFilterValue): boolean {
  return filters.method !== 'ALL'
    || filters.priceMode !== 'ALL'
    || filters.includesReport
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

/** "距你 3.8 公里"; under a kilometre it is given in metres, rounded to ten. */
export function formatCatalogDistance(meters: number): string {
  const distance = Math.max(0, meters)
  if (distance < 1000) {
    return `距你 ${Math.max(10, Math.round(distance / 10) * 10)} 米`
  }
  return `距你 ${(distance / 1000).toFixed(1)} 公里`
}

function normalizeKeyword(keyword: string): string {
  return keyword.trim().slice(0, 50)
}
