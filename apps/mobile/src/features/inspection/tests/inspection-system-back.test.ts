import { compileScript, parse } from '@vue/compiler-sfc'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'

import * as orderNavigation from '../application/service-flow/inspection-order-navigation'
import * as planDomain from '../domain/service-flow/plan'
import * as inspectionRoutes from '../navigation/inspection-routes'
import * as fulfillmentView from '../presentation/fulfillment/fulfillment-view'
import submittedPageSource from '../presentation/booking/pages/InspectionBookingSubmittedPage.vue?raw'
import fulfillmentPageSource from '../presentation/fulfillment/pages/InspectionFulfillmentPage.vue?raw'
import * as serviceFlowFormat from '../presentation/shared/service-flow-format'

type BackPressHandler = (event: { from: string }) => boolean

const ORDER_CENTER_URL = '/pages/mine/inspection-orders?stage=ALL'
const MINE_ROUTE = 'pages/mine/index'
const ORDER_CENTER_ROUTE = 'pages/mine/inspection-orders'
const DETAIL_ROUTE = 'pages/service/inspection/detail'
const SUBMITTED_ROUTE = 'pages/service/inspection/booking-submitted'
const FULFILLMENT_ROUTE = 'pages/service/inspection/fulfillment'

function mountPage(source: string, filename: string): BackPressHandler {
  const compiled = compileScript(parse(source, { filename }).descriptor, {
    id: 'inspection-system-back-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText

  let backPressHandler: BackPressHandler | undefined
  const dependencies: Record<string, unknown> = {
    vue: Vue,
    // Step G1: customer service and its preview flag (off: the pages keep their notices).
    '@/features/support/application/support-entry': { openSupportChat: () => undefined },
    '@/features/support/application/support-preview': { SUPPORT_PREVIEW_ENABLED: false },
    '@dcloudio/uni-app': {
      onBackPress: (handler: BackPressHandler) => {
        backPressHandler = handler
      },
      onLoad: () => undefined,
      onShow: () => undefined,
    },
    '../../../application/service-flow/booking-crypto': {},
    '../../../application/service-flow/inspection-order-cancellation': {},
    '../../../application/service-flow/inspection-order-navigation': orderNavigation,
    '../../../data/service-flow/uni-booking-http-client': {
      inspectionBookingApi: {},
      inspectionPlanApi: {},
    },
    // 2026-11-20: the per-category service picture.
    '../../../data/inspection-service-images': { inspectionServiceImage: () => '' },
    '@/shared/city/state/current-city': { useCurrentCityStore: () => ({ currentCity: null }) },
    '../../shared/service-flow-format': serviceFlowFormat,
    '../../../domain/service-flow/plan': planDomain,
    '../../../navigation/inspection-routes': inspectionRoutes,
    '../../shared/inspection-cancellation-ui': {},
    '../fulfillment-view': fulfillmentView,
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): object }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  testModule.exports.default.setup({}, {
    expose: () => undefined,
    emit: () => undefined,
  })
  if (!backPressHandler) throw new Error(`${filename} did not register onBackPress`)
  return backPressHandler
}

describe.each([
  {
    name: 'Submitted page',
    source: submittedPageSource,
    filename: 'InspectionBookingSubmittedPage.vue',
    route: SUBMITTED_ROUTE,
    flowBehind: DETAIL_ROUTE,
  },
  {
    name: 'Fulfillment page',
    source: fulfillmentPageSource,
    filename: 'InspectionFulfillmentPage.vue',
    route: FULFILLMENT_ROUTE,
    flowBehind: SUBMITTED_ROUTE,
  },
])('$name App back key', ({ source, filename, route, flowBehind }) => {
  const navigateBack = vi.fn()
  const redirectTo = vi.fn()

  function stubPageStack(routes: string[]): void {
    vi.stubGlobal('getCurrentPages', () => routes.map((item) => ({ route: item })))
  }

  beforeEach(() => {
    navigateBack.mockReset()
    redirectTo.mockReset()
    vi.stubGlobal('uni', { navigateBack, redirectTo })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens the order center like the top back button, not the booking flow behind it', () => {
    stubPageStack([flowBehind, route])
    const pressBack = mountPage(source, filename)

    expect(pressBack({ from: 'backbutton' })).toBe(true)
    expect(redirectTo).toHaveBeenCalledWith({ url: ORDER_CENTER_URL })
    expect(navigateBack).not.toHaveBeenCalled()
  })

  it('walks back to the order center it was opened from', () => {
    stubPageStack([MINE_ROUTE, ORDER_CENTER_ROUTE, route])
    const pressBack = mountPage(source, filename)

    expect(pressBack({ from: 'backbutton' })).toBe(true)
    expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it('opens the order center when the page is alone', () => {
    stubPageStack([route])
    const pressBack = mountPage(source, filename)

    expect(pressBack({ from: 'backbutton' })).toBe(true)
    expect(redirectTo).toHaveBeenCalledWith({ url: ORDER_CENTER_URL })
  })

  it('stays out of the way of a navigateBack already under way', () => {
    stubPageStack([MINE_ROUTE, ORDER_CENTER_ROUTE, route])
    const pressBack = mountPage(source, filename)

    expect(pressBack({ from: 'navigateBack' })).toBe(false)
    expect(navigateBack).not.toHaveBeenCalled()
    expect(redirectTo).not.toHaveBeenCalled()
  })
})
