import { reactive } from 'vue'

import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '../../../shared/list/paged-list-loader'
import type { FavoriteApi } from '../data/favorite-api'
import {
  FAVORITE_PAGE_SIZE,
  type FavoriteCounts,
  type FavoriteGroup,
  type FavoriteItem,
} from '../domain/favorite'

import { favoriteErrorMessage } from './favorite-toggle'

// The list of board Favorites: one tab (group) at a time, newest first, with the counts of all four
// tabs; 取消收藏 takes a row away at once and offers 撤销 until the next tab change or removal.

export interface FavoritesListState {
  group: FavoriteGroup
  counts: FavoriteCounts | null
  list: PagedListState<FavoriteItem>
  // The row just removed, while 撤销 is offered.
  undo: FavoriteItem | null
  busy: boolean
}

export interface FavoritesListDeps {
  api: FavoriteApi
  getAccessToken(): string | null
  getCityCode(): string
  // No (or an expired) login: the page sends the user to sign in.
  onLogin(): void
  toast(message: string): void
  // A product left (or came back to) the favorites: the comparison follows.
  onRemoved?(item: FavoriteItem): void
  onRestored?(item: FavoriteItem): void
}

const isUnauthorized = (error: unknown) =>
  Boolean(error && typeof error === 'object' && 'status' in error && error.status === 401)

export function createFavoritesList(deps: FavoritesListDeps, group: FavoriteGroup = 'GOODS') {
  const state = reactive<FavoritesListState>({
    group,
    counts: null,
    list: emptyPagedList<FavoriteItem>(),
    undo: null,
    busy: false,
  })

  const loader = createPagedListLoader<FavoriteGroup, FavoriteItem>({
    async loadPage(selected, page, size) {
      const token = deps.getAccessToken()
      if (!token) {
        deps.onLogin()
        throw new Error('login required')
      }
      try {
        const result = await deps.api.list(token, selected, deps.getCityCode(), page, size)
        state.counts = result.counts
        return result
      } catch (error) {
        if (isUnauthorized(error)) deps.onLogin()
        throw error
      }
    },
    getState: () => state.list,
    setState: (next) => { state.list = next },
    keyOf: (item) => `${item.targetType}:${item.targetId}`,
    pageSize: FAVORITE_PAGE_SIZE,
  })

  async function reload(): Promise<void> {
    await loader.reload(state.group)
  }

  async function selectGroup(next: FavoriteGroup): Promise<void> {
    // 撤销 only applies to the list it was offered on (board Favorites).
    state.undo = null
    if (next === state.group && state.list.status === 'ready') return
    state.group = next
    await reload()
  }

  function adjustCount(item: FavoriteItem, by: number): void {
    if (!state.counts) return
    const owner = groupOf(item)
    state.counts = { ...state.counts, [owner]: Math.max(0, state.counts[owner] + by) }
  }

  async function remove(item: FavoriteItem): Promise<void> {
    const token = deps.getAccessToken()
    if (!token) {
      deps.onLogin()
      return
    }
    if (state.busy) return
    state.busy = true
    try {
      await deps.api.remove(token, item.targetType, item.targetId)
      state.list = {
        ...state.list,
        items: state.list.items.filter((row) => !(row.targetType === item.targetType && row.targetId === item.targetId)),
      }
      adjustCount(item, -1)
      state.undo = item
      deps.onRemoved?.(item)
    } catch (error) {
      if (isUnauthorized(error)) deps.onLogin()
      else deps.toast(favoriteErrorMessage(error))
    } finally {
      state.busy = false
    }
  }

  // Favoriting again within ten minutes restores the same row (its time and title), even when the
  // target is gone; the list is read again so the row is back in its place.
  async function undo(): Promise<void> {
    const item = state.undo
    const token = deps.getAccessToken()
    if (!item || !token || state.busy) return
    state.busy = true
    try {
      await deps.api.add(token, item.targetType, item.targetId, deps.getCityCode())
      state.undo = null
      deps.onRestored?.(item)
      await reload()
    } catch (error) {
      if (isUnauthorized(error)) deps.onLogin()
      else deps.toast(favoriteErrorMessage(error))
    } finally {
      state.busy = false
    }
  }

  function dismissUndo(): void {
    state.undo = null
  }

  return {
    state,
    reload,
    loadMore: () => loader.loadMore(),
    selectGroup,
    remove,
    undo,
    dismissUndo,
  }
}

export function groupOf(item: Pick<FavoriteItem, 'targetType'>): FavoriteGroup {
  switch (item.targetType) {
    case 'PRODUCT': return 'GOODS'
    case 'INSPECTION_SERVICE':
    case 'PRO_SERVICE': return 'SERVICE'
    case 'ACTIVITY':
    case 'VENUE': return 'EVENT'
    case 'CONTENT': return 'CONTENT'
  }
}
