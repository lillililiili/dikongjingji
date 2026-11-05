import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia, storeToRefs } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'
import type { Ref } from 'vue'

import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import { createInspectionBookingApi } from '../data/service-flow/inspection-booking-api'
import { ApiRequestError } from '../data/service-flow/uni-booking-http-client'
import type { BookingServiceSnapshot } from '../domain/service-flow/booking'
import * as bookingDomain from '../domain/service-flow/booking'
import * as inspectionRoutes from '../navigation/inspection-routes'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'

interface BookingRouteQuery {
  serviceId?: string
  cityCode?: string
}

interface BookingPageBindings {
  canRetryLoad: Ref<boolean>
  isLoading: Ref<boolean>
  loadError: Ref<string>
  service: Ref<BookingServiceSnapshot | null>
  goBack(): void
  recoverFromLoadError(): void
}

type LoadServiceSnapshot = (
  serviceId: string,
  cityCode: string,
) => Promise<BookingServiceSnapshot | null>

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const CATALOG_URL = '/pages/service/inspection/catalog'
const UNAVAILABLE_COPY = '当前城市暂无这项服务，或服务已下架'
const RETRY_COPY = '服务信息加载失败，请检查网络后重试'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve)
  })
}

function snapshot(serviceId: string, cityCode: string): BookingServiceSnapshot {
  return { serviceId, cityCode } as BookingServiceSnapshot
}

function mountBookingPage(loadServiceSnapshot: LoadServiceSnapshot): {
  bindings: BookingPageBindings
  load(query: BookingRouteQuery): void
} {
  const filename = 'InspectionBookingPage.vue'
  const compiled = compileScript(parse(bookingPageSource, { filename }).descriptor, {
    id: 'inspection-booking-recovery-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText

  let onLoadHandler: ((query: BookingRouteQuery) => void) | undefined
  const dependencies: Record<string, unknown> = {
    vue: Vue,
    pinia: { storeToRefs },
    '@dcloudio/uni-app': {
      onLoad: (handler: (query: BookingRouteQuery) => void) => {
        onLoadHandler = handler
      },
      onUnload: () => undefined,
    },
    '@/shared/city/state/current-city': { useCurrentCityStore },
    '@/uni_modules/inspection-document-picker': {},
    '../../../application/service-flow/submit-booking-reservation': {
      createBookingSubmissionSession: () => ({}),
    },
    '../../../application/service-flow/booking-crypto': {
      createBookingFileHashPort: () => ({}),
    },
    '../../../application/service-flow/booking-attachment-selection': {},
    '../../../data/service-flow/booking-file-access': {},
    '../../../data/service-flow/uni-booking-document-picker': {
      createUniBookingDocumentPicker: () => ({}),
    },
    '../../../data/service-flow/uni-booking-http-client': {
      ApiRequestError,
      inspectionBookingApi: { loadServiceSnapshot },
      platformCityApi: { load: async () => ({ hotCities: [], provinces: [] }) },
    },
    '../../../domain/service-flow/booking': bookingDomain,
    '../../../navigation/inspection-routes': inspectionRoutes,
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): BookingPageBindings }
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

describe('M04 load failure recovery', () => {
  const navigateBack = vi.fn()
  const redirectTo = vi.fn()

  function stubPageStack(depth: number): void {
    vi.stubGlobal('getCurrentPages', () => Array.from({ length: depth }, () => ({})))
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    navigateBack.mockClear()
    redirectTo.mockClear()
    vi.stubGlobal('uni', { navigateBack, redirectTo })
    stubPageStack(3)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('tells the user the service is unavailable when the list does not have it', async () => {
    const loadSnapshot = vi.fn(async () => null)
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370200' })
    await flushPromises()

    expect(bindings.loadError.value).toBe(UNAVAILABLE_COPY)
    expect(bindings.canRetryLoad.value).toBe(false)
    expect(bindings.service.value).toBeNull()
  })

  it.each([
    ['the city code is invalid', 400, 'COMMON_INVALID_ARGUMENT', '请求参数无效'],
    ['the city is not open', 404, 'INS_CATALOG_CITY_NOT_ENABLED', '当前城市暂未开通巡检服务'],
    ['a gateway asks for a login', 401, 'COMMON_UNAUTHORIZED', '请先登录'],
    ['a gateway refuses access', 403, 'COMMON_FORBIDDEN', '无权限访问'],
  ])('offers no retry when %s', async (_name, status, code, message) => {
    const loadSnapshot = vi.fn(async () => {
      throw new ApiRequestError(message, status, code)
    })
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(bindings.loadError.value).toBe(UNAVAILABLE_COPY)
    expect(bindings.canRetryLoad.value).toBe(false)
    expect(loadSnapshot).toHaveBeenCalledOnce()
  })

  it.each([
    ['the network fails', new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')],
    ['the server fails', new ApiRequestError('系统繁忙，请稍后重试', 500, 'COMMON_INTERNAL_ERROR')],
    ['a gateway fails', new ApiRequestError('请求失败（502）', 502, null)],
    ['requests are rate limited', new ApiRequestError('请求过于频繁', 429, 'COMMON_RATE_LIMITED')],
    ['the response cannot be read', new TypeError("Cannot read properties of undefined (reading 'find')")],
  ])('offers a retry and hides the raw message when %s', async (_name, failure) => {
    const { bindings, load } = mountBookingPage(async () => {
      throw failure
    })

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()

    expect(bindings.loadError.value).toBe(RETRY_COPY)
    expect(bindings.canRetryLoad.value).toBe(true)
  })

  it('requests the same service again and shows the form once it loads', async () => {
    const loadSnapshot = vi.fn<Parameters<LoadServiceSnapshot>, ReturnType<LoadServiceSnapshot>>()
      .mockRejectedValueOnce(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR'))
      .mockImplementationOnce(async (serviceId, cityCode) => snapshot(serviceId, cityCode))
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    expect(bindings.isLoading.value).toBe(true)
    await flushPromises()

    expect(loadSnapshot).toHaveBeenCalledTimes(2)
    expect(loadSnapshot).toHaveBeenLastCalledWith(SERVICE_ID, '370500')
    expect(bindings.service.value?.serviceId).toBe(SERVICE_ID)
    expect(bindings.loadError.value).toBe('')
    expect(navigateBack).not.toHaveBeenCalled()
  })

  it('keeps the shared city on a retry, as the first request did', async () => {
    useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
    const loadSnapshot = vi.fn<Parameters<LoadServiceSnapshot>, ReturnType<LoadServiceSnapshot>>()
      .mockRejectedValue(new ApiRequestError('系统繁忙，请稍后重试', 500, 'COMMON_INTERNAL_ERROR'))
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(loadSnapshot.mock.calls).toEqual([
      [SERVICE_ID, '370100'],
      [SERVICE_ID, '370100'],
    ])
  })

  it('stays retryable when the retry fails too', async () => {
    const loadSnapshot = vi.fn(async () => {
      throw new ApiRequestError('系统繁忙，请稍后重试', 500, 'COMMON_INTERNAL_ERROR')
    })
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(loadSnapshot).toHaveBeenCalledTimes(2)
    expect(bindings.loadError.value).toBe(RETRY_COPY)
    expect(bindings.canRetryLoad.value).toBe(true)
  })

  it('stops offering a retry once a retry finds the service gone', async () => {
    const loadSnapshot = vi.fn<Parameters<LoadServiceSnapshot>, ReturnType<LoadServiceSnapshot>>()
      .mockRejectedValueOnce(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce(null)
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()
    await flushPromises()

    expect(bindings.loadError.value).toBe(UNAVAILABLE_COPY)
    expect(bindings.canRetryLoad.value).toBe(false)
  })

  it('skips M03 on the way out, because M03 would offer the same service again', async () => {
    const { bindings, load } = mountBookingPage(async () => null)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()

    expect(navigateBack).toHaveBeenCalledWith({ delta: 2 })
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it.each([
    ['M03 is the only page behind', 2],
    ['a deep link left this page alone', 1],
  ])('opens the catalog when %s', async (_name, depth) => {
    stubPageStack(depth)
    const { bindings, load } = mountBookingPage(async () => null)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.recoverFromLoadError()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })

  it('leaves the top back as it was while a retry is on offer', async () => {
    const { bindings, load } = mountBookingPage(async () => {
      throw new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')
    })

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.goBack()

    expect(navigateBack).toHaveBeenCalledWith()
  })

  it.each([
    ['service id', { cityCode: '370500' }, '未找到要预约的巡检服务'],
    ['city', { serviceId: SERVICE_ID }, '请从巡检服务页面进入并选择服务城市'],
  ])('leads a link without a %s to the services without requesting one', async (_name, query, copy) => {
    stubPageStack(1)
    const loadSnapshot = vi.fn(async () => null)
    const { bindings, load } = mountBookingPage(loadSnapshot)

    load(query)
    await flushPromises()
    bindings.recoverFromLoadError()

    expect(bindings.loadError.value).toBe(copy)
    expect(bindings.canRetryLoad.value).toBe(false)
    expect(loadSnapshot).not.toHaveBeenCalled()
    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })
})

describe('booking service snapshot', () => {
  it('answers null instead of an internal code when the list lacks the service', async () => {
    const request = vi.fn(async () => ({ items: [] }))
    const api = createInspectionBookingApi({ request } as never)

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370200')).resolves.toBeNull()
  })
})
