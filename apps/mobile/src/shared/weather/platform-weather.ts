import type { CurrentCity } from '../city/state/current-city'

export type PlatformWeatherCode =
  | 'CLEAR'
  | 'PARTLY_CLOUDY'
  | 'OVERCAST'
  | 'RAIN'
  | 'THUNDERSTORM'
  | 'SNOW'
  | 'FOG'
  | 'UNKNOWN'

export interface PlatformWeatherDto {
  cityCode: string
  cityName: string
  conditionCode: PlatformWeatherCode
  conditionText: string
  temperatureCelsius: number
  observedAt: string
}

export interface PlatformWeatherPresentation {
  available: boolean
  text: string
  iconUrl: string
  conditionCode: PlatformWeatherCode
  observedAt: string | null
}

export interface PlatformWeatherHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}

export interface PlatformWeatherApi {
  loadCurrent(cityCode: string): Promise<PlatformWeatherDto>
  loadCurrentByCityName(cityName: string): Promise<PlatformWeatherDto>
}

const WEATHER_ICON_BY_CODE: Record<PlatformWeatherCode, string> = {
  CLEAR: '/static/images/inspection/platform/weather-clear.png',
  PARTLY_CLOUDY: '/static/images/inspection/platform/weather-partly-cloudy-v2.png',
  OVERCAST: '/static/images/inspection/platform/weather-overcast.png',
  RAIN: '/static/images/inspection/platform/weather-rain.png',
  THUNDERSTORM: '/static/images/inspection/platform/weather-thunderstorm.png',
  SNOW: '/static/images/inspection/platform/weather-snow.png',
  FOG: '/static/images/inspection/platform/weather-fog.png',
  UNKNOWN: '/static/images/inspection/platform/weather-unknown.png',
}

const unavailableWeather = (): PlatformWeatherPresentation => ({
  available: false,
  text: '天气暂不可用',
  iconUrl: WEATHER_ICON_BY_CODE.UNKNOWN,
  conditionCode: 'UNKNOWN',
  observedAt: null,
})

export function toPlatformWeatherPresentation(
  weather: PlatformWeatherDto,
): PlatformWeatherPresentation {
  const conditionText = weather.conditionText.trim()
  const roundedTemperature = Math.round(weather.temperatureCelsius)
  return {
    available: weather.conditionCode !== 'UNKNOWN' && Boolean(conditionText),
    text: conditionText
      ? `${conditionText} ${roundedTemperature}℃`
      : '天气暂不可用',
    iconUrl: WEATHER_ICON_BY_CODE[weather.conditionCode] ?? WEATHER_ICON_BY_CODE.UNKNOWN,
    conditionCode: weather.conditionCode,
    observedAt: weather.observedAt,
  }
}

export function createPlatformWeatherApi(
  http: PlatformWeatherHttpClient,
): PlatformWeatherApi {
  return {
    loadCurrent(cityCode: string): Promise<PlatformWeatherDto> {
      const normalizedCityCode = cityCode.trim()
      if (!/^\d{6}$/.test(normalizedCityCode)) {
        return Promise.reject(new Error('city code must be a six-digit adcode'))
      }
      return http.request<PlatformWeatherDto>({
        method: 'GET',
        url: `/api/v1/platform/weather/current?cityCode=${encodeURIComponent(normalizedCityCode)}`,
      })
    },
    loadCurrentByCityName(cityName: string): Promise<PlatformWeatherDto> {
      const normalizedCityName = cityName.trim()
      if (
        normalizedCityName.length < 2
        || normalizedCityName.length > 40
        || /[/?#&=\u0000-\u001f]/.test(normalizedCityName)
      ) {
        return Promise.reject(new Error('city name is invalid'))
      }
      return http.request<PlatformWeatherDto>({
        method: 'GET',
        url: `/api/v1/platform/weather/current?cityName=${encodeURIComponent(normalizedCityName)}`,
      })
    },
  }
}

export function createCurrentCityWeatherLoader(api: PlatformWeatherApi) {
  return {
    async load(city: CurrentCity | null): Promise<PlatformWeatherPresentation> {
      if (!city) return unavailableWeather()
      try {
        const weather = await api.loadCurrent(city.code)
        return toPlatformWeatherPresentation(weather)
      } catch {
        return unavailableWeather()
      }
    },
  }
}
