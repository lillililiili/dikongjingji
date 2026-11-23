import { describe, expect, it } from 'vitest'

import pagesJson from '../../../pages.json?raw'
import homeSource from '../../../pages/home/index.vue?raw'
import discoverSource from '../../../pages/discover/index.vue?raw'
import { articleCardDto } from '../../content/tests/content-fixtures'
import { cardDto as proServiceCardDto } from '../../proservice/tests/proservice-fixtures'
import searchFlagSource from '../application/search-preview.ts?raw'
import { SEARCH_CATEGORY_ENTRIES } from '../application/search-categories'
import {
  describeSearchGroups,
  describeSearchSuggestions,
  hasSearchResults,
  highlightKeyword,
  searchCityLine,
} from '../application/search-view'
import { buildSearchQueryString, createSearchApi } from '../data/search-api'
import { SEARCH_SCOPES, SEARCH_SCOPE_LABELS, type SearchResult } from '../domain/search'
import {
  buildSearchPreUrl,
  buildSearchResultsUrl,
  parseSearchTarget,
  searchScopeOfHomeChannel,
} from '../navigation/search-routes'

const CITY = '370500'

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

// Shapes of contracts/openapi-search.yaml 1.0.0.
function resultDto(overrides: Record<string, unknown> = {}) {
  return {
    cityCode: CITY,
    keyword: '测绘',
    scope: 'ALL',
    correction: null,
    products: {
      total: 2,
      items: [{
        productId: 'c2000000-0000-4000-8000-000000000101',
        category: 'DRONE',
        name: '行业测绘无人机',
        coverImage: { url: 'https://cdn.example.test/drone.jpg', alt: '行业测绘无人机' },
        saleMode: 'INQUIRY',
        experienceBadge: null,
        price: { fromAmountMinor: null, unit: 'ITEM', multiplePrices: false },
        sellerCount: 2,
      }],
    },
    services: {
      total: 5,
      items: [
        {
          kind: 'INSPECTION',
          inspection: {
            serviceId: 'a1000000-0000-4000-8000-000000000001',
            category: { code: 'PARK', name: '园区' },
            name: '园区测绘巡检',
            summary: '园区安防',
            coverImage: null,
            features: [{ code: 'REPORT', name: '含报告' }],
            price: { mode: 'STARTING_PRICE', startingAmountMinor: 88000, unitLabel: '次' },
          },
          proService: null,
        },
        { kind: 'PRO_SERVICE', inspection: null, proService: proServiceCardDto({ type: 'SURVEY', title: '无人机航测与三维建模', category: { code: 'ENGINEERING', label: '工程' }, price: { mode: 'PROJECT', amountMinor: null, unit: null, note: '方案确认后定价' } }) },
      ],
    },
    activities: {
      total: 1,
      items: [{
        activityId: 'b1000000-0000-4000-8000-000000000001',
        type: 'TRAINING',
        title: '无人机测绘员技能培训',
        coverImage: null,
        venueName: '东营低空与机器人体验馆',
        scheduleNote: null,
        price: { mode: 'INQUIRY', amountMinor: null, currency: 'CNY' },
        ageRange: { minAge: null, maxAge: null },
        enrollmentStatus: 'ENROLLING',
        nextSession: null,
      }],
    },
    contents: { total: 1, items: [articleCardDto({ title: '无人机航测如何三天出图' })] },
    venues: { total: 0, items: [] },
    ...overrides,
  }
}

async function resultOf(overrides: Record<string, unknown> = {}): Promise<{ result: SearchResult; urls: string[] }> {
  const urls: string[] = []
  const api = createSearchApi({
    async request<T>(input: { url: string }): Promise<T> {
      urls.push(input.url)
      return resultDto(overrides) as T
    },
  })
  const result = await api.search({ cityCode: CITY, keyword: ' 测绘 ', scope: 'ALL' })
  return { result, urls }
}

describe('search highlight (board SearchPre cut)', () => {
  it('ignores spaces and case and puts the cut back on the original text', () => {
    expect(highlightKeyword('3 分钟看懂编程机器人', '3分钟')).toEqual({ pre: '', hit: '3 分钟', post: '看懂编程机器人' })
    expect(highlightKeyword('行业测绘无人机', '测绘')).toEqual({ pre: '行业', hit: '测绘', post: '无人机' })
    expect(highlightKeyword('DJI Mini 4', 'mini')).toEqual({ pre: 'DJI ', hit: 'Mini', post: ' 4' })
    expect(highlightKeyword('园区安防巡检', '测绘')).toEqual({ pre: '园区安防巡检', hit: '', post: '' })
    expect(highlightKeyword('园区安防巡检', '  ')).toEqual({ pre: '园区安防巡检', hit: '', post: '' })
  })
})

describe('search api', () => {
  it('asks for one city, the trimmed words, the scope and the size of the scope', () => {
    expect(buildSearchQueryString({ cityCode: CITY, keyword: ' 测 绘 ', scope: 'ALL' }))
      .toBe(`cityCode=${CITY}&keyword=${encodeURIComponent('测 绘')}&scope=ALL&size=3`)
    expect(buildSearchQueryString({ cityCode: CITY, keyword: '机器狗', scope: 'PRODUCT' }))
      .toBe(`cityCode=${CITY}&keyword=${encodeURIComponent('机器狗')}&scope=PRODUCT&size=20`)
  })

  it('maps every group with the card mapper of its own feature', async () => {
    const { result, urls } = await resultOf({ correction: { keyword: '巡检' } })
    expect(urls[0]).toBe(`/api/v1/search?cityCode=${CITY}&keyword=${encodeURIComponent('测绘')}&scope=ALL&size=3`)
    expect(result.correction).toBe('巡检')
    expect(result.products?.items[0].name).toBe('行业测绘无人机')
    expect(result.services?.items.map((hit) => hit.kind)).toEqual(['INSPECTION', 'PRO_SERVICE'])
    expect(result.activities?.items[0].title).toBe('无人机测绘员技能培训')
    expect(result.contents?.items[0].title).toBe('无人机航测如何三天出图')
    expect(result.venues?.items).toEqual([])
  })

  it('keeps a group outside the scope as null and drops a hit without its card', async () => {
    const { result } = await resultOf({
      scope: 'SERVICE',
      products: null,
      activities: null,
      contents: null,
      venues: null,
      services: { total: 1, items: [{ kind: 'INSPECTION', inspection: null, proService: null }] },
    })
    expect(result.products).toBeNull()
    expect(result.services).toEqual({ total: 1, items: [] })
  })
})

describe('search view (board MallSearch)', () => {
  it('draws the groups in the board order with each module\'s own words', async () => {
    const { result } = await resultOf()
    const groups = describeSearchGroups(result)
    expect(groups.map((group) => `${group.label} · ${group.total}`)).toEqual(['商品 · 2', '服务 · 5', '活动 · 1', '内容 · 1', '场馆 · 0'])
    const [goods] = groups[0].rows
    expect(goods.title).toEqual({ pre: '行业', hit: '测绘', post: '无人机' })
    expect(goods.price).toBe('询价')
    expect(goods.sellers).toBe('2 家可询价')
    expect(goods.url).toBe(`/pages/mall/product?productId=c2000000-0000-4000-8000-000000000101&cityCode=${CITY}`)
    const [inspection, survey] = groups[1].rows
    expect(inspection.sub).toBe('¥880/次 · 巡检 · 园区')
    expect(inspection.url).toBe(`/pages/service/inspection/detail?serviceId=a1000000-0000-4000-8000-000000000001&cityCode=${CITY}`)
    expect(inspection.demandUrl).toBe('/pages/demand/publish?type=INSPECTION')
    expect(survey.sub).toBe('按项目报价 · 测绘')
    expect(survey.demandUrl).toBe('/pages/demand/publish?type=SURVEY')
    expect(groups[2].rows[0].tag).toBe('培训')
    expect(groups[2].rows[0].price).toBe('询价')
    expect(groups[3].rows[0].tag).toBe('案例')
    expect(groups[3].rows[0].url).toBe(`/pages/content/article?articleId=f3000000-0000-4000-8000-000000000001&cityCode=${CITY}`)
    expect(hasSearchResults(groups)).toBe(true)
    expect(hasSearchResults(groups.filter((group) => group.scope === 'VENUE'))).toBe(false)
  })

  it('opens a video card on the video page', async () => {
    const { result } = await resultOf({ contents: { total: 1, items: [articleCardDto({ kind: 'VIDEO', video: { durationSeconds: 60, orientation: 'PORTRAIT' } })] } })
    const [content] = describeSearchGroups(result).find((group) => group.scope === 'CONTENT')?.rows ?? []
    expect(content.tag).toBe('视频')
    expect(content.url.startsWith('/pages/content/video?')).toBe(true)
  })

  it('lists a few suggestions filtered by the scope tab', async () => {
    const { result } = await resultOf()
    expect(describeSearchSuggestions(result, 'ALL').map((row) => row.typeLabel)).toEqual(['商品', '服务', '服务', '活动', '内容'])
    expect(describeSearchSuggestions(result, 'ACTIVITY').map((row) => row.name)).toEqual(['无人机测绘员技能培训'])
    expect(describeSearchSuggestions(result, 'VENUE')).toEqual([])
  })

  it('writes the city line with the short city name', () => {
    expect(searchCityLine('东营市')).toBe('东营 · 结果按本城可用范围显示')
    expect(searchCityLine(null)).toBe('结果按本城可用范围显示')
  })
})

describe('search routes', () => {
  it('carries only the scope and the words', () => {
    expect(buildSearchPreUrl()).toBe('/pages/search/index')
    expect(buildSearchPreUrl({ scope: 'ALL' })).toBe('/pages/search/index')
    expect(buildSearchPreUrl({ scope: 'PRODUCT', keyword: ' 测绘 ' })).toBe(`/pages/search/index?scope=PRODUCT&keyword=${encodeURIComponent('测绘')}`)
    expect(buildSearchResultsUrl(' 机器狗 ', 'VENUE')).toBe(`/pages/search/results?keyword=${encodeURIComponent('机器狗')}&scope=VENUE`)
    expect(parseSearchTarget({ scope: 'CONTENT', keyword: encodeURIComponent('测绘') })).toEqual({ scope: 'CONTENT', keyword: '测绘' })
    expect(parseSearchTarget({ scope: 'PEOPLE', keyword: '测'.repeat(60) })).toEqual({ scope: 'ALL', keyword: '测'.repeat(40) })
    expect(parseSearchTarget(undefined)).toEqual({ scope: 'ALL', keyword: '' })
  })

  it('lands each home channel on its scope (Batch3Spec §4 从频道进入)', () => {
    expect(searchScopeOfHomeChannel('ACTIVITY')).toBe('ACTIVITY')
    expect(searchScopeOfHomeChannel('MALL')).toBe('PRODUCT')
    expect(searchScopeOfHomeChannel('RECOMMEND')).toBe('ALL')
    expect(searchScopeOfHomeChannel('EXPERIENCE')).toBe('ALL')
  })

  it('has the six scope tabs and the eight category entries of the board', () => {
    expect(SEARCH_SCOPES.map((scope) => SEARCH_SCOPE_LABELS[scope])).toEqual(['综合', '商品', '服务', '活动', '内容', '场馆'])
    expect(SEARCH_CATEGORY_ENTRIES.map((entry) => entry.label)).toEqual(['专业服务', '赛事研学', '体验', '无人机', '机器人', '配件', '租赁', '课程'])
  })

  it('registers the pages and keeps them to preview builds', () => {
    for (const path of ['pages/search/index', 'pages/search/results', 'pages/mine/order-search']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    // Release switch SEARCH (app-shell/release/release-features.json).
    expect(flagExpression(searchFlagSource, 'SEARCH_PREVIEW_ENABLED'))
      .toBe('import.meta.env.DEV || __RELEASE_FEATURE_SEARCH__')
    // The 专业服务 box keeps going to the inspection catalog (AGENTS §9).
    expect(homeSource).toContain("SEARCH_PREVIEW_ENABLED && activeChannel.value !== 'SERVICE'")
    expect(discoverSource).toContain('@search="openSearch"')
  })
})
