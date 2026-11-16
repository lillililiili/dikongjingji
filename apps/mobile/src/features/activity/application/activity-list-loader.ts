import {
  ACTIVITY_PAGE_SIZE,
  type ActivityCard,
  type ActivityListQuery,
  type ActivityPage,
} from '../domain/activity'

export type ActivityListStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface ActivityListState {
  status: ActivityListStatus
  items: ActivityCard[]
  hasMore: boolean
  loadingMore: boolean
  // True when loading the next page failed; the first page failing is status 'error'.
  loadMoreFailed: boolean
}

export const EMPTY_ACTIVITY_LIST: ActivityListState = {
  status: 'idle',
  items: [],
  hasMore: false,
  loadingMore: false,
  loadMoreFailed: false,
}

export interface ActivityListLoader {
  reload(query: ActivityListQuery): Promise<void>
  loadMore(): Promise<void>
}

/**
 * Loads the channel list page by page. Every reload gets a new revision; a response that belongs to an
 * older revision is dropped, so quick filter changes and a slow page can never overwrite the latest list.
 */
export function createActivityListLoader(options: {
  loadPage(query: ActivityListQuery, page: number, size: number): Promise<ActivityPage>
  getState(): ActivityListState
  setState(state: ActivityListState): void
  pageSize?: number
}): ActivityListLoader {
  const pageSize = options.pageSize ?? ACTIVITY_PAGE_SIZE
  let revision = 0
  let currentQuery: ActivityListQuery | null = null
  let nextPage = 1

  return {
    async reload(query) {
      const requested = ++revision
      currentQuery = query
      nextPage = 1
      options.setState({ ...EMPTY_ACTIVITY_LIST, status: 'loading' })
      try {
        const page = await options.loadPage(query, 1, pageSize)
        if (requested !== revision) return
        nextPage = 2
        options.setState({
          status: 'ready',
          items: page.items,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        })
      } catch {
        if (requested !== revision) return
        options.setState({ ...EMPTY_ACTIVITY_LIST, status: 'error' })
      }
    },

    async loadMore() {
      const state = options.getState()
      if (
        !currentQuery
        || state.status !== 'ready'
        || !state.hasMore
        || state.loadingMore
      ) return

      const requested = revision
      const query = currentQuery
      options.setState({ ...state, loadingMore: true, loadMoreFailed: false })
      try {
        const page = await options.loadPage(query, nextPage, pageSize)
        if (requested !== revision) return
        nextPage += 1
        // A session filling up between two requests can shift an activity onto the next page; keep one copy.
        const known = new Set(state.items.map((item) => item.activityId))
        options.setState({
          status: 'ready',
          items: [...state.items, ...page.items.filter((item) => !known.has(item.activityId))],
          hasMore: page.hasMore,
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
