import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'

import { hasAnyRole } from '@/core/auth/roles'

export interface GuardSession {
  hasToken(): boolean
  ensureAccount(): Promise<boolean>
  readonly roles: readonly string[]
}

export const LOGIN_PATH = '/login'
export const HOME_PATH = '/'
export const FORBIDDEN_NAME = 'forbidden'

/** 登录后回到原来要去的页面；只接受站内路径，不接受 //host 这种外链。 */
export function safeRedirect(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : HOME_PATH
}

/**
 * 路由守卫：
 * - 登录页公开；已登录再去登录页直接回首页（或 redirect 指的页）。
 * - 其余页面要登录：没令牌或令牌失效（读 /auth/me 失败）去登录页并带上 redirect。
 * - 页面 meta.roles 写了角色的，账号没有其中任一个时去“无权限”页。
 */
export function createAuthGuard(session: GuardSession) {
  return async (to: RouteLocationNormalized): Promise<true | RouteLocationRaw> => {
    if (to.meta.public) {
      if (to.path === LOGIN_PATH && session.hasToken() && await session.ensureAccount()) {
        return safeRedirect(to.query.redirect)
      }
      return true
    }
    if (!session.hasToken() || !(await session.ensureAccount())) {
      return { path: LOGIN_PATH, query: to.fullPath === HOME_PATH ? {} : { redirect: to.fullPath } }
    }
    if (!hasAnyRole(session.roles, to.meta.roles)) {
      return { name: FORBIDDEN_NAME, query: { from: to.fullPath } }
    }
    return true
  }
}
