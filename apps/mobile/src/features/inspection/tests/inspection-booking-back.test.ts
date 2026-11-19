import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia, storeToRefs } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'

import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import type { BookingServiceSnapshot } from '../domain/service-flow/booking'
import * as bookingDomain from '../domain/service-flow/booking'
import * as inspectionRoutes from '../navigation/inspection-routes'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'

interface BookingRouteQuery {
  serviceId?: string
  cityCode?: string
}

interface BookingPageBindings {
  goBack(): void
}

type LoadServiceSnapshot = (
  serviceId: string,
  cityCode: string,
) => Promise<BookingServiceSnapshot | null>

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const CATALOG_URL = '/pages/service/inspection/catalog'

function detailUrl(cityCode: string): string {
  return `/pages/service/inspection/detail?serviceId=${SERVICE_ID}&cityCode=${cityCode}`
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve)
  })
}

function loadedSnapshot(serviceId: string, cityCode: string) {
  return Promise.resolve({ serviceId, cityCode } as BookingServiceSnapshot)
}

function mountBookingPage(loadServiceSnapshot: LoadServiceSnapshot): {
  bindings: BookingPageBindings
  load(query: BookingRouteQuery): void
} {
  const filename = 'InspectionBookingPage.vue'
  const compiled = compileScript(parse(bookingPageSource, { filename }).descriptor, {
    id: 'inspection-booking-back-test',
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
    // Step G1: customer service and its preview flag (off: the pages keep their notices).
    '@/features/support/application/support-entry': { openSupportChat: () => undefined },
    '@/features/support/application/support-preview': { SUPPORT_PREVIEW_ENABLED: false },
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

describe('M04 back button', () => {
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
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('walks the page stack when M03 is behind this page', async () => {
    stubPageStack(2)
    const { bindings, load } = mountBookingPage(loadedSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.goBack()

    expect(navigateBack).toHaveBeenCalledOnce()
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it('returns to M03 when a deep link or refresh left this page alone', async () => {
    stubPageStack(1)
    const { bindings, load } = mountBookingPage(loadedSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.goBack()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(redirectTo).toHaveBeenCalledWith({ url: detailUrl('370500') })
  })

  it('returns to M03 in the city the page loaded, not the one in the route', async () => {
    stubPageStack(1)
    useCurrentCityStore().setCurrentCity({ code: '370100', name: '济南市' })
    const { bindings, load } = mountBookingPage(loadedSnapshot)

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.goBack()

    expect(redirectTo).toHaveBeenCalledWith({ url: detailUrl('370100') })
  })

  it('returns to M02 when the route names no service', () => {
    stubPageStack(1)
    const { bindings, load } = mountBookingPage(loadedSnapshot)

    load({})
    bindings.goBack()

    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })

  it('returns to M02 when no city is known', async () => {
    stubPageStack(1)
    const { bindings, load } = mountBookingPage(loadedSnapshot)

    load({ serviceId: SERVICE_ID })
    await flushPromises()
    bindings.goBack()

    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })

  it('returns to M02 when the service failed to load', async () => {
    stubPageStack(1)
    const { bindings, load } = mountBookingPage(() => Promise.resolve(null))

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    await flushPromises()
    bindings.goBack()

    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })

  it('returns to M02 while the service is still loading', () => {
    stubPageStack(1)
    const { bindings, load } = mountBookingPage(() => new Promise(() => undefined))

    load({ serviceId: SERVICE_ID, cityCode: '370500' })
    bindings.goBack()

    expect(redirectTo).toHaveBeenCalledWith({ url: CATALOG_URL })
  })
})
