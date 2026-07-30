import { afterEach, describe, expect, it, vi } from 'vitest'

import { locateCurrentCityName } from './uni-city-location'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('native city location adapter', () => {
  it('uses the geocoded App location city name', async () => {
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ success }: {
        success: (result: unknown) => void
      }) => success({ address: { city: ' 济南市 ' } }),
    })

    await expect(locateCurrentCityName()).resolves.toBe('济南市')
  })

  it('rejects a malformed App address without calling string methods on it', async () => {
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ success }: {
        success: (result: unknown) => void
      }) => success({ address: { city: 370100, province: [] } }),
    })

    await expect(locateCurrentCityName()).rejects.toThrow('CITY_NAME_UNAVAILABLE')
  })

  it('keeps manual selection available when the runtime is not App', async () => {
    const getLocation = vi.fn()
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'web' }),
      getLocation,
    })

    await expect(locateCurrentCityName()).rejects.toThrow('CITY_LOCATION_UNSUPPORTED')
    expect(getLocation).not.toHaveBeenCalled()
  })
})
