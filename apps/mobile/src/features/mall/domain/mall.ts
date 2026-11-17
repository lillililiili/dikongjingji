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

export interface MallProductQuery {
  cityCode: string
  category: MallCategory | null
  sort: MallProductSort
}

export interface MallOffer {
  offerId: string
  skuId: string
  skuName: string
  merchantName: string
  merchantKind: MallMerchantKind
  amountMinor: number | null
  unit: MallPriceUnit
  deliveryNote: string | null
  shippingNote: string | null
  fulfilledFrom: string | null
}

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
}

export interface MallProductPage {
  hasMore: boolean
  total: number
  items: MallProductCard[]
}

export const MALL_PAGE_SIZE = 10
