import { ref, type Ref } from 'vue'

import type { FavoriteApi } from '../data/favorite-api'
import type { FavoriteTargetType } from '../domain/favorite'

// The 收藏 / 已收藏 button of a detail page (boards ActDetail, ContentVideo, ContentArticle, Product).
// Signed out, a tap remembers the wish and opens the login page; the login page comes back to the
// same page (a new page), and the button there finishes the favorite once it reads the state.

export interface FavoriteTarget {
  type: FavoriteTargetType
  targetId: string
  cityCode: string
}

export interface FavoriteToggleDeps {
  api: FavoriteApi
  getAccessToken(): string | null
  openLogin(): void
  toast(message: string): void
  now?(): number
}

export interface FavoriteToggle {
  favorited: Ref<boolean>
  busy: Ref<boolean>
  // Reads the state for the signed-in account (and finishes a favorite asked for before signing in).
  refresh(target: FavoriteTarget): Promise<void>
  toggle(target: FavoriteTarget): Promise<void>
}

// A wish made before signing in counts only for a while: a login abandoned and a page opened much
// later must not favorite on its own.
export const PENDING_FAVORITE_MS = 10 * 60 * 1000
let pending: { type: FavoriteTargetType; targetId: string; at: number } | null = null

export function resetPendingFavorite(): void {
  pending = null
}

function errorCode(error: unknown): string | null {
  return error && typeof error === 'object' && 'code' in error && typeof error.code === 'string' ? error.code : null
}

function errorStatus(error: unknown): number | null {
  return error && typeof error === 'object' && 'status' in error && typeof error.status === 'number' ? error.status : null
}

export function favoriteErrorMessage(error: unknown): string {
  const code = errorCode(error)
  if (code === 'FAVORITE_TARGET_UNAVAILABLE') return '该内容已下架或当前城市不提供，不能收藏'
  if (code === 'FAVORITE_LIMIT_REACHED') return '收藏已满 500 条，请先取消一些'
  if (code === 'NETWORK_ERROR') return '网络连接失败，请稍后重试'
  return '操作没有成功，请稍后重试'
}

export function createFavoriteToggle(deps: FavoriteToggleDeps): FavoriteToggle {
  const favorited = ref(false)
  const busy = ref(false)
  const now = deps.now ?? (() => Date.now())

  function needsLogin(error: unknown): boolean {
    return errorStatus(error) === 401
  }

  async function add(target: FavoriteTarget, token: string): Promise<void> {
    const state = await deps.api.add(token, target.type, target.targetId, target.cityCode)
    favorited.value = state.favorited
    deps.toast('已收藏')
  }

  async function refresh(target: FavoriteTarget): Promise<void> {
    const token = deps.getAccessToken()
    if (!token) {
      favorited.value = false
      return
    }
    const wish = pending
    if (wish && wish.type === target.type && wish.targetId === target.targetId) {
      pending = null
      if (now() - wish.at <= PENDING_FAVORITE_MS) {
        busy.value = true
        try {
          await add(target, token)
          return
        } catch (error) {
          deps.toast(favoriteErrorMessage(error))
        } finally {
          busy.value = false
        }
      }
    }
    try {
      favorited.value = (await deps.api.state(token, target.type, target.targetId)).favorited
    } catch {
      // Unknown: drawn as not favorited; a tap is idempotent either way.
      favorited.value = false
    }
  }

  async function toggle(target: FavoriteTarget): Promise<void> {
    if (busy.value) return
    const token = deps.getAccessToken()
    if (!token) {
      pending = { type: target.type, targetId: target.targetId, at: now() }
      deps.openLogin()
      return
    }
    busy.value = true
    try {
      if (favorited.value) {
        favorited.value = (await deps.api.remove(token, target.type, target.targetId)).favorited
        deps.toast('已取消收藏')
      } else {
        await add(target, token)
      }
    } catch (error) {
      if (needsLogin(error)) {
        pending = { type: target.type, targetId: target.targetId, at: now() }
        deps.openLogin()
      } else {
        deps.toast(favoriteErrorMessage(error))
      }
    } finally {
      busy.value = false
    }
  }

  return { favorited, busy, refresh, toggle }
}
