import type { MallProductDetail } from '../../mall/domain/mall'

// Reads every picked product again for the city. A product that cannot be read (no longer sold
// here, taken down) is left out and reported, so the page can drop it from the picks.
export interface CompareLoadResult {
  products: MallProductDetail[]
  missing: string[]
}

const isNotFound = (error: unknown) =>
  Boolean(error && typeof error === 'object' && 'status' in error && error.status === 404)

export async function loadCompareProducts(
  productIds: readonly string[],
  cityCode: string,
  loadProduct: (productId: string, cityCode: string) => Promise<MallProductDetail>,
): Promise<CompareLoadResult> {
  const settled = await Promise.allSettled(productIds.map((id) => loadProduct(id, cityCode)))
  const products: MallProductDetail[] = []
  const missing: string[] = []
  settled.forEach((result, index) => {
    if (result.status === 'fulfilled') products.push(result.value)
    else if (isNotFound(result.reason)) missing.push(productIds[index])
    else throw result.reason
  })
  return { products, missing }
}
