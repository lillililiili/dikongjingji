import { describe, expect, it } from 'vitest'

import {
  createCityDirectoryApi,
  type PlatformCityHttpClient,
} from '../../../shared/city/data/city-directory-api'

describe('platform city API adapter', () => {
  it('loads the public city directory and records selected search results', async () => {
    const calls: Array<{ url: string; method: 'GET' | 'POST' }> = []
    const http: PlatformCityHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' | 'POST' }) => {
        calls.push(input)
        return { hotCities: [], provinces: [] } as T
      },
    }
    const api = createCityDirectoryApi(http)

    await api.load()
    await api.recordSearchSelection('370100')

    expect(calls).toEqual([
      { url: '/api/v1/platform/cities', method: 'GET' },
      {
        url: '/api/v1/platform/cities/370100/search-selections',
        method: 'POST',
      },
    ])
  })
})
