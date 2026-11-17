export type PagedListStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface PagedListState<Item> {
  status: PagedListStatus
  items: Item[]
  hasMore: boolean
  loadingMore: boolean
  // True when loading the next page failed; the first page failing is status 'error'.
  loadMoreFailed: boolean
}

export interface PagedResult<Item> {
  hasMore: boolean
  items: Item[]
}

export function emptyPagedList<Item>(): PagedListState<Item> {
  return { status: 'idle', items: [], hasMore: false, loadingMore: false, loadMoreFailed: false }
}

export interface PagedListLoader<Query> {
  reload(query: Query): Promise<void>
  loadMore(): Promise<void>
}

/**
 * Loads a server-paged list. Every reload gets a new revision; a response that belongs to an older
 * revision is dropped, so quick filter changes and a slow page can never overwrite the latest list.
 */
export function createPagedListLoader<Query, Item>(options: {
  loadPage(query: Query, page: number, size: number): Promise<PagedResult<Item>>
  getState(): PagedListState<Item>
  setState(state: PagedListState<Item>): void
  keyOf(item: Item): string
  pageSize: number
}): PagedListLoader<Query> {
  let revision = 0
  let currentQuery: { value: Query } | null = null
  let nextPage = 1

  return {
    async reload(query) {
      const requested = ++revision
      currentQuery = { value: query }
      nextPage = 1
      options.setState({ ...emptyPagedList<Item>(), status: 'loading' })
      try {
        const page = await options.loadPage(query, 1, options.pageSize)
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
        options.setState({ ...emptyPagedList<Item>(), status: 'error' })
      }
    },

    async loadMore() {
      const state = options.getState()
      if (!currentQuery || state.status !== 'ready' || !state.hasMore || state.loadingMore) return

      const requested = revision
      const query = currentQuery.value
      options.setState({ ...state, loadingMore: true, loadMoreFailed: false })
      try {
        const page = await options.loadPage(query, nextPage, options.pageSize)
        if (requested !== revision) return
        nextPage += 1
        // A row can move onto the next page between two requests; keep one copy.
        const known = new Set(state.items.map(options.keyOf))
        options.setState({
          status: 'ready',
          items: [...state.items, ...page.items.filter((item) => !known.has(options.keyOf(item)))],
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
