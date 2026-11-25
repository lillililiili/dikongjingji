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
  type CatalogSearchInput,
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
    priceNote: '含现场巡检和报告',
    completedCount: 8560,
    availability: 'AVAILABLE',
    primaryAction: 'BOOK',
  }],
}

function search(overrides: Partial<CatalogSearchInput> = {}): CatalogSearchInput {
  return {
    cityCode: '370500',
    query: { keyword: '', category: 'ALL', sort: 'COMPREHENSIVE' },
    filters: { method: 'ALL', priceMode: 'ALL', includesReport: false },
    position: null,
    page: 1,
    size: 5,
    ...overrides,
  }
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
    expect(catalogPageSource).toContain('inspectionCatalogApi.searchServices')
    expect(catalogPageSource).toContain('inspectionCatalogApi.loadFilterOptions')
    expect(portalPageSource).not.toContain('data/fixtures')
    expect(catalogPageSource).not.toContain('fixtures/')
  })

  it('asks the server for one page with only the conditions the customer set', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return response as T
      },
    }
    const api = createInspectionCatalogApi(http)

    await api.searchServices(search())
    await api.searchServices(search({
      query: { keyword: '线路 50%', category: 'POWER', sort: 'PRICE_DESC' },
      filters: { method: 'UAV', priceMode: 'FIXED', includesReport: true },
      page: 3,
    }))
    await api.searchServices(search({
      filters: { method: 'ALL', priceMode: 'PROJECT_QUOTE', includesReport: false },
    }))

    expect(calls).toEqual([
      '/api/v1/inspection/services?cityCode=370500&sort=COMPREHENSIVE&page=1&size=5',
      '/api/v1/inspection/services?cityCode=370500&keyword=%E7%BA%BF%E8%B7%AF%2050%25'
        + '&categoryCode=POWER&serviceMethods=UAV'
        + '&priceModes=STARTING_PRICE&priceModes=PER_DISTANCE&priceModes=PER_VISIT'
        + '&includesReport=true&sort=PRICE_DESC&page=3&size=5',
      '/api/v1/inspection/services?cityCode=370500&priceModes=PROJECT_QUOTE'
        + '&sort=COMPREHENSIVE&page=1&size=5',
    ])
  })

  it('sends the position with the distance sort and with nothing else', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return response as T
      },
    }
    const api = createInspectionCatalogApi(http)
    const position = { latitude: 37.4346, longitude: 118.6747 }

    await api.searchServices(search({
      query: { keyword: '', category: 'ALL', sort: 'DISTANCE_ASC' },
      position,
    }))
    await api.searchServices(search({
      query: { keyword: '', category: 'ALL', sort: 'PRICE_ASC' },
      position,
    }))

    expect(calls[0]).toBe(
      '/api/v1/inspection/services?cityCode=370500&sort=DISTANCE_ASC'
        + '&latitude=37.4346&longitude=118.6747&page=1&size=5',
    )
    expect(calls[1]).not.toContain('latitude')
    expect(calls[1]).not.toContain('longitude')
  })

  it('maps the real catalog DTO to M02 with the verified completed count', async () => {
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => ({ ...response, hasMore: true }) as T,
    }

    const content = await createInspectionCatalogApi(http).searchServices(search())

    expect(content.hasMore).toBe(true)
    expect(content.cityClosed).toBe(false)
    expect(content.services[0]).toMatchObject({
      id: '33333333-3333-3333-3333-333333333333',
      category: 'POWER',
      title: '输电线路无人机巡检',
      tags: ['线路巡检', '巡检报告'],
      completedCount: 8560,
      price: { mode: 'STARTING_PRICE', amountFen: 69900 },
      priceNote: '含现场巡检和报告',
    })
    expect(content.services[0]).not.toHaveProperty('rating')
    expect(content.services[0]).not.toHaveProperty('distanceMeters')
  })

  it('keeps the distance only when the server measured one', async () => {
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => ({
        ...response,
        items: [
          { ...response.items[0], distanceMeters: 3812 },
          { ...response.items[0], serviceId: 'b', distanceMeters: 0 },
          { ...response.items[0], serviceId: 'c', distanceMeters: null },
        ],
      }) as T,
    }

    const content = await createInspectionCatalogApi(http).searchServices(search())

    expect(content.services[0].distanceMeters).toBe(3812)
    expect(content.services[1].distanceMeters).toBe(0)
    expect(content.services[2]).not.toHaveProperty('distanceMeters')
  })

  it.each([null, undefined, ''])(
    'omits priceNote when the server sends %s so the card does not render the line',
    async (priceNote) => {
      const http: InspectionCatalogHttpClient = {
        request: async <T,>() => ({
          ...response,
          items: [{ ...response.items[0], priceNote }],
        }) as T,
      }

      const content = await createInspectionCatalogApi(http).searchServices(search())

      expect(content.services[0]).not.toHaveProperty('priceNote')
    },
  )

  it('passes the server wording for project-quote cards through unchanged', async () => {
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => ({
        ...response,
        items: [{
          ...response.items[0],
          price: { mode: 'PROJECT_QUOTE', startingAmountMinor: null, currency: 'CNY', unitCode: null, unitLabel: null },
          priceNote: '方案确认后定价',
        }],
      }) as T,
    }

    const content = await createInspectionCatalogApi(http).searchServices(search())

    expect(content.services[0]).toMatchObject({
      price: { mode: 'PROJECT_QUOTE' },
      priceNote: '方案确认后定价',
    })
  })

  it('keeps every category the server returns, with its own code and no invented image', async () => {
    const codes = ['POWER', 'SUBSTATION', 'OIL_GAS', 'THERMAL', 'SOMETHING_NEW']
    const http: InspectionCatalogHttpClient = {
      request: async <T,>() => ({
        ...response,
        items: codes.map((code, index) => ({
          ...response.items[0],
          serviceId: `33333333-3333-4333-8333-33333333333${index}`,
          category: { code, name: code },
          coverImage: null,
        })),
      }) as T,
    }

    const content = await createInspectionCatalogApi(http).searchServices(search())

    expect(content.services.map((service) => service.category)).toEqual(codes)
    expect(content.services.every((service) => !service.imageUrl)).toBe(true)
    expect(content.services[2].imageUrl).toBe('')
  })

  it('reports a city without inspection services as closed instead of failing', async () => {
    const closed = Object.assign(new Error('not enabled'), {
      status: 404,
      code: 'INS_CATALOG_CITY_NOT_ENABLED',
    })
    const api = createInspectionCatalogApi({
      request: async () => { throw closed },
    })

    await expect(api.searchServices(search())).resolves.toEqual({
      services: [],
      hasMore: false,
      cityClosed: true,
    })
    const portal = await api.loadPortal('370700')
    expect(portal.hero).toBeNull()
    expect(portal.categories).toEqual([])
    expect(portal.hotServices).toEqual([])
  })

  it('passes other failures on so the page can offer a retry', async () => {
    const failure = Object.assign(new Error('boom'), { status: 500, code: 'COMMON_INTERNAL_ERROR' })
    const api = createInspectionCatalogApi({
      request: async () => { throw failure },
    })

    await expect(api.searchServices(search())).rejects.toBe(failure)
    await expect(api.loadPortal('370500')).rejects.toBe(failure)
    await expect(api.loadFilterOptions('370500')).rejects.toBe(failure)
  })

  it('maps the filter options of a city', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return {
          categories: [
            { code: 'POWER', name: '电力', sortOrder: 10 },
            { code: 'PARK', name: '园区', sortOrder: 50 },
          ],
          serviceMethods: [{ code: 'UAV', name: '无人机' }],
          availability: [],
          priceModes: [{ code: 'PER_VISIT', name: '按次计价' }],
          sorts: ['COMPREHENSIVE', 'DISTANCE_ASC', 'PRICE_ASC', 'PRICE_DESC'],
          supportsReportFilter: true,
        } as T
      },
    }

    const options = await createInspectionCatalogApi(http).loadFilterOptions('370500')

    expect(calls).toEqual(['/api/v1/inspection/filters?cityCode=370500'])
    expect(options).toEqual({
      categories: [
        { code: 'POWER', label: '电力' },
        { code: 'PARK', label: '园区' },
      ],
      serviceMethods: [{ code: 'UAV', label: '无人机' }],
      priceModes: ['PER_VISIT'],
      supportsDistanceSort: true,
      supportsReportFilter: true,
    })
  })

  it('builds M01 from the portal endpoint', async () => {
    const calls: string[] = []
    const http: InspectionCatalogHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' }) => {
        calls.push(input.url)
        return {
          hero: {
            title: '专业巡检',
            highlightedText: '隐患早发现',
            subtitle: '无人机与智能设备',
            sellingPoints: [],
            image: null,
          },
          categories: [
            { code: 'POWER', name: '电力', subtitle: '线路·杆塔·变电站' },
            { code: 'THERMAL', name: '热力', subtitle: null },
            { code: 'SOMETHING_NEW', name: '新分类', subtitle: null },
          ],
          guarantees: [],
          hotServices: [
            response.items[0],
            {
              ...response.items[0],
              serviceId: 'sub',
              category: { code: 'SUBSTATION', name: '变电站' },
              providerName: '示例电力巡检服务商',
            },
          ],
          processSteps: [{ code: 'REQUEST', title: '提交需求', subtitle: null }],
        } as T
      },
    }

    const content = await createInspectionCatalogApi(http).loadPortal('370500')

    expect(calls).toEqual(['/api/v1/inspection/portal?cityCode=370500'])
    expect(content.hero).toEqual({
      title: '专业巡检',
      accent: '隐患早发现',
      description: '无人机与智能设备',
      highlights: [],
      imageUrl: '',
    })
    // Newly configured categories remain available even without a bundled category icon.
    expect(content.categories).toEqual([
      {
        code: 'POWER',
        name: '电力',
        subtitle: '线路·杆塔·变电站',
        imageUrl: '/static/images/inspection/category-power.webp',
      },
      {
        code: 'THERMAL',
        name: '热力',
        subtitle: '',
        imageUrl: '/static/images/inspection/category-thermal.webp',
      },
      { code: 'SOMETHING_NEW', name: '新分类', subtitle: '', imageUrl: '' },
    ])
    expect(content.hotServices.map((service) => service.categoryCode))
      .toEqual(['POWER', 'SUBSTATION'])
    expect(content.hotServices[0]).toMatchObject({
      id: '33333333-3333-3333-3333-333333333333',
      title: '输电线路无人机巡检',
      priceLabel: '¥699/公里起',
    })
    expect(content.hotServices[0]).not.toHaveProperty('rating')
    expect(content.hotServices[0]).not.toHaveProperty('servedCountLabel')
    // Catalog 1.4.0: the provider is the card's second line (board HomeSvc); no provider, no line.
    expect(content.hotServices[0]).not.toHaveProperty('providerName')
    expect(content.hotServices[1]?.providerName).toBe('示例电力巡检服务商')
    expect(content.guarantees).toEqual([])
    expect(content.processSteps).toEqual([{ code: 'REQUEST', title: '提交需求', icon: '' }])
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
