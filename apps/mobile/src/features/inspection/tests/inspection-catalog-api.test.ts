import { describe, expect, it } from 'vitest'

import {
  createInspectionCatalogApi,
  type InspectionCatalogHttpClient,
} from '../data/catalog/inspection-catalog-api'
import bookingHttpClientSource from '../data/service-flow/uni-booking-http-client.ts?raw'
import catalogPageSource from '../presentation/catalog/pages/InspectionCatalogPage.vue?raw'
import portalPageSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'

const response = {
  page: 1,
  size: 20,
  total: 1,
  hasMore: false,
  items: [{
    serviceId: '33333333-3333-3333-3333-333333333333',
    serviceNo: 'INS-POWER-001',
    category: { code: 'POWER', name: '电力巡检' },
    name: '输电线路无人机巡检',
    summary: '杆塔与线路巡检',
    coverImage: null,
    features: [
      { code: 'POWER_LINE', name: '线路巡检' },
      { code: 'REPORT', name: '巡检报告' },
    ],
    price: {
      mode: 'STARTING_PRICE',
      startingAmountMinor: 69900,
      currency: 'CNY',
      unitCode: 'KM',
      unitLabel: '公里起',
    },
    availability: 'AVAILABLE',
    primaryAction: 'BOOK',
  }],
}

describe('real inspection catalog adapter', () => {
  it('exports the public platform city API through the shared public request', () => {
    expect(bookingHttpClientSource).toContain(
      "import { createCityDirectoryApi } from '@/shared/city/data/city-directory-api'",
    )
    expect(bookingHttpClientSource).toContain(
      'export const platformCityApi = createCityDirectoryApi({ request: publicRequest })',
    )
  })

  it('keeps production page code independent from development fixtures', () => {
    expect(portalPageSource).toContain('inspectionCatalogApi.loadPortal')
    expect(catalogPageSource).toContain('inspectionCatalogApi.loadCatalog')
    expect(portalPageSource).not.toContain('data/fixtures')
    expect(catalogPageSource).not.toContain('fixtures/power-inspection-catalog')
  })

  it('maps the real catalog DTO to M02 without fake ratings or service counts', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return response as T
      },
    }

    const content = await createInspectionCatalogApi(http).loadCatalog('370500')

    expect(calls).toEqual([
      '/api/v1/inspection/services?cityCode=370500&page=1&size=50',
    ])
    expect(content.services[0]).toMatchObject({
      id: '33333333-3333-3333-3333-333333333333',
      category: 'TRANSMISSION',
      title: '输电线路无人机巡检',
      tags: ['线路巡检', '巡检报告'],
      includesReport: true,
      price: { mode: 'STARTING_PRICE', amountFen: 69900 },
    })
    expect(content.services[0]).not.toHaveProperty('rating')
    expect(content.services[0]).not.toHaveProperty('servedCountLabel')
  })

  it('derives M01 navigation cards from the same real services', async () => {
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => response as T,
    }

    const content = await createInspectionCatalogApi(http).loadPortal('370500')

    expect(content.categories).toHaveLength(1)
    expect(content.categories[0]).toMatchObject({
      code: 'POWER',
      name: '电力巡检',
    })
    expect(content.hotServices[0]).toMatchObject({
      id: '33333333-3333-3333-3333-333333333333',
      categoryCode: 'POWER',
      title: '输电线路无人机巡检',
    })
    expect(content.hotServices[0]).not.toHaveProperty('rating')
    expect(content.hotServices[0]).not.toHaveProperty('servedCountLabel')
    expect(content.guarantees).toEqual([])
    expect(content.processSteps).toEqual([])
  })
})
