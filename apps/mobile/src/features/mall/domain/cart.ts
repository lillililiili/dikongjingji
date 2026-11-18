import type { Address } from '../../account/domain/address'
import type { MallImage, MallMerchantKind } from './mall'

// Mirrors contracts/openapi-mall-cart.yaml.

export type CartInvalidReason =
  | 'PRODUCT_OFFLINE'
  | 'OFFER_UNAVAILABLE'
  | 'NOT_PURCHASABLE'
  | 'NOT_SOLD_IN_CITY'

export type DeliveryMode = 'EXPRESS' | 'PICKUP'

export interface CartMerchant {
  organizationId: string
  name: string
  kind: MallMerchantKind
}

export interface CartItem {
  cartItemId: string
  offerId: string
  productId: string
  productName: string
  skuName: string
  coverImage: MallImage | null
  merchant: CartMerchant
  // Null on an item that cannot be bought any more.
  unitAmountMinor: number | null
  quantity: number
  shippingNote: string | null
  deliveryModes: DeliveryMode[]
  invalidReason: CartInvalidReason | null
}

export interface CartGroup {
  merchant: CartMerchant
  items: CartItem[]
}

export interface Cart {
  groups: CartGroup[]
  invalidItems: CartItem[]
  validCount: number
}

export interface CheckoutLine {
  cartItemId: string | null
  offerId: string
  productId: string
  productName: string
  skuName: string
  coverImage: MallImage | null
  unitAmountMinor: number
  quantity: number
  subtotalMinor: number
}

export interface DeliveryOption {
  mode: DeliveryMode
  note: string | null
}

export type CheckoutBlocker = 'ADDRESS_REQUIRED' | 'NO_DELIVERY_OPTION'

export interface CheckoutPreview {
  merchant: CartMerchant
  lines: CheckoutLine[]
  pieceCount: number
  fulfilledFrom: string | null
  deliveryOptions: DeliveryOption[]
  deliveryMode: DeliveryMode | null
  address: Address | null
  amounts: { goodsMinor: number; shippingMinor: number; payableMinor: number; currency: string }
  blockers: CheckoutBlocker[]
}

// Where the lines of a confirm page come from: chosen cart lines, or one offer bought at once.
export type CheckoutSource =
  | { kind: 'CART'; cartItemIds: string[] }
  | { kind: 'OFFER'; offerId: string; quantity: number }

export const CART_MAX_QUANTITY = 99
