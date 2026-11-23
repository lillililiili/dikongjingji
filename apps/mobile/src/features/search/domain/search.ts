import type { ActivityCard } from '../../activity/domain/activity'
import type { ContentArticleCard } from '../../content/domain/content'
import type { VenueCard } from '../../experience/domain/experience'
import type { InspectionCatalogService } from '../../inspection/presentation/catalog/model/inspection-catalog'
import type { MallProductCard } from '../../mall/domain/mall'
import type { ProServiceCard } from '../../proservice/domain/proservice'

// The global search (contracts/openapi-search.yaml): one keyword over the public lists of the
// mall, the inspection and pro-service catalogs, the activities, the discover content and the
// venues of the current city. Every item is the card of its own list.

// The scope tabs of boards SearchPre and MallSearch, in their order.
export type SearchScope = 'ALL' | 'PRODUCT' | 'SERVICE' | 'ACTIVITY' | 'CONTENT' | 'VENUE'

export const SEARCH_SCOPES: readonly SearchScope[] = ['ALL', 'PRODUCT', 'SERVICE', 'ACTIVITY', 'CONTENT', 'VENUE']

export const SEARCH_SCOPE_LABELS: Record<SearchScope, string> = {
  ALL: '综合',
  PRODUCT: '商品',
  SERVICE: '服务',
  ACTIVITY: '活动',
  CONTENT: '内容',
  VENUE: '场馆',
}

// The server takes 1–40 characters after trimming.
export const SEARCH_KEYWORD_MAX = 40
// 综合 shows three of each group; one scope shows up to twenty.
export const SEARCH_ALL_SIZE = 3
export const SEARCH_SCOPE_SIZE = 20

export type SearchServiceHit =
  | { kind: 'INSPECTION'; service: InspectionCatalogService }
  | { kind: 'PRO_SERVICE'; service: ProServiceCard }

export interface SearchGroup<T> {
  total: number
  items: T[]
}

export interface SearchResult {
  cityCode: string
  keyword: string
  scope: SearchScope
  // 你是不是要搜：the server's suggestion for a known typo, or null.
  correction: string | null
  // A group outside the scope is null.
  products: SearchGroup<MallProductCard> | null
  services: SearchGroup<SearchServiceHit> | null
  activities: SearchGroup<ActivityCard> | null
  contents: SearchGroup<ContentArticleCard> | null
  venues: SearchGroup<VenueCard> | null
}

export interface SearchQuery {
  cityCode: string
  keyword: string
  scope: SearchScope
  size?: number
}

export function isSearchScope(value: unknown): value is SearchScope {
  return typeof value === 'string' && (SEARCH_SCOPES as readonly string[]).includes(value)
}
