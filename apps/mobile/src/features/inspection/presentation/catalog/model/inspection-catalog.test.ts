import { describe, expect, it } from 'vitest'

import {
  ALL_CATALOG_CATEGORY,
  ALL_CATALOG_CATEGORY_OPTION,
  CATALOG_PAGE_SIZE,
  DEFAULT_CATALOG_FILTERS,
  DEFAULT_CATALOG_QUERY,
  EMPTY_CATALOG_CONTENT,
  appendCatalogPage,
  applyCatalogSearch,
  catalogCategoryChips,
  createOpenServiceDetailIntent,
  formatCatalogDistance,
  formatCatalogPrice,
  formatCompletedCount,
  hasCatalogFilters,
  nextPriceSort,
  normalizeCatalogCategory,
  normalizeCatalogQuery,
  priceModesForFilter,
} from './inspection-catalog'
import type { InspectionCatalogService } from './inspection-catalog'

describe('inspection catalog query', () => {
  it('opens on every category, comprehensive order, five cards a page', () => {
    expect(DEFAULT_CATALOG_QUERY).toEqual({
      keyword: '',
      category: ALL_CATALOG_CATEGORY,
      sort: 'COMPREHENSIVE',
    })
    expect(CATALOG_PAGE_SIZE).toBe(5)
  })

  it('accepts any well-formed category code from a link and falls back to all', () => {
    expect(normalizeCatalogCategory('POWER')).toBe('POWER')
    expect(normalizeCatalogCategory(' OIL_GAS ')).toBe('OIL_GAS')
    expect(normalizeCatalogCategory('SUBSTATION')).toBe('SUBSTATION')
    expect(normalizeCatalogCategory(undefined)).toBe(ALL_CATALOG_CATEGORY)
    expect(normalizeCatalogCategory('')).toBe(ALL_CATALOG_CATEGORY)
    expect(normalizeCatalogCategory("POWER' OR 1=1")).toBe(ALL_CATALOG_CATEGORY)
    expect(normalizeCatalogCategory('power')).toBe(ALL_CATALOG_CATEGORY)
  })

  it('trims and limits search text', () => {
    expect(applyCatalogSearch(
      { ...DEFAULT_CATALOG_QUERY, category: 'POWER', sort: 'PRICE_ASC' },
      `  ${'线'.repeat(60)}  `,
    )).toEqual({
      keyword: '线'.repeat(50),
      category: 'POWER',
      sort: 'PRICE_ASC',
    })
  })

  it('restores keyword, category and price sort from a link', () => {
    expect(normalizeCatalogQuery({
      keyword: ' 热成像 ',
      category: 'THERMAL',
      sort: 'PRICE_DESC',
    })).toEqual({ keyword: '热成像', category: 'THERMAL', sort: 'PRICE_DESC' })
  })

  it('does not take a distance sort or an unknown sort from a link', () => {
    // A link cannot carry the customer's position, and asking for it on arrival would be a
    // surprise; the customer picks 距离最近 on the page.
    expect(normalizeCatalogQuery({ sort: 'DISTANCE_ASC' }).sort).toBe('COMPREHENSIVE')
    expect(normalizeCatalogQuery({ sort: 'SALES_DESC' }).sort).toBe('COMPREHENSIVE')
    expect(normalizeCatalogQuery({ sort: 'DROP TABLE' }).sort).toBe('COMPREHENSIVE')
  })

  it('toggles the price sort between ascending and descending', () => {
    expect(nextPriceSort('COMPREHENSIVE')).toBe('PRICE_ASC')
    expect(nextPriceSort('DISTANCE_ASC')).toBe('PRICE_ASC')
    expect(nextPriceSort('PRICE_ASC')).toBe('PRICE_DESC')
    expect(nextPriceSort('PRICE_DESC')).toBe('PRICE_ASC')
  })
})

describe('inspection catalog chips and filters', () => {
  const serverCategories = [
    { code: 'POWER', label: '电力' },
    { code: 'PHOTOVOLTAIC', label: '光伏' },
  ]

  it('puts 全部 before the categories the server offers, in the server order', () => {
    expect(catalogCategoryChips(serverCategories, ALL_CATALOG_CATEGORY)).toEqual([
      ALL_CATALOG_CATEGORY_OPTION,
      ...serverCategories,
    ])
    expect(catalogCategoryChips([], ALL_CATALOG_CATEGORY)).toEqual([
      ALL_CATALOG_CATEGORY_OPTION,
    ])
  })

  it('keeps a category from a link selectable when the server did not list it', () => {
    expect(catalogCategoryChips(serverCategories, 'SUBSTATION').map((chip) => chip.code))
      .toEqual([ALL_CATALOG_CATEGORY, 'POWER', 'PHOTOVOLTAIC', 'SUBSTATION'])
  })

  it('asks the server for listed price modes, the project quote, or nothing', () => {
    expect(priceModesForFilter('ALL')).toEqual([])
    expect(priceModesForFilter('FIXED')).toEqual([
      'STARTING_PRICE',
      'PER_DISTANCE',
      'PER_VISIT',
    ])
    expect(priceModesForFilter('PROJECT_QUOTE')).toEqual(['PROJECT_QUOTE'])
  })

  it('knows when a filter is in effect', () => {
    expect(hasCatalogFilters(DEFAULT_CATALOG_FILTERS)).toBe(false)
    expect(hasCatalogFilters({ ...DEFAULT_CATALOG_FILTERS, method: 'UAV' })).toBe(true)
    expect(hasCatalogFilters({ ...DEFAULT_CATALOG_FILTERS, priceMode: 'FIXED' })).toBe(true)
    expect(hasCatalogFilters({ ...DEFAULT_CATALOG_FILTERS, includesReport: true })).toBe(true)
  })
})

describe('inspection catalog card text', () => {
  it('formats a listed price in yuan and a project quote as words', () => {
    expect(formatCatalogPrice({
      mode: 'PER_DISTANCE',
      amountFen: 69900,
      unitLabel: '公里',
    })).toEqual({ amountLabel: '¥699', unitLabel: '公里' })
    expect(formatCatalogPrice({ mode: 'PROJECT_QUOTE' }))
      .toEqual({ amountLabel: '按项目报价', unitLabel: '' })
    expect(formatCatalogPrice({ mode: 'STARTING_PRICE' }))
      .toEqual({ amountLabel: '按项目报价', unitLabel: '' })
  })

  it('formats the completed count', () => {
    expect(formatCompletedCount(8560)).toBe('已服务 8,560 次')
  })

  it('gives short distances in metres and longer ones in kilometres', () => {
    expect(formatCatalogDistance(0)).toBe('距你 10 米')
    expect(formatCatalogDistance(846)).toBe('距你 850 米')
    expect(formatCatalogDistance(999)).toBe('距你 1000 米')
    expect(formatCatalogDistance(1000)).toBe('距你 1.0 公里')
    expect(formatCatalogDistance(3812)).toBe('距你 3.8 公里')
    expect(formatCatalogDistance(22963)).toBe('距你 23.0 公里')
  })

  it('creates a typed detail intent and rejects an empty service id', () => {
    expect(createOpenServiceDetailIntent(' power-001 ')).toEqual({
      type: 'OPEN_SERVICE_DETAIL',
      serviceId: 'power-001',
    })
    expect(() => createOpenServiceDetailIntent('   ')).toThrow(
      'serviceId is required',
    )
  })
})

describe('inspection catalog paging', () => {
  const card = (id: string): InspectionCatalogService => ({
    id,
    category: 'POWER',
    categoryLabel: '电力',
    title: `服务 ${id}`,
    description: '',
    tags: [],
    imageUrl: '/static/images/inspection/service-power-line.webp',
    price: { mode: 'PROJECT_QUOTE' },
  })

  it('starts empty, open and with nothing more to load', () => {
    expect(EMPTY_CATALOG_CONTENT).toEqual({ services: [], hasMore: false, cityClosed: false })
  })

  it('appends the next page and takes its answer on whether more exist', () => {
    const first = { services: [card('a'), card('b')], hasMore: true, cityClosed: false }

    expect(appendCatalogPage(first, {
      services: [card('c')],
      hasMore: false,
      cityClosed: false,
    })).toEqual({
      services: [card('a'), card('b'), card('c')],
      hasMore: false,
      cityClosed: false,
    })
  })

  it('keeps one copy of a card that moved onto the next page between two requests', () => {
    const first = { services: [card('a'), card('b')], hasMore: true, cityClosed: false }

    const merged = appendCatalogPage(first, {
      services: [card('b'), card('c')],
      hasMore: true,
      cityClosed: false,
    })

    expect(merged.services.map((service) => service.id)).toEqual(['a', 'b', 'c'])
    expect(merged.hasMore).toBe(true)
  })
})
