import { describe, expect, it } from 'vitest'

import type { ActivityListQuery, ActivityPage } from '../../activity/domain/activity'
import {
  createDiscoverFeedLoader,
  createDiscoverFeedSource,
  emptyDiscoverFeed,
  type DiscoverFeedPage,
  type DiscoverFeedSource,
  type DiscoverFeedState,
} from '../application/discover-feed'
import type { ContentFeedChannel, ContentFeedPage, DiscoverChannel, DiscoverEntry } from '../domain/content'
import { CITY, activityCard, articleCard } from './content-fixtures'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function articleEntry(id: string): DiscoverEntry {
  return { kind: 'ARTICLE', key: `article:${id}`, article: articleCard({ articleId: id }) }
}

function harness(source: DiscoverFeedSource) {
  let state: DiscoverFeedState = emptyDiscoverFeed('RECOMMEND', CITY)
  const loader = createDiscoverFeedLoader({
    source,
    getState: () => state,
    setState: (next) => { state = next },
  })
  return { loader, state: () => state }
}

describe('discover feed source', () => {
  it('reads 推荐 / 案例 / 知识 from the content feed, with topics on the first page only', async () => {
    const calls: Array<[ContentFeedChannel, string, number, number]> = []
    const page: ContentFeedPage = {
      hasMore: true,
      total: 3,
      topics: [{
        topicId: 't1',
        title: '黄河口低空体验季',
        subtitle: null,
        publisher: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' },
        startsOn: '2026-11-01',
        endsOn: '2026-11-30',
        coverImage: null,
        sample: true,
      }],
      items: [articleCard()],
    }
    const source = createDiscoverFeedSource({
      content: { loadFeed: async (...args) => { calls.push(args); return page } },
      activity: { loadPage: async () => { throw new Error('not used') } },
      pageSize: 4,
    })

    const first = await source.loadPage('RECOMMEND', CITY, 1)
    const second = await source.loadPage('KNOWLEDGE', CITY, 2)

    expect(calls).toEqual([['RECOMMEND', CITY, 1, 4], ['KNOWLEDGE', CITY, 2, 4]])
    expect(first.topics).toHaveLength(1)
    expect(first.entries[0]).toMatchObject({ kind: 'ARTICLE', key: `article:${articleCard().articleId}` })
    expect(second.topics).toEqual([])
  })

  it('reads 赛事 and 研学 from the activity list: 研学 by type, 赛事 everything but study tours', async () => {
    const queries: ActivityListQuery[] = []
    const all: ActivityPage = {
      page: 1,
      size: 10,
      total: 4,
      hasMore: false,
      items: [
        activityCard('a1', 'CONTEST'),
        activityCard('a2', 'STUDY_TOUR'),
        activityCard('a3', 'OPEN_DAY'),
        activityCard('a4', 'TRAINING'),
      ],
    }
    const source = createDiscoverFeedSource({
      content: { loadFeed: async () => { throw new Error('not used') } },
      activity: { loadPage: async (query) => { queries.push(query); return all } },
    })

    const events = await source.loadPage('EVENTS', CITY, 1)
    const study = await source.loadPage('STUDY', CITY, 1)

    expect(queries.map((query) => query.type)).toEqual([null, 'STUDY_TOUR'])
    expect(queries[0]).toMatchObject({ cityCode: CITY, date: null, enrollingOnly: false, freeOnly: false })
    expect(events.entries.map((entry) => entry.key)).toEqual(['activity:a1', 'activity:a3', 'activity:a4'])
    expect(study.entries.map((entry) => entry.key)).toEqual(['activity:a2'])
  })
})

describe('discover feed loader', () => {
  it('keeps the latest channel when an earlier channel answers last', async () => {
    const knowledge = deferred<DiscoverFeedPage>()
    const recommend = deferred<DiscoverFeedPage>()
    const pending: Record<string, ReturnType<typeof deferred<DiscoverFeedPage>>> = { KNOWLEDGE: knowledge, RECOMMEND: recommend }
    const { loader, state } = harness({ loadPage: (channel) => pending[channel].promise })

    const first = loader.reload('KNOWLEDGE', CITY)
    const second = loader.reload('RECOMMEND', CITY)
    recommend.resolve({ entries: [articleEntry('r1')], hasMore: false, topics: [] })
    await second
    knowledge.resolve({ entries: [articleEntry('k1')], hasMore: false, topics: [] })
    await first

    expect(state()).toMatchObject({ status: 'ready', channel: 'RECOMMEND' })
    expect(state().entries.map((entry) => entry.key)).toEqual(['article:r1'])
  })

  it('drops a next page that belongs to the channel left meanwhile', async () => {
    const more = deferred<DiscoverFeedPage>()
    const answers: Array<Promise<DiscoverFeedPage>> = [
      Promise.resolve({ entries: [articleEntry('c1')], hasMore: true, topics: [] }),
      more.promise,
      Promise.resolve({ entries: [articleEntry('k1')], hasMore: false, topics: [] }),
    ]
    const { loader, state } = harness({ loadPage: () => answers.shift() as Promise<DiscoverFeedPage> })

    await loader.reload('CASE', CITY)
    const loading = loader.loadMore()
    await loader.reload('KNOWLEDGE', CITY)
    more.resolve({ entries: [articleEntry('c2')], hasMore: false, topics: [] })
    await loading

    expect(state().channel).toBe('KNOWLEDGE')
    expect(state().entries.map((entry) => entry.key)).toEqual(['article:k1'])
  })

  it('pages on with the next page number, keeps one copy of each entry and stops at the end', async () => {
    const pages: number[] = []
    const { loader, state } = harness({
      async loadPage(_channel, _city, page) {
        pages.push(page)
        if (page === 1) return { entries: [articleEntry('a'), articleEntry('b')], hasMore: true, topics: [] }
        return { entries: [articleEntry('b'), articleEntry('c')], hasMore: false, topics: [] }
      },
    })

    await loader.reload('RECOMMEND', CITY)
    await loader.loadMore()
    await loader.loadMore()

    expect(pages).toEqual([1, 2])
    expect(state().entries.map((entry) => entry.key)).toEqual(['article:a', 'article:b', 'article:c'])
    expect(state().hasMore).toBe(false)
  })

  it('keeps the list when the next page fails and loads it again on retry', async () => {
    let fail = true
    const { loader, state } = harness({
      async loadPage(_channel, _city, page) {
        if (page === 1) return { entries: [articleEntry('a')], hasMore: true, topics: [] }
        if (fail) throw new Error('offline')
        return { entries: [articleEntry('b')], hasMore: false, topics: [] }
      },
    })

    await loader.reload('RECOMMEND', CITY)
    await loader.loadMore()
    expect(state()).toMatchObject({ status: 'ready', loadMoreFailed: true, loadingMore: false })
    expect(state().entries).toHaveLength(1)

    fail = false
    await loader.loadMore()
    expect(state().entries.map((entry) => entry.key)).toEqual(['article:a', 'article:b'])
    expect(state().loadMoreFailed).toBe(false)
  })

  it('shows an error when the first page fails', async () => {
    const { loader, state } = harness({ loadPage: async () => { throw new Error('offline') } })
    await loader.reload('EVENTS', CITY)
    expect(state()).toMatchObject({ status: 'error', channel: 'EVENTS', entries: [] })
  })

  it('reads on past pages that keep nothing (赛事 without study tours)', async () => {
    const pages: number[] = []
    const { loader, state } = harness({
      async loadPage(_channel: DiscoverChannel, _city, page) {
        pages.push(page)
        if (page < 3) return { entries: [], hasMore: true, topics: [] }
        return { entries: [articleEntry('x')], hasMore: true, topics: [] }
      },
    })

    await loader.reload('EVENTS', CITY)
    expect(pages).toEqual([1, 2, 3])
    expect(state().entries).toHaveLength(1)

    await loader.loadMore()
    expect(pages).toEqual([1, 2, 3, 4])
  })
})
