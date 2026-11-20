import { compileScript, parse } from '@vue/compiler-sfc'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'

import * as qualification from '../../../shared/ui/qualification'
import * as orderNavigation from '../application/service-flow/inspection-order-navigation'
import * as planDomain from '../domain/service-flow/plan'
import * as inspectionRoutes from '../navigation/inspection-routes'
import * as planView from '../presentation/plan/plan-confirmation-view'
import * as serviceFlowFormat from '../presentation/shared/service-flow-format'
import planPageSource from '../presentation/plan/pages/InspectionPlanConfirmationPage.vue?raw'

interface PlanBindings {
  goBack(): void
}

type BackPressHandler = (event: { from: string }) => boolean

const ORDER_CENTER_URL = '/pages/mine/inspection-orders?stage=ALL'
const MINE_ROUTE = 'pages/mine/index'
const ORDER_CENTER_ROUTE = 'pages/mine/inspection-orders'
const SUBMITTED_ROUTE = 'pages/service/inspection/booking-submitted'
const PLAN_ROUTE = 'pages/service/inspection/plan-confirmation'

function mountPlanPage(): { bindings: PlanBindings; pressBack: BackPressHandler } {
  const filename = 'InspectionPlanConfirmationPage.vue'
  const compiled = compileScript(parse(planPageSource, { filename }).descriptor, {
    id: 'inspection-plan-back-test',
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
    // R6-4: the provider's 资质已审核 tag.
    '@/shared/ui/qualification': qualification,
    '@dcloudio/uni-app': {
      onBackPress: (handler: BackPressHandler) => {
        backPressHandler = handler
      },
      onLoad: () => undefined,
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
    '../../../domain/service-flow/plan': planDomain,
    '../../../navigation/inspection-routes': inspectionRoutes,
    '../../shared/inspection-cancellation-ui': {},
    '../../shared/service-flow-format': serviceFlowFormat,
    '../plan-confirmation-view': planView,
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): PlanBindings }
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
  return {
    bindings,
    pressBack(event) {
      if (!backPressHandler) throw new Error(`${filename} did not register onBackPress`)
      return backPressHandler(event)
    },
  }
}

describe('Plan confirmation back', () => {
  const navigateBack = vi.fn()
  const redirectTo = vi.fn()

  function stubPageStack(routes: string[]): void {
    vi.stubGlobal('getCurrentPages', () => routes.map((route) => ({ route })))
  }

  beforeEach(() => {
    navigateBack.mockReset()
    redirectTo.mockReset()
    vi.stubGlobal('uni', { navigateBack, redirectTo })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe.each([
    ['top back button', (page: ReturnType<typeof mountPlanPage>) => page.bindings.goBack()],
    ['App back key', (page: ReturnType<typeof mountPlanPage>) => {
      expect(page.pressBack({ from: 'backbutton' })).toBe(true)
    }],
  ])('%s', (_name, press) => {
    it('returns to the submitted page it was opened from', () => {
      stubPageStack([SUBMITTED_ROUTE, PLAN_ROUTE])
      press(mountPlanPage())

      expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
      expect(redirectTo).not.toHaveBeenCalled()
    })

    it('returns to the order center it was opened from', () => {
      stubPageStack([MINE_ROUTE, ORDER_CENTER_ROUTE, PLAN_ROUTE])
      press(mountPlanPage())

      expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
      expect(redirectTo).not.toHaveBeenCalled()
    })

    it('opens the order center when my tab is behind the page', () => {
      stubPageStack([MINE_ROUTE, PLAN_ROUTE])
      press(mountPlanPage())

      expect(redirectTo).toHaveBeenCalledWith({ url: ORDER_CENTER_URL })
      expect(navigateBack).not.toHaveBeenCalled()
    })

    it('opens the order center when the page is alone', () => {
      stubPageStack([PLAN_ROUTE])
      press(mountPlanPage())

      expect(redirectTo).toHaveBeenCalledWith({ url: ORDER_CENTER_URL })
      expect(navigateBack).not.toHaveBeenCalled()
    })
  })

  it('stays out of the way of a navigateBack already under way', () => {
    stubPageStack([SUBMITTED_ROUTE, PLAN_ROUTE])
    const { pressBack } = mountPlanPage()

    expect(pressBack({ from: 'navigateBack' })).toBe(false)
    expect(navigateBack).not.toHaveBeenCalled()
    expect(redirectTo).not.toHaveBeenCalled()
  })
})
