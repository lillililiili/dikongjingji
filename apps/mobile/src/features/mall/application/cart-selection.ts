import type { Cart, CartGroup, CartInvalidReason } from '../domain/cart'

// What is ticked on the cart page. One merchant is settled at a time (prototype board Cart):
// picking a merchant ticks all of its goods and leaves the others unticked; inside that merchant
// single goods can be unticked. Nothing here is sent to the server until 结算.

export interface CartSelection {
  merchantId: string | null
  checkedIds: string[]
}

export const EMPTY_CART_SELECTION: CartSelection = { merchantId: null, checkedIds: [] }

function groupOf(cart: Cart, merchantId: string | null): CartGroup | null {
  return cart.groups.find((group) => group.merchant.organizationId === merchantId) ?? null
}

/**
 * The selection after the cart was read again. The merchant stays when it still has goods, and
 * ticked goods stay ticked; a merchant seen for the first time starts with everything ticked.
 */
export function reconcileCartSelection(cart: Cart, previous: CartSelection): CartSelection {
  const kept = groupOf(cart, previous.merchantId)
  if (kept) {
    const ids = new Set(kept.items.map((item) => item.cartItemId))
    return { merchantId: previous.merchantId, checkedIds: previous.checkedIds.filter((id) => ids.has(id)) }
  }
  const first = cart.groups[0]
  return first
    ? { merchantId: first.merchant.organizationId, checkedIds: first.items.map((item) => item.cartItemId) }
    : EMPTY_CART_SELECTION
}

export function selectCartMerchant(cart: Cart, merchantId: string): CartSelection {
  const group = groupOf(cart, merchantId)
  return group
    ? { merchantId, checkedIds: group.items.map((item) => item.cartItemId) }
    : EMPTY_CART_SELECTION
}

export function toggleCartItem(cart: Cart, selection: CartSelection, cartItemId: string): CartSelection {
  const group = groupOf(cart, selection.merchantId)
  if (!group?.items.some((item) => item.cartItemId === cartItemId)) return selection
  const checked = selection.checkedIds.includes(cartItemId)
  return {
    merchantId: selection.merchantId,
    checkedIds: checked
      ? selection.checkedIds.filter((id) => id !== cartItemId)
      : [...selection.checkedIds, cartItemId],
  }
}

// 全选本商家: ticks every good of the current merchant, or unticks them all when all are ticked.
export function toggleWholeMerchant(cart: Cart, selection: CartSelection): CartSelection {
  const group = groupOf(cart, selection.merchantId)
  if (!group) return selection
  return isWholeMerchantChecked(cart, selection)
    ? { merchantId: selection.merchantId, checkedIds: [] }
    : { merchantId: selection.merchantId, checkedIds: group.items.map((item) => item.cartItemId) }
}

export function isWholeMerchantChecked(cart: Cart, selection: CartSelection): boolean {
  const group = groupOf(cart, selection.merchantId)
  return Boolean(group && group.items.length > 0
    && group.items.every((item) => selection.checkedIds.includes(item.cartItemId)))
}

export interface CartTotals {
  // Lines ticked, as on the 结算(n) button.
  lineCount: number
  amountMinor: number
  // In the order shown, for the confirm page.
  cartItemIds: string[]
}

export function summarizeCartSelection(cart: Cart, selection: CartSelection): CartTotals {
  const group = groupOf(cart, selection.merchantId)
  const picked = group?.items.filter((item) => selection.checkedIds.includes(item.cartItemId)) ?? []
  return {
    lineCount: picked.length,
    amountMinor: picked.reduce((sum, item) => sum + (item.unitAmountMinor ?? 0) * item.quantity, 0),
    cartItemIds: picked.map((item) => item.cartItemId),
  }
}

// Manage mode ticks goods of any merchant, and invalid ones, for deleting.
export function allCartItemIds(cart: Cart): string[] {
  return [
    ...cart.groups.flatMap((group) => group.items.map((item) => item.cartItemId)),
    ...cart.invalidItems.map((item) => item.cartItemId),
  ]
}

export const CART_INVALID_REASON_TEXT: Record<CartInvalidReason, string> = {
  PRODUCT_OFFLINE: '商品已下架',
  OFFER_UNAVAILABLE: '商家已下架',
  NOT_PURCHASABLE: '已改为询价或租赁，不能直接购买',
  NOT_SOLD_IN_CITY: '当前城市不可售',
  OUT_OF_STOCK: '暂时缺货',
}
