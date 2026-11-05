import { compileScript, parse } from '@vue/compiler-sfc'
import { createPinia, setActivePinia, storeToRefs } from 'pinia'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Vue from 'vue'

import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import * as portalNavigation from '../application/portal/build-portal-navigation'
import * as portalContent from '../application/portal/resolve-portal-content'
import type { InspectionPortalHotService } from '../domain/inspection-portal-content'
import portalPageSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'

interface PortalBindings {
  selectHotService(service: InspectionPortalHotService): void
}

const CITY = { code: '370500', name: '东营市', provinceCode: '370000' }

function hotService(
  categoryCode: InspectionPortalHotService['categoryCode'],
): InspectionPortalHotService {
  return { id: 'service-1', categoryCode, title: '巡检服务', tag: '巡检' }
}

function mountPortalPage(): PortalBindings {
  const filename = 'InspectionPortalPage.vue'
  const compiled = compileScript(parse(portalPageSource, { filename }).descriptor, {
    id: 'inspection-portal-hot-service-test',
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText.replaceAll('import.meta.env', 'testEnv')

  const dependencies: Record<string, unknown> = {
    vue: { ...Vue, onMounted: () => undefined },
    pinia: { storeToRefs },
    '@/shared/city/state/current-city': { useCurrentCityStore },
    '../../../data/preview/inspection-portal-preview': {},
    '../../../data/service-flow/uni-booking-http-client': {
      inspectionCatalogApi: { loadPortal: async () => ({}) },
    },
    '../../../application/portal/build-portal-navigation': portalNavigation,
    '../../../application/portal/resolve-portal-content': portalContent,
  }
  const testRequire = (request: string): unknown => {
    if (request.endsWith('.vue')) return { default: {} }
    const dependency = dependencies[request]
    if (!dependency) throw new Error(`Unexpected SFC dependency: ${request}`)
    return dependency
  }

  const testModule: {
    exports: {
      default?: { setup(props: object, context: object): PortalBindings }
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', 'testEnv', output)
  evaluate(testRequire, testModule, testModule.exports, { DEV: false })
  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }

  return testModule.exports.default.setup({}, {
    expose: () => undefined,
    emit: () => undefined,
  })
}

describe('M01 hot service card', () => {
  const navigateTo = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    useCurrentCityStore().setCurrentCity(CITY)
    navigateTo.mockReset()
    vi.stubGlobal('uni', { navigateTo })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens the detail of the tapped service in the current city', () => {
    mountPortalPage().selectHotService(hotService('POWER'))

    expect(navigateTo).toHaveBeenCalledOnce()
    expect(navigateTo).toHaveBeenCalledWith({
      url: '/pages/service/inspection/detail?serviceId=service-1&cityCode=370500',
    })
  })

  it('does not send a non-power service to the catalog, which would turn it away', () => {
    mountPortalPage().selectHotService(hotService('OIL_GAS'))

    expect(navigateTo.mock.calls[0][0].url).not.toContain('/catalog')
  })
})
