import { describe, expect, it } from 'vitest'

import { createFavoriteApi } from '../data/favorite-api'
import { formatFavoritePrice, resolveFavoritePicture } from '../application/favorite-targets'

const KIT = 'a1000000-0000-4000-8000-000000000001'

function recorder(response: unknown) {
  const calls: { url: string; method: string; headers?: Record<string, string> }[] = []
  const api = createFavoriteApi(async <T>(input: { url: string; method: 'GET' | 'PUT' | 'DELETE'; headers?: Record<string, string> }) => {
    calls.push(input)
    return response as T
  })
  return { api, calls }
}

describe('favorite api', () => {
  it('sends the token, the group and the city, and keeps only rows it can draw', async () => {
    const { api, calls } = recorder({
      page: 1,
      size: 10,
      total: 3,
      hasMore: false,
      counts: { goods: 3, service: 1, event: 0, content: 2 },
      items: [
        { targetType: 'PRODUCT', targetId: KIT, favoritedAt: '2026-11-22T02:00:00Z', available: true, title: '编程机器人探索套装',
          meta: '3 家在售', tag: null, price: { mode: 'AMOUNT', amountMinor: 129900, from: true, unitLabel: null },
          coverUrl: 'http://img.example.com/kit.jpg', productCategory: 'ROBOT' },
        { targetType: 'PRODUCT', targetId: 'gone', favoritedAt: '2026-11-21T02:00:00Z', available: false, title: '入门教学无人机',
          meta: 'stale', tag: 'stale', price: { mode: 'AMOUNT', amountMinor: 1, from: false, unitLabel: null }, coverUrl: null,
          productCategory: 'DRONE' },
        { targetType: 'COURSE', targetId: 'x', available: true, title: '未知' },
      ],
    })

    const page = await api.list('token-1', 'GOODS', '370500', 2, 10)

    expect(calls[0]).toEqual({
      url: '/api/v1/favorites?group=GOODS&cityCode=370500&page=2&size=10',
      method: 'GET',
      headers: { Authorization: 'Bearer token-1' },
    })
    expect(page.counts).toEqual({ GOODS: 3, SERVICE: 1, EVENT: 0, CONTENT: 2 })
    expect(page.items).toHaveLength(2)
    expect(page.items[0]).toMatchObject({ available: true, coverUrl: null, productCategory: 'ROBOT', meta: '3 家在售' })
    // A gone favorite keeps only its remembered title.
    expect(page.items[1]).toMatchObject({ available: false, title: '入门教学无人机', meta: null, tag: null, price: null, productCategory: null })
  })

  it('puts and deletes the one target with only stable ids in the path', async () => {
    const { api, calls } = recorder({ targetType: 'VENUE', targetId: KIT, favorited: true, favoritedAt: '2026-11-22T02:00:00Z' })

    expect(await api.add('t', 'VENUE', KIT, '370500')).toEqual({ targetType: 'VENUE', targetId: KIT, favorited: true })
    await api.remove('t', 'VENUE', KIT)
    await api.state('t', 'VENUE', KIT)

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      `PUT /api/v1/favorites/VENUE/${KIT}?cityCode=370500`,
      `DELETE /api/v1/favorites/VENUE/${KIT}`,
      `GET /api/v1/favorites/VENUE/${KIT}`,
    ])
  })
})

describe('favorite rows', () => {
  it('writes prices the way the board does', () => {
    expect(formatFavoritePrice({ mode: 'AMOUNT', amountMinor: 129900, from: true, unitLabel: null })).toEqual({ amount: '¥1,299', unit: '起' })
    expect(formatFavoritePrice({ mode: 'AMOUNT', amountMinor: 38000, from: true, unitLabel: '/天' })).toEqual({ amount: '¥380', unit: '/天 起' })
    expect(formatFavoritePrice({ mode: 'AMOUNT', amountMinor: 12000, from: false, unitLabel: '/人' })).toEqual({ amount: '¥120', unit: '/人' })
    expect(formatFavoritePrice({ mode: 'QUOTE', amountMinor: null, from: false, unitLabel: null })).toEqual({ amount: '按项目报价', unit: '' })
    expect(formatFavoritePrice({ mode: 'FREE', amountMinor: null, from: false, unitLabel: null })).toEqual({ amount: '免费', unit: '' })
    expect(formatFavoritePrice(null)).toBeNull()
  })

  it('uses the server picture first and no sample picture on public builds', () => {
    expect(resolveFavoritePicture({ targetType: 'PRODUCT', targetId: KIT, coverUrl: 'https://img.example.com/a.jpg' }, false))
      .toEqual({ url: 'https://img.example.com/a.jpg', contain: false })
    expect(resolveFavoritePicture({ targetType: 'PRODUCT', targetId: KIT, coverUrl: null }, false)).toBeNull()
  })
})
