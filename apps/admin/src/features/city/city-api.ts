import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-city.yaml 1.0.0。

export type CityServiceStatus = 'OPEN' | 'PREPARING' | 'CLOSED'

export const CITY_STATUS_OPTIONS: ReadonlyArray<{ value: CityServiceStatus, label: string, tag: 'success' | 'warning' | 'info', hint: string }> = [
  { value: 'OPEN', label: '已开通', tag: 'success', hint: 'App 城市面板“已开通城市”里能选' },
  { value: 'PREPARING', label: '筹备中', tag: 'warning', hint: 'App 城市面板“筹备中”里能选，先浏览全国商品与线上体验' },
  { value: 'CLOSED', label: '未开通', tag: 'info', hint: 'App 城市面板不列，只能搜索到' },
]

export function cityStatusLabel(status: CityServiceStatus): string {
  return CITY_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? status
}

export function cityStatusTag(status: CityServiceStatus): 'success' | 'warning' | 'info' {
  return CITY_STATUS_OPTIONS.find((option) => option.value === status)?.tag ?? 'info'
}

export interface OpsCity {
  cityCode: string
  cityName: string
  provinceCode: string
  provinceName: string
  serviceStatus: CityServiceStatus
  serviceStatusChangedAt: string | null
  version: number
}

export interface OpsCityStatusChange {
  changeId: string
  fromStatus: CityServiceStatus
  toStatus: CityServiceStatus
  note: string
  operatorName: string
  changedAt: string
}

export interface CityFilters {
  keyword: string
  serviceStatus: CityServiceStatus | ''
}

export const CITY_NOTE_MAX = 200

export function createCityApi(http: ApiClient) {
  return {
    list(filters: CityFilters, page: number, size: number): Promise<Page<OpsCity>> {
      return http.get<Page<OpsCity>>('/api/v1/ops/cities', {
        keyword: filters.keyword.trim(),
        serviceStatus: filters.serviceStatus,
        page,
        size,
      })
    },
    changeStatus(cityCode: string, serviceStatus: CityServiceStatus, note: string, version: number): Promise<OpsCity> {
      return http.put<OpsCity>(`/api/v1/ops/cities/${encodeURIComponent(cityCode)}/service-status`,
        { serviceStatus, note: note.trim(), version })
    },
    changes(cityCode: string): Promise<OpsCityStatusChange[]> {
      return http.get<OpsCityStatusChange[]>(`/api/v1/ops/cities/${encodeURIComponent(cityCode)}/service-status-changes`)
    },
  }
}

export type CityApi = ReturnType<typeof createCityApi>
