import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type {
  MallCategory,
  MallExperienceBadge,
  MallImage,
  MallListPrice,
  MallMerchantKind,
  MallPackItem,
  MallPriceUnit,
  MallProductCard,
  MallProductDetail,
  MallProductPage,
  MallProductQuery,
  MallSaleMode,
  MallSpec,
} from '../domain/mall'

interface MediaAssetDto {
  url: string
  alt: string
}

export interface ProductCardDto {
  productId: string
  category: MallCategory
  name: string
  coverImage: MediaAssetDto | null
  saleMode: MallSaleMode
  experienceBadge: MallExperienceBadge | null
  price: MallListPrice
  sellerCount: number
}

interface ProductPageDto {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: ProductCardDto[]
}

interface ProductDetailDto {
  productId: string
  category: MallCategory
  name: string
  summary: string | null
  coverImage: MediaAssetDto | null
  saleMode: MallSaleMode
  experienceBadge: MallExperienceBadge | null
  offers: {
    offerId: string
    skuId: string
    skuName: string
    merchant: { organizationId: string; name: string; kind: MallMerchantKind }
    amountMinor: number | null
    currency: string
    unit: MallPriceUnit
    deliveryNote: string | null
    shippingNote: string | null
    fulfilledFrom: string | null
  }[]
  specs: MallSpec[]
  packItems: MallPackItem[]
}

export interface MallApi {
  loadProducts(query: MallProductQuery, page: number, size: number): Promise<MallProductPage>
  loadProduct(productId: string, cityCode: string): Promise<MallProductDetail>
}

// The server already filters images to HTTPS; this keeps a bad row from reaching an <image> tag anyway.
function toImage(asset: MediaAssetDto | null): MallImage | null {
  return asset && /^https:\/\//i.test(asset.url)
    ? { url: asset.url, alt: asset.alt }
    : null
}

// Also used by the home recommend block, which receives the same card shape.
export function toMallProductCard(item: ProductCardDto): MallProductCard {
  return {
    productId: item.productId,
    category: item.category,
    name: item.name,
    coverImage: toImage(item.coverImage),
    saleMode: item.saleMode,
    experienceBadge: item.experienceBadge,
    price: { ...item.price },
    sellerCount: item.sellerCount,
  }
}

type QueryValue = string | number | null | readonly string[]

// A list becomes the same key repeated, which is how the server reads multi-value filters.
function toQueryString(parameters: Record<string, QueryValue>): string {
  return Object.entries(parameters)
    .flatMap(([key, value]): [string, string | number][] => {
      if (value === null) return []
      if (typeof value === 'string' || typeof value === 'number') return [[key, value]]
      return value.map((item): [string, string] => [key, item])
    })
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&')
}

const yuanToMinor = (yuan: number | null | undefined): number | null =>
  yuan === null || yuan === undefined ? null : yuan * 100

export function createMallApi(http: PublicApiHttpClient): MallApi {
  return {
    async loadProducts(query, page, size) {
      const dto = await http.request<ProductPageDto>({
        method: 'GET',
        url: `/api/v1/mall/products?${toQueryString({
          cityCode: query.cityCode,
          category: query.category,
          keyword: query.keyword?.trim() || null,
          saleModes: query.filters?.saleModes ?? [],
          experienceBadges: query.filters?.experienceBadges ?? [],
          merchantKinds: query.filters?.merchantKinds ?? [],
          minPriceMinor: yuanToMinor(query.filters?.minPriceYuan),
          maxPriceMinor: yuanToMinor(query.filters?.maxPriceYuan),
          // DEFAULT is the server default, so the URL stays short for the common case.
          sort: query.sort === 'DEFAULT' ? null : query.sort,
          page,
          size,
        })}`,
      })
      return {
        hasMore: dto.hasMore,
        total: dto.total,
        items: dto.items.map(toMallProductCard),
      }
    },

    async loadProduct(productId, cityCode) {
      const dto = await http.request<ProductDetailDto>({
        method: 'GET',
        url: `/api/v1/mall/products/${encodeURIComponent(productId)}?${toQueryString({ cityCode })}`,
      })
      return {
        productId: dto.productId,
        category: dto.category,
        name: dto.name,
        summary: dto.summary,
        coverImage: toImage(dto.coverImage),
        saleMode: dto.saleMode,
        experienceBadge: dto.experienceBadge,
        offers: dto.offers.map((offer) => ({
          offerId: offer.offerId,
          skuId: offer.skuId,
          skuName: offer.skuName,
          merchantName: offer.merchant.name,
          merchantKind: offer.merchant.kind,
          amountMinor: offer.amountMinor,
          unit: offer.unit,
          deliveryNote: offer.deliveryNote,
          shippingNote: offer.shippingNote,
          fulfilledFrom: offer.fulfilledFrom,
        })),
        specs: dto.specs.map((spec) => ({ label: spec.label, value: spec.value })),
        packItems: dto.packItems.map((item) => ({ name: item.name, quantity: item.quantity })),
      }
    },
  }
}
