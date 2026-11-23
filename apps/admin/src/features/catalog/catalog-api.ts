import { newIdempotencyKey, type ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-catalog.yaml 1.0.0（商品、规格、在售供给、商品图片）。金额都是分。

export type MallCategory = 'DRONE' | 'ROBOT' | 'PARTS' | 'RENTAL' | 'COURSE'
export type MallSaleMode = 'BUY' | 'RENT' | 'INQUIRY'
export type MallExperienceBadge = 'ONLINE_EXPERIENCE' | 'VENUE_DISPLAY'
export type MallMerchantKind = 'FACTORY_DIRECT' | 'AUTHORIZED_DEALER' | 'LOCAL_STORE'
export type MallPriceUnit = 'ITEM' | 'DAY' | 'HALF_DAY'
export type OpsProductStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type OpsSkuStatus = 'ACTIVE' | 'DISABLED'
export type OpsOfferStatus = 'ACTIVE' | 'DISABLED'
export type OpsProductStatusAction = 'PUBLISH' | 'UNPUBLISH'
export type OpsOfferStatusAction = 'ACTIVATE' | 'DEACTIVATE'
export type OpsIntroBlockType = 'TEXT' | 'IMAGE'
export type OpsPublishBlocker = 'SUMMARY_MISSING' | 'NO_ACTIVE_SKU' | 'NO_ACTIVE_OFFER'
export type OpsCatalogAction =
  | 'PRODUCT_CREATE' | 'PRODUCT_UPDATE' | 'PRODUCT_PUBLISH' | 'PRODUCT_UNPUBLISH' | 'SKU_CREATE' | 'SKU_UPDATE'
  | 'OFFER_CREATE' | 'OFFER_UPDATE' | 'OFFER_PRICE_CHANGE' | 'OFFER_ACTIVATE' | 'OFFER_DEACTIVATE' | 'IMPORT'

type Option<T extends string> = { value: T, label: string }

export const CATEGORY_OPTIONS: ReadonlyArray<Option<MallCategory>> = [
  { value: 'DRONE', label: '无人机' },
  { value: 'ROBOT', label: '机器人' },
  { value: 'PARTS', label: '配件' },
  { value: 'RENTAL', label: '租赁' },
  { value: 'COURSE', label: '课程' },
]

export const SALE_MODE_OPTIONS: ReadonlyArray<Option<MallSaleMode>> = [
  { value: 'BUY', label: '标价购买' },
  { value: 'RENT', label: '租赁' },
  { value: 'INQUIRY', label: '询价' },
]

export const BADGE_OPTIONS: ReadonlyArray<Option<MallExperienceBadge>> = [
  { value: 'ONLINE_EXPERIENCE', label: '可线上体验' },
  { value: 'VENUE_DISPLAY', label: '场馆有展示' },
]

export const MERCHANT_KIND_OPTIONS: ReadonlyArray<Option<MallMerchantKind>> = [
  { value: 'FACTORY_DIRECT', label: '厂家直营' },
  { value: 'AUTHORIZED_DEALER', label: '授权经销' },
  { value: 'LOCAL_STORE', label: '本地门店' },
]

export const PRICE_UNIT_OPTIONS: ReadonlyArray<Option<MallPriceUnit>> = [
  { value: 'ITEM', label: '每件' },
  { value: 'DAY', label: '每天' },
  { value: 'HALF_DAY', label: '每半天' },
]

export const PRODUCT_STATUS_OPTIONS: ReadonlyArray<Option<OpsProductStatus> & { tag: 'info' | 'success' | 'warning' }> = [
  { value: 'DRAFT', label: '草稿', tag: 'info' },
  { value: 'PUBLISHED', label: '已上架', tag: 'success' },
  { value: 'OFFLINE', label: '已下架', tag: 'warning' },
]

export const BLOCKER_LABELS: Record<OpsPublishBlocker, string> = {
  SUMMARY_MISSING: '缺一句话介绍',
  NO_ACTIVE_SKU: '没有启用的规格',
  NO_ACTIVE_OFFER: '没有启用的供给',
}

export const ACTION_LABELS: Record<OpsCatalogAction, string> = {
  PRODUCT_CREATE: '新建商品',
  PRODUCT_UPDATE: '修改商品资料',
  PRODUCT_PUBLISH: '上架',
  PRODUCT_UNPUBLISH: '下架',
  SKU_CREATE: '新建规格',
  SKU_UPDATE: '修改规格',
  OFFER_CREATE: '新建供给',
  OFFER_UPDATE: '修改供给',
  OFFER_PRICE_CHANGE: '改价',
  OFFER_ACTIVATE: '启用供给',
  OFFER_DEACTIVATE: '停用供给',
  IMPORT: '批量导入',
}

function labelOf<T extends string>(options: ReadonlyArray<Option<T>>, value: T | null | undefined): string {
  if (!value) return '—'
  return options.find((option) => option.value === value)?.label ?? value
}

export const categoryLabel = (value: MallCategory) => labelOf(CATEGORY_OPTIONS, value)
export const saleModeLabel = (value: MallSaleMode) => labelOf(SALE_MODE_OPTIONS, value)
export const merchantKindLabel = (value: MallMerchantKind) => labelOf(MERCHANT_KIND_OPTIONS, value)
export const priceUnitLabel = (value: MallPriceUnit) => labelOf(PRICE_UNIT_OPTIONS, value)

export function productStatus(status: OpsProductStatus): { label: string, tag: 'info' | 'success' | 'warning' } {
  return PRODUCT_STATUS_OPTIONS.find((option) => option.value === status) ?? { label: status, tag: 'info' }
}

/** 交易方式能用哪些价格单位：购买和询价只能按件，租赁只能按天或半天（和后端一致）。 */
export function priceUnitsFor(saleMode: MallSaleMode): MallPriceUnit[] {
  return saleMode === 'RENT' ? ['DAY', 'HALF_DAY'] : ['ITEM']
}

/** 询价商品不标价。 */
export function priceAllowed(saleMode: MallSaleMode): boolean {
  return saleMode !== 'INQUIRY'
}

export const CATALOG_LIMITS = {
  keyword: 40,
  name: 160,
  summary: 300,
  sellingPoints: 5,
  sellingPoint: 12,
  specs: 30,
  specLabel: 40,
  specValue: 200,
  packItems: 30,
  packName: 80,
  packQuantity: 999,
  gallery: 9,
  introBlocks: 30,
  introText: 1000,
  url: 500,
  sortOrder: 9999,
  skus: 20,
  skuName: 80,
  priceMinor: 10_000_000_000,
  stock: 999_999,
  shippingFeeMinor: 10_000_000,
  pickupPoint: 120,
  note: 200,
  fulfilledFrom: 120,
  mediaBytes: 5 * 1024 * 1024,
} as const

export const MEDIA_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const

export interface OpsProductSummary {
  productId: string
  category: MallCategory
  name: string
  saleMode: MallSaleMode
  status: OpsProductStatus
  coverUrl: string | null
  skuCount: number
  activeOfferCount: number
  updatedAt: string
  version: number
}

export interface OpsProduct {
  productId: string
  category: MallCategory
  name: string
  summary: string | null
  coverUrl: string | null
  saleMode: MallSaleMode
  experienceBadge: MallExperienceBadge | null
  sortOrder: number
  status: OpsProductStatus
  createdAt: string
  updatedAt: string
  version: number
}

export interface OpsSpec {
  label: string
  value: string
}

export interface OpsPackItem {
  name: string
  quantity: number
}

export interface OpsIntroBlock {
  type: OpsIntroBlockType
  text: string | null
  imageUrl: string | null
}

export interface OpsSku {
  skuId: string
  name: string
  sortOrder: number
  status: OpsSkuStatus
  version: number
}

export interface OpsOffer {
  offerId: string
  skuId: string
  skuName: string
  merchantId: string
  merchantName: string
  merchantKind: MallMerchantKind
  cityCode: string | null
  cityName: string | null
  priceAmountMinor: number | null
  priceUnit: MallPriceUnit
  stockQuantity: number | null
  expressAvailable: boolean
  shippingFeeMinor: number
  pickupPoint: string | null
  deliveryNote: string | null
  shippingNote: string | null
  fulfilledFrom: string | null
  status: OpsOfferStatus
  merchantCollecting: boolean
  updatedAt: string
  version: number
}

export interface OpsCatalogOperation {
  operationId: string
  action: OpsCatalogAction
  detail: string
  operatorName: string
  occurredAt: string
}

export interface OpsProductDetail {
  product: OpsProduct
  sellingPoints: string[]
  specs: OpsSpec[]
  packItems: OpsPackItem[]
  gallery: string[]
  introBlocks: OpsIntroBlock[]
  skus: OpsSku[]
  offers: OpsOffer[]
  publishBlockers: OpsPublishBlocker[]
  operations: OpsCatalogOperation[]
}

export interface OpsMedia {
  mediaId: string
  url: string
  contentType: 'image/jpeg' | 'image/png' | 'image/webp'
  sizeBytes: number
}

export interface ProductFilters {
  keyword: string
  category: MallCategory | ''
  saleMode: MallSaleMode | ''
  status: OpsProductStatus | ''
}

export interface ProductCreateDraft {
  category: MallCategory
  name: string
  saleMode: MallSaleMode
}

/** 基本资料表单（整份替换）。 */
export interface ProductDraft {
  category: MallCategory
  name: string
  summary: string
  coverUrl: string
  experienceBadge: MallExperienceBadge | null
  sortOrder: number | null
  sellingPoints: string[]
  specs: OpsSpec[]
  packItems: OpsPackItem[]
  gallery: string[]
  introBlocks: OpsIntroBlock[]
}

export interface SkuDraft {
  name: string
  sortOrder: number | null
}

/** 供给表单：价格和运费在页面上是元，提交时换成分（由页面换好再交给这里）。 */
export interface OfferDraft {
  merchantKind: MallMerchantKind
  cityCode: string | null
  priceAmountMinor: number | null
  priceUnit: MallPriceUnit
  stockQuantity: number | null
  expressAvailable: boolean
  shippingFeeMinor: number
  pickupPoint: string
  deliveryNote: string
  shippingNote: string
  fulfilledFrom: string
}

function blankToNull(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim()
  return trimmed === '' ? null : trimmed
}

/** 基本资料 → 请求体：去首尾空白，空的可选项写 null，空行（参数、包装、图文）去掉。 */
export function productUpdateBody(draft: ProductDraft, version: number) {
  return {
    category: draft.category,
    name: draft.name.trim(),
    summary: blankToNull(draft.summary),
    coverUrl: blankToNull(draft.coverUrl),
    experienceBadge: draft.experienceBadge ?? null,
    sortOrder: draft.sortOrder ?? null,
    sellingPoints: draft.sellingPoints.map((point) => point.trim()).filter((point) => point !== ''),
    specs: draft.specs
      .map((spec) => ({ label: spec.label.trim(), value: spec.value.trim() }))
      .filter((spec) => spec.label !== '' || spec.value !== ''),
    packItems: draft.packItems
      .map((item) => ({ name: item.name.trim(), quantity: item.quantity }))
      .filter((item) => item.name !== ''),
    gallery: draft.gallery.map((url) => url.trim()).filter((url) => url !== ''),
    introBlocks: draft.introBlocks
      .map((block) => block.type === 'TEXT'
        ? { type: 'TEXT' as const, text: blankToNull(block.text), imageUrl: null }
        : { type: 'IMAGE' as const, text: blankToNull(block.text), imageUrl: blankToNull(block.imageUrl) })
      .filter((block) => (block.type === 'TEXT' ? block.text !== null : block.imageUrl !== null)),
    version,
  }
}

function offerFields(draft: OfferDraft) {
  return {
    merchantKind: draft.merchantKind,
    cityCode: blankToNull(draft.cityCode),
    priceAmountMinor: draft.priceAmountMinor,
    priceUnit: draft.priceUnit,
    stockQuantity: draft.stockQuantity,
    expressAvailable: draft.expressAvailable,
    shippingFeeMinor: draft.shippingFeeMinor,
    pickupPoint: blankToNull(draft.pickupPoint),
    deliveryNote: blankToNull(draft.deliveryNote),
    shippingNote: blankToNull(draft.shippingNote),
    fulfilledFrom: blankToNull(draft.fulfilledFrom),
  }
}

const PRODUCTS = '/api/v1/ops/catalog/products'

export function createCatalogApi(http: ApiClient) {
  const one = (productId: string) => `${PRODUCTS}/${encodeURIComponent(productId)}`
  return {
    list(filters: ProductFilters, page: number, size: number): Promise<Page<OpsProductSummary>> {
      return http.get<Page<OpsProductSummary>>(PRODUCTS, {
        keyword: filters.keyword.trim(),
        category: filters.category,
        saleMode: filters.saleMode,
        status: filters.status,
        page,
        size,
      })
    },
    /** 新建商品：同一次弹窗里的重试要用同一个幂等键（由弹窗保存）。 */
    create(draft: ProductCreateDraft, idempotencyKey: string = newIdempotencyKey()): Promise<OpsProductDetail> {
      return http.post<OpsProductDetail>(PRODUCTS,
        { category: draft.category, name: draft.name.trim(), saleMode: draft.saleMode }, { idempotencyKey })
    },
    detail(productId: string): Promise<OpsProductDetail> {
      return http.get<OpsProductDetail>(one(productId))
    },
    update(productId: string, draft: ProductDraft, version: number): Promise<OpsProductDetail> {
      return http.put<OpsProductDetail>(one(productId), productUpdateBody(draft, version))
    },
    changeStatus(productId: string, action: OpsProductStatusAction, version: number): Promise<OpsProductDetail> {
      return http.post<OpsProductDetail>(`${one(productId)}/status`, { action, version })
    },
    createSku(productId: string, draft: SkuDraft): Promise<OpsProductDetail> {
      return http.post<OpsProductDetail>(`${one(productId)}/skus`,
        { name: draft.name.trim(), sortOrder: draft.sortOrder ?? null })
    },
    updateSku(productId: string, skuId: string, draft: SkuDraft, status: OpsSkuStatus, version: number):
    Promise<OpsProductDetail> {
      return http.put<OpsProductDetail>(`${one(productId)}/skus/${encodeURIComponent(skuId)}`,
        { name: draft.name.trim(), sortOrder: draft.sortOrder ?? null, status, version })
    },
    createOffer(productId: string, skuId: string, merchantId: string, draft: OfferDraft,
      idempotencyKey: string = newIdempotencyKey()): Promise<OpsProductDetail> {
      return http.post<OpsProductDetail>(`${one(productId)}/offers`,
        { skuId, merchantId, ...offerFields(draft) }, { idempotencyKey })
    },
    updateOffer(productId: string, offerId: string, draft: OfferDraft, version: number): Promise<OpsProductDetail> {
      return http.put<OpsProductDetail>(`${one(productId)}/offers/${encodeURIComponent(offerId)}`,
        { ...offerFields(draft), version })
    },
    changeOfferStatus(productId: string, offerId: string, action: OpsOfferStatusAction, version: number):
    Promise<OpsProductDetail> {
      return http.post<OpsProductDetail>(`${one(productId)}/offers/${encodeURIComponent(offerId)}/status`,
        { action, version })
    },
    uploadMedia(file: File): Promise<OpsMedia> {
      const form = new FormData()
      form.append('file', file, file.name)
      return http.request<OpsMedia>('POST', '/api/v1/ops/catalog/media', { form })
    },
  }
}

export type CatalogApi = ReturnType<typeof createCatalogApi>

/** 上传前在页面上先挡一次（后端按文件头再判断一次）。返回错误说明，没问题返回 null。 */
export function mediaProblem(file: { size: number, type: string }): string | null {
  if (file.size <= 0) return '文件是空的'
  if (file.size > CATALOG_LIMITS.mediaBytes) return '图片不能超过 5 MB'
  if (!(MEDIA_TYPES as readonly string[]).includes(file.type)) return '只能上传 JPEG、PNG 或 WebP 图片'
  return null
}

/** OPS_PRODUCT_INCOMPLETE 的 details.blockers（不认识的编码丢掉）。 */
export function blockersFromDetails(details: Record<string, unknown> | null | undefined): OpsPublishBlocker[] {
  const raw = details?.blockers
  if (!Array.isArray(raw)) return []
  return raw.filter((value): value is OpsPublishBlocker => typeof value === 'string' && value in BLOCKER_LABELS)
}
