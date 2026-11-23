import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia, storeToRefs } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'
import type { Ref } from 'vue'

import type {
  CityDirectory,
  PlatformCity,
} from '../../../shared/city/domain/city-directory'
import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import * as detailActions from '../application/detail/inspection-detail-actions'
import * as detailResolver from '../application/detail/resolve-inspection-service-detail'
import type { BookingServiceSnapshot } from '../domain/service-flow/booking'
import * as bookingDomain from '../domain/service-flow/booking'
import type { InspectionServiceDetail } from '../domain/inspection-service-detail'
import * as inspectionRoutes from '../navigation/inspection-routes'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'
import detailPageSource from '../presentation/detail/pages/InspectionServiceDetailPage.vue?raw'

interface ServiceRouteQuery {
  serviceId?: string
  cityCode?: string
}

interface DetailPageBindings {
  reserveService(): void
}

interface BookingPageBindings {
  isTimePickerVisible: Ref<boolean>
  openTimePicker(): void
}

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'

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

function mountPage<TBindings>(
  source: string,
  filename: string,
  dependencies: Record<string, unknown>,
  emit: (...args: unknown[]) => void = () => undefined,
): {
  bindings: TBindings
  load(query: ServiceRouteQuery): void
} {
  const compiled = compileScript(parse(source, { filename }).descriptor, {
    id: 'inspection-service-route-city-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText

  let onLoadHandler: ((query: ServiceRouteQuery) => void) | undefined
  const sharedDependencies: Record<string, unknown> = {
    vue: Vue,
    // Step G1: customer service and its preview flag (off: the pages keep their notices).
    '@/features/support/application/support-entry': { openSupportChat: () => undefined },
    '@/features/support/application/support-preview': { SUPPORT_PREVIEW_ENABLED: false },
    // Plan 08 S5-6: 收藏 on the bar, off here (the button is a component of its own).
    '@/features/favorite/application/favorite-preview': { FAVORITE_PREVIEW_ENABLED: false },
    pinia: { storeToRefs },
    '@dcloudio/uni-app': {
      onLoad: (handler: (query: ServiceRouteQuery) => void) => {
        onLoadHandler = handler
      },
      onUnload: () => undefined,
    },
    '@/shared/city/state/current-city': { useCurrentCityStore },
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request] ?? sharedDependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): TBindings }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  const bindings = testModule.exports.default.setup({}, {
    expose: () => undefined,
    emit,
  })

  return {
    bindings,
    load(query) {
      if (!onLoadHandler) throw new Error(`${filename} did not register onLoad`)
      onLoadHandler(query)
    },
  }
}

function mountDetailPage(loadDirectory: () => Promise<CityDirectory>) {
  const loadDetail = vi.fn(async (serviceId: string) => ({
    ...detailResolver.EMPTY_INSPECTION_SERVICE_DETAIL,
    serviceId,
  }) as InspectionServiceDetail)
  const emit = vi.fn()
  const page = mountPage<DetailPageBindings>(
    detailPageSource,
    'InspectionServiceDetailPage.vue',
    {
      '../../../application/detail/inspection-detail-actions': detailActions,
      '../../../navigation/inspection-routes': inspectionRoutes,
      '../../../application/detail/resolve-inspection-service-detail': detailResolver,
      '../../../data/service-flow/uni-booking-http-client': {
        inspectionServiceDetailApi: { loadDetail },
        platformCityApi: { load: loadDirectory },
      },
    },
    emit,
  )
  return { ...page, emit, loadDetail }
}

function mountBookingPage(loadDirectory: () => Promise<CityDirectory>) {
  const loadServiceSnapshot = vi.fn(async (serviceId: string, cityCode: string) => ({
    serviceId,
    cityCode,
  }) as BookingServiceSnapshot)
  const page = mountPage<BookingPageBindings>(
    bookingPageSource,
    'InspectionBookingPage.vue',
    {
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
        inspectionBookingApi: { loadServiceSnapshot },
        platformCityApi: { load: loadDirectory },
      },
      '../../../domain/service-flow/booking': bookingDomain,
      '../../../navigation/inspection-routes': inspectionRoutes,
      '@/uni_modules/inspection-document-picker': {},
    },
  )
  return { ...page, loadServiceSnapshot }
}

describe('M03 and M04 route city', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal('uni', {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('M03', () => {
    it('restores the route city by name from the directory when the shared city is empty', async () => {
      const loadDirectory = vi.fn(async () => directory)
      const { load, loadDetail } = mountDetailPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()

      expect(loadDetail).toHaveBeenCalledWith(SERVICE_ID, '370500')
      expect(useCurrentCityStore().currentCity).toEqual({
        code: '370500',
        name: '东营市',
        provinceCode: '370000',
      })
    })

    it('does not request the directory when the shared city matches the route', async () => {
      useCurrentCityStore().setCurrentCity({ code: '370500', name: '东营市' })
      const loadDirectory = vi.fn(async () => directory)
      const { load, loadDetail } = mountDetailPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()

      expect(loadDirectory).not.toHaveBeenCalled()
      expect(loadDetail).toHaveBeenCalledWith(SERVICE_ID, '370500')
    })

    it('keeps the shared city and reserves in it when the route carries another one', async () => {
      const shared = { code: '370100', name: '济南市', provinceCode: '370000' }
      useCurrentCityStore().setCurrentCity(shared)
      const loadDirectory = vi.fn(async () => directory)
      const { bindings, emit, load, loadDetail } = mountDetailPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()
      bindings.reserveService()

      expect(loadDirectory).not.toHaveBeenCalled()
      expect(loadDetail).toHaveBeenCalledWith(SERVICE_ID, '370100')
      expect(useCurrentCityStore().currentCity).toEqual(shared)
      expect(emit).toHaveBeenCalledWith('reserve', {
        type: 'RESERVE_SERVICE',
        serviceId: SERVICE_ID,
        cityCode: '370100',
      })
    })

    it('never stores a route code the directory does not know', async () => {
      const { load, loadDetail } = mountDetailPage(vi.fn(async () => directory))

      load({ serviceId: SERVICE_ID, cityCode: '999999' })
      await flushPromises()

      expect(loadDetail).toHaveBeenCalledWith(SERVICE_ID, '999999')
      expect(useCurrentCityStore().currentCity).toBeNull()
    })

    it('still loads and reserves in the route city when the directory fails', async () => {
      const { bindings, emit, load, loadDetail } = mountDetailPage(
        vi.fn(async () => {
          throw new Error('NETWORK_ERROR')
        }),
      )

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()
      bindings.reserveService()

      expect(loadDetail).toHaveBeenCalledWith(SERVICE_ID, '370500')
      expect(useCurrentCityStore().currentCity).toBeNull()
      expect(emit).toHaveBeenCalledWith('reserve', {
        type: 'RESERVE_SERVICE',
        serviceId: SERVICE_ID,
        cityCode: '370500',
      })
    })

    it('keeps a city chosen while the directory is still loading', async () => {
      const pendingDirectory = deferred<CityDirectory>()
      const { load } = mountDetailPage(vi.fn(() => pendingDirectory.promise))

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()
      useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
      pendingDirectory.resolve(directory)
      await flushPromises()

      expect(useCurrentCityStore().currentCity?.code).toBe('370100')
    })
  })

  describe('M04', () => {
    it('restores the route city by name from the directory when the shared city is empty', async () => {
      const loadDirectory = vi.fn(async () => directory)
      const { load, loadServiceSnapshot } = mountBookingPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()

      expect(loadServiceSnapshot).toHaveBeenCalledWith(SERVICE_ID, '370500')
      expect(useCurrentCityStore().currentCity).toEqual({
        code: '370500',
        name: '东营市',
        provinceCode: '370000',
      })
    })

    it('does not request the directory when the shared city matches the route', async () => {
      useCurrentCityStore().setCurrentCity({ code: '370500', name: '东营市' })
      const loadDirectory = vi.fn(async () => directory)
      const { load, loadServiceSnapshot } = mountBookingPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()

      expect(loadDirectory).not.toHaveBeenCalled()
      expect(loadServiceSnapshot).toHaveBeenCalledWith(SERVICE_ID, '370500')
    })

    it('keeps the shared city and books in it when the route carries another one', async () => {
      const shared = { code: '370100', name: '济南市', provinceCode: '370000' }
      useCurrentCityStore().setCurrentCity(shared)
      const loadDirectory = vi.fn(async () => directory)
      const { load, loadServiceSnapshot } = mountBookingPage(loadDirectory)

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()

      expect(loadDirectory).not.toHaveBeenCalled()
      expect(loadServiceSnapshot).toHaveBeenCalledWith(SERVICE_ID, '370100')
      expect(useCurrentCityStore().currentCity).toEqual(shared)
    })

    it('never stores a route code the directory does not know', async () => {
      const { load, loadServiceSnapshot } = mountBookingPage(
        vi.fn(async () => directory),
      )

      load({ serviceId: SERVICE_ID, cityCode: '999999' })
      await flushPromises()

      expect(loadServiceSnapshot).toHaveBeenCalledWith(SERVICE_ID, '999999')
      expect(useCurrentCityStore().currentCity).toBeNull()
    })

    it('still offers the time picker when the directory fails', async () => {
      const { bindings, load, loadServiceSnapshot } = mountBookingPage(
        vi.fn(async () => {
          throw new Error('NETWORK_ERROR')
        }),
      )

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()
      bindings.openTimePicker()

      expect(loadServiceSnapshot).toHaveBeenCalledWith(SERVICE_ID, '370500')
      expect(useCurrentCityStore().currentCity).toBeNull()
      expect(bindings.isTimePickerVisible.value).toBe(true)
    })

    it('keeps a city chosen while the directory is still loading', async () => {
      const pendingDirectory = deferred<CityDirectory>()
      const { load } = mountBookingPage(vi.fn(() => pendingDirectory.promise))

      load({ serviceId: SERVICE_ID, cityCode: '370500' })
      await flushPromises()
      useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
      pendingDirectory.resolve(directory)
      await flushPromises()

      expect(useCurrentCityStore().currentCity?.code).toBe('370100')
    })

    it('reads time slots for the city the service was loaded in', () => {
      expect(bookingPageSource).toContain(':city-code="service.cityCode"')
    })
  })
})
