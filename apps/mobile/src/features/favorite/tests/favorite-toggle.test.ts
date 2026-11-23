import { beforeEach, describe, expect, it } from 'vitest'

import type { FavoriteApi } from '../data/favorite-api'
import type { FavoriteTargetType } from '../domain/favorite'
import { PENDING_FAVORITE_MS, createFavoriteToggle, resetPendingFavorite } from '../application/favorite-toggle'

const TARGET = { type: 'ACTIVITY' as const, targetId: 'c1000000-0000-4000-8000-000000000001', cityCode: '370500' }

function fakeApi(initial = false, failAdd: unknown = null) {
  let favorited = initial
  const calls: string[] = []
  const state = (type: FavoriteTargetType, targetId: string) => ({ targetType: type, targetId, favorited })
  const api: FavoriteApi = {
    list: async () => { throw new Error('not used') },
    state: async (_token, type, id) => { calls.push('state'); return state(type, id) },
    add: async (_token, type, id) => {
      calls.push('add')
      if (failAdd) throw failAdd
      favorited = true
      return state(type, id)
    },
    remove: async (_token, type, id) => { calls.push('remove'); favorited = false; return state(type, id) },
  }
  return { api, calls }
}

describe('favorite toggle', () => {
  let token: string | null
  let now: number
  let logins: number
  let toasts: string[]

  beforeEach(() => {
    resetPendingFavorite()
    token = 'token'
    now = 1_000_000
    logins = 0
    toasts = []
  })

  function toggleWith(api: FavoriteApi) {
    return createFavoriteToggle({
      api,
      getAccessToken: () => token,
      openLogin: () => { logins += 1 },
      toast: (message) => toasts.push(message),
      now: () => now,
    })
  }

  it('reads the state, favorites and unfavorites for a signed-in account', async () => {
    const { api, calls } = fakeApi(false)
    const toggle = toggleWith(api)

    await toggle.refresh(TARGET)
    expect(toggle.favorited.value).toBe(false)
    await toggle.toggle(TARGET)
    expect(toggle.favorited.value).toBe(true)
    await toggle.toggle(TARGET)
    expect(toggle.favorited.value).toBe(false)
    expect(calls).toEqual(['state', 'add', 'remove'])
    expect(toasts).toEqual(['已收藏', '已取消收藏'])
  })

  it('signed out, opens the login and finishes the favorite on the page it comes back to', async () => {
    token = null
    const { api, calls } = fakeApi(false)
    await toggleWith(api).toggle(TARGET)
    expect(logins).toBe(1)
    expect(calls).toEqual([])

    token = 'token'
    const afterLogin = toggleWith(api)
    await afterLogin.refresh(TARGET)
    expect(afterLogin.favorited.value).toBe(true)
    expect(calls).toEqual(['add'])

    // Only once.
    const again = toggleWith(api)
    await again.refresh(TARGET)
    expect(calls).toEqual(['add', 'state'])
  })

  it('forgets a wish that is too old or about something else', async () => {
    token = null
    const { api, calls } = fakeApi(false)
    await toggleWith(api).toggle(TARGET)
    token = 'token'
    await toggleWith(api).refresh({ ...TARGET, targetId: 'c1000000-0000-4000-8000-000000000002' })
    expect(calls).toEqual(['state'])

    token = null
    await toggleWith(api).toggle(TARGET)
    token = 'token'
    now += PENDING_FAVORITE_MS + 1
    const late = toggleWith(api)
    await late.refresh(TARGET)
    expect(late.favorited.value).toBe(false)
    expect(calls).toEqual(['state', 'state'])
  })

  it('explains why a favorite was refused and sends an expired login to sign in', async () => {
    const gone = fakeApi(false, Object.assign(new Error('x'), { status: 409, code: 'FAVORITE_TARGET_UNAVAILABLE' }))
    const toggle = toggleWith(gone.api)
    await toggle.toggle(TARGET)
    expect(toggle.favorited.value).toBe(false)
    expect(toasts).toEqual(['该内容已下架或当前城市不提供，不能收藏'])

    const expired = fakeApi(false, Object.assign(new Error('x'), { status: 401, code: 'COMMON_UNAUTHORIZED' }))
    await toggleWith(expired.api).toggle(TARGET)
    expect(logins).toBe(1)
  })
})
