import type { ApiClient } from '@/core/http/api-client'
import type { OpsChange } from '@/core/ops/ops-common'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-inspection-catalog.yaml 1.0.0。

export type InspectionServiceStatus = 'ACTIVE' | 'DISABLED'
export type InspectionCategoryStatus = 'ACTIVE' | 'INACTIVE'
export type InspectionPriceMode = 'STARTING_PRICE' | 'PER_DISTANCE' | 'PER_VISIT' | 'PROJECT_QUOTE'
export type InspectionAvailability = 'AVAILABLE' | 'RESERVABLE' | 'UNAVAILABLE'
export type InspectionPrimaryAction = 'CONSULT' | 'RESERVE' | 'REQUEST_QUOTE' | 'NONE'
export type InspectionServiceMethod = 'UAV' | 'ROBOT'

export interface OpsInspectionCategory {
  code: string
  name: string
  subtitle: string | null
  parentCode: string | null
  sortOrder: number
  status: InspectionCategoryStatus
  activeServiceCount: number
  version: number
  updatedAt: string
}

export interface OpsInspectionServiceRow {
  serviceId: string
  serviceNo: string | null
  title: string
  categoryCode: string | null
  categoryName: string | null
  providerId: string
  providerName: string | null
  priceMode: InspectionPriceMode | null
  startingAmountMinor: number | null
  status: InspectionServiceStatus
  coverUrl: string | null
  cityCount: number
  updatedAt: string
  version: number
}

export interface OpsInspectionItem {
  code: string
  title: string
  description: string | null
  iconKey: string | null
}

export interface OpsInspectionService {
  serviceId: string
  serviceNo: string | null
  status: InspectionServiceStatus
  version: number
  updatedAt: string
  providerId: string
  providerName: string | null
  title: string
  summary: string | null
  categoryCode: string | null
  categoryName: string | null
  coverUrl: string | null
  priceMode: InspectionPriceMode | null
  startingAmountMinor: number | null
  unitCode: string | null
  unitLabel: string | null
  priceNote: string | null
  cancellationNote: string | null
  availability: InspectionAvailability | null
  primaryAction: InspectionPrimaryAction | null
  includesReport: boolean
  serviceMethods: InspectionServiceMethod[]
  cities: Array<{ cityCode: string, cityName: string | null }>
  bookingItems: OpsInspectionItem[]
  priceFactors: OpsInspectionItem[]
  capabilities: OpsInspectionItem[]
  processSteps: OpsInspectionItem[]
  deliverables: OpsInspectionItem[]
  guarantees: OpsInspectionItem[]
  completedCount: number
}

export interface InspectionItemRequest {
  code: string | null
  title: string
  description: string | null
  iconKey: string | null
}

export interface InspectionServiceRequest {
  providerId: string
  title: string
  summary: string | null
  categoryCode: string | null
  coverUrl: string | null
  priceMode: InspectionPriceMode | null
  startingAmountMinor: number | null
  unitCode: string | null
  unitLabel: string | null
  priceNote: string | null
  cancellationNote: string | null
  availability: InspectionAvailability | null
  primaryAction: InspectionPrimaryAction | null
  includesReport: boolean
  serviceMethods: InspectionServiceMethod[]
  cityCodes: string[]
  bookingItems: InspectionItemRequest[]
  priceFactors: InspectionItemRequest[]
  capabilities: InspectionItemRequest[]
  processSteps: InspectionItemRequest[]
  deliverables: InspectionItemRequest[]
  guarantees: InspectionItemRequest[]
  version?: number
}

export interface InspectionServiceFilters {
  keyword: string
  categoryCode: string
  status: InspectionServiceStatus | ''
}

export interface CategoryCreateRequest {
  code: string
  name: string
  subtitle: string | null
  parentCode: string | null
  sortOrder: number
}

export function createInspectionCatalogApi(http: ApiClient) {
  const categories = '/api/v1/ops/inspection/categories'
  const services = '/api/v1/ops/inspection/services'
  const category = (code: string) => `${categories}/${encodeURIComponent(code)}`
  const service = (id: string) => `${services}/${encodeURIComponent(id)}`
  return {
    categories(): Promise<OpsInspectionCategory[]> {
      return http.get<OpsInspectionCategory[]>(categories)
    },
    createCategory(request: CategoryCreateRequest): Promise<OpsInspectionCategory> {
      return http.post<OpsInspectionCategory>(categories, request)
    },
    updateCategory(code: string, name: string, subtitle: string | null, sortOrder: number, version: number):
    Promise<OpsInspectionCategory> {
      return http.put<OpsInspectionCategory>(category(code), { name, subtitle, sortOrder, version })
    },
    changeCategoryStatus(code: string, status: InspectionCategoryStatus, version: number): Promise<OpsInspectionCategory> {
      return http.put<OpsInspectionCategory>(`${category(code)}/status`, { status, version })
    },
    categoryChanges(code: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${category(code)}/changes`)
    },
    list(filters: InspectionServiceFilters, page: number, size: number): Promise<Page<OpsInspectionServiceRow>> {
      return http.get<Page<OpsInspectionServiceRow>>(services, {
        keyword: filters.keyword.trim(),
        categoryCode: filters.categoryCode,
        status: filters.status,
        page,
        size,
      })
    },
    detail(id: string): Promise<OpsInspectionService> {
      return http.get<OpsInspectionService>(service(id))
    },
    create(request: InspectionServiceRequest): Promise<OpsInspectionService> {
      return http.post<OpsInspectionService>(services, request)
    },
    update(id: string, request: InspectionServiceRequest, version: number): Promise<OpsInspectionService> {
      return http.put<OpsInspectionService>(service(id), { ...request, version })
    },
    publish(id: string, version: number): Promise<OpsInspectionService> {
      return http.post<OpsInspectionService>(`${service(id)}/publish`, { version })
    },
    offline(id: string, version: number): Promise<OpsInspectionService> {
      return http.post<OpsInspectionService>(`${service(id)}/offline`, { version })
    },
    changes(id: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${service(id)}/changes`)
    },
  }
}

export type InspectionCatalogApi = ReturnType<typeof createInspectionCatalogApi>
