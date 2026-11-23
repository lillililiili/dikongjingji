import { describe, expect, it } from 'vitest'

import type { FavoriteApi } from '../data/favorite-api'
import type { FavoriteCounts, FavoriteGroup, FavoriteItem } from '../domain/favorite'
import { createFavoritesList, groupOf } from '../application/favorites-list'

function item(targetId: string, targetType: FavoriteItem['targetType'] = 'PRODUCT', available = true): FavoriteItem {
  return {
    targetType,
    targetId,
    favoritedAt: '2026-11-22T02:00:00Z',
    available,
    title: targetId,
    meta: null,
    tag: null,
    price: null,
    coverUrl: null,
    productCategory: targetType === 'PRODUCT' && available ? 'ROBOT' : null,
  }
}

function fakeApi(rows: Record<FavoriteGroup, FavoriteItem[]>) {
  const calls: string[] = []
  const counts = (): FavoriteCounts => ({
    GOODS: rows.GOODS.length, SERVICE: rows.SERVICE.length, EVENT: rows.EVENT.length, CONTENT: rows.CONTENT.length,
  })
  const removed: FavoriteItem[] = []
  const api: FavoriteApi = {
    async list(_token, group, cityCode, page) {
      calls.push(`list ${group} ${cityCode} ${page}`)
      return { total: rows[group].length, hasMore: false, counts: counts(), items: [...rows[group]] }
    },
    async state() { throw new Error('not used') },
    async add(_token, type, id) {
      calls.push(`add ${type} ${id}`)
      const back = removed.find((row) => row.targetId === id)
      if (back) rows[groupOf(back)].unshift(back)
      return { targetType: type, targetId: id, favorited: true }
    },
    async remove(_token, type, id) {
      calls.push(`remove ${type} ${id}`)
      for (const group of Object.keys(rows) as FavoriteGroup[]) {
        const index = rows[group].findIndex((row) => row.targetId === id)
        if (index >= 0) removed.push(...rows[group].splice(index, 1))
      }
      return { targetType: type, targetId: id, favorited: false }
    },
  }
  return { api, calls }
}

describe('favorites list (board Favorites)', () => {
  it('reads one tab with the counts of all four, removes with 撤销 and forgets 撤销 on a tab change', async () => {
    const { api, calls } = fakeApi({ GOODS: [item('kit'), item('arm'), item('old', 'PRODUCT', false)], SERVICE: [item('park', 'INSPECTION_SERVICE')], EVENT: [], CONTENT: [] })
    const removed: string[] = []
    const restored: string[] = []
    const list = createFavoritesList({
      api,
      getAccessToken: () => 'token',
      getCityCode: () => '370500',
      onLogin: () => { throw new Error('signed in') },
      toast: () => undefined,
      onRemoved: (row) => removed.push(row.targetId),
      onRestored: (row) => restored.push(row.targetId),
    })

    await list.reload()
    expect(list.state.counts).toEqual({ GOODS: 3, SERVICE: 1, EVENT: 0, CONTENT: 0 })
    expect(list.state.list.items.map((row) => row.targetId)).toEqual(['kit', 'arm', 'old'])

    await list.remove(list.state.list.items[0])
    expect(list.state.list.items.map((row) => row.targetId)).toEqual(['arm', 'old'])
    expect(list.state.counts?.GOODS).toBe(2)
    expect(list.state.undo?.targetId).toBe('kit')
    expect(removed).toEqual(['kit'])

    await list.undo()
    expect(list.state.undo).toBeNull()
    expect(restored).toEqual(['kit'])
    expect(list.state.list.items.map((row) => row.targetId)).toEqual(['kit', 'arm', 'old'])

    await list.remove(list.state.list.items[2])
    await list.selectGroup('SERVICE')
    expect(list.state.undo).toBeNull()
    expect(list.state.list.items.map((row) => row.targetId)).toEqual(['park'])
    expect(calls).toEqual([
      'list GOODS 370500 1', 'remove PRODUCT kit', 'add PRODUCT kit', 'list GOODS 370500 1',
      'remove PRODUCT old', 'list SERVICE 370500 1',
    ])
  })

  it('sends a signed-out visitor to sign in', async () => {
    let logins = 0
    const { api } = fakeApi({ GOODS: [], SERVICE: [], EVENT: [], CONTENT: [] })
    const list = createFavoritesList({
      api,
      getAccessToken: () => null,
      getCityCode: () => '370500',
      onLogin: () => { logins += 1 },
      toast: () => undefined,
    })
    await list.reload()
    expect(logins).toBe(1)
    expect(list.state.list.status).toBe('error')
  })

  it('files every target type under one tab', () => {
    expect(['PRODUCT', 'INSPECTION_SERVICE', 'PRO_SERVICE', 'ACTIVITY', 'VENUE', 'CONTENT']
      .map((type) => groupOf({ targetType: type as FavoriteItem['targetType'] })))
      .toEqual(['GOODS', 'SERVICE', 'SERVICE', 'EVENT', 'EVENT', 'CONTENT'])
  })
})
