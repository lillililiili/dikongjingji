import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { http } from '@/core/http'

import { createAuthApi, type AuthApi, type CurrentAccount } from './auth-api'
import { hasAnyRole, isAdminRole } from './roles'
import { tokenStore, type TokenStore } from './token-store'

/** 登录成功但账号没有任何运营端角色（比如普通客户账号）。 */
export class NoAdminRoleError extends Error {
  constructor() {
    super('这个账号没有运营端权限，请换一个账号登录')
    this.name = 'NoAdminRoleError'
  }
}

/**
 * 登录状态：令牌在 TokenStore（内存 + sessionStorage），账号资料只放内存（刷新后由路由守卫重新读 /auth/me）。
 * 不另建账号体系，登录、资料都走身份模块已有的两个接口。
 */
export function createSessionStore(api: AuthApi, tokens: TokenStore) {
  return defineStore('session', () => {
    const account = ref<CurrentAccount | null>(null)
    const roles = computed(() => account.value?.roles ?? [])
    const signedIn = computed(() => account.value !== null && tokens.get() !== null)

    function hasToken(): boolean {
      return tokens.get() !== null
    }

    async function login(loginName: string, password: string): Promise<CurrentAccount> {
      const issued = await api.login(loginName, password)
      tokens.set(issued.accessToken, issued.expiresInSeconds)
      try {
        const me = await api.me()
        if (!me.roles.some(isAdminRole)) throw new NoAdminRoleError()
        account.value = me
        return me
      } catch (failure) {
        logout()
        throw failure
      }
    }

    /** 有令牌但还没读资料（刷新页面后）时读一次；令牌失效或没有运营端角色时清掉登录状态，返回 false。 */
    async function ensureAccount(): Promise<boolean> {
      if (!hasToken()) {
        account.value = null
        return false
      }
      if (account.value) return true
      try {
        const me = await api.me()
        if (!me.roles.some(isAdminRole)) {
          logout()
          return false
        }
        account.value = me
        return true
      } catch {
        logout()
        return false
      }
    }

    function logout(): void {
      tokens.clear()
      account.value = null
    }

    function can(required: readonly string[] | undefined): boolean {
      return hasAnyRole(roles.value, required)
    }

    return { account, roles, signedIn, hasToken, login, ensureAccount, logout, can }
  })
}

export const useSessionStore = createSessionStore(createAuthApi(http), tokenStore)
export type SessionStore = ReturnType<typeof useSessionStore>
