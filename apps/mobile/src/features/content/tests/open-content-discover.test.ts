import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import { platformCityApi } from '../../inspection/data/service-flow/uni-booking-http-client'

import { openContentDiscover } from '../navigation/open-content-discover'

vi.mock('../../inspection/data/service-flow/uni-booking-http-client', () => ({
  platformCityApi: { load: vi.fn() },
}))

const city = { code: '370100', name: '济南市', provinceCode: '370000' }
const load = vi.mocked(platformCityApi.load)
const switchTab = vi.fn()

describe('returning to discovery from a content link', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.stubGlobal('uni', { switchTab })
  })
  afterEach(() => { vi.unstubAllGlobals() })

  it('restores the city from the directory before opening the tab', async () => {
    load.mockResolvedValue({
      hotCities: [],
      provinces: [{ code: '370000', name: '山东省', pinyin: 'shandong', initials: 'sd',
        cities: [{ ...city, provinceName: '山东省', pinyin: 'jinan', initials: 'jn' }] }],
    })
    await openContentDiscover(city.code)
    expect(useCurrentCityStore().currentCity).toEqual(city)
    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/discover/index' })
  })

  it('retains an existing choice and can return to discovery when the directory is unavailable', async () => {
    const store = useCurrentCityStore()
    store.setCurrentCity(city)
    await openContentDiscover('370500')
    expect(load).not.toHaveBeenCalled()
    expect(store.currentCity).toEqual(city)

    store.clearCurrentCity()
    load.mockRejectedValue(new Error('offline'))
    await openContentDiscover('370500')
    expect(store.currentCity).toBeNull()
    expect(switchTab).toHaveBeenCalledTimes(2)
  })
})
