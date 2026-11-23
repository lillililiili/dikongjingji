import { reactive } from 'vue'

import type { MallCategory } from '../../mall/domain/mall'

// The products picked for 对比 (boards MallHome, Mall2, Favorites): kept only on this device,
// shared by the mall channel and the favorites page, and kept across restarts in local storage.
// At most three (the boards: “最多 3 件”), all of one mall category.
export const MAX_COMPARE_ITEMS = 3
const STORAGE_KEY = 'mall-compare-selection'

export interface ComparePick {
  productId: string
  category: MallCategory
}

export type CompareToggleResult = 'ADDED' | 'REMOVED' | 'FULL' | 'OTHER_CATEGORY'

export interface CompareSelection {
  readonly picks: readonly ComparePick[]
  isPicked(productId: string): boolean
  // Whether a product that is not picked can be picked now; null when it can.
  blockedReason(pick: ComparePick): 'FULL' | 'OTHER_CATEGORY' | null
  toggle(pick: ComparePick): CompareToggleResult
  remove(productId: string): void
  clear(): void
}

interface Storage {
  read(): unknown
  write(value: ComparePick[]): void
}

const CATEGORIES: readonly string[] = ['DRONE', 'ROBOT', 'PARTS', 'RENTAL', 'COURSE']

function sanitize(value: unknown): ComparePick[] {
  if (!Array.isArray(value)) return []
  const picks: ComparePick[] = []
  for (const entry of value) {
    if (!entry || typeof entry !== 'object') continue
    const { productId, category } = entry as Record<string, unknown>
    if (typeof productId !== 'string' || !productId || typeof category !== 'string' || !CATEGORIES.includes(category)) continue
    if (picks.some((pick) => pick.productId === productId)) continue
    if (picks.length && picks[0].category !== category) continue
    picks.push({ productId, category: category as MallCategory })
    if (picks.length === MAX_COMPARE_ITEMS) break
  }
  return picks
}

export function createCompareSelection(storage: Storage): CompareSelection {
  const state = reactive({ picks: sanitize(storage.read()) })
  const save = () => storage.write(state.picks.map((pick) => ({ ...pick })))

  function blockedReason(pick: ComparePick): 'FULL' | 'OTHER_CATEGORY' | null {
    if (state.picks.some((picked) => picked.productId === pick.productId)) return null
    if (state.picks.length && state.picks[0].category !== pick.category) return 'OTHER_CATEGORY'
    if (state.picks.length >= MAX_COMPARE_ITEMS) return 'FULL'
    return null
  }

  return {
    get picks() { return state.picks },
    isPicked: (productId) => state.picks.some((pick) => pick.productId === productId),
    blockedReason,
    toggle(pick) {
      const index = state.picks.findIndex((picked) => picked.productId === pick.productId)
      if (index >= 0) {
        state.picks.splice(index, 1)
        save()
        return 'REMOVED'
      }
      const blocked = blockedReason(pick)
      if (blocked) return blocked
      state.picks.push({ productId: pick.productId, category: pick.category })
      save()
      return 'ADDED'
    },
    remove(productId) {
      const index = state.picks.findIndex((pick) => pick.productId === productId)
      if (index < 0) return
      state.picks.splice(index, 1)
      save()
    },
    clear() {
      state.picks.splice(0, state.picks.length)
      save()
    },
  }
}

// Local storage can be missing or refuse (private mode, a full disk): the selection then only
// lasts while the app runs.
const uniStorage: Storage = {
  read() {
    try {
      return uni.getStorageSync(STORAGE_KEY)
    } catch {
      return null
    }
  },
  write(value) {
    try {
      uni.setStorageSync(STORAGE_KEY, value)
    } catch {
      // Kept in memory only.
    }
  },
}

let shared: CompareSelection | null = null

export function useCompareSelection(): CompareSelection {
  shared ??= createCompareSelection(uniStorage)
  return shared
}

export const COMPARE_BLOCKED_MESSAGES: Record<'FULL' | 'OTHER_CATEGORY', string> = {
  FULL: `最多对比 ${MAX_COMPARE_ITEMS} 件商品`,
  OTHER_CATEGORY: '只能对比同一类目的商品，请先取消已选的',
}
