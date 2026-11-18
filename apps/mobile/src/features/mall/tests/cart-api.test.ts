import { describe, expect, it, vi } from 'vitest'

import { createCartApi } from '../data/cart-api'

const TOKEN = 'token-1'
const AUTH = { Authorization: 'Bearer token-1' }

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createCartApi(request), request }
}

const ITEM = {
  cartItemId: 'c1',
  offerId: 'o1',
  productId: 'p1',
  productName: '编程机器人探索套装',
  skuName: '标准版',
  merchant: { organizationId: 'm1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
  unitAmountMinor: 129900,
  quantity: 2,
  deliveryModes: ['EXPRESS'],
}

describe('cart api', () => {
  it('reads the cart of a city and fills left-out fields with null', async () => {
    const { api, request } = apiReturning({
      groups: [{ merchant: ITEM.merchant, items: [ITEM] }],
      invalidItems: [{ ...ITEM, cartItemId: 'c2', unitAmountMinor: null, deliveryModes: undefined, invalidReason: 'NOT_SOLD_IN_CITY' }],
      validCount: 1,
    })

    const cart = await api.load(TOKEN, '370500')

    expect(request).toHaveBeenCalledWith({ url: '/api/v1/mall/cart?cityCode=370500', method: 'GET', headers: AUTH })
    expect(cart.groups[0].items[0]).toMatchObject({ coverImage: null, shippingNote: null, invalidReason: null })
    expect(cart.invalidItems[0]).toMatchObject({ unitAmountMinor: null, deliveryModes: [], invalidReason: 'NOT_SOLD_IN_CITY' })
  })

  it('adds, changes, removes and clears with the caller\'s token', async () => {
    const { api, request } = apiReturning({ cartItemId: 'c 1', quantity: 3, cartCount: 2, removed: 1, count: 2 })

    await api.add(TOKEN, { offerId: 'o1', quantity: 1, cityCode: '370500' })
    await api.setQuantity(TOKEN, 'c 1', 3)
    await api.remove(TOKEN, ['c1', 'c2'])
    await api.removeInvalid(TOKEN, '370500')
    await api.count(TOKEN, '370500')

    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data])).toEqual([
      ['POST', '/api/v1/mall/cart/items', { offerId: 'o1', quantity: 1, cityCode: '370500' }],
      ['PUT', '/api/v1/mall/cart/items/c%201', { quantity: 3 }],
      ['POST', '/api/v1/mall/cart/remove', { cartItemIds: ['c1', 'c2'] }],
      ['POST', '/api/v1/mall/cart/remove-invalid', { cityCode: '370500' }],
      ['GET', '/api/v1/mall/cart/count?cityCode=370500', undefined],
    ])
    expect(request.mock.calls.every(([input]) => input.headers.Authorization === AUTH.Authorization)).toBe(true)
  })

  it('asks for a preview of cart lines or of one offer', async () => {
    const dto = {
      merchant: ITEM.merchant,
      lines: [{ offerId: 'o1', productId: 'p1', productName: 'x', skuName: '标准版', unitAmountMinor: 100, quantity: 1, subtotalMinor: 100 }],
      pieceCount: 1,
      deliveryOptions: [{ mode: 'PICKUP' }],
      deliveryMode: 'PICKUP',
      amounts: { goodsMinor: 100, shippingMinor: 0, payableMinor: 100, currency: 'CNY' },
    }
    const { api, request } = apiReturning(dto)

    const fromCart = await api.preview(TOKEN, {
      cityCode: '370500',
      source: { kind: 'CART', cartItemIds: ['c1', 'c2'] },
      deliveryMode: 'EXPRESS',
      addressId: 'a1',
    })
    await api.preview(TOKEN, {
      cityCode: '370500',
      source: { kind: 'OFFER', offerId: 'o1', quantity: 2 },
      deliveryMode: null,
      addressId: null,
    })

    expect(request.mock.calls.map(([input]) => input.url)).toEqual([
      '/api/v1/mall/checkout-preview?cityCode=370500&cartItemIds=c1&cartItemIds=c2&deliveryMode=EXPRESS&addressId=a1',
      '/api/v1/mall/checkout-preview?cityCode=370500&offerId=o1&quantity=2',
    ])
    expect(fromCart).toMatchObject({
      fulfilledFrom: null,
      address: null,
      blockers: [],
      deliveryOptions: [{ mode: 'PICKUP', note: null }],
      lines: [{ cartItemId: null, coverImage: null }],
    })
  })
})
