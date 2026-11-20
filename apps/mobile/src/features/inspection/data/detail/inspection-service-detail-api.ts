import type {
  DetailCapability,
  InspectionServiceDetail,
} from '../../domain/inspection-service-detail'
import { inspectionDetailHeroImage } from '../inspection-service-images'

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

const ASSET_PATHS: Record<string, string> = {
  FEIXIANG_APP_ICON: '/static/app-icons/feixiang-xxxhdpi.png',
  POWER_LINE_IMAGE: '/static/images/inspection/service-power-line.webp',
  THERMAL_IMAGE: '/static/images/inspection/category-thermal.webp',
  INSPECTION_HERO_IMAGE: '/static/images/inspection/inspection-hero-v1.png',
  DEFECT_LIST_IMAGE: '/static/images/inspection/detail-defect-list.png',
  REPORT_COVER_IMAGE: '/static/images/inspection/detail-report-cover.png',
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

function assetPath(
  remote: MediaAssetDto | null | undefined,
  assetCode: string | null | undefined,
  fallback = '',
): string {
  return remote?.url || (assetCode ? ASSET_PATHS[assetCode] : '') || fallback
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
    imageUrl: assetPath(item.image, item.imageAssetCode),
  }))
}

function mapDetail(dto: InspectionServiceDetailDto): InspectionServiceDetail {
  // Without a server cover the head shows the category's picture (炼化厂 for 油气, 光伏板 for 光伏…).
  const fallbackHero = inspectionDetailHeroImage(dto.category.code)
  return {
    serviceId: dto.serviceId,
    categoryCode: dto.category.code,
    categoryLabel: dto.category.name,
    title: dto.name,
    subtitle: dto.summary,
    heroImageUrl: assetPath(dto.coverImage, null, fallbackHero),
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
    serviceCityNames: (dto.serviceCities ?? []).map((city) => city.name),
    availability: dto.availability,
    primaryAction: dto.primaryAction,
    ratingAverage: dto.reviews.average,
    completedCountLabel: dto.completedCount
      ? `已服务${dto.completedCount.toLocaleString()}+`
      : null,
    provider: dto.provider
      ? {
          id: dto.provider.providerId,
          name: dto.provider.name,
          logoUrl: assetPath(
            dto.provider.logo,
            dto.provider.logoAssetCode,
            '/static/app-icons/feixiang-xxxhdpi.png',
          ),
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
        .map((image) => assetPath(image.image, image.assetCode))
        .filter(Boolean),
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
