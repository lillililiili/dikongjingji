import { afterEach, describe, expect, it, vi } from 'vitest'

import { locateCurrentCityName } from './uni-city-location'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('native city location adapter', () => {
  it('uses the geocoded App location city name', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ success }: {
        success: (result: unknown) => void
      }) => success({ address: { city: ' 济南市 ' } }),
    })

    await expect(locateCurrentCityName()).resolves.toBe('济南市')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('rejects a malformed App address without calling string methods on it', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ success }: {
        success: (result: unknown) => void
      }) => success({ address: { city: 370100, province: [] } }),
    })

    await expect(locateCurrentCityName()).rejects.toThrow('CITY_NAME_UNAVAILABLE')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('clears the timeout when native location fails', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ fail }: {
        fail: () => void
      }) => fail(),
    })

    await expect(locateCurrentCityName()).rejects.toThrow(
      'LOCATION_UNAVAILABLE',
    )
    expect(vi.getTimerCount()).toBe(0)
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

  it('times out when the native location callback never settles', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: vi.fn(),
    })

    const locating = locateCurrentCityName()
    const rejection = expect(locating).rejects.toThrow(
      'CITY_LOCATION_TIMEOUT',
    )

    await vi.advanceTimersByTimeAsync(10_000)

    await rejection
  })

  it('ignores a success callback that arrives after the timeout', async () => {
    vi.useFakeTimers()
    let lateSuccess: ((result: unknown) => void) | undefined
    vi.stubGlobal('uni', {
      getAppBaseInfo: () => ({ uniPlatform: 'app' }),
      getLocation: ({ success }: {
        success: (result: unknown) => void
      }) => {
        lateSuccess = success
      },
    })

    const locating = locateCurrentCityName()
    const rejection = expect(locating).rejects.toThrow(
      'CITY_LOCATION_TIMEOUT',
    )
    await vi.advanceTimersByTimeAsync(10_000)
    await rejection

    expect(lateSuccess).toBeTypeOf('function')
    expect(() => lateSuccess?.({
      get address() {
        throw new Error('LATE_RESULT_MUST_NOT_BE_READ')
      },
    })).not.toThrow()
  })
})
