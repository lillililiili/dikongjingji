import type { ApiClient } from '@/core/http/api-client'
import type { OpsChange } from '@/core/ops/ops-common'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-proservice.yaml 1.1.0（1.1.0 加质保期 warrantyText；服务商资料审核在 provider-profile-review）。

export type ProServiceStatus = 'PUBLISHED' | 'UNPUBLISHED'
export type ProServiceType = 'AERIAL' | 'SURVEY' | 'MAINTENANCE' | 'TECH_SUPPORT'
export type ProServicePriceMode = 'PRICE_FROM' | 'PROJECT'
export type ProServiceFacet =
  | 'VIDEO_FOOTAGE' | 'PHOTOS' | 'ORTHOPHOTO' | 'MODEL_3D' | 'ON_SITE' | 'MAIL_IN' | 'REMOTE' | 'STATIONED'
export type ProServiceCategory =
  | 'ENGINEERING' | 'ENERGY' | 'TOURISM' | 'EVENT' | 'MODELING' | 'PARK' | 'TERRAIN' | 'CORRIDOR' | 'GENERAL'
  | 'DRONE' | 'ROBOT' | 'ROBOT_DOG' | 'FLIGHT' | 'SOFTWARE'

export interface ProServiceLine {
  label: string
  detail: string | null
}

export interface OpsProServiceRow {
  serviceId: string
  type: ProServiceType
  categoryCode: ProServiceCategory
  title: string
  providerId: string
  providerName: string | null
  priceMode: ProServicePriceMode
  priceAmountMinor: number | null
  status: ProServiceStatus
  sample: boolean
  sortOrder: number
  cityCount: number
  updatedAt: string
  version: number
}

export interface OpsProService {
  serviceId: string
  status: ProServiceStatus
  version: number
  updatedAt: string
  sample: boolean
  providerId: string
  providerName: string | null
  type: ProServiceType
  categoryCode: ProServiceCategory
  title: string
  summary: string
  priceMode: ProServicePriceMode
  priceAmountMinor: number | null
  priceUnit: string | null
  priceNote: string | null
  coverUrl: string | null
  sortOrder: number
  cities: Array<{ cityCode: string, cityName: string | null }>
  tags: string[]
  facets: ProServiceFacet[]
  capabilities: string[]
  priceRows: ProServiceLine[]
  scopes: string[]
  outputs: ProServiceLine[]
  serviceModes: ProServiceLine[]
  terms: ProServiceLine[]
  steps: string[]
  guarantees: string[]
  /** 质保期（1.1.0），如“维修后质保 90 天”；App 设备维护详情的“保修与收费边界”里显示，没有时为 null。 */
  warrantyText: string | null
}

export interface ProServiceRequest {
  providerId: string
  type: ProServiceType
  categoryCode: ProServiceCategory
  title: string
  summary: string
  priceMode: ProServicePriceMode
  priceAmountMinor: number | null
  priceUnit: string | null
  priceNote: string | null
  coverUrl: string | null
  sortOrder: number
  cityCodes: string[]
  tags: string[]
  facets: ProServiceFacet[]
  capabilities: string[]
  priceRows: Array<{ label: string, detail: string }>
  scopes: string[]
  outputs: Array<{ label: string, detail: string }>
  serviceModes: Array<{ label: string, detail: string }>
  terms: Array<{ label: string, detail: string }>
  steps: string[]
  guarantees: string[]
  warrantyText: string | null
  version?: number
}

export interface ProServiceFilters {
  keyword: string
  type: ProServiceType | ''
  status: ProServiceStatus | ''
}

export function createProServiceApi(http: ApiClient) {
  const base = '/api/v1/ops/pro-services'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    list(filters: ProServiceFilters, page: number, size: number): Promise<Page<OpsProServiceRow>> {
      return http.get<Page<OpsProServiceRow>>(base, {
        keyword: filters.keyword.trim(),
        type: filters.type,
        status: filters.status,
        page,
        size,
      })
    },
    detail(id: string): Promise<OpsProService> {
      return http.get<OpsProService>(one(id))
    },
    create(request: ProServiceRequest): Promise<OpsProService> {
      return http.post<OpsProService>(base, request)
    },
    update(id: string, request: ProServiceRequest, version: number): Promise<OpsProService> {
      return http.put<OpsProService>(one(id), { ...request, version })
    },
    publish(id: string, version: number): Promise<OpsProService> {
      return http.post<OpsProService>(`${one(id)}/publish`, { version })
    },
    offline(id: string, version: number): Promise<OpsProService> {
      return http.post<OpsProService>(`${one(id)}/offline`, { version })
    },
    changes(id: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${one(id)}/changes`)
    },
  }
}

export type ProServiceApi = ReturnType<typeof createProServiceApi>
