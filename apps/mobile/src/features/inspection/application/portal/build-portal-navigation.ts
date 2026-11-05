import type { InspectionCategoryCode } from '../../domain/inspection-portal-content'
import {
  buildInspectionCatalogUrl,
  buildInspectionDetailUrl,
} from '../../navigation/inspection-routes'

const MAX_SEARCH_LENGTH = 50

export function buildPortalSearchUrl(
  cityCode: string | undefined,
  rawKeyword: string,
): string {
  const keyword = Array.from(rawKeyword.trim())
    .slice(0, MAX_SEARCH_LENGTH)
    .join('')

  return buildInspectionCatalogUrl({
    cityCode,
    keyword: keyword || undefined,
    source: 'SEARCH',
  })
}

export function buildPortalCategoryUrl(
  cityCode: string | undefined,
  categoryCode: InspectionCategoryCode,
): string {
  if (categoryCode === 'MORE') {
    return buildInspectionCatalogUrl({
      cityCode,
      openFilter: true,
      source: 'CATEGORY',
    })
  }

  return buildInspectionCatalogUrl({
    cityCode,
    categoryCode,
    source: 'CATEGORY',
  })
}

export function buildPortalViewAllUrl(
  cityCode: string | undefined,
): string {
  return buildInspectionCatalogUrl({
    cityCode,
    source: 'VIEW_ALL',
  })
}

export function buildPortalHotServiceUrl(
  cityCode: string | undefined,
  serviceId: string,
): string {
  return buildInspectionDetailUrl(serviceId, cityCode ?? '')
}
