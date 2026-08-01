import { describe, expect, it, vi } from 'vitest'

import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
  toPlatformWeatherPresentation,
} from './platform-weather'

describe('platform weather presentation', () => {
  it.each([
    ['CLEAR', 'weather-clear.png'],
    ['PARTLY_CLOUDY', 'weather-partly-cloudy-v2.png'],
    ['OVERCAST', 'weather-overcast.png'],
    ['RAIN', 'weather-rain.png'],
    ['THUNDERSTORM', 'weather-thunderstorm.png'],
    ['SNOW', 'weather-snow.png'],
    ['FOG', 'weather-fog.png'],
    ['UNKNOWN', 'weather-unknown.png'],
  ] as const)('maps %s to a stable local icon', (conditionCode, fileName) => {
    const weather = toPlatformWeatherPresentation({
      cityCode: '370100',
      cityName: '济南市',
      conditionCode,
      conditionText: conditionCode === 'UNKNOWN' ? '未知' : '多云',
      temperatureCelsius: 26,
      observedAt: '2026-07-29T02:30:00Z',
    })

    expect(weather.iconUrl).toBe(`/static/images/inspection/platform/${fileName}`)
  })

  it('builds weather text from real API fields without inventing a value', () => {
    expect(toPlatformWeatherPresentation({
      cityCode: '370100',
      cityName: '济南市',
      conditionCode: 'PARTLY_CLOUDY',
      conditionText: '多云',
      temperatureCelsius: 26,
      observedAt: '2026-07-29T02:30:00Z',
    })).toMatchObject({
      available: true,
      text: '多云 26℃',
    })
  })
})

describe('current city weather loading', () => {
  it('resolves the located city name to an adcode and live weather', async () => {
    const request = vi.fn().mockResolvedValue({
      cityCode: '370500',
      cityName: '\u4e1c\u8425\u5e02',
      conditionCode: 'CLEAR',
      conditionText: '\u6674',
      temperatureCelsius: 28,
      observedAt: '2026-07-30T08:00:00Z',
    })
    const api = createPlatformWeatherApi({ request }) as ReturnType<
      typeof createPlatformWeatherApi
    > & {
      loadCurrentByCityName?: (cityName: string) => Promise<unknown>
    }

    expect(api.loadCurrentByCityName).toBeTypeOf('function')
    if (!api.loadCurrentByCityName) return

    await expect(api.loadCurrentByCityName('\u4e1c\u8425\u5e02')).resolves.toMatchObject({
      cityCode: '370500',
      cityName: '\u4e1c\u8425\u5e02',
    })
    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: '/api/v1/platform/weather/current?cityName=%E4%B8%9C%E8%90%A5%E5%B8%82',
    })
  })

  it('queries the selected city code instead of phone weather-app data', async () => {
    const request = vi.fn().mockResolvedValue({
      cityCode: '370100',
      cityName: '济南市',
      conditionCode: 'CLEAR',
      conditionText: '晴',
      temperatureCelsius: 29,
      observedAt: '2026-07-29T03:00:00Z',
    })
    const loader = createCurrentCityWeatherLoader(createPlatformWeatherApi({ request }))

    await expect(loader.load({ code: '370100', name: '济南市' })).resolves.toMatchObject({
      available: true,
      text: '晴 29℃',
    })
    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: '/api/v1/platform/weather/current?cityCode=370100',
    })
  })

  it('returns an explicit unavailable state when weather cannot be queried', async () => {
    const loader = createCurrentCityWeatherLoader(createPlatformWeatherApi({
      request: vi.fn().mockRejectedValue(new Error('provider unavailable')),
    }))

    await expect(loader.load({ code: '370100', name: '济南市' })).resolves.toMatchObject({
      available: false,
      text: '天气暂不可用',
      iconUrl: '/static/images/inspection/platform/weather-unknown.png',
    })
  })

  it('does not call the backend when there is no selected city', async () => {
    const request = vi.fn()
    const loader = createCurrentCityWeatherLoader(createPlatformWeatherApi({ request }))

    await expect(loader.load(null)).resolves.toMatchObject({
      available: false,
      text: '天气暂不可用',
    })
    expect(request).not.toHaveBeenCalled()
  })
})
