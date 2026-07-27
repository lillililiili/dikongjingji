import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  INSPECTION_CATALOG_PATH,
  INSPECTION_PORTAL_PATH,
  buildInspectionCatalogUrl,
} from '../navigation/inspection-routes'
import { ROOT_TABS } from '../../../app-shell/navigation/root-tabs'
import { navigateToInspectionPortal } from '../../../app-shell/navigation/service-entry'
import { useCurrentCityStore } from '../../../shared/city/state/current-city'

describe('inspection route skeleton', () => {
  it('registers the two stable inspection paths', () => {
    expect(INSPECTION_PORTAL_PATH).toBe('/pages/service/inspection/index')
    expect(INSPECTION_CATALOG_PATH).toBe('/pages/service/inspection/catalog')
  })

  it('serializes stable catalog query parameters in route order', () => {
    expect(
      buildInspectionCatalogUrl({
        cityCode: '370500',
        categoryCode: 'POWER',
        keyword: ' 电力 ',
        source: 'CATEGORY',
      }),
    ).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER&keyword=%E7%94%B5%E5%8A%9B&source=CATEGORY',
    )
  })
})

describe('minimal host shell', () => {
  it('keeps the required five root tabs in product order', () => {
    expect(ROOT_TABS.map((tab) => tab.code)).toEqual([
      'HOME',
      'SERVICE',
      'DISCOVER',
      'MALL',
      'MINE',
    ])
  })

  it('opens inspection through the service entry instead of an internal test hook', () => {
    const navigate = vi.fn()

    navigateToInspectionPortal(navigate)

    expect(navigate).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledWith(INSPECTION_PORTAL_PATH)
  })
})

describe('shared city context', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('starts empty and stores one shared city value', () => {
    const store = useCurrentCityStore()

    expect(store.currentCity).toBeNull()

    store.setCurrentCity({
      code: '370500',
      name: '东营',
      provinceCode: '370000',
    })

    expect(store.currentCity?.code).toBe('370500')
  })
})
