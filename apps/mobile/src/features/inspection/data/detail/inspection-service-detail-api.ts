import { resolveBusinessMediaUrl } from '../../../../shared/http/business-media'
import type {
  DetailCapability,
  InspectionServiceDetail,
} from '../../domain/inspection-service-detail'

export interface InspectionServiceDetailHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}

interface MediaAssetDto {
  url: string
  alt?: string | null
}

interface DetailItemDto {
  code: string
  name: string
  description?: string | null
  iconKey?: string | null
  image?: MediaAssetDto | null
  imageAssetCode?: string | null
}

interface InspectionServiceDetailDto {
  serviceId: string
  serviceNo: string
  category: { code: string; name: string }
  name: string
  summary: string
  coverImage: MediaAssetDto | null
  price: {
    mode: string
    startingAmountMinor?: number | null
    currency: string
    unitCode?: string | null
    unitLabel?: string | null
  }
  priceNote?: string | null
  priceFactors?: string[] | null
  cancellationNote?: string | null
  serviceCities?: Array<{ code: string; name: string }> | null
  availability: string
  primaryAction: string
  completedCount?: number
  demonstrationMetrics?: boolean
  provider: {
    providerId: string
    name: string
    logo: MediaAssetDto | null
    logoAssetCode?: string | null
    certificationLabel?: string | null
    qualificationReviewed?: boolean | null
    teamDescription?: string | null
  } | null
  capabilities: DetailItemDto[]
  inspectionItems: DetailItemDto[]
  processSteps: DetailItemDto[]
  deliverables: DetailItemDto[]
  guarantees: DetailItemDto[]
  reviews: {
    average: number | null
    total: number
    positiveRate: number | null
    tags: Array<{ code: string; name: string; count: number }>
    items: Array<{
      id: string
      maskedUserName: string
      rating: number
      date: string
      content: string
      imageAssets: Array<{
        image: MediaAssetDto | null
        assetCode?: string | null
      }>
    }>
  }
}

const ICONS: Record<string, string> = {
  checkbox: 'checkbox-filled',
  tower: 'map-pin-ellipse',
  'power-line': 'paperplane-filled',
  insulator: 'settings-filled',
  shield: 'auth-filled',
  image: 'image-filled',
  list: 'list',
  compose: 'compose',
  staff: 'staff-filled',
  auth: 'auth-filled',
  locked: 'locked-filled',
}

function mapDetailItems(
  items: DetailItemDto[],
  fallbackTone: DetailCapability['tone'] = 'orange',
): DetailCapability[] {
  return items.map((item, index) => ({
    code: item.code,
    label: item.name,
    description: item.description ?? '',
    icon: ICONS[item.iconKey ?? ''] ?? 'checkbox-filled',
    tone: index % 2 === 0 ? fallbackTone : 'green',
    imageUrl: resolveBusinessMediaUrl(item.image?.url) ?? '',
  }))
}

function mapDetail(dto: InspectionServiceDetailDto): InspectionServiceDetail {
  return {
    serviceId: dto.serviceId,
    categoryCode: dto.category.code,
    categoryLabel: dto.category.name,
    title: dto.name,
    subtitle: dto.summary,
    heroImageUrl: resolveBusinessMediaUrl(dto.coverImage?.url) ?? '',
    priceAmountMinor: dto.price.mode === 'PROJECT_QUOTE'
      ? null
      : dto.price.startingAmountMinor ?? null,
    // "/公里起": the unit when the server has one, and always 起 for a listed price.
    priceUnitLabel: dto.price.mode === 'PROJECT_QUOTE'
      ? ''
      : `${dto.price.unitLabel ? `/${dto.price.unitLabel}` : ''}起`,
    priceNote: dto.priceNote ?? '',
    // T03 迁移前的存量数据这三项都是 null，页面按“没有配置”隐藏
    priceFactors: (dto.priceFactors ?? []).filter((factor) => factor.trim() !== ''),
    cancellationNote: dto.cancellationNote ?? '',
    serviceCities: (dto.serviceCities ?? []).map(({ code, name }) => ({ code, name })),
    availability: dto.availability,
    primaryAction: dto.primaryAction,
    ratingAverage: dto.reviews.average,
    demonstrationMetrics: dto.demonstrationMetrics === true,
    completedCountLabel: typeof dto.completedCount === 'number'
      ? `已完成 ${dto.completedCount.toLocaleString('zh-CN')} 单`
      : null,
    provider: dto.provider
      ? {
          id: dto.provider.providerId,
          name: dto.provider.name,
          logoUrl: resolveBusinessMediaUrl(dto.provider.logo?.url) ?? '',
          certificationLabel: dto.provider.certificationLabel ?? '',
          // Only an explicit true shows 资质已审核.
          qualificationReviewed: dto.provider.qualificationReviewed === true,
          teamDescription: dto.provider.teamDescription ?? '',
        }
      : null,
    capabilities: mapDetailItems(dto.capabilities, 'green'),
    inspectionItems: mapDetailItems(dto.inspectionItems),
    processSteps: dto.processSteps.map((step) => ({
      code: step.code,
      label: step.name,
    })),
    deliverables: mapDetailItems(dto.deliverables),
    guarantees: mapDetailItems(dto.guarantees),
    reviewCount: dto.reviews.total,
    positiveRateLabel: dto.reviews.positiveRate === null
      ? null
      : `${dto.reviews.positiveRate}%好评`,
    reviewFilters: dto.reviews.tags.map((tag) => ({
      code: tag.code,
      label: tag.name,
      count: tag.count,
    })),
    reviews: dto.reviews.items.map((review) => ({
      id: review.id,
      maskedUserName: review.maskedUserName,
      rating: review.rating,
      date: review.date,
      context: dto.name,
      content: review.content,
      imageUrls: review.imageAssets
        .flatMap(({ image }) => {
          const url = resolveBusinessMediaUrl(image?.url)
          return url ? [url] : []
        }),
    })),
  }
}

export function createInspectionServiceDetailApi(
  http: InspectionServiceDetailHttpClient,
) {
  return {
    async loadDetail(
      serviceId: string,
      cityCode: string,
    ): Promise<InspectionServiceDetail> {
      const detail = await http.request<InspectionServiceDetailDto>({
        url: `/api/v1/inspection/services/${encodeURIComponent(serviceId)}?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
      })
      return mapDetail(detail)
    },
  }
}
