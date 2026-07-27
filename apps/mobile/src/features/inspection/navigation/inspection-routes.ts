export const INSPECTION_PORTAL_PATH = '/pages/service/inspection/index'
export const INSPECTION_CATALOG_PATH = '/pages/service/inspection/catalog'

export const INSPECTION_ROUTES = [
  {
    name: 'InspectionPortal',
    path: INSPECTION_PORTAL_PATH,
  },
  {
    name: 'InspectionCatalog',
    path: INSPECTION_CATALOG_PATH,
  },
] as const

export type InspectionCatalogSort =
  | 'COMPREHENSIVE'
  | 'DISTANCE_ASC'
  | 'PRICE_ASC'
  | 'PRICE_DESC'

export type InspectionRouteSource =
  | 'SEARCH'
  | 'CATEGORY'
  | 'HOT'
  | 'VIEW_ALL'
  | 'DEEP_LINK'

export interface InspectionCatalogRouteQuery {
  cityCode?: string
  categoryCode?: string
  keyword?: string
  sort?: InspectionCatalogSort
  openFilter?: boolean
  source?: InspectionRouteSource
}

export function buildInspectionCatalogUrl(
  query: InspectionCatalogRouteQuery = {},
): string {
  const parameters: string[] = []

  appendParameter(parameters, 'cityCode', query.cityCode)
  appendParameter(parameters, 'categoryCode', query.categoryCode)
  appendParameter(parameters, 'keyword', query.keyword?.trim())
  appendParameter(parameters, 'sort', query.sort)
  appendParameter(parameters, 'openFilter', query.openFilter ? 'true' : undefined)
  appendParameter(parameters, 'source', query.source)

  return parameters.length === 0
    ? INSPECTION_CATALOG_PATH
    : `${INSPECTION_CATALOG_PATH}?${parameters.join('&')}`
}

function appendParameter(
  parameters: string[],
  key: string,
  value: string | undefined,
): void {
  if (!value) {
    return
  }

  parameters.push(`${key}=${encodeURIComponent(value)}`)
}

