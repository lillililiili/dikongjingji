import type { InspectionServiceDetail } from '../../domain/inspection-service-detail'

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
  ratingAverage: null,
  completedCountLabel: null,
  capabilities: [],
  inspectionItems: [],
  processSteps: [],
  deliverables: [],
  guarantees: [],
  explanations: [],
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
