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
  // (catalog 1.3.0, R6-4) The platform reviewed the provider's qualification: 资质已审核 in place of the label.
  qualificationReviewed: boolean
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
  /** 类目编码，用来按类目配头图和角标颜色；旧快照里没有 */
  categoryCode?: string
  categoryLabel: string
  title: string
  subtitle: string
  heroImageUrl: string
  priceAmountMinor: number | null
  priceUnitLabel: string
  /** 计价方式文案；为空时“价格与取消”卡不显示该行 */
  priceNote: string
  /** 影响价格的因素，按服务端顺序；为空时不显示该行 */
  priceFactors: readonly string[]
  /** 取消说明，只是展示文案，能否取消仍由服务流程状态机决定 */
  cancellationNote: string
  /** 已开通城市的名称，用于服务商行“可服务城市”；为空时不显示 */
  serviceCityNames: readonly string[]
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
