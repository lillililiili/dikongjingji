import { describe, expect, it } from 'vitest'

import { createContentApi } from '../data/content-api'
import { CITY, articleCardDto } from './content-fixtures'

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

const TOPIC = {
  topicId: 'f3100000-0000-4000-8000-000000000001',
  title: '黄河口低空体验季',
  subtitle: ' 到馆体验、赛事、研学合集 ',
  publisher: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' },
  startsOn: '2026-11-01',
  endsOn: '2026-11-30',
  coverImage: null,
  sample: true,
}

describe('content API', () => {
  it('reads a feed page by city, channel and page, keeping only HTTPS pictures', async () => {
    const http = fakeHttp({
      channel: 'RECOMMEND',
      cityCode: CITY,
      page: 1,
      size: 10,
      total: 2,
      hasMore: false,
      topics: [TOPIC],
      items: [
        articleCardDto({ coverImage: { url: 'http://cdn.example/a.jpg', alt: 'a' } }),
        articleCardDto({
          articleId: 'f3000000-0000-4000-8000-000000000005',
          kind: 'KNOWLEDGE',
          cityCode: null,
          author: { kind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001', name: '示例机器人商家' },
          sponsored: false,
          coverImage: { url: 'https://cdn.example/b.jpg', alt: 'b' },
        }),
      ],
    })
    const page = await createContentApi(http.client).loadFeed('RECOMMEND', CITY, 1, 10)

    expect(http.urls).toEqual([`/api/v1/content/feed?cityCode=${CITY}&channel=RECOMMEND&page=1&size=10`])
    expect(page.hasMore).toBe(false)
    expect(page.topics).toEqual([{ ...TOPIC, subtitle: '到馆体验、赛事、研学合集' }])
    expect(page.items[0].coverImage).toBeNull()
    expect(page.items[1]).toMatchObject({
      kind: 'KNOWLEDGE',
      cityCode: null,
      // A merchant's content is an advertisement even when a row forgets to say so.
      sponsored: true,
      coverImage: { url: 'https://cdn.example/b.jpg', alt: 'b' },
    })
  })

  it('maps an article: unknown or empty blocks are left out, mentions keep their order once each', async () => {
    const http = fakeHttp({
      ...articleCardDto(),
      blocks: [
        { type: 'FACTS', text: null, items: [{ title: '场景', text: '物流园区夜间安防' }, { title: ' ', text: 'x' }] },
        { type: 'PARAGRAPH', text: ' 白天人多车多 ', items: [] },
        { type: 'VIDEO', text: 'not yet', items: [] },
        { type: 'HEADING', text: '  ', items: [] },
        { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }] },
      ],
      refs: [
        { type: 'INSPECTION_SERVICE', refId: '77777777-7777-7777-7777-777777777777', title: '园区综合巡检服务' },
        { type: 'COUPON', refId: 'x', title: '优惠券' },
        { type: 'PRODUCT', refId: 'c2000000-0000-4000-8000-000000000401', title: '巡检机器狗' },
        { type: 'INSPECTION_SERVICE', refId: '77777777-7777-7777-7777-777777777777', title: '重复' },
      ],
    })
    const article = await createContentApi(http.client).loadArticle('f3000000-0000-4000-8000-000000000001', CITY)

    expect(http.urls).toEqual([`/api/v1/content/articles/f3000000-0000-4000-8000-000000000001?cityCode=${CITY}`])
    expect(article.blocks).toEqual([
      { type: 'FACTS', text: null, items: [{ title: '场景', text: '物流园区夜间安防' }] },
      { type: 'PARAGRAPH', text: '白天人多车多', items: [] },
      { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }] },
    ])
    expect(article.refs.map((ref) => `${ref.type}:${ref.title}`)).toEqual([
      'INSPECTION_SERVICE:园区综合巡检服务',
      'PRODUCT:巡检机器狗',
    ])
  })

  it('maps a topic with its activity, venue and article ids', async () => {
    const http = fakeHttp({
      ...TOPIC,
      cityCode: CITY,
      activityIds: ['a1', 'a2', 'a1'],
      venueIds: ['v1'],
      articles: [articleCardDto()],
    })
    const topic = await createContentApi(http.client).loadTopic(TOPIC.topicId, CITY)

    expect(http.urls).toEqual([`/api/v1/content/topics/${TOPIC.topicId}?cityCode=${CITY}`])
    expect(topic.activityIds).toEqual(['a1', 'a2'])
    expect(topic.venueIds).toEqual(['v1'])
    expect(topic.articles).toHaveLength(1)
    expect(topic.publisher).toEqual({ kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' })
  })
})
