import { compileScript, parse } from '@vue/compiler-sfc'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'

import * as orderNavigation from '../application/service-flow/inspection-order-navigation'
import * as orderCenterView from '../presentation/orders/order-center-view'
import orderCenterPageSource from '../presentation/orders/pages/InspectionOrderCenterPage.vue?raw'
import * as inspectionRoutes from '../navigation/inspection-routes'

const MINE_URL = '/pages/mine/index'
const MINE_ROUTE = 'pages/mine/index'
const ORDER_CENTER_ROUTE = 'pages/mine/inspection-orders'
const SUBMITTED_ROUTE = 'pages/service/inspection/booking-submitted'
const BOOKING_FLOW_ROUTES = [
  'pages/home/index',
  'pages/service/inspection/catalog',
  'pages/service/inspection/detail',
]

interface OrderCenterBindings {
  returnMine(): void
}

type BackPressHandler = (event: { from: string }) => boolean

interface NavigateBackOptions {
  delta: number
  success(): void
  fail(): void
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve)
  })
}

function mountOrderCenterPage(): {
  bindings: OrderCenterBindings
  pressBack: BackPressHandler
} {
  const filename = 'InspectionOrderCenterPage.vue'
  const compiled = compileScript(parse(orderCenterPageSource, { filename }).descriptor, {
    id: 'inspection-order-center-back-test',
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
    '@dcloudio/uni-app': {
      onBackPress: (handler: BackPressHandler) => {
        backPressHandler = handler
      },
      onLoad: () => undefined,
      onPullDownRefresh: () => undefined,
      onReachBottom: () => undefined,
      onShow: () => undefined,
    },
    '../../../application/service-flow/inspection-order-navigation': orderNavigation,
    // S4-1: 去付款 on a card.
    '../../../application/service-flow/node-payment': { openNodePayment: async () => undefined },
    '../../../data/service-flow/uni-booking-http-client': {
      inspectionOrderApi: { list: async () => ({}) },
    },
    '../order-center-view': orderCenterView,
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
      default?: { setup(props: object, context: object): OrderCenterBindings }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  const bindings = testModule.exports.default.setup({}, {
    expose: () => undefined,
    emit: () => undefined,
  })
  if (!backPressHandler) throw new Error(`${filename} did not register onBackPress`)
  return { bindings, pressBack: backPressHandler }
}

describe('M08 back button', () => {
  const navigateBack = vi.fn<[NavigateBackOptions], void>()
  const switchTab = vi.fn()

  function stubPageStack(routes: string[]): void {
    vi.stubGlobal('getCurrentPages', () => routes.map((route) => ({ route })))
  }

  beforeEach(() => {
    // The runtime reports success even when all it did was reload the page, so the
    // stub does too. Only the page stack check keeps M08 away from that call.
    navigateBack.mockReset()
    navigateBack.mockImplementation((options) => options.success())
    switchTab.mockReset()
    vi.stubGlobal('uni', { navigateBack, switchTab })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('walks the page stack when my tab is right behind M08', async () => {
    stubPageStack([MINE_ROUTE, ORDER_CENTER_ROUTE])
    const { bindings } = mountOrderCenterPage()

    bindings.returnMine()
    await flushPromises()

    expect(navigateBack).toHaveBeenCalledOnce()
    expect(navigateBack.mock.calls[0][0].delta).toBe(1)
    expect(switchTab).not.toHaveBeenCalled()
  })

  it('goes to my tab when a deep link or refresh left M08 alone', async () => {
    stubPageStack([ORDER_CENTER_ROUTE])
    const { bindings } = mountOrderCenterPage()

    bindings.returnMine()
    await flushPromises()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })

  it('goes to my tab when the booking flow is behind M08', async () => {
    stubPageStack([...BOOKING_FLOW_ROUTES, ORDER_CENTER_ROUTE])
    const { bindings } = mountOrderCenterPage()

    bindings.returnMine()
    await flushPromises()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })

  it('goes to my tab when the submitted page is behind M08', async () => {
    stubPageStack([...BOOKING_FLOW_ROUTES, SUBMITTED_ROUTE, ORDER_CENTER_ROUTE])
    const { bindings } = mountOrderCenterPage()

    bindings.returnMine()
    await flushPromises()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })

  it('still goes to my tab when navigateBack fails', async () => {
    stubPageStack([MINE_ROUTE, ORDER_CENTER_ROUTE])
    navigateBack.mockImplementation((options) => options.fail())
    const { bindings } = mountOrderCenterPage()

    bindings.returnMine()
    await flushPromises()

    expect(navigateBack).toHaveBeenCalledOnce()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })

  it('takes the same way out for the App back key when M08 is alone', async () => {
    stubPageStack([ORDER_CENTER_ROUTE])
    const { pressBack } = mountOrderCenterPage()

    expect(pressBack({ from: 'backbutton' })).toBe(true)
    await flushPromises()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })

  it('takes the same way out for the App back key behind the booking flow', async () => {
    stubPageStack([...BOOKING_FLOW_ROUTES, ORDER_CENTER_ROUTE])
    const { pressBack } = mountOrderCenterPage()

    expect(pressBack({ from: 'backbutton' })).toBe(true)
    await flushPromises()

    expect(navigateBack).not.toHaveBeenCalled()
    expect(switchTab).toHaveBeenCalledWith({ url: MINE_URL })
  })
})
