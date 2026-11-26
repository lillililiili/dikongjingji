import { expect, test } from '@playwright/test'

// Run against a production H5 built with only VITE_FEATURE_MALL=true and a development API.
test.skip(process.env.E2E_MALL_ONLY_BUILD !== '1', '需要仅开启 MALL 的公开构建')

test('单独开放商城时可以看商品和商家，关闭的购物车、租赁和对比入口不可用', async ({ page }) => {
  const base = '/#/pages/mall/product?cityCode=370500&productId='
  const kit = 'c2000000-0000-4000-8000-000000000101'
  const dog = 'c2000000-0000-4000-8000-000000000401'
  const requests: string[] = []
  page.on('request', (request) => requests.push(new URL(request.url()).pathname))
  await page.goto(base + kit)
  await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
  await expect(page.getByTestId('mall-product-to-cart')).toHaveCount(0)
  await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
  await expect(page.getByTestId('mall-product-chosen')).toHaveCount(0)
  await expect(page.getByTestId('mall-product-primary')).toHaveCount(0)
  await page.getByTestId('mall-product-merchant').click()
  await expect(page.getByTestId('mall-merchant-name')).toBeVisible()
  await page.goto('/?case=rental' + base.slice(1) + dog)
  await expect(page.getByTestId('mall-product-name')).toHaveText('巡检机器狗')
  await expect(page.getByTestId('mall-product-primary')).toHaveCount(0)
  for (const [route, closed] of [
    ['cart/checkout?offerId=123&quantity=1&cityCode=370500', 'checkout-unavailable'],
    ['mine/addresses?select=1', 'address-book-unavailable'],
    [`mall/compare?ids=${kit},${dog}&cityCode=370500`, 'mall-compare-unavailable'],
  ]) {
    await page.goto('/#/pages/' + route)
    await expect(page.getByTestId(closed)).toBeVisible()
  }
  expect(requests.some((path) => path.startsWith('/api/v1/mall/products/'))).toBe(true)
  expect(requests.filter((path) => /^\/api\/v1\/(mall\/(cart|checkout-preview)|account\/addresses|rental|trade|me\/favorites)/.test(path))).toEqual([])
})
