import type { HomeChannelCode } from '@/app-shell/navigation/home-channels'

import { SEARCH_KEYWORD_MAX, isSearchScope, type SearchScope } from '../domain/search'

export const SEARCH_PRE_PATH = '/pages/search/index'
export const SEARCH_RESULTS_PATH = '/pages/search/results'

// Only the scope and the typed words travel; the city is the shared current city.
export function buildSearchPreUrl(target: { scope?: SearchScope; keyword?: string } = {}): string {
  const parameters: string[] = []
  if (target.scope && target.scope !== 'ALL') parameters.push(`scope=${target.scope}`)
  const keyword = target.keyword?.trim()
  if (keyword) parameters.push(`keyword=${encodeURIComponent(keyword)}`)
  return parameters.length ? `${SEARCH_PRE_PATH}?${parameters.join('&')}` : SEARCH_PRE_PATH
}

export function buildSearchResultsUrl(keyword: string, scope: SearchScope = 'ALL'): string {
  return `${SEARCH_RESULTS_PATH}?keyword=${encodeURIComponent(keyword.trim())}&scope=${scope}`
}

function read(query: Record<string, unknown> | undefined, key: string): string {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

export interface SearchTarget {
  scope: SearchScope
  keyword: string
}

export function parseSearchTarget(query: Record<string, unknown> | undefined): SearchTarget {
  const scope = read(query, 'scope')
  return {
    scope: isSearchScope(scope) ? scope : 'ALL',
    keyword: read(query, 'keyword').slice(0, SEARCH_KEYWORD_MAX),
  }
}

/**
 * Where a home channel's search box lands (Batch3Spec §4 从频道进入): 赛事研学 searches 活动,
 * 商城 (the product categories) searches 商品, the others 综合. 专业服务 keeps its own box, which
 * goes straight to the inspection catalog (AGENTS §9), so it never comes here.
 */
export function searchScopeOfHomeChannel(channel: HomeChannelCode): SearchScope {
  if (channel === 'ACTIVITY') return 'ACTIVITY'
  if (channel === 'MALL') return 'PRODUCT'
  return 'ALL'
}
