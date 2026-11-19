import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia, storeToRefs } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'
import type { ComputedRef, Ref } from 'vue'

import {
  findCityByLocatedName,
  type CityDirectory,
  type PlatformCity,
} from '../../../shared/city/domain/city-directory'
import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import * as catalogModel from '../presentation/catalog/model/inspection-catalog'
import type { InspectionCatalogContent } from '../presentation/catalog/model/inspection-catalog'
import catalogPageSource from '../presentation/catalog/pages/InspectionCatalogPage.vue?raw'

interface CatalogRouteQuery {
  cityCode?: string
  categoryCode?: string
}

interface CatalogPageBindings {
  loadError: Ref<string>
  isLoading: Ref<boolean>
  content: Ref<InspectionCatalogContent>
  displayCityName: ComputedRef<string>
  loadCatalogContent(): Promise<void>
  applyCitySelection(payload: {
    city: PlatformCity
    source: 'TREE'
  }): Promise<void>
}

const NETWORK_ERROR = '巡检服务加载失败，请检查网络后重试'

const dongying: PlatformCity = {
  code: '370500',
  name: '东营市',
  pinyin: 'dongyingshi',
  initials: 'dys',
  provinceCode: '370000',
  provinceName: '山东省',
}
const jinan: PlatformCity = {
  ...dongying,
  code: '370100',
  name: '济南市',
  pinyin: 'jinanshi',
  initials: 'jns',
}
const directory: CityDirectory = {
  hotCities: [],
  provinces: [{
    code: '370000',
    name: '山东省',
    pinyin: 'shandongsheng',
    initials: 'sds',
    cities: [dongying, jinan],
  }],
}

function catalogContent(cityCode: string): InspectionCatalogContent {
  return {
    ...catalogModel.EMPTY_CATALOG_CONTENT,
    services: [{ id: `service-${cityCode}` }],
  } as unknown as InspectionCatalogContent
}

function deferred<T>() {
  let resolvePromise!: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise }
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve)
  })
}

function mountCatalogPage(api: {
  loadDirectory: () => Promise<CityDirectory>
  loadCatalog: (cityCode: string, sort: string) => Promise<InspectionCatalogContent>
}): {
  bindings: CatalogPageBindings
  load(query: CatalogRouteQuery): void
} {
  const parsed = parse(catalogPageSource, {
    filename: 'InspectionCatalogPage.vue',
  })
  const compiled = compileScript(parsed.descriptor, {
    id: 'inspection-catalog-route-city-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText

  let onLoadHandler: ((query: CatalogRouteQuery) => void) | undefined
  const testRequire = (request: string): unknown => {
    if (request === 'vue') return Vue
    if (request === 'pinia') return { storeToRefs }
    if (request === '@dcloudio/uni-app') {
      return {
        onLoad: (handler: (query: CatalogRouteQuery) => void) => {
          onLoadHandler = handler
        },
      }
    }
    if (request === '@/shared/city/state/current-city') {
      return { useCurrentCityStore }
    }
    if (request === '@/shared/city/domain/city-directory') {
      return { findCityByLocatedName }
    }
    if (request === '@/shared/city/data/recent-city-storage') {
      return {
        createRecentCityStorage: () => ({
          load: () => [],
          save: () => undefined,
        }),
      }
    }
    if (request === '@/shared/city/data/uni-city-location') {
      return { locateCurrentCityName: vi.fn() }
    }
    if (request === '../../../data/catalog/uni-catalog-position') {
      return { locateCatalogPosition: vi.fn() }
    }
    if (request === '../../../data/service-flow/uni-booking-http-client') {
      return {
        inspectionCatalogApi: {
          // The tests only care which city and sort the first page is asked for.
          searchServices: (input: { cityCode: string; query: { sort: string } }) =>
            api.loadCatalog(input.cityCode, input.query.sort),
          loadFilterOptions: async () => catalogModel.EMPTY_CATALOG_FILTER_OPTIONS,
        },
        platformCityApi: {
          load: api.loadDirectory,
          recordSearchSelection: vi.fn(async () => undefined),
        },
      }
    }
    // Step G1: customer service and its preview flag (off: the page keeps its notice).
    if (request === '@/features/support/application/support-entry') return { openSupportChat: () => undefined }
    if (request === '@/features/support/application/support-preview') return { SUPPORT_PREVIEW_ENABLED: false }
    if (request === '../model/inspection-catalog') return catalogModel
    if (request.endsWith('.vue')) return { default: {} }
    throw new Error(`Unexpected SFC dependency: ${request}`)
  }

  const testModule: {
    exports: {
      default?: {
        setup(props: object, context: object): CatalogPageBindings
      }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  const bindings = testModule.exports.default.setup({}, {
    expose: () => undefined,
  })

  return {
    bindings,
    load(query) {
      if (!onLoadHandler) throw new Error('Catalog page did not register onLoad')
      onLoadHandler(query)
    },
  }
}

describe('M02 route city restore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal('uni', {
      getStorageSync: () => '',
      setStorageSync: () => undefined,
      showToast: () => undefined,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('restores the route city by name from the directory when the shared city is empty', async () => {
    const loadDirectory = vi.fn(async () => directory)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { bindings, load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load({ cityCode: '370500', categoryCode: 'POWER' })
    await flushPromises()

    expect(loadDirectory).toHaveBeenCalledOnce()
    expect(loadCatalog).toHaveBeenCalledWith('370500', 'COMPREHENSIVE')
    expect(useCurrentCityStore().currentCity).toEqual({
      code: '370500',
      name: '东营市',
      provinceCode: '370000',
    })
    expect(bindings.displayCityName.value).toBe('东营市')
    expect(bindings.loadError.value).toBe('')
    expect(bindings.isLoading.value).toBe(false)
  })

  it('does not request the directory when the shared city already matches the route', async () => {
    useCurrentCityStore().setCurrentCity({ code: '370500', name: '东营市' })
    const loadDirectory = vi.fn(async () => directory)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load({ cityCode: '370500' })
    await flushPromises()

    expect(loadDirectory).not.toHaveBeenCalled()
    expect(loadCatalog).toHaveBeenCalledWith('370500', 'COMPREHENSIVE')
  })

  it('keeps the shared city when the route carries another one', async () => {
    useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
    const loadDirectory = vi.fn(async () => directory)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load({ cityCode: '370500' })
    await flushPromises()

    expect(loadDirectory).not.toHaveBeenCalled()
    expect(loadCatalog).toHaveBeenCalledWith('370100', 'COMPREHENSIVE')
    expect(useCurrentCityStore().currentCity?.code).toBe('370100')
  })

  it.each([
    ['no route city', {}],
    ['a route city outside the directory', { cityCode: '999999' }],
  ])('asks for a city instead of blaming the network with %s', async (_name, query) => {
    const loadDirectory = vi.fn(async () => directory)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { bindings, load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load(query)
    await flushPromises()

    expect(loadCatalog).not.toHaveBeenCalled()
    expect(useCurrentCityStore().currentCity).toBeNull()
    expect(bindings.loadError.value).toBe('')
    expect(bindings.isLoading.value).toBe(false)
  })

  it('reports a directory failure and retries it from the reload action', async () => {
    const loadDirectory = vi.fn<[], Promise<CityDirectory>>()
      .mockRejectedValueOnce(new Error('NETWORK_ERROR'))
      .mockResolvedValueOnce(directory)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { bindings, load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load({ cityCode: '370500' })
    await flushPromises()

    expect(bindings.loadError.value).toBe(NETWORK_ERROR)
    expect(loadCatalog).not.toHaveBeenCalled()

    await bindings.loadCatalogContent()

    expect(loadDirectory).toHaveBeenCalledTimes(2)
    expect(loadCatalog).toHaveBeenCalledWith('370500', 'COMPREHENSIVE')
    expect(bindings.loadError.value).toBe('')
    expect(bindings.displayCityName.value).toBe('东营市')
  })

  it('keeps a city chosen by hand while the directory is still loading', async () => {
    const pendingDirectory = deferred<CityDirectory>()
    const loadDirectory = vi.fn(() => pendingDirectory.promise)
    const loadCatalog = vi.fn(async (cityCode: string) => catalogContent(cityCode))
    const { bindings, load } = mountCatalogPage({ loadDirectory, loadCatalog })

    load({ cityCode: '370500' })
    await flushPromises()
    await bindings.applyCitySelection({ city: jinan, source: 'TREE' })
    pendingDirectory.resolve(directory)
    await flushPromises()

    expect(useCurrentCityStore().currentCity?.code).toBe('370100')
    expect(loadCatalog).not.toHaveBeenCalledWith('370500', 'COMPREHENSIVE')
    expect(bindings.content.value.services[0]?.id).toBe('service-370100')
  })
})
