import type { InspectionServiceDetail } from '../../domain/inspection-service-detail'
import type { BookingServiceSnapshot } from '../../domain/service-flow/booking'

export type DetailRuntimeEnvironment = 'development' | 'production'

export const EMPTY_INSPECTION_SERVICE_DETAIL: InspectionServiceDetail = {
  serviceId: '',
  categoryLabel: '',
  title: '',
  subtitle: '',
  heroImageUrl: '',
  priceAmountMinor: null,
  priceUnitLabel: '',
  priceNote: '',
  priceFactors: [],
  cancellationNote: '',
  serviceCityNames: [],
  availability: '',
  primaryAction: '',
  ratingAverage: null,
  completedCountLabel: null,
  provider: null,
  capabilities: [],
  inspectionItems: [],
  processSteps: [],
  deliverables: [],
  guarantees: [],
  reviewCount: null,
  positiveRateLabel: null,
  reviewFilters: [],
  reviews: [],
}

export function resolveInspectionServiceDetail(
  environment: DetailRuntimeEnvironment,
  developmentDetail: InspectionServiceDetail,
): InspectionServiceDetail {
  return environment === 'development'
    ? developmentDetail
    : EMPTY_INSPECTION_SERVICE_DETAIL
}

export function toInspectionServiceDetail(
  snapshot: BookingServiceSnapshot,
): InspectionServiceDetail {
  return {
    serviceId: snapshot.serviceId,
    categoryLabel: snapshot.categoryName,
    title: snapshot.title,
    subtitle: snapshot.summary,
    heroImageUrl: snapshot.coverImageUrl ?? '',
    priceAmountMinor: snapshot.priceMode === 'PROJECT_QUOTE'
      ? null
      : snapshot.priceAmountMinor,
    priceUnitLabel: snapshot.priceUnitLabel
      ? `/${snapshot.priceUnitLabel}`
      : '',
    priceNote: '',
    priceFactors: [],
    cancellationNote: '',
    serviceCityNames: [],
    availability: 'RESERVABLE',
    primaryAction: 'RESERVE',
    ratingAverage: null,
    completedCountLabel: null,
    provider: null,
    capabilities: [],
    inspectionItems: snapshot.inspectionItems.map((item, index) => ({
      code: item.code,
      label: item.label,
      description: '',
      icon: inspectionItemIcon(item.code),
      tone: index % 2 === 0 ? 'orange' : 'green',
      imageUrl: '',
    })),
    processSteps: [],
    deliverables: [],
    guarantees: [],
    reviewCount: null,
    positiveRateLabel: null,
    reviewFilters: [],
    reviews: [],
  }
}

function inspectionItemIcon(code: string): string {
  return {
    POWER_LINE: 'paperplane-filled',
    TOWER: 'flag-filled',
    INSULATOR_FITTINGS: 'settings-filled',
    CORRIDOR_ENV: 'map-filled',
  }[code] ?? 'navigate-filled'
}
