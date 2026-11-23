import { describe, expect, it, vi } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

import { createAuthGuard, safeRedirect, type GuardSession } from './guard'

function route(fullPath: string, meta: RouteLocationNormalized['meta'] = {}, query = {}): RouteLocationNormalized {
  const path = fullPath.split('?')[0] ?? fullPath
  return { fullPath, path, meta, query, params: {}, hash: '', name: undefined, matched: [], redirectedFrom: undefined }
}

function session(options: { token: boolean, account: boolean, roles?: string[] }): GuardSession & {
  ensureAccount: ReturnType<typeof vi.fn>
} {
  return {
    hasToken: () => options.token,
    ensureAccount: vi.fn(async () => options.account),
    roles: options.roles ?? [],
  }
}

describe('路由守卫', () => {
  it('没登录去登录页，带上原来要去的页面', async () => {
    const guard = createAuthGuard(session({ token: false, account: false }))

    await expect(guard(route('/organizations?type=PROVIDER'))).resolves.toEqual({
      path: '/login',
      query: { redirect: '/organizations?type=PROVIDER' },
    })
    await expect(guard(route('/'))).resolves.toEqual({ path: '/login', query: {} })
  })

  it('有令牌但读资料失败（令牌失效、没有运营端角色）也去登录页', async () => {
    const guard = createAuthGuard(session({ token: true, account: false }))

    await expect(guard(route('/cities'))).resolves.toMatchObject({ path: '/login' })
  })

  it('登录页公开；已登录再去登录页回到 redirect 指的站内页面', async () => {
    expect(await createAuthGuard(session({ token: false, account: false }))(
      route('/login', { public: true }))).toBe(true)
    const signedIn = createAuthGuard(session({ token: true, account: true, roles: ['PLATFORM_OPERATOR'] }))
    await expect(signedIn(route('/login', { public: true }, { redirect: '/cities' }))).resolves.toBe('/cities')
    await expect(signedIn(route('/login', { public: true }, { redirect: '//evil.example' }))).resolves.toBe('/')
  })

  it('角色不对去无权限页，角色对放行，不写角色的页面只要登录', async () => {
    const support = createAuthGuard(session({ token: true, account: true, roles: ['PLATFORM_SUPPORT'] }))

    await expect(support(route('/cities', { roles: ['PLATFORM_OPERATOR'] }))).resolves.toEqual({
      name: 'forbidden',
      query: { from: '/cities' },
    })
    await expect(support(route('/feedback', { roles: ['PLATFORM_SUPPORT'] }))).resolves.toBe(true)
    await expect(support(route('/'))).resolves.toBe(true)
  })

  it('只接受站内的回跳地址', () => {
    expect(safeRedirect('/organizations/1')).toBe('/organizations/1')
    expect(safeRedirect('https://evil.example')).toBe('/')
    expect(safeRedirect('//evil.example')).toBe('/')
    expect(safeRedirect(['/a'])).toBe('/')
  })
})
