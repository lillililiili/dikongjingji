import type { ActivityApi } from '../../activity/data/activity-api'
import type { ActivityType } from '../../activity/domain/activity'

import type { ContentApi } from '../data/content-api'
import {
  CONTENT_FEED_PAGE_SIZE,
  type ContentTopicCard,
  type DiscoverChannel,
  type DiscoverEntry,
} from '../domain/content'

export interface DiscoverFeedPage {
  entries: DiscoverEntry[]
  hasMore: boolean
  topics: ContentTopicCard[]
}

export interface DiscoverFeedSource {
  loadPage(channel: DiscoverChannel, cityCode: string, page: number): Promise<DiscoverFeedPage>
}

// 赛事 shows contests, training and open days (the board's 正在报名); 研学 shows study tours. The
// activity list takes one type or all, so 赛事 reads all and leaves the study tours to 研学.
const EVENT_TYPES: readonly ActivityType[] = ['CONTEST', 'TRAINING', 'OPEN_DAY']

/**
 * Reads one page of a discover channel: 推荐 / 视频 / 案例 / 知识 from the content module (推荐 mixes
 * articles and videos), 赛事 / 研学 straight from the activity module's list (the same API the
 * 赛事研学 channel uses).
 */
export function createDiscoverFeedSource(apis: {
  content: Pick<ContentApi, 'loadFeed'>
  activity: Pick<ActivityApi, 'loadPage'>
  pageSize?: number
}): DiscoverFeedSource {
  const size = apis.pageSize ?? CONTENT_FEED_PAGE_SIZE
  return {
    async loadPage(channel, cityCode, page) {
      if (channel === 'EVENTS' || channel === 'STUDY') {
        const result = await apis.activity.loadPage({
          cityCode,
          type: channel === 'STUDY' ? 'STUDY_TOUR' : null,
          date: null,
          enrollingOnly: false,
          freeOnly: false,
        }, page, size)
        const wanted = channel === 'STUDY'
          ? result.items.filter((item) => item.type === 'STUDY_TOUR')
          : result.items.filter((item) => EVENT_TYPES.includes(item.type))
        return {
          entries: wanted.map((activity) => ({ kind: 'ACTIVITY', key: `activity:${activity.activityId}`, activity })),
          hasMore: result.hasMore,
          topics: [],
        }
      }
      // 直播 is the live module's list; while its tab is hidden nothing asks for it.
      if (channel === 'LIVE') return { entries: [], hasMore: false, topics: [] }
      const feed = await apis.content.loadFeed(channel, cityCode, page, size)
      return {
        entries: feed.items.map((article) => ({ kind: 'ARTICLE', key: `article:${article.articleId}`, article })),
        hasMore: feed.hasMore,
        topics: page === 1 ? feed.topics : [],
      }
    },
  }
}

export type DiscoverFeedStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface DiscoverFeedState {
  status: DiscoverFeedStatus
  channel: DiscoverChannel
  cityCode: string
  topics: ContentTopicCard[]
  entries: DiscoverEntry[]
  hasMore: boolean
  loadingMore: boolean
  // Loading the next page failed; the first page failing is status 'error'.
  loadMoreFailed: boolean
}

export function emptyDiscoverFeed(channel: DiscoverChannel, cityCode: string): DiscoverFeedState {
  return {
    status: 'idle',
    channel,
    cityCode,
    topics: [],
    entries: [],
    hasMore: false,
    loadingMore: false,
    loadMoreFailed: false,
  }
}

export interface DiscoverFeedLoader {
  reload(channel: DiscoverChannel, cityCode: string): Promise<void>
  loadMore(): Promise<void>
}

/**
 * Loads a discover channel page by page. Every reload (a channel tab, a city change, 重新加载) gets
 * a new revision and an answer for an older one is dropped, so tapping 知识 and then 推荐 quickly
 * never leaves 知识's list under the 推荐 tab. A page that keeps nothing (赛事 reads every activity
 * and leaves the study tours out) is followed by the next one, up to a few pages.
 */
export function createDiscoverFeedLoader(options: {
  source: DiscoverFeedSource
  getState(): DiscoverFeedState
  setState(state: DiscoverFeedState): void
  maxEmptyPages?: number
}): DiscoverFeedLoader {
  const maxEmptyPages = options.maxEmptyPages ?? 4
  let revision = 0
  let nextPage = 1

  async function fetchFrom(channel: DiscoverChannel, cityCode: string, first: number) {
    let page = first
    let result = await options.source.loadPage(channel, cityCode, page)
    const topics = result.topics
    for (let skipped = 0; result.entries.length === 0 && result.hasMore && skipped < maxEmptyPages; skipped += 1) {
      page += 1
      result = await options.source.loadPage(channel, cityCode, page)
    }
    return { ...result, topics, lastPage: page }
  }

  return {
    async reload(channel, cityCode) {
      const requested = ++revision
      nextPage = 1
      options.setState({ ...emptyDiscoverFeed(channel, cityCode), status: 'loading' })
      try {
        const result = await fetchFrom(channel, cityCode, 1)
        if (requested !== revision) return
        nextPage = result.lastPage + 1
        options.setState({
          status: 'ready',
          channel,
          cityCode,
          topics: result.topics,
          entries: result.entries,
          hasMore: result.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        })
      } catch {
        if (requested !== revision) return
        options.setState({ ...emptyDiscoverFeed(channel, cityCode), status: 'error' })
      }
    },

    async loadMore() {
      const state = options.getState()
      if (state.status !== 'ready' || !state.hasMore || state.loadingMore) return
      const requested = revision
      options.setState({ ...state, loadingMore: true, loadMoreFailed: false })
      try {
        const result = await fetchFrom(state.channel, state.cityCode, nextPage)
        if (requested !== revision) return
        nextPage = result.lastPage + 1
        // Something published between two requests shifts the pages; keep one copy of each.
        const known = new Set(state.entries.map((entry) => entry.key))
        options.setState({
          ...state,
          entries: [...state.entries, ...result.entries.filter((entry) => !known.has(entry.key))],
          hasMore: result.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        })
      } catch {
        if (requested !== revision) return
        options.setState({ ...state, loadingMore: false, loadMoreFailed: true })
      }
    },
  }
}
