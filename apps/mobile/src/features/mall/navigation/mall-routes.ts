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

// `delivery`: the mode picked on 规格与数量, which the confirm page starts from when the offer has it.
export function buildBuyNowCheckoutUrl(
  offerId: string,
  quantity: number,
  cityCode: string,
  delivery: 'EXPRESS' | 'PICKUP' | null = null,
): string {
  return `${MALL_CHECKOUT_PATH}?offerId=${encodeURIComponent(offerId)}&quantity=${quantity}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
    + (delivery ? `&delivery=${delivery}` : '')
}

/**
 * The confirm page of a chosen inquiry quote (F4): quantity and price come from the quote. The
 * inquiry id travels too, so a quote that can no longer be ordered leads back to its quotes page.
 */
export function buildQuoteCheckoutUrl(inquiryQuoteId: string, inquiryId: string, cityCode: string): string {
  return `${MALL_CHECKOUT_PATH}?inquiryQuoteId=${encodeURIComponent(inquiryQuoteId)}`
    + `&inquiryId=${encodeURIComponent(inquiryId)}&cityCode=${encodeURIComponent(cityCode)}`
}

export const MALL_INQUIRY_PATH = '/pages/mall/inquiry'
export const MALL_INQUIRY_QUOTES_PATH = '/pages/mall/inquiry-quotes'

export interface MallInquiryTarget {
  productId: string
  // The delivery city: the inquiry goes to the merchants selling the product there.
  cityCode: string
}

// The inquiry form of an inquiry product (F4); the form reads the product and its merchants again.
export function buildMallInquiryUrl(target: MallInquiryTarget): string {
  return `${MALL_INQUIRY_PATH}?productId=${encodeURIComponent(target.productId)}`
    + `&cityCode=${encodeURIComponent(target.cityCode)}`
}

export function parseMallInquiryTarget(query: Record<string, unknown> | undefined): MallInquiryTarget | null {
  const read = (key: string) => (typeof query?.[key] === 'string' ? (query[key] as string).trim() : '')
  const target = { productId: read('productId'), cityCode: read('cityCode') }
  return target.productId && target.cityCode ? target : null
}

export function buildMallInquiryQuotesUrl(inquiryId: string): string {
  return `${MALL_INQUIRY_QUOTES_PATH}?inquiryId=${encodeURIComponent(inquiryId)}`
}

// select=1: tapping an address uses it for the order being confirmed and goes back.
export function buildAddressBookUrl(forCheckout: boolean): string {
  return forCheckout ? `${ADDRESS_BOOK_PATH}?select=1` : ADDRESS_BOOK_PATH
}
