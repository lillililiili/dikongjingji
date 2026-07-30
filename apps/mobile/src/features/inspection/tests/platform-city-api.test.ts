import { describe, expect, it } from 'vitest'

import {
  createCityDirectoryApi,
  type PlatformCityHttpClient,
} from '../../../shared/city/data/city-directory-api'

describe('platform city API adapter', () => {
  it('loads the public city directory and records selected search results', async () => {
    const calls: Array<{ url: string; method: 'GET' | 'POST' }> = []
    const directory = {
      hotCities: [{
        code: '370100',
        name: '济南市',
        pinyin: 'jinanshi',
        initials: 'jns',
        provinceCode: '370000',
        provinceName: '山东省',
      }],
      provinces: [{
        code: '370000',
        name: '山东省',
        pinyin: 'shandongsheng',
        initials: 'sds',
        cities: [{
          code: '370100',
          name: '济南市',
          pinyin: 'jinanshi',
          initials: 'jns',
          provinceCode: '370000',
          provinceName: '山东省',
        }],
      }],
    }
    const http: PlatformCityHttpClient = {
      request: async <T,>(input: { url: string; method: 'GET' | 'POST' }) => {
        calls.push(input)
        return directory as T
      },
    }
    const api = createCityDirectoryApi(http)

    await expect(api.load()).resolves.toEqual(directory)
    await api.recordSearchSelection('370100')

    expect(calls).toEqual([
      { url: '/api/v1/platform/cities', method: 'GET' },
      {
        url: '/api/v1/platform/cities/370100/search-selections',
        method: 'POST',
      },
    ])
  })

  it.each([
    null,
    { hotCities: [], provinces: {} },
    { hotCities: [{}], provinces: [] },
    {
      hotCities: [],
      provinces: [{
        code: '370000',
        name: '山东省',
        pinyin: 'shandongsheng',
        initials: 'sds',
        cities: [{ code: '370100' }],
      }],
    },
  ])('rejects a malformed city directory response: %#', async (payload) => {
    const http: PlatformCityHttpClient = {
      request: async <T,>() => payload as T,
    }

    await expect(createCityDirectoryApi(http).load())
      .rejects.toThrow('CITY_DIRECTORY_INVALID')
  })
})
