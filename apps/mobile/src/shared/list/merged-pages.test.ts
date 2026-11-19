import { describe, expect, it } from 'vitest'

import { loadMoreMerged, mergedHasMore, mergedItems, nextMergedSource, type MergedKeys } from './merged-pages'

interface Row {
  id: string
  at: string
}

const KEYS: MergedKeys<Row> = { idOf: (row) => row.id, timeOf: (row) => row.at }
const row = (id: string, minute: number): Row => ({ id, at: `2026-11-19T10:${String(minute).padStart(2, '0')}:00Z` })

describe('merged pages', () => {
  it('shows complete lists merged newest first', () => {
    const sources = [
      { items: [row('a', 50), row('b', 20)], hasMore: false, page: 1 },
      { items: [row('x', 40), row('y', 10)], hasMore: false, page: 1 },
    ]
    expect(mergedItems(sources, KEYS).map((item) => item.id)).toEqual(['a', 'x', 'b', 'y'])
    expect(mergedHasMore(sources)).toBe(false)
  })

  it('holds back what a list with more pages could still precede', () => {
    const sources = [
      // More pages: its next item is at most 10:30.
      { items: [row('a', 50), row('b', 30)], hasMore: true, page: 1 },
      { items: [row('x', 40), row('y', 10)], hasMore: false, page: 1 },
    ]
    expect(mergedItems(sources, KEYS).map((item) => item.id)).toEqual(['a', 'x', 'b'])
    expect(nextMergedSource(sources, KEYS)).toBe(0)
  })

  it('loads the deciding list until something more can be shown, dropping repeats', async () => {
    const sources = [
      { items: [row('a', 50), row('b', 30)], hasMore: true, page: 1 },
      { items: [row('x', 40), row('y', 10)], hasMore: false, page: 1 },
    ]
    const asked: Array<[number, number]> = []
    const next = await loadMoreMerged(sources, KEYS, async (index, page) => {
      asked.push([index, page])
      return { items: [row('b', 30), row('c', 5)], hasMore: false }
    })
    expect(asked).toEqual([[0, 2]])
    expect(mergedItems(next, KEYS).map((item) => item.id)).toEqual(['a', 'x', 'b', 'y', 'c'])
    expect(mergedHasMore(next)).toBe(false)
  })

  it('stops when a page comes back empty', async () => {
    const sources = [{ items: [row('a', 50)], hasMore: true, page: 1 }]
    const next = await loadMoreMerged(sources, KEYS, async () => ({ items: [], hasMore: true }))
    expect(mergedHasMore(next)).toBe(false)
    expect(mergedItems(next, KEYS).map((item) => item.id)).toEqual(['a'])
  })
})
