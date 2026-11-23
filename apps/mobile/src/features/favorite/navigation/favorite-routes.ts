import { FAVORITE_GROUPS, type FavoriteGroup } from '../domain/favorite'

export const FAVORITES_PATH = '/pages/mine/favorites'
export const MALL_COMPARE_PATH = '/pages/mall/compare'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function buildFavoritesUrl(group: FavoriteGroup = 'GOODS'): string {
  return group === 'GOODS' ? FAVORITES_PATH : `${FAVORITES_PATH}?group=${group}`
}

export function parseFavoriteGroup(value: string | undefined): FavoriteGroup {
  return FAVORITE_GROUPS.find((group) => group === value) ?? 'GOODS'
}

// Only the products' stable ids and the city; the page reads each product again.
export function buildMallCompareUrl(productIds: readonly string[], cityCode: string): string {
  return `${MALL_COMPARE_PATH}?ids=${productIds.map(encodeURIComponent).join(',')}&cityCode=${encodeURIComponent(cityCode)}`
}

export interface MallCompareQuery {
  productIds: string[]
  cityCode: string
}

// At least two distinct product ids and a city, or null (the page then explains there is nothing
// to compare).
export function parseMallCompareQuery(query: Record<string, string | undefined>, maxItems: number): MallCompareQuery | null {
  const cityCode = query.cityCode?.trim() ?? ''
  const ids = [...new Set(decodeURIComponent(query.ids ?? '').split(',').map((id) => id.trim()).filter((id) => UUID.test(id)))]
  if (!/^[A-Z0-9]{4,16}$/.test(cityCode) || ids.length < 2) return null
  return { productIds: ids.slice(0, maxItems), cityCode }
}
