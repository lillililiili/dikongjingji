import { describe, expect, it } from 'vitest'

import {
  CATALOG_PAGE_SIZE,
  DEFAULT_CATALOG_QUERY,
  EMPTY_CATALOG_CONTENT,
  applyCatalogCategory,
  applyCatalogSearch,
  applyCatalogSort,
  createOpenServiceDetailIntent,
  formatCompletedCount,
  mergeCatalogPage,
  normalizeCatalogEntry,
  normalizeCatalogQuery,
  resolveCatalogContent,
  selectCatalogServices,
} from './inspection-catalog'
import type {
  InspectionCatalogContent,
  InspectionCatalogService,
} from './inspection-catalog'

const service = (
  id: string,
  overrides: Partial<InspectionCatalogService> = {},
): InspectionCatalogService => ({
  id,
  category: 'TRANSMISSION',
  categoryLabel: '输电',
  title: `电力巡检服务 ${id}`,
  description: '杆塔精细巡检 · 导线通道检测 · 缺陷报告',
  tags: ['高精度建模', 'AI 缺陷识别', '巡检报告'],
  imageUrl: '/static/images/inspection/service-power-line.webp',
  price: {
    mode: 'STARTING_PRICE',
    amountFen: 69900,
    unitLabel: '公里起',
  },
  rating: 4.9,
  completedCount: 8560,
  ...overrides,
})

const content: InspectionCatalogContent = {
  services: [service('power-001')],
}

describe('power inspection catalog query', () => {
  it('accepts only the POWER entry and rejects old non-power M01 categories', () => {
    expect(normalizeCatalogEntry(undefined)).toEqual({
      accepted: true,
      category: 'ALL',
    })
    expect(normalizeCatalogEntry('POWER')).toEqual({
      accepted: true,
      category: 'ALL',
    })
    expect(normalizeCatalogEntry('OIL_GAS')).toEqual({
      accepted: false,
      category: 'ALL',
    })
    expect(normalizeCatalogEntry('PHOTOVOLTAIC')).toEqual({
      accepted: false,
      category: 'ALL',
    })
  })

  it('trims and limits search text, then resets pagination', () => {
    const query = applyCatalogSearch(
      {
        keyword: '',
        category: 'CABLE',
        sort: 'PRICE_ASC',
        page: 4,
        size: CATALOG_PAGE_SIZE,
      },
      `  ${'巡'.repeat(55)}  `,
    )

    expect(query.keyword).toBe('巡'.repeat(50))
    expect(query.page).toBe(1)
  })

  it('resets pagination when category changes', () => {
    const query = applyCatalogCategory(
      {
        keyword: '巡检',
        category: 'ALL',
        sort: 'COMPREHENSIVE',
        page: 3,
        size: CATALOG_PAGE_SIZE,
      },
      'SUBSTATION',
    )

    expect(query.category).toBe('SUBSTATION')
    expect(query.page).toBe(1)
  })

  it('accepts sales sorting and falls back from the removed distance route', () => {
    const current = {
      keyword: '',
      category: 'ALL' as const,
      sort: 'COMPREHENSIVE' as const,
      page: 1,
      size: CATALOG_PAGE_SIZE,
    }

    expect(normalizeCatalogQuery({ sort: 'DISTANCE_ASC' }).sort).toBe(
      'COMPREHENSIVE',
    )
    expect(applyCatalogSort(current, 'SALES_DESC').sort).toBe('SALES_DESC')
    expect(applyCatalogSort(current, 'PRICE_DESC').sort).toBe('PRICE_DESC')
  })

  it('uses the approved comprehensive and sales order with a stable id fallback', () => {
    const services = [
      service('transmission', {
        category: 'TRANSMISSION',
        completedCount: 8560,
      }),
      service('substation', {
        category: 'SUBSTATION',
        completedCount: 6230,
      }),
      service('distribution', {
        category: 'DISTRIBUTION',
        completedCount: 5420,
      }),
      service('cable', {
        category: 'CABLE',
        completedCount: 7310,
      }),
      service('photovoltaic', {
        category: 'PHOTOVOLTAIC',
        completedCount: 3210,
      }),
    ]

    expect(selectCatalogServices(services, {
      ...DEFAULT_CATALOG_QUERY,
      sort: 'COMPREHENSIVE',
    }).map(({ id }) => id)).toEqual([
      'transmission',
      'substation',
      'distribution',
      'cable',
      'photovoltaic',
    ])
    expect(selectCatalogServices(services, {
      ...DEFAULT_CATALOG_QUERY,
      sort: 'SALES_DESC',
    }).map(({ id }) => id)).toEqual([
      'transmission',
      'cable',
      'substation',
      'distribution',
      'photovoltaic',
    ])
    expect(formatCompletedCount(8560)).toBe('已服务 8,560 次')
  })
})

describe('power inspection catalog state', () => {
  it('deduplicates appended pages and reports the no-more state', () => {
    expect(
      mergeCatalogPage(
        [service('power-001'), service('power-002')],
        [service('power-002'), service('power-003')],
        false,
      ),
    ).toEqual({
      services: [
        service('power-001'),
        service('power-002'),
        service('power-003'),
      ],
      hasMore: false,
    })
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

  it('exposes visual fixtures only in development', () => {
    expect(resolveCatalogContent('development', content)).toBe(content)
    expect(resolveCatalogContent('production', content)).toEqual(
      EMPTY_CATALOG_CONTENT,
    )
  })
})
