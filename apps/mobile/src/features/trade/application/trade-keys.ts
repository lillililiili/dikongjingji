import type { SubmitOrderInput } from '../domain/trade-order'

// A random UUID (version 4 layout) for the Idempotency-Key header.
export function createTradeKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

// Everything the server checks when it builds the order. Two submissions with the same signature
// are the same order.
export function submitSignature(input: SubmitOrderInput): string {
  const source = input.source.kind === 'CART'
    ? `cart:${[...input.source.cartItemIds].sort().join(',')}`
    : input.source.kind === 'QUOTE'
      ? `quote:${input.source.inquiryQuoteId}`
      : `offer:${input.source.offerId}x${input.source.quantity}`
  return [
    input.cityCode,
    source,
    input.deliveryMode,
    input.deliveryMode === 'EXPRESS' ? input.addressId ?? '' : '',
    input.buyerNote.trim(),
    String(input.expectedPayableMinor),
  ].join('|')
}

/**
 * One idempotency key per distinct order. Tapping 提交订单 again after a network failure sends the
 * same content and so the same key: if the first request did reach the server, it answers with the
 * order it already made instead of making a second one. Another delivery mode, another address,
 * another note or a new amount after a fresh preview is a different order and gets a new key.
 */
export function createSubmitKeyHolder(createKey: () => string = createTradeKey) {
  let signature: string | null = null
  let key = ''
  return {
    keyFor(input: SubmitOrderInput): string {
      const next = submitSignature(input)
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}
