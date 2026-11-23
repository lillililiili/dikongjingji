import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import { toCard as toActivityCard, type ActivityCardDto } from '../../activity/data/activity-api'
import { toContentArticleCard } from '../../content/data/content-api'
import { toVenueCard, type VenueCardDto } from '../../experience/data/experience-api'
import { toCatalogService } from '../../inspection/data/catalog/inspection-catalog-api'
import { toMallProductCard, type ProductCardDto } from '../../mall/data/mall-api'
import { toProServiceCard } from '../../proservice/data/proservice-api'
import {
  SEARCH_ALL_SIZE,
  SEARCH_SCOPE_SIZE,
  type SearchGroup,
  type SearchQuery,
  type SearchResult,
  type SearchScope,
  type SearchServiceHit,
} from '../domain/search'

type InspectionServiceDto = Parameters<typeof toCatalogService>[0]
type ProServiceCardDto = Parameters<typeof toProServiceCard>[0]
type ContentCardDto = Parameters<typeof toContentArticleCard>[0]

interface GroupDto<T> {
  total: number
  items: T[]
}

interface ServiceHitDto {
  kind: 'INSPECTION' | 'PRO_SERVICE'
  inspection: InspectionServiceDto | null
  proService: ProServiceCardDto | null
}

// Mirrors contracts/openapi-search.yaml.
interface SearchResultDto {
  cityCode: string
  keyword: string
  scope: SearchScope
  correction: { keyword: string } | null
  products: GroupDto<ProductCardDto> | null
  services: GroupDto<ServiceHitDto> | null
  activities: GroupDto<ActivityCardDto> | null
  contents: GroupDto<ContentCardDto> | null
  venues: GroupDto<VenueCardDto> | null
}

export interface SearchApi {
  search(query: SearchQuery): Promise<SearchResult>
}

function group<D, T>(dto: GroupDto<D> | null | undefined, map: (item: D) => T | null): SearchGroup<T> | null {
  if (!dto) return null
  return {
    total: dto.total,
    items: dto.items.map(map).filter((item): item is T => item !== null),
  }
}

function serviceHit(dto: ServiceHitDto): SearchServiceHit | null {
  if (dto.kind === 'INSPECTION' && dto.inspection) return { kind: 'INSPECTION', service: toCatalogService(dto.inspection) }
  if (dto.kind === 'PRO_SERVICE' && dto.proService) return { kind: 'PRO_SERVICE', service: toProServiceCard(dto.proService) }
  return null
}

export function buildSearchQueryString(query: SearchQuery): string {
  const size = query.size ?? (query.scope === 'ALL' ? SEARCH_ALL_SIZE : SEARCH_SCOPE_SIZE)
  return [
    `cityCode=${encodeURIComponent(query.cityCode)}`,
    `keyword=${encodeURIComponent(query.keyword.trim())}`,
    `scope=${query.scope}`,
    `size=${size}`,
  ].join('&')
}

export function createSearchApi(http: PublicApiHttpClient): SearchApi {
  return {
    async search(query) {
      const dto = await http.request<SearchResultDto>({
        method: 'GET',
        url: `/api/v1/search?${buildSearchQueryString(query)}`,
      })
      return {
        cityCode: dto.cityCode,
        keyword: dto.keyword,
        scope: dto.scope,
        correction: dto.correction?.keyword ?? null,
        products: group(dto.products, toMallProductCard),
        services: group(dto.services, serviceHit),
        activities: group(dto.activities, toActivityCard),
        contents: group(dto.contents, toContentArticleCard),
        venues: group(dto.venues, toVenueCard),
      }
    },
  }
}
