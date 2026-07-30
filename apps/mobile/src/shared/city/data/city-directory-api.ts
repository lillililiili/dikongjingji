import type {
  CityDirectory,
  PlatformCity,
  PlatformProvince,
} from '../domain/city-directory'

export interface PlatformCityHttpClient {
  request<T>(input: {
    url: string
    method: 'GET' | 'POST'
  }): Promise<T>
}

export interface CityDirectoryApi {
  load(): Promise<CityDirectory>
  recordSearchSelection(cityCode: string): Promise<void>
}

function invalidDirectory(): never {
  throw new Error('CITY_DIRECTORY_INVALID')
}

function asRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalidDirectory()
  return value as Record<string, unknown>
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string') invalidDirectory()
  return value
}

function mapCity(value: unknown): PlatformCity {
  const city = asRecord(value)
  return {
    code: requiredString(city, 'code'),
    name: requiredString(city, 'name'),
    pinyin: requiredString(city, 'pinyin'),
    initials: requiredString(city, 'initials'),
    provinceCode: requiredString(city, 'provinceCode'),
    provinceName: requiredString(city, 'provinceName'),
  }
}

function mapProvince(value: unknown): PlatformProvince {
  const province = asRecord(value)
  if (!Array.isArray(province.cities)) invalidDirectory()
  return {
    code: requiredString(province, 'code'),
    name: requiredString(province, 'name'),
    pinyin: requiredString(province, 'pinyin'),
    initials: requiredString(province, 'initials'),
    cities: province.cities.map(mapCity),
  }
}

function mapDirectory(value: unknown): CityDirectory {
  const directory = asRecord(value)
  if (!Array.isArray(directory.hotCities) || !Array.isArray(directory.provinces)) {
    invalidDirectory()
  }
  return {
    hotCities: directory.hotCities.map(mapCity),
    provinces: directory.provinces.map(mapProvince),
  }
}

export function createCityDirectoryApi(http: PlatformCityHttpClient): CityDirectoryApi {
  return {
    async load(): Promise<CityDirectory> {
      const response = await http.request<unknown>({
        url: '/api/v1/platform/cities',
        method: 'GET',
      })
      return mapDirectory(response)
    },
    recordSearchSelection(cityCode: string): Promise<void> {
      return http.request<void>({
        url: `/api/v1/platform/cities/${encodeURIComponent(cityCode)}/search-selections`,
        method: 'POST',
      })
    },
  }
}
