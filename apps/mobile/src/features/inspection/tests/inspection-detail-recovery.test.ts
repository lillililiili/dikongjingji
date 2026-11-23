import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'
import type { ComputedRef, Ref } from 'vue'

import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import * as detailActions from '../application/detail/inspection-detail-actions'
import * as inspectionRoutes from '../navigation/inspection-routes'
import * as detailResolver from '../application/detail/resolve-inspection-service-detail'
import { ApiRequestError } from '../data/service-flow/uni-booking-http-client'
import type { InspectionServiceDetail } from '../domain/inspection-service-detail'
import detailPageSource from '../presentation/detail/pages/InspectionServiceDetailPage.vue?raw'

interface DetailRouteQuery {
  serviceId?: string
  cityCode?: string
}

interface DetailPageBindings {
  canReserve: ComputedRef<boolean>
  canRetryLoad: Ref<boolean>
  hasDetail: ComputedRef<boolean>
  loadError: Ref<string>
  recoverFromLoadError(): void
}

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const UNAVAILABLE_COPY = '当前城市暂无这项服务，或服务已下架'
const RETRY_COPY = '服务详情加载失败，请检查网络后重试'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve)
  })
}

function serviceDetail(serviceId: string): InspectionServiceDetail {
  return {
    ...detailResolver.EMPTY_INSPECTION_SERVICE_DETAIL,
    serviceId,
  } as InspectionServiceDetail
}

function mountDetailPage(
  loadDetail: (serviceId: string, cityCode: string) => Promise<InspectionServiceDetail>,
): {
  bindings: DetailPageBindings
  load(query: DetailRouteQuery): void
} {
  const filename = 'InspectionServiceDetailPage.vue'
  const compiled = compileScript(parse(detailPageSource, { filename }).descriptor, {
    id: 'inspection-detail-recovery-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText

  let onLoadHandler: ((query: DetailRouteQuery) => void) | undefined
  const dependencies: Record<string, unknown> = {
    vue: Vue,
    // Step G1: customer service and its preview flag (off: the pages keep their notices).
    '@/features/support/application/support-entry': { openSupportChat: () => undefined },
    '@/features/support/application/support-preview': { SUPPORT_PREVIEW_ENABLED: false },
    // Plan 08 S5-10: the shared pickers and their preview flag (off: the system location picker).
    '@/features/place/application/place-preview': { PLACE_PREVIEW_ENABLED: false },
    '@/features/place/navigation/place-routes': { buildMapPickUrl: () => '' },
    '@/features/place/state/map-pick-result': {
      createMapPickRequestId: () => 'request1',
      useMapPickResultStore: () => ({ take: () => null, put: () => undefined }),
    },
    '@/shared/photo/photo-viewer': { openPhotoViewer: () => undefined },
    '@dcloudio/uni-app': {
      onLoad: (handler: (query: DetailRouteQuery) => void) => {
        onLoadHandler = handler
      },
    },
    '@/shared/city/state/current-city': { useCurrentCityStore },
    '../../../application/detail/inspection-detail-actions': detailActions,
    '../../../navigation/inspection-routes': inspectionRoutes,
    '../../../application/detail/resolve-inspection-service-detail': detailResolver,
    '../../../data/service-flow/uni-booking-http-client': {
      ApiRequestError,
      inspectionServiceDetailApi: { loadDetail },
      platformCityApi: { load: async () => ({ hotCities: [], provinces: [] }) },
    },
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): DetailPageBindings }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  return {
    bindings: testModule.exports.default.setup({}, {
      expose: () => undefined,
      emit: () => undefined,
    }),
    load(query) {
      if (!onLoadHandler) throw new Error(`${filename} did not register onLoad`)
      onLoadHandler(query)
    },
  }
}

describe('M03 reserve button', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it.each([
    ['offers booking when the service has an item to book', [{ code: 'TOWER', label: '杆塔设备巡检', icon: '', tone: 'orange' }], true],
    ['holds booking back when nothing can be booked', [], false],
  ])('%s', async (_name, inspectionItems, expected) => {
    const { bindings, load } = mountDetailPage(async (serviceId) => ({
      ...serviceDetail(serviceId),
      inspectionItems,
    }) as InspectionServiceDetail)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()

    expect(bindings.hasDetail.value).toBe(true)
    expect(bindings.canReserve.value).toBe(expected)
  })

  it('stays held back while the detail is not there', async () => {
    const { bindings, load } = mountDetailPage(() => new Promise(() => undefined))

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()

    expect(bindings.canReserve.value).toBe(false)
  })
})

describe('M03 load failure recovery', () => {
  const navigateBack = vi.fn()
  const redirectTo = vi.fn()

  function stubPageStack(depth: number): void {
    vi.stubGlobal('getCurrentPages', () => Array.from({ length: depth }))
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    navigateBack.mockClear()
    redirectTo.mockClear()
    vi.stubGlobal('uni', { navigateBack, redirectTo })
    stubPageStack(2)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([
    ['the city or the listing no longer offers it', 404, 'COMMON_NOT_FOUND', '资源不存在'],
    ['the link carries an invalid parameter', 400, 'COMMON_INVALID_ARGUMENT', '请求参数无效'],
  ])('sends the user back to the services when %s', async (_name, status, code, message) => {
    const loadDetail = vi.fn(async () => {
      throw new ApiRequestError(message, status, code)
    })
    const { bindings, load } = mountDetailPage(loadDetail)

    load({ serviceId: SERVICE_ID, cityCode: '370200' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(bindings.loadError.value).toBe(UNAVAILABLE_COPY)
    expect(bindings.canRetryLoad.value).toBe(false)
    expect(navigateBack).toHaveBeenCalledOnce()
    expect(loadDetail).toHaveBeenCalledOnce()
  })

  it('opens the catalog when a deep link left nothing to go back to', async () => {
    stubPageStack(1)
    const { bindings, load } = mountDetailPage(vi.fn(async () => {
      throw new ApiRequestError('资源不存在', 404, 'COMMON_NOT_FOUND')
    }))

    load({ serviceId: SERVICE_ID, cityCode: '370200' })
    await flushPromises()
    bindings.recoverFromLoadError()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(redirectTo).toHaveBeenCalledWith({
      url: '/pages/service/inspection/catalog',
    })
  })

  it('leads a link without a service id to the services without requesting one', async () => {
    const loadDetail = vi.fn(async () => serviceDetail(SERVICE_ID))
    const { bindings, load } = mountDetailPage(loadDetail)

    load({ cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()

    expect(loadDetail).not.toHaveBeenCalled()
    expect(bindings.canRetryLoad.value).toBe(false)
    expect(navigateBack).toHaveBeenCalledOnce()
  })

  it.each([
    ['the network fails', new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')],
    ['the server fails', new ApiRequestError('系统繁忙，请稍后重试', 500, 'COMMON_INTERNAL_ERROR')],
    ['a gateway replies without a body', new ApiRequestError('请求失败（502）', 502, null)],
    ['the request is rate limited', new ApiRequestError('请求频繁', 429, 'COMMON_RATE_LIMITED')],
    ['the response cannot be read', new TypeError("Cannot read properties of undefined (reading 'name')")],
  ])('retries the same request when %s', async (_name, failure) => {
    const loadDetail = vi.fn<[string, string], Promise<InspectionServiceDetail>>()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(serviceDetail(SERVICE_ID))
    const { bindings, load } = mountDetailPage(loadDetail)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()

    expect(bindings.loadError.value).toBe(RETRY_COPY)
    expect(bindings.canRetryLoad.value).toBe(true)

    bindings.recoverFromLoadError()
    await flushPromises()

    expect(loadDetail).toHaveBeenCalledTimes(2)
    expect(loadDetail).toHaveBeenLastCalledWith(SERVICE_ID, '370500')
    expect(bindings.hasDetail.value).toBe(true)
    expect(bindings.loadError.value).toBe('')
    expect(navigateBack).not.toHaveBeenCalled()
  })

  it('retries in the city the page loaded, not the one in the route', async () => {
    useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
    const loadDetail = vi.fn<[string, string], Promise<InspectionServiceDetail>>()
      .mockRejectedValueOnce(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce(serviceDetail(SERVICE_ID))
    const { bindings, load } = mountDetailPage(loadDetail)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(loadDetail).toHaveBeenLastCalledWith(SERVICE_ID, '370100')
  })

  it('stops offering a retry once the retry shows the service is gone', async () => {
    const loadDetail = vi.fn<[string, string], Promise<InspectionServiceDetail>>()
      .mockRejectedValueOnce(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR'))
      .mockRejectedValueOnce(new ApiRequestError('资源不存在', 404, 'COMMON_NOT_FOUND'))
    const { bindings, load } = mountDetailPage(loadDetail)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(bindings.canRetryLoad.value).toBe(false)
    expect(bindings.loadError.value).toBe(UNAVAILABLE_COPY)
  })
})
