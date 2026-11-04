import { describe, expect, it, vi } from 'vitest'

import type { CityDirectory, PlatformCity } from '../domain/city-directory'
import type { CurrentCity } from '../state/current-city'
import {
  resolveCurrentCity,
  type ResolveCurrentCityOptions,
} from './resolve-current-city'

const dongying: PlatformCity = {
  code: '370500',
  name: '东营市',
  pinyin: 'dongyingshi',
  initials: 'dys',
  provinceCode: '370000',
  provinceName: '山东省',
}

const directory: CityDirectory = {
  hotCities: [],
  provinces: [{
    code: '370000',
    name: '山东省',
    pinyin: 'shandongsheng',
    initials: 'sds',
    cities: [dongying],
  }],
}

const jinan: CurrentCity = { code: '370100', name: '济南市' }
const fallback: CurrentCity = { code: '370500', name: '默认城市' }

function createOptions(
  overrides: Partial<ResolveCurrentCityOptions> = {},
): ResolveCurrentCityOptions & { store: { city: CurrentCity | null } } {
  const store: { city: CurrentCity | null } = { city: null }
  return {
    store,
    getCurrentCity: () => store.city,
    setCurrentCity: (city) => {
      store.city = city
    },
    locateCityName: vi.fn().mockResolvedValue('东营市'),
    loadDirectory: vi.fn().mockResolvedValue(directory),
    restoreDefaultCity: () => {
      store.city ??= fallback
      return store.city
    },
    ...overrides,
  }
}

describe('resolveCurrentCity', () => {
  it('keeps the chosen city without asking for a location', async () => {
    const options = createOptions()
    options.store.city = jinan

    await expect(resolveCurrentCity(options)).resolves.toBe(jinan)

    expect(options.locateCityName).not.toHaveBeenCalled()
  })

  it('stores the located city when none is set', async () => {
    const options = createOptions()

    await expect(resolveCurrentCity(options)).resolves.toEqual({
      code: '370500',
      name: '东营市',
      provinceCode: '370000',
    })
    expect(options.store.city?.code).toBe('370500')
  })

  it('does not overwrite a city picked while the location was pending', async () => {
    const options = createOptions()
    let finishLocating: (cityName: string) => void = () => undefined
    options.locateCityName = () => new Promise((resolve) => {
      finishLocating = resolve
    })

    const resolving = resolveCurrentCity(options)
    options.store.city = jinan
    finishLocating('东营市')

    await expect(resolving).resolves.toBe(jinan)
    expect(options.store.city).toBe(jinan)
  })

  it('falls back to the default city when location fails', async () => {
    const options = createOptions({
      locateCityName: () => Promise.reject(new Error('denied')),
    })

    await expect(resolveCurrentCity(options)).resolves.toBe(fallback)
  })

  it('falls back to the default city when the located city is not served', async () => {
    const options = createOptions({
      locateCityName: () => Promise.resolve('潍坊市'),
    })

    await expect(resolveCurrentCity(options)).resolves.toBe(fallback)
  })
})
