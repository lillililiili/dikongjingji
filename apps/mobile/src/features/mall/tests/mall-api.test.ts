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
        onlinePayment: true,
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
      merchantId: 'm1',
      merchantName: '示例机器人商家',
      merchantKind: 'FACTORY_DIRECT',
      amountMinor: 129900,
      unit: 'ITEM',
      deliveryNote: '东营现货',
      shippingNote: null,
      fulfilledFrom: '示例机器人商家 东营仓',
      onlinePayment: true,
      stockQuantity: null,
    })
    expect(product.specs).toEqual([{ label: '适用年龄', value: '8 岁以上' }])
    expect(product.packItems).toEqual([{ name: '机器人主机', quantity: 1 }])
    // An older server (catalog 1.2.0) sends none of the 1.3.0 fields: the page keeps its old shape.
    expect(product.sellingPoints).toEqual([])
    expect(product.skus).toEqual([])
    expect(product.gallery).toEqual([])
    expect(product.introBlocks).toEqual([])
  })

  it('maps the 1.3.0 selling points, specs, pictures, intro and stock', async () => {
    const offer = {
      skuName: '标准套装',
      merchant: { organizationId: 'm1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
      currency: 'CNY',
      unit: 'ITEM',
      deliveryNote: null,
      shippingNote: null,
      fulfilledFrom: null,
      onlinePayment: true,
    }
    const { api } = apiReturning({
      productId: PRODUCT_ID,
      category: 'ROBOT',
      name: '编程机器人探索套装',
      summary: null,
      coverImage: null,
      saleMode: 'BUY',
      experienceBadge: 'ONLINE_EXPERIENCE',
      offers: [
        { ...offer, offerId: 'o1', skuId: 's1', amountMinor: 129900, stockQuantity: 12 },
        { ...offer, offerId: 'o2', skuId: 's2', skuName: '教室套装', amountMinor: 738000, stockQuantity: 0 },
        { ...offer, offerId: 'o3', skuId: 's1', amountMinor: 135000, stockQuantity: -1 },
      ],
      specs: [],
      packItems: [],
      sellingPoints: [' 图形化编程 ', '避障练习', '  '],
      skus: [{ skuId: 's1', name: '标准套装' }, { skuId: 's2', name: '教室套装' }, { skuId: 's3', name: '扩展传感器包' }],
      gallery: [{ url: 'https://cdn.example/1.jpg', alt: '正面' }, { url: 'http://cdn.example/2.jpg', alt: '侧面' }],
      introBlocks: [
        { type: 'TEXT', text: '图形化编程入门', image: null },
        { type: 'TEXT', text: '   ', image: null },
        { type: 'IMAGE', text: null, image: { url: 'https://cdn.example/3.jpg', alt: '课堂' } },
        { type: 'IMAGE', text: '不安全', image: { url: 'http://cdn.example/4.jpg', alt: '' } },
      ],
    })

    const product = await api.loadProduct(PRODUCT_ID, '370500')

    expect(product.offers.map((item) => item.stockQuantity)).toEqual([12, 0, null])
    expect(product.sellingPoints).toEqual(['图形化编程', '避障练习'])
    expect(product.skus).toEqual([
      { skuId: 's1', name: '标准套装' },
      { skuId: 's2', name: '教室套装' },
      { skuId: 's3', name: '扩展传感器包' },
    ])
    expect(product.gallery).toEqual([{ url: 'https://cdn.example/1.jpg', alt: '正面' }])
    expect(product.introBlocks).toEqual([
      { type: 'TEXT', text: '图形化编程入门' },
      { type: 'IMAGE', text: null, image: { url: 'https://cdn.example/3.jpg', alt: '课堂' } },
    ])
  })

  it('sends the keyword, repeats multi-value filters and converts yuan to minor units', async () => {
    const request = vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, hasMore: false, items: [] })
    const api = createMallApi({ request })

    await api.loadProducts({
      cityCode: '370500',
      category: null,
      sort: 'DEFAULT',
      keyword: '  编程 100% ',
      filters: {
        saleModes: ['BUY', 'RENT'],
        experienceBadges: [],
        merchantKinds: ['LOCAL_STORE'],
        minPriceYuan: 100,
        maxPriceYuan: null,
      },
    }, 1, 10)

    expect(request.mock.calls[0][0].url).toBe(
      '/api/v1/mall/products?cityCode=370500&keyword=%E7%BC%96%E7%A8%8B%20100%25'
      + '&saleModes=BUY&saleModes=RENT&merchantKinds=LOCAL_STORE&minPriceMinor=10000&page=1&size=10',
    )
  })
})
