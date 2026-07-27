export interface DetailCapability {
  code: string
  label: string
  icon: string
  tone: 'orange' | 'green'
}

export interface DetailProcessStep {
  code: string
  label: string
}

export interface DetailExplanation {
  label: string
  value: string
}

export interface DetailReviewFilter {
  code: string
  label: string
  count: number
}

export interface DetailReview {
  id: string
  maskedUserName: string
  rating: number
  date: string
  context: string
  content: string
  imageUrls: readonly string[]
}

export interface InspectionServiceDetail {
  serviceId: string
  categoryLabel: string
  title: string
  subtitle: string
  heroImageUrl: string
  priceAmountMinor: number | null
  priceUnitLabel: string
  priceNote: string
  ratingAverage: number | null
  completedCountLabel: string | null
  capabilities: readonly DetailCapability[]
  inspectionItems: readonly DetailCapability[]
  processSteps: readonly DetailProcessStep[]
  deliverables: readonly DetailCapability[]
  guarantees: readonly DetailCapability[]
  explanations: readonly DetailExplanation[]
  reviewCount: number | null
  positiveRateLabel: string | null
  reviewFilters: readonly DetailReviewFilter[]
  reviews: readonly DetailReview[]
}
