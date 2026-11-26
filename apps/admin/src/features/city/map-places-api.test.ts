import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from '@/core/http/api-client'
import { createMapPlacesApi, newPlaceId } from './map-places-api'

describe('运营地图地点', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('将停用筛选发送给后端，未指定的状态保留为全部', async () => {
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(JSON.stringify({ items: [] })))
    const api = createMapPlacesApi(createApiClient({ getToken: () => null, onUnauthorized: () => {}, fetchImpl }))
    await api.list({ cityCode: '370500', keyword: ' A & B ', status: 'off' }, 2, 10)
    await api.list({ cityCode: '', keyword: '', status: '' }, 1, 20)
    const urls = fetchImpl.mock.calls.map(([input]) => new URL(String(input), 'http://localhost'))
    expect(Object.fromEntries(urls[0]!.searchParams)).toEqual({
      cityCode: '370500', keyword: 'A & B', published: 'false', page: '2', size: '10',
    })
    expect(Object.fromEntries(urls[1]!.searchParams)).toEqual({ page: '1', size: '20' })
  })

  it('局域网 HTTP 预览生成可重试的 UUID', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(255) })
    expect(newPlaceId()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
  })
})
