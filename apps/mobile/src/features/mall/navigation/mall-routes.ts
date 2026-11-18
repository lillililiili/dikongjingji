export const MALL_PRODUCT_PATH = '/pages/mall/product'

// Only stable ids travel in the URL; the page reads everything else from the server.
export function buildMallProductUrl(productId: string, cityCode: string): string {
  return `${MALL_PRODUCT_PATH}?productId=${encodeURIComponent(productId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export const CART_TAB_PATH = '/pages/cart/index'
export const MALL_CHECKOUT_PATH = '/pages/cart/checkout'
export const ADDRESS_BOOK_PATH = '/pages/mine/addresses'

// Cart lines travel as their stable ids; the confirm page reads prices and stock again.
export function buildCartCheckoutUrl(cartItemIds: string[], cityCode: string): string {
  return `${MALL_CHECKOUT_PATH}?items=${cartItemIds.map(encodeURIComponent).join(',')}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildBuyNowCheckoutUrl(offerId: string, quantity: number, cityCode: string): string {
  return `${MALL_CHECKOUT_PATH}?offerId=${encodeURIComponent(offerId)}&quantity=${quantity}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

// select=1: tapping an address uses it for the order being confirmed and goes back.
export function buildAddressBookUrl(forCheckout: boolean): string {
  return forCheckout ? `${ADDRESS_BOOK_PATH}?select=1` : ADDRESS_BOOK_PATH
}
