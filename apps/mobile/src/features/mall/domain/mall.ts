// Mirrors contracts/openapi-mall-catalog.yaml. Prices and seller counts are worked out by the server for the city.

export type MallCategory = 'DRONE' | 'ROBOT' | 'PARTS' | 'RENTAL' | 'COURSE'

export type MallProductSort = 'DEFAULT' | 'PRICE_ASC' | 'PRICE_DESC'

export type MallSaleMode = 'BUY' | 'RENT' | 'INQUIRY'

export type MallExperienceBadge = 'ONLINE_EXPERIENCE' | 'VENUE_DISPLAY'

export type MallPriceUnit = 'ITEM' | 'DAY' | 'HALF_DAY'

export type MallMerchantKind = 'FACTORY_DIRECT' | 'AUTHORIZED_DEALER' | 'LOCAL_STORE'

export interface MallImage {
  url: string
  alt: string
}

export interface MallListPrice {
  fromAmountMinor: number | null
  unit: MallPriceUnit
  multiplePrices: boolean
}

export interface MallProductCard {
  productId: string
  category: MallCategory
  name: string
  coverImage: MallImage | null
  saleMode: MallSaleMode
  experienceBadge: MallExperienceBadge | null
  price: MallListPrice
  sellerCount: number
}

// What the filter sheet can narrow by. Empty lists and null prices mean "any".
// Prices are whole yuan, as typed; the API client converts them to minor units.
export interface MallFilters {
  saleModes: MallSaleMode[]
  experienceBadges: MallExperienceBadge[]
  merchantKinds: MallMerchantKind[]
  minPriceYuan: number | null
  maxPriceYuan: number | null
}

export interface MallProductQuery {
  cityCode: string
  category: MallCategory | null
  sort: MallProductSort
  /** Trimmed; empty for no search. */
  keyword?: string
  filters?: MallFilters
}

export interface MallOffer {
  offerId: string
  skuId: string
  skuName: string
  // The selling (or lessor, or quoting) organization: the counterpart of 咨询.
  merchantId: string
  merchantName: string
  merchantKind: MallMerchantKind
  amountMinor: number | null
  unit: MallPriceUnit
  deliveryNote: string | null
  shippingNote: string | null
  fulfilledFrom: string | null
  // (catalog 1.2.0) The merchant can take payment online. Buying, renting and ordering by quote all
  // pay the merchant online, so an offer without it is shown but not preselected or chosen.
  onlinePayment: boolean
  // (catalog 1.3.0) Pieces this merchant has for this spec in the city. null: not tracked (no count is
  // shown); 0: sold out, the spec cannot be chosen and the cart refuses it.
  stockQuantity: number | null
}

// (catalog 1.3.0) One active spec of the product, whether or not a merchant here sells it.
export interface MallSku {
  skuId: string
  name: string
}

// (catalog 1.3.0) One block of 图文介绍: a paragraph, or a picture with an optional caption.
export type MallIntroBlock =
  | { type: 'TEXT'; text: string }
  | { type: 'IMAGE'; text: string | null; image: MallImage }

export interface MallSpec {
  label: string
  value: string
}

export interface MallPackItem {
  name: string
  quantity: number
}

export interface MallProductDetail {
  productId: string
  category: MallCategory
  name: string
  summary: string | null
  coverImage: MallImage | null
  saleMode: MallSaleMode
  experienceBadge: MallExperienceBadge | null
  offers: MallOffer[]
  specs: MallSpec[]
  packItems: MallPackItem[]
  // (catalog 1.3.0) Empty when an older server does not send them: the page then keeps one row per
  // offer and a single-spec 规格与数量 sheet, as before.
  sellingPoints: string[]
  skus: MallSku[]
  gallery: MallImage[]
  introBlocks: MallIntroBlock[]
}

export interface MallProductPage {
  hasMore: boolean
  total: number
  items: MallProductCard[]
}

export const MALL_PAGE_SIZE = 10
