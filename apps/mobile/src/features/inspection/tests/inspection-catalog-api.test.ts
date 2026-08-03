import { parse } from '@vue/compiler-sfc'
import {
  ModuleKind,
  ScriptTarget,
  transpileModule,
} from 'typescript'
import { describe, expect, it } from 'vitest'

import { findCityByLocatedName } from '../../../shared/city/domain/city-directory'
import {
  createInspectionCatalogApi,
  type InspectionCatalogHttpClient,
} from '../data/catalog/inspection-catalog-api'
import bookingHttpClientSource from '../data/service-flow/uni-booking-http-client.ts?raw'
import catalogPageSource from '../presentation/catalog/pages/InspectionCatalogPage.vue?raw'
import portalPageSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'

interface LatestCatalogLoaderOptions<T> {
  getCityCode(): string | undefined
  load(cityCode: string): Promise<T>
  emptyContent: T
  setContent(content: T): void
  setError(message: string): void
  setLoading(loading: boolean): void
  setRefreshing(refreshing: boolean): void
  errorMessage: string
}

type LatestCatalogLoaderFactory = <T>(
  options: LatestCatalogLoaderOptions<T>,
) => () => Promise<void>

async function loadLatestCatalogLoaderFactory():
Promise<LatestCatalogLoaderFactory> {
  const parsed = parse(catalogPageSource, {
    filename: 'InspectionCatalogPage.vue',
  })
  expect(parsed.descriptor.script).not.toBeNull()
  if (!parsed.descriptor.script) {
    throw new Error('Catalog page runtime script is unavailable')
  }
  const output = transpileModule(parsed.descriptor.script.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText
  const testModule: {
    exports: {
      createLatestCatalogLoader?: LatestCatalogLoaderFactory
    }
  } = { exports: {} }
  const testRequire = (request: string): unknown => {
    if (request === '@/shared/city/domain/city-directory') {
      return { findCityByLocatedName }
    }
    throw new Error(`Unexpected catalog runtime dependency: ${request}`)
  }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  const factory = testModule.exports.createLatestCatalogLoader
  expect(factory).toBeTypeOf('function')
  if (!factory) throw new Error('Latest catalog loader is unavailable')
  return factory
}

function deferred<T>(): {
  promise: Promise<T>
  resolve(value: T): void
  reject(reason: unknown): void
} {
  let resolvePromise!: (value: T) => void
  let rejectPromise!: (reason: unknown) => void
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve
    rejectPromise = reject
  })
  return {
    promise,
    resolve: resolvePromise,
    reject: rejectPromise,
  }
}

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
    completedCount: 8560,
    availability: 'AVAILABLE',
    primaryAction: 'BOOK',
  }],
}

describe('real inspection catalog adapter', () => {
  it('exports the public platform city API through the shared public request', () => {
    expect(bookingHttpClientSource).toContain(
      "import { createCityDirectoryApi } from '../../../../shared/city/data/city-directory-api'",
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

  it('maps the real catalog DTO to M02 with the verified completed count', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return response as T
      },
    }

    const content = await createInspectionCatalogApi(http).loadCatalog(
      '370500',
      'SALES_DESC',
    )

    expect(calls).toEqual([
      '/api/v1/inspection/services?cityCode=370500&categoryCode=POWER&sort=SALES_DESC&page=1&size=50',
    ])
    expect(content.services[0]).toMatchObject({
      id: '33333333-3333-3333-3333-333333333333',
      category: 'TRANSMISSION',
      title: '输电线路无人机巡检',
      tags: ['线路巡检', '巡检报告'],
      includesReport: true,
      completedCount: 8560,
      price: { mode: 'STARTING_PRICE', amountFen: 69900 },
    })
    expect(content.services[0]).not.toHaveProperty('rating')
  })

  it('maps the five approved power subcategories', async () => {
    const codes = [
      'POWER',
      'SUBSTATION',
      'DISTRIBUTION',
      'CABLE',
      'PHOTOVOLTAIC',
    ]
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => ({
        ...response,
        items: codes.map((code, index) => ({
          ...response.items[0],
          serviceId: `33333333-3333-4333-8333-33333333333${index}`,
          category: { code, name: code },
        })),
      }) as T,
    }

    const content = await createInspectionCatalogApi(http).loadCatalog(
      '370500',
      'COMPREHENSIVE',
    )

    expect(content.services.map(({ category }) => category)).toEqual([
      'TRANSMISSION',
      'SUBSTATION',
      'DISTRIBUTION',
      'CABLE',
      'PHOTOVOLTAIC',
    ])
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

describe('catalog latest-request protection', () => {
  it.each(['resolve', 'reject'] as const)(
    'ignores stale A %s writes while B is still loading',
    async (settlement) => {
      const createLoader = await loadLatestCatalogLoaderFactory()
      const requests = {
        A: deferred<string>(),
        B: deferred<string>(),
      }
      let cityCode: keyof typeof requests = 'A'
      const state = {
        content: 'seed',
        error: '',
        loading: false,
        refreshing: true,
      }
      const loadCatalog = createLoader<string>({
        getCityCode: () => cityCode,
        load: (code) => requests[code as keyof typeof requests].promise,
        emptyContent: 'empty',
        setContent: (content) => {
          state.content = content
        },
        setError: (message) => {
          state.error = message
        },
        setLoading: (loading) => {
          state.loading = loading
        },
        setRefreshing: (refreshing) => {
          state.refreshing = refreshing
        },
        errorMessage: 'catalog failed',
      })

      const requestA = loadCatalog()
      cityCode = 'B'
      const requestB = loadCatalog()

      if (settlement === 'resolve') requests.A.resolve('A-content')
      else requests.A.reject(new Error('A-failed'))
      await requestA

      expect(state).toEqual({
        content: 'seed',
        error: '',
        loading: true,
        refreshing: true,
      })

      requests.B.resolve('B-content')
      await requestB
      expect(state).toEqual({
        content: 'B-content',
        error: '',
        loading: false,
        refreshing: false,
      })
    },
  )
})
