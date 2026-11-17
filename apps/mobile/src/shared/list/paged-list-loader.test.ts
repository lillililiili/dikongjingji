import { describe, expect, it } from 'vitest'

import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
  type PagedResult,
} from './paged-list-loader'

interface Row { id: string }

const rows = (...ids: string[]): Row[] => ids.map((id) => ({ id }))

function setup(loadPage: (query: string, page: number, size: number) => Promise<PagedResult<Row>>) {
  let state: PagedListState<Row> = emptyPagedList()
  const loader = createPagedListLoader<string, Row>({
    loadPage,
    getState: () => state,
    setState: (next) => { state = next },
    keyOf: (row) => row.id,
    pageSize: 2,
  })
  return { loader, state: () => state }
}

describe('paged list loader', () => {
  it('loads the first page, appends the next one and keeps one copy of a repeated row', async () => {
    const calls: [string, number, number][] = []
    const { loader, state } = setup(async (query, page, size) => {
      calls.push([query, page, size])
      return page === 1 ? { hasMore: true, items: rows('a', 'b') } : { hasMore: false, items: rows('b', 'c') }
    })

    await loader.reload('q')
    await loader.loadMore()
    await loader.loadMore()

    expect(state().items.map((row) => row.id)).toEqual(['a', 'b', 'c'])
    expect(state().hasMore).toBe(false)
    expect(calls).toEqual([['q', 1, 2], ['q', 2, 2]])
  })

  it('lets only the latest reload write the list', async () => {
    let releaseSlow!: (value: PagedResult<Row>) => void
    const slow = new Promise<PagedResult<Row>>((resolve) => { releaseSlow = resolve })
    const { loader, state } = setup((query) =>
      query === 'slow' ? slow : Promise.resolve({ hasMore: false, items: rows('fresh') }))

    const first = loader.reload('slow')
    await loader.reload('fast')
    releaseSlow({ hasMore: false, items: rows('stale') })
    await first

    expect(state().items.map((row) => row.id)).toEqual(['fresh'])
  })

  it('reports a failed first page as an error and a failed next page without losing the list', async () => {
    let call = 0
    const { loader, state } = setup(async () => {
      call += 1
      if (call === 1 || call === 3) throw new Error('offline')
      return call === 2 ? { hasMore: true, items: rows('a', 'b') } : { hasMore: false, items: rows('c') }
    })

    await loader.reload('q')
    expect(state().status).toBe('error')

    await loader.reload('q')
    await loader.loadMore()
    expect(state()).toMatchObject({ status: 'ready', loadMoreFailed: true, loadingMore: false })
    expect(state().items).toHaveLength(2)

    await loader.loadMore()
    expect(state().items.map((row) => row.id)).toEqual(['a', 'b', 'c'])
  })

  it('does nothing before the first reload', async () => {
    let called = false
    const { loader, state } = setup(async () => { called = true; return { hasMore: false, items: [] } })

    await loader.loadMore()

    expect(called).toBe(false)
    expect(state().status).toBe('idle')
  })
})
