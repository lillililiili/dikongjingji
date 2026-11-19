import { isProServiceType, type ProServiceType } from '../domain/proservice'

export const PROSERVICE_CATALOG_PATH = '/pages/proservice/catalog'
export const PROSERVICE_DETAIL_PATH = '/pages/proservice/detail'
export const PROVIDER_PAGE_PATH = '/pages/provider/detail'

const KEYWORD_MAX = 40

export interface ProServiceCatalogTarget {
  type: ProServiceType
  cityCode: string | null
  keyword: string
}

// Only the type, the city and a keyword travel; the list is read from the server again.
export function buildProServiceCatalogUrl(target: {
  type: ProServiceType
  cityCode?: string | null
  keyword?: string
}): string {
  const parameters = [`type=${target.type}`]
  if (target.cityCode) parameters.push(`cityCode=${encodeURIComponent(target.cityCode)}`)
  const keyword = target.keyword?.trim().slice(0, KEYWORD_MAX)
  if (keyword) parameters.push(`keyword=${encodeURIComponent(keyword)}`)
  return `${PROSERVICE_CATALOG_PATH}?${parameters.join('&')}`
}

function read(query: Record<string, unknown> | undefined, key: string): string {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  // H5 hands the query over decoded; an App deep link may still carry %-escapes.
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

// An unknown type opens aerial, the first tab of the catalog.
export function parseProServiceCatalogTarget(query: Record<string, unknown> | undefined): ProServiceCatalogTarget {
  const type = read(query, 'type')
  return {
    type: isProServiceType(type) ? type : 'AERIAL',
    cityCode: read(query, 'cityCode') || null,
    keyword: read(query, 'keyword').slice(0, KEYWORD_MAX),
  }
}

export function buildProServiceDetailUrl(serviceId: string, cityCode: string): string {
  return `${PROSERVICE_DETAIL_PATH}?serviceId=${encodeURIComponent(serviceId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildProviderPageUrl(providerId: string, cityCode: string): string {
  return `${PROVIDER_PAGE_PATH}?providerId=${encodeURIComponent(providerId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function parseProServiceDetailTarget(query: Record<string, unknown> | undefined) {
  return { serviceId: read(query, 'serviceId'), cityCode: read(query, 'cityCode') }
}

export function parseProviderPageTarget(query: Record<string, unknown> | undefined) {
  return { providerId: read(query, 'providerId'), cityCode: read(query, 'cityCode') }
}
