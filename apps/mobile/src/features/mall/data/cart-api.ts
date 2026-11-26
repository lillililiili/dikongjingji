import { businessImage } from '../../../shared/http/business-media'
import { toAddress } from '../../account/data/address-api'
import type {
  Cart,
  CartItem,
  CheckoutPreview,
  CheckoutSource,
  DeliveryMode,
} from '../domain/cart'

type CartRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

export interface CartApi {
  load(accessToken: string, cityCode: string): Promise<Cart>
  count(accessToken: string, cityCode: string): Promise<number>
  add(
    accessToken: string,
    input: { offerId: string; quantity: number; cityCode: string },
  ): Promise<{ cartItemId: string; quantity: number; cartCount: number }>
  setQuantity(accessToken: string, cartItemId: string, quantity: number): Promise<number>
  remove(accessToken: string, cartItemIds: string[]): Promise<number>
  removeInvalid(accessToken: string, cityCode: string): Promise<number>
  preview(
    accessToken: string,
    input: { cityCode: string; source: CheckoutSource; deliveryMode: DeliveryMode | null; addressId: string | null },
  ): Promise<CheckoutPreview>
}

export function createCartApi(request: CartRequest): CartApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  return {
    async load(accessToken, cityCode) {
      const dto = await request<Cart>({
        url: `/api/v1/mall/cart?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        groups: dto.groups.map((group) => ({ merchant: group.merchant, items: group.items.map(toItem) })),
        invalidItems: dto.invalidItems.map(toItem),
        validCount: dto.validCount,
      }
    },
    async count(accessToken, cityCode) {
      const dto = await request<{ count: number }>({
        url: `/api/v1/mall/cart/count?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return dto.count
    },
    async add(accessToken, input) {
      return request({ url: '/api/v1/mall/cart/items', method: 'POST', data: input, headers: auth(accessToken) })
    },
    async setQuantity(accessToken, cartItemId, quantity) {
      const dto = await request<{ quantity: number }>({
        url: `/api/v1/mall/cart/items/${encodeURIComponent(cartItemId)}`,
        method: 'PUT',
        data: { quantity },
        headers: auth(accessToken),
      })
      return dto.quantity
    },
    async remove(accessToken, cartItemIds) {
      const dto = await request<{ removed: number }>({
        url: '/api/v1/mall/cart/remove',
        method: 'POST',
        data: { cartItemIds },
        headers: auth(accessToken),
      })
      return dto.removed
    },
    async removeInvalid(accessToken, cityCode) {
      const dto = await request<{ removed: number }>({
        url: '/api/v1/mall/cart/remove-invalid',
        method: 'POST',
        data: { cityCode },
        headers: auth(accessToken),
      })
      return dto.removed
    },
    async preview(accessToken, input) {
      const query = [`cityCode=${encodeURIComponent(input.cityCode)}`]
      if (input.source.kind === 'CART') {
        input.source.cartItemIds.forEach((id) => query.push(`cartItemIds=${encodeURIComponent(id)}`))
      } else if (input.source.kind === 'QUOTE') {
        query.push(`inquiryQuoteId=${encodeURIComponent(input.source.inquiryQuoteId)}`)
      } else {
        query.push(`offerId=${encodeURIComponent(input.source.offerId)}`, `quantity=${input.source.quantity}`)
      }
      if (input.deliveryMode) query.push(`deliveryMode=${input.deliveryMode}`)
      if (input.addressId) query.push(`addressId=${encodeURIComponent(input.addressId)}`)
      const dto = await request<CheckoutPreview>({
        url: `/api/v1/mall/checkout-preview?${query.join('&')}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        merchant: dto.merchant,
        lines: dto.lines.map((line) => ({
          ...line,
          cartItemId: line.cartItemId ?? null,
          coverImage: businessImage(line.coverImage),
        })),
        pieceCount: dto.pieceCount,
        fulfilledFrom: dto.fulfilledFrom ?? null,
        deliveryOptions: dto.deliveryOptions.map((option) => ({ mode: option.mode, note: option.note ?? null })),
        deliveryMode: dto.deliveryMode ?? null,
        address: dto.address ? toAddress(dto.address) : null,
        amounts: dto.amounts,
        blockers: dto.blockers ?? [],
      }
    },
  }
}

function toItem(dto: CartItem): CartItem {
  return {
    cartItemId: dto.cartItemId,
    offerId: dto.offerId,
    productId: dto.productId,
    productName: dto.productName,
    skuName: dto.skuName,
    coverImage: businessImage(dto.coverImage),
    merchant: dto.merchant,
    unitAmountMinor: dto.unitAmountMinor ?? null,
    quantity: dto.quantity,
    shippingNote: dto.shippingNote ?? null,
    deliveryModes: dto.deliveryModes ?? [],
    invalidReason: dto.invalidReason ?? null,
  }
}
