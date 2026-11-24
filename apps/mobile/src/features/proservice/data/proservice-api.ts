import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type {
  ProServiceCard,
  ProServiceCategoryView,
  ProServiceCity,
  ProServiceDetail,
  ProServiceFacetGroup,
  ProServiceLine,
  ProServicePage,
  ProServicePrice,
  ProServiceQuery,
  ProServiceType,
  ProviderPage,
  ProviderProfile,
} from '../domain/proservice'
import { isMediaUrl } from '../../../shared/http/media-url'

interface PriceDto {
  mode: ProServicePrice['mode']
  amountMinor: number | null
  unit: string | null
  note: string | null
}

interface CardDto {
  serviceId: string
  type: ProServiceType
  category: ProServiceCategoryView
  title: string
  summary: string
  tags: string[]
  price: PriceDto
  coverUrl: string | null
  sample: boolean
}

interface PageDto {
  type: ProServiceType
  cityCode: string
  cityName: string | null
  cityOpen: boolean
  facetGroup: ProServiceFacetGroup
  page: number
  size: number
  total: number
  hasMore: boolean
  items: CardDto[]
}

interface DetailDto extends Omit<CardDto, 'tags'> {
  capabilities: string[]
  priceRows: ProServiceLine[]
  scope: string[]
  outputs: ProServiceLine[]
  serviceModes: ProServiceLine[]
  terms: ProServiceLine[]
  steps: string[]
  guarantees: string[]
  provider: {
    providerId: string
    name: string
    certificationLabel: string | null
    description: string | null
    qualificationReviewed?: boolean | null
  } | null
  cities: ProServiceCity[]
  warrantyText?: string | null
}

interface ProviderProfileDto {
  legalEntity?: string | null
  serviceModes?: string | null
  authorizationUntil?: string | null
  deliverables?: string[] | null
  localTeamLabel?: string | null
}

interface ProviderPageDto {
  providerId: string
  name: string
  certificationLabel: string | null
  description: string | null
  qualificationReviewed?: boolean | null
  cityCode: string
  inspectionServices: Array<{
    serviceId: string
    title: string
    summary: string
    categoryName: string
    price: PriceDto
  }>
  proServices: CardDto[]
  cities: ProServiceCity[]
  profile?: ProviderProfileDto | null
}

export interface ProServiceApi {
  listServices(query: ProServiceQuery, page: number, size: number): Promise<ProServicePage>
  loadService(serviceId: string, cityCode: string): Promise<ProServiceDetail>
  loadProvider(providerId: string, cityCode: string): Promise<ProviderPage>
}

// The server only hands out HTTPS pictures; this keeps a bad row away from an <image> anyway.
function toCoverUrl(url: string | null): string | null {
  return url && isMediaUrl(url) ? url : null
}

function toPrice(dto: PriceDto): ProServicePrice {
  // A starting price without an amount cannot be shown as one; it reads as 按项目报价 then.
  if (dto.mode === 'PRICE_FROM' && (dto.amountMinor === null || dto.amountMinor <= 0)) {
    return { mode: 'PROJECT', amountMinor: null, unit: null, note: dto.note?.trim() || null }
  }
  return {
    mode: dto.mode,
    amountMinor: dto.mode === 'PRICE_FROM' ? dto.amountMinor : null,
    unit: dto.mode === 'PRICE_FROM' ? dto.unit?.trim() || null : null,
    note: dto.note?.trim() || null,
  }
}

const copyLines = (lines: ProServiceLine[]): ProServiceLine[] =>
  lines.map((line) => ({ label: line.label, detail: line.detail }))

const copyCities = (cities: ProServiceCity[]): ProServiceCity[] =>
  cities.map((city) => ({ cityCode: city.cityCode, cityName: city.cityName }))

export function toProServiceCard(dto: CardDto): ProServiceCard {
  return {
    serviceId: dto.serviceId,
    type: dto.type,
    category: { code: dto.category.code, label: dto.category.label },
    title: dto.title,
    summary: dto.summary,
    tags: [...dto.tags],
    price: toPrice(dto.price),
    coverUrl: toCoverUrl(dto.coverUrl),
    sample: dto.sample,
  }
}

export function toProServiceQueryString(query: ProServiceQuery, page: number, size: number): string {
  const parameters: Array<[string, string | number]> = [
    ['type', query.type],
    ['cityCode', query.cityCode],
  ]
  const keyword = query.keyword.trim()
  if (keyword) parameters.push(['keyword', keyword])
  if (query.facet) parameters.push(['deliverables', query.facet])
  if (query.priceMode) parameters.push(['priceMode', query.priceMode])
  // DEFAULT is the server default, so the common URL stays short.
  if (query.sort !== 'DEFAULT') parameters.push(['sort', query.sort])
  parameters.push(['page', page], ['size', size])
  return parameters.map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`).join('&')
}

export function createProServiceApi(http: PublicApiHttpClient): ProServiceApi {
  return {
    async listServices(query, page, size) {
      const dto = await http.request<PageDto>({
        method: 'GET',
        url: `/api/v1/pro-services?${toProServiceQueryString(query, page, size)}`,
      })
      return {
        type: dto.type,
        cityCode: dto.cityCode,
        cityName: dto.cityName,
        cityOpen: dto.cityOpen,
        facetGroup: {
          label: dto.facetGroup.label,
          options: dto.facetGroup.options.map((option) => ({ code: option.code, label: option.label })),
        },
        hasMore: dto.hasMore,
        total: dto.total,
        items: dto.items.map(toProServiceCard),
      }
    },

    async loadService(serviceId, cityCode) {
      const dto = await http.request<DetailDto>({
        method: 'GET',
        url: `/api/v1/pro-services/${encodeURIComponent(serviceId)}?cityCode=${encodeURIComponent(cityCode)}`,
      })
      return {
        serviceId: dto.serviceId,
        type: dto.type,
        category: { code: dto.category.code, label: dto.category.label },
        title: dto.title,
        summary: dto.summary,
        price: toPrice(dto.price),
        coverUrl: toCoverUrl(dto.coverUrl),
        sample: dto.sample,
        capabilities: [...dto.capabilities],
        priceRows: copyLines(dto.priceRows),
        scope: [...dto.scope],
        outputs: copyLines(dto.outputs),
        serviceModes: copyLines(dto.serviceModes),
        terms: copyLines(dto.terms),
        steps: [...dto.steps],
        guarantees: [...dto.guarantees],
        provider: dto.provider
          ? {
              providerId: dto.provider.providerId,
              name: dto.provider.name,
              certificationLabel: dto.provider.certificationLabel?.trim() || null,
              description: dto.provider.description?.trim() || null,
              // Only an explicit true shows 资质已审核.
              qualificationReviewed: dto.provider.qualificationReviewed === true,
            }
          : null,
        cities: copyCities(dto.cities),
        warrantyText: dto.warrantyText?.trim() || null,
      }
    },

    async loadProvider(providerId, cityCode) {
      const dto = await http.request<ProviderPageDto>({
        method: 'GET',
        url: `/api/v1/providers/${encodeURIComponent(providerId)}?cityCode=${encodeURIComponent(cityCode)}`,
      })
      return {
        providerId: dto.providerId,
        name: dto.name,
        certificationLabel: dto.certificationLabel?.trim() || null,
        description: dto.description?.trim() || null,
        qualificationReviewed: dto.qualificationReviewed === true,
        cityCode: dto.cityCode,
        inspectionServices: dto.inspectionServices.map((service) => ({
          serviceId: service.serviceId,
          title: service.title,
          summary: service.summary,
          categoryName: service.categoryName,
          price: toPrice(service.price),
        })),
        proServices: dto.proServices.map(toProServiceCard),
        cities: copyCities(dto.cities),
        profile: toProviderProfile(dto.profile),
      }
    },
  }
}

// Only a profile the platform approved arrives; blank rows are left out like missing ones.
export function toProviderProfile(dto: ProviderProfileDto | null | undefined): ProviderProfile | null {
  if (!dto) return null
  return {
    legalEntity: dto.legalEntity?.trim() ?? '',
    serviceModes: dto.serviceModes?.trim() ?? '',
    authorizationUntil: dto.authorizationUntil?.trim() || null,
    deliverables: (dto.deliverables ?? []).map((item) => item.trim()).filter(Boolean),
    localTeamLabel: dto.localTeamLabel?.trim() || null,
  }
}
