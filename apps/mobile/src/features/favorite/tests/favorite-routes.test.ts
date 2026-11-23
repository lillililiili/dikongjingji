import { describe, expect, it } from 'vitest'

import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import { buildContentArticleUrl, buildContentVideoUrl } from '../../content/navigation/content-routes'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { buildInspectionDetailUrl, normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import { buildProServiceDetailUrl } from '../../proservice/navigation/proservice-routes'
import pagesJson from '../../../pages.json?raw'
import favoriteFlagSource from '../application/favorite-preview.ts?raw'
import { MAX_COMPARE_ITEMS } from '../application/compare-selection'
import { favoriteTargetUrl } from '../application/favorite-targets'
import {
  buildFavoritesUrl,
  buildMallCompareUrl,
  parseFavoriteGroup,
  parseMallCompareQuery,
} from '../navigation/favorite-routes'

const A = 'a1000000-0000-4000-8000-000000000001'
const B = 'a1000000-0000-4000-8000-000000000002'
const C = 'a1000000-0000-4000-8000-000000000003'
const D = 'a1000000-0000-4000-8000-000000000004'
const CITY = '370500'

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('favorite routes', () => {
  it('registers the favorites and comparison pages', () => {
    expect(pagesJson).toContain('"path": "pages/mine/favorites"')
    expect(pagesJson).toContain('"path": "pages/mall/compare"')
  })

  it('carries only the tab, or the products’ ids and the city', () => {
    expect(buildFavoritesUrl()).toBe('/pages/mine/favorites')
    expect(buildFavoritesUrl('EVENT')).toBe('/pages/mine/favorites?group=EVENT')
    expect(parseFavoriteGroup('CONTENT')).toBe('CONTENT')
    expect(parseFavoriteGroup('ALL')).toBe('GOODS')
    expect(parseFavoriteGroup(undefined)).toBe('GOODS')
    expect(buildMallCompareUrl([A, B], CITY)).toBe(`/pages/mall/compare?ids=${A},${B}&cityCode=${CITY}`)
  })

  it('reads at most three distinct product ids and needs two', () => {
    expect(parseMallCompareQuery({ ids: `${A},${B},${A},nope,${C},${D}`, cityCode: CITY }, MAX_COMPARE_ITEMS))
      .toEqual({ productIds: [A, B, C], cityCode: CITY })
    expect(parseMallCompareQuery({ ids: encodeURIComponent(`${A},${B}`), cityCode: CITY }, MAX_COMPARE_ITEMS))
      .toEqual({ productIds: [A, B], cityCode: CITY })
    expect(parseMallCompareQuery({ ids: `${A},${A}`, cityCode: CITY }, MAX_COMPARE_ITEMS)).toBeNull()
    expect(parseMallCompareQuery({ ids: `${A},${B}`, cityCode: 'dongying' }, MAX_COMPARE_ITEMS)).toBeNull()
  })

  // A 收藏 tapped while signed out goes through the login page, which only returns to pages it knows.
  it('lets the login page come back to every page with 收藏 and to the favorites list', () => {
    const urls = [
      buildMallProductUrl(A, CITY),
      buildInspectionDetailUrl(A, CITY),
      buildProServiceDetailUrl(A, CITY),
      buildActivityDetailUrl(A, CITY),
      buildVenueDetailUrl(A, CITY),
      buildContentArticleUrl(A, CITY),
      buildContentVideoUrl(A, CITY),
      buildFavoritesUrl('SERVICE'),
      buildFavoritesUrl(),
    ]
    for (const url of urls) expect(normalizeInspectionReturnUrl(url)).toBe(url)
  })

  it('opens every kind of favorite on its own detail page', () => {
    expect(favoriteTargetUrl({ targetType: 'PRODUCT', targetId: A }, CITY)).toBe(buildMallProductUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'INSPECTION_SERVICE', targetId: A }, CITY)).toBe(buildInspectionDetailUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'PRO_SERVICE', targetId: A }, CITY)).toBe(buildProServiceDetailUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'ACTIVITY', targetId: A }, CITY)).toBe(buildActivityDetailUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'VENUE', targetId: A }, CITY)).toBe(buildVenueDetailUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'CONTENT', targetId: A, tag: '案例' }, CITY)).toBe(buildContentArticleUrl(A, CITY))
    expect(favoriteTargetUrl({ targetType: 'CONTENT', targetId: A, tag: '视频' }, CITY)).toBe(buildContentVideoUrl(A, CITY))
  })
})

describe('favorite release switch', () => {
  it('reads its own compile-time constant (release switch FAVORITE)', () => {
    expect(flagExpression(favoriteFlagSource, 'FAVORITE_PREVIEW_ENABLED')).toBe(moduleFlagExpression('FAVORITE'))
  })
})
