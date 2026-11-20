import { describe, expect, it, vi } from 'vitest'

import type { ContentArticleCard, ContentFeedPage } from '../../content/domain/content'
import {
  CITY_NEWS_FEED_SIZE,
  createCityNewsLoader,
  formatCityNewsSource,
  pickCityNews,
} from '../application/home-city-news'

function article(id: string, overrides: Partial<ContentArticleCard> = {}): ContentArticleCard {
  return {
    articleId: id,
    kind: 'KNOWLEDGE',
    title: `文章 ${id}`,
    author: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' },
    sponsored: false,
    cityCode: null,
    coverImage: null,
    sample: true,
    publishedAt: '2026-11-02T02:00:00Z',
    ...overrides,
  }
}

function feed(items: ContentArticleCard[]): ContentFeedPage {
  return { hasMore: false, total: items.length, topics: [], items }
}

describe('home city news', () => {
  it('takes the first article the city operations centre published', () => {
    const provider = article('p', { author: { kind: 'PROVIDER', organizationId: 'o', name: '鹰眼智能巡检' } })
    const sponsored = article('s', { sponsored: true })
    const platform = article('a')

    expect(pickCityNews(feed([provider, sponsored, platform]))?.articleId).toBe('a')
    expect(pickCityNews(feed([provider]))).toBeNull()
  })

  it('names the city and the publisher', () => {
    expect(formatCityNewsSource(article('a'), '东营')).toBe('东营资讯 · 东营城市运营中心')
    expect(formatCityNewsSource(article('a'), '')).toBe('城市资讯 · 东营城市运营中心')
  })

  it('drops an older city answer and clears the block on failure', async () => {
    let resolveFirst: (value: ContentFeedPage) => void = () => undefined
    const loadFeed = vi.fn()
      .mockImplementationOnce(() => new Promise<ContentFeedPage>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(feed([article('second')]))
      .mockRejectedValueOnce(new Error('offline'))
    const shown: Array<string | null> = []
    const loader = createCityNewsLoader({ loadFeed }, {
      setArticle: (value) => { shown.push(value?.articleId ?? null) },
    })

    const first = loader.load('370500')
    await loader.load('370700')
    resolveFirst(feed([article('first')]))
    await first
    await loader.load('370100')

    expect(loadFeed).toHaveBeenCalledWith('RECOMMEND', '370700', 1, CITY_NEWS_FEED_SIZE)
    expect(shown).toEqual([null, null, 'second', null])
  })
})
