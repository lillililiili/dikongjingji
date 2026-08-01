export interface DetailCapability {
  code: string
  label: string
  description?: string
  icon: string
  tone: 'orange' | 'green'
  imageUrl?: string
}

export interface DetailProcessStep {
  code: string
  label: string
}

export interface DetailProvider {
  id: string
  name: string
  logoUrl: string
  certificationLabel: string
  teamDescription: string
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
  availability: string
  primaryAction: string
  ratingAverage: number | null
  completedCountLabel: string | null
  provider: DetailProvider | null
  capabilities: readonly DetailCapability[]
  inspectionItems: readonly DetailCapability[]
  processSteps: readonly DetailProcessStep[]
  deliverables: readonly DetailCapability[]
  guarantees: readonly DetailCapability[]
  reviewCount: number | null
  positiveRateLabel: string | null
  reviewFilters: readonly DetailReviewFilter[]
  reviews: readonly DetailReview[]
}
