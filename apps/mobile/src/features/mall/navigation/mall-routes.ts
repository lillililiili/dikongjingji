export const MALL_PRODUCT_PATH = '/pages/mall/product'

// Only stable ids travel in the URL; the page reads everything else from the server.
export function buildMallProductUrl(productId: string, cityCode: string): string {
  return `${MALL_PRODUCT_PATH}?productId=${encodeURIComponent(productId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}
