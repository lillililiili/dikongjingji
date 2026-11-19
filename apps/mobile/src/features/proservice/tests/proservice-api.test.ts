import { describe, expect, it } from 'vitest'

import { createProServiceApi, toProServiceQueryString } from '../data/proservice-api'
import type { ProServiceQuery } from '../domain/proservice'
import { cardDto } from './proservice-fixtures'

function fakeHttp(body: unknown) {
  const urls: string[] = []
  return {
    urls,
    client: {
      async request<T>(input: { url: string; method: 'GET' }): Promise<T> {
        urls.push(input.url)
        return body as T
      },
    },
  }
}

const QUERY: ProServiceQuery = {
  type: 'AERIAL',
  cityCode: '370500',
  keyword: '',
  facet: null,
  priceMode: null,
  sort: 'DEFAULT',
}

describe('pro service API', () => {
  it('sends only the conditions that are set, DEFAULT left to the server', () => {
    expect(toProServiceQueryString(QUERY, 1, 20)).toBe('type=AERIAL&cityCode=370500&page=1&size=20')
    expect(toProServiceQueryString({
      ...QUERY,
      type: 'TECH_SUPPORT',
      keyword: '  无人机 ',
      facet: 'REMOTE',
      priceMode: 'PROJECT',
      sort: 'PRICE_DESC',
    }, 2, 3)).toBe(
      'type=TECH_SUPPORT&cityCode=370500&keyword=%E6%97%A0%E4%BA%BA%E6%9C%BA&deliverables=REMOTE'
      + '&priceMode=PROJECT&sort=PRICE_DESC&page=2&size=3',
    )
  })

  it('reads a page with its city state and filter group', async () => {
    const http = fakeHttp({
      type: 'AERIAL',
      cityCode: '370500',
      cityName: '东营市',
      cityOpen: true,
      facetGroup: { label: '成果类型', options: [{ code: 'VIDEO_FOOTAGE', label: '视频素材' }] },
      sort: 'DEFAULT',
      page: 1,
      size: 20,
      total: 2,
      hasMore: false,
      items: [
        cardDto(),
        cardDto({
          serviceId: 's2',
          coverUrl: 'http://insecure.example/a.jpg',
          price: { mode: 'PROJECT', amountMinor: null, unit: null, note: '方案确认后定价' },
        }),
      ],
    })
    const page = await createProServiceApi(http.client).listServices(QUERY, 1, 20)
    expect(http.urls[0]).toBe('/api/v1/pro-services?type=AERIAL&cityCode=370500&page=1&size=20')
    expect(page.cityOpen).toBe(true)
    expect(page.facetGroup.label).toBe('成果类型')
    expect(page.items[0].price).toEqual({ mode: 'PRICE_FROM', amountMinor: 128000, unit: '/次起', note: '含拍摄和素材整理' })
    // Only HTTPS pictures reach an <image>; the card draws the icon otherwise.
    expect(page.items[1].coverUrl).toBeNull()
    expect(page.items[1].price.mode).toBe('PROJECT')
  })

  it('reads a starting price without an amount as 按项目报价', async () => {
    const http = fakeHttp({
      ...cardDto({ price: { mode: 'PRICE_FROM', amountMinor: null, unit: '/次起', note: null } }),
      capabilities: [], priceRows: [], scope: [], outputs: [], serviceModes: [], terms: [],
      steps: [], guarantees: [], provider: null, cities: [],
    })
    const detail = await createProServiceApi(http.client).loadService('s 1', '370500')
    expect(http.urls[0]).toBe('/api/v1/pro-services/s%201?cityCode=370500')
    expect(detail.price).toEqual({ mode: 'PROJECT', amountMinor: null, unit: null, note: null })
    expect(detail.provider).toBeNull()
  })

  it('reads a provider page with its inspection services, the four types and cities', async () => {
    const http = fakeHttp({
      providerId: 'p1',
      name: '云翼科技',
      certificationLabel: ' 企业认证 ',
      description: '',
      cityCode: '370500',
      inspectionServices: [{
        serviceId: 'i1',
        title: '光伏电站巡检服务',
        summary: '组件热斑检测',
        categoryName: '光伏',
        price: { mode: 'PRICE_FROM', amountMinor: 69900, unit: '/次', note: null },
      }],
      proServices: [cardDto()],
      cities: [{ cityCode: '370100', cityName: '济南市' }],
    })
    const page = await createProServiceApi(http.client).loadProvider('p1', '370500')
    expect(http.urls[0]).toBe('/api/v1/providers/p1?cityCode=370500')
    expect(page.certificationLabel).toBe('企业认证')
    expect(page.description).toBeNull()
    expect(page.inspectionServices[0].price.amountMinor).toBe(69900)
    expect(page.proServices[0].type).toBe('AERIAL')
    expect(page.cities).toEqual([{ cityCode: '370100', cityName: '济南市' }])
  })
})
