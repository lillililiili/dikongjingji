import { describe, expect, it } from 'vitest'

import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import cartFlagSource from '../application/cart-preview.ts?raw'
import {
  CART_INVALID_REASON_TEXT,
  EMPTY_CART_SELECTION,
  allCartItemIds,
  isWholeMerchantChecked,
  reconcileCartSelection,
  selectCartMerchant,
  summarizeCartSelection,
  toggleCartItem,
  toggleWholeMerchant,
} from '../application/cart-selection'
import type { Cart, CartItem, CartMerchant } from '../domain/cart'

const ROBOT: CartMerchant = { organizationId: 'm-robot', name: '示例机器人商家', kind: 'FACTORY_DIRECT' }
const STORE: CartMerchant = { organizationId: 'm-store', name: '示例科创器材店', kind: 'LOCAL_STORE' }

function item(id: string, merchant = ROBOT, unit: number | null = 10000, quantity = 1): CartItem {
  return {
    cartItemId: id,
    offerId: `offer-${id}`,
    productId: `product-${id}`,
    productName: id,
    skuName: '标准版',
    coverImage: null,
    merchant,
    unitAmountMinor: unit,
    quantity,
    shippingNote: null,
    deliveryModes: ['EXPRESS'],
    invalidReason: unit === null ? 'OFFER_UNAVAILABLE' : null,
  }
}

const CART: Cart = {
  groups: [
    { merchant: ROBOT, items: [item('kit', ROBOT, 129900, 2), item('field', ROBOT, 46000)] },
    { merchant: STORE, items: [item('drone', STORE, 95000)] },
  ],
  invalidItems: [item('sensor', ROBOT, null)],
  validCount: 3,
}

describe('cart selection', () => {
  it('starts on the first merchant with all of its goods ticked', () => {
    expect(reconcileCartSelection(CART, EMPTY_CART_SELECTION))
      .toEqual({ merchantId: 'm-robot', checkedIds: ['kit', 'field'] })
    expect(reconcileCartSelection({ groups: [], invalidItems: [], validCount: 0 }, EMPTY_CART_SELECTION))
      .toEqual(EMPTY_CART_SELECTION)
  })

  it('keeps the merchant and the ticks after a reload, and drops lines that are gone', () => {
    const before = { merchantId: 'm-robot', checkedIds: ['field', 'gone'] }
    expect(reconcileCartSelection(CART, before)).toEqual({ merchantId: 'm-robot', checkedIds: ['field'] })
    // The merchant left the cart: start over on the first one.
    expect(reconcileCartSelection(CART, { merchantId: 'm-other', checkedIds: ['x'] }))
      .toEqual({ merchantId: 'm-robot', checkedIds: ['kit', 'field'] })
  })

  it('settles one merchant at a time: picking one ticks all of it and unticks the other', () => {
    expect(selectCartMerchant(CART, 'm-store')).toEqual({ merchantId: 'm-store', checkedIds: ['drone'] })
    expect(selectCartMerchant(CART, 'm-unknown')).toEqual(EMPTY_CART_SELECTION)
  })

  it('only ticks goods of the current merchant', () => {
    const start = selectCartMerchant(CART, 'm-robot')
    const without = toggleCartItem(CART, start, 'kit')
    expect(without.checkedIds).toEqual(['field'])
    expect(toggleCartItem(CART, without, 'kit').checkedIds).toEqual(['field', 'kit'])
    expect(toggleCartItem(CART, start, 'drone')).toBe(start)
  })

  it('toggles the whole current merchant', () => {
    const all = selectCartMerchant(CART, 'm-robot')
    expect(isWholeMerchantChecked(CART, all)).toBe(true)
    const none = toggleWholeMerchant(CART, all)
    expect(none.checkedIds).toEqual([])
    expect(isWholeMerchantChecked(CART, none)).toBe(false)
    expect(toggleWholeMerchant(CART, none).checkedIds).toEqual(['kit', 'field'])
    expect(toggleWholeMerchant(CART, EMPTY_CART_SELECTION)).toBe(EMPTY_CART_SELECTION)
  })

  it('adds up the ticked lines of the current merchant only', () => {
    expect(summarizeCartSelection(CART, selectCartMerchant(CART, 'm-robot')))
      .toEqual({ lineCount: 2, amountMinor: 129900 * 2 + 46000, cartItemIds: ['kit', 'field'] })
    expect(summarizeCartSelection(CART, { merchantId: 'm-robot', checkedIds: ['drone'] }))
      .toEqual({ lineCount: 0, amountMinor: 0, cartItemIds: [] })
  })

  it('lists every line, invalid ones too, for manage mode', () => {
    expect(allCartItemIds(CART)).toEqual(['kit', 'field', 'drone', 'sensor'])
  })

  it('words every reason a good became invalid', () => {
    expect(Object.keys(CART_INVALID_REASON_TEXT).sort())
      .toEqual(['NOT_PURCHASABLE', 'NOT_SOLD_IN_CITY', 'OFFER_UNAVAILABLE', 'PRODUCT_OFFLINE'])
    expect(CART_INVALID_REASON_TEXT.NOT_SOLD_IN_CITY).toBe('当前城市不可售')
  })
})

describe('cart preview flag', () => {
  it('reads its own release switch', () => {
    const expression = (source: string, name: string) =>
      new RegExp(`export const ${name} = (.+)`).exec(source)?.[1].trim()
    expect(expression(cartFlagSource, 'CART_PREVIEW_ENABLED'))
      .toBe(moduleFlagExpression('CART'))
  })
})
