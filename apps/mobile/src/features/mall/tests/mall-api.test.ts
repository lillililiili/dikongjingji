import { describe, expect, it, vi } from 'vitest'

import { createMallApi } from '../data/mall-api'

const PRODUCT_ID = 'c2000000-0000-4000-8000-000000000101'

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createMallApi({ request }), request }
}

const CARD = {
  productId: PRODUCT_ID,
  category: 'ROBOT',
  name: '编程机器人探索套装',
  coverImage: { url: 'http://insecure.example/a.jpg', alt: '封面' },
  saleMode: 'BUY',
  experienceBadge: 'ONLINE_EXPERIENCE',
  price: { fromAmountMinor: 129900, unit: 'ITEM', multiplePrices: true },
  sellerCount: 3,
}

describe('mall api', () => {
  it('asks for the default order without a sort parameter', async () => {
    const { api, request } = apiReturning({ page: 1, size: 10, total: 1, hasMore: false, items: [CARD] })

    const page = await api.loadProducts({ cityCode: '370500', category: null, sort: 'DEFAULT' }, 1, 10)

    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: '/api/v1/mall/products?cityCode=370500&page=1&size=10',
    })
    expect(page.total).toBe(1)
    expect(page.items[0]).toEqual({
      productId: PRODUCT_ID,
      category: 'ROBOT',
      name: '编程机器人探索套装',
      coverImage: null,
      saleMode: 'BUY',
      experienceBadge: 'ONLINE_EXPERIENCE',
      price: { fromAmountMinor: 129900, unit: 'ITEM', multiplePrices: true },
      sellerCount: 3,
    })
  })

  it('sends the category and a price sort when they are chosen', async () => {
    const { api, request } = apiReturning({ page: 2, size: 10, total: 0, hasMore: false, items: [] })

    await api.loadProducts({ cityCode: '370500', category: 'RENTAL', sort: 'PRICE_DESC' }, 2, 10)

    expect(request.mock.calls[0][0].url)
      .toBe('/api/v1/mall/products?cityCode=370500&category=RENTAL&sort=PRICE_DESC&page=2&size=10')
  })

  it('loads a product by stable ids and flattens each offer merchant', async () => {
    const { api, request } = apiReturning({
      productId: PRODUCT_ID,
      category: 'ROBOT',
      name: '编程机器人探索套装',
      summary: '图形化编程入门',
      coverImage: null,
      saleMode: 'BUY',
      experienceBadge: null,
      offers: [{
        offerId: 'o1',
        skuId: 's1',
        skuName: '标准版',
        merchant: { organizationId: 'm1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
        amountMinor: 129900,
        currency: 'CNY',
        unit: 'ITEM',
        deliveryNote: '东营现货',
        shippingNote: null,
        fulfilledFrom: '示例机器人商家 东营仓',
      }],
      specs: [{ label: '适用年龄', value: '8 岁以上' }],
      packItems: [{ name: '机器人主机', quantity: 1 }],
    })

    const product = await api.loadProduct(PRODUCT_ID, '370500')

    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: `/api/v1/mall/products/${PRODUCT_ID}?cityCode=370500`,
    })
    expect(product.offers[0]).toEqual({
      offerId: 'o1',
      skuId: 's1',
      skuName: '标准版',
      merchantName: '示例机器人商家',
      merchantKind: 'FACTORY_DIRECT',
      amountMinor: 129900,
      unit: 'ITEM',
      deliveryNote: '东营现货',
      shippingNote: null,
      fulfilledFrom: '示例机器人商家 东营仓',
    })
    expect(product.specs).toEqual([{ label: '适用年龄', value: '8 岁以上' }])
    expect(product.packItems).toEqual([{ name: '机器人主机', quantity: 1 }])
  })
})
