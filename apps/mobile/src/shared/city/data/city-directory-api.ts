import type { CityDirectory } from '../domain/city-directory'

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

export function createCityDirectoryApi(http: PlatformCityHttpClient): CityDirectoryApi {
  return {
    load(): Promise<CityDirectory> {
      return http.request<CityDirectory>({
        url: '/api/v1/platform/cities',
        method: 'GET',
      })
    },
    recordSearchSelection(cityCode: string): Promise<void> {
      return http.request<void>({
        url: `/api/v1/platform/cities/${encodeURIComponent(cityCode)}/search-selections`,
        method: 'POST',
      })
    },
  }
}
