import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import type { AuthApi, CurrentAccount } from './auth-api'
import { createSessionStore, NoAdminRoleError } from './session'
import { TokenStore, type KeyValueStorage } from './token-store'

class MemoryStorage implements KeyValueStorage {
  readonly items = new Map<string, string>()
  getItem(key: string) {
    return this.items.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.items.set(key, value)
  }

  removeItem(key: string) {
    this.items.delete(key)
  }
}

const OPERATOR: CurrentAccount = {
  displayName: '平台运营（示例）',
  organizationName: '低空产业平台（示例）',
  organizationType: 'PLATFORM',
  roles: ['PLATFORM_OPERATOR'],
}

describe('令牌', () => {
  it('放内存也放 sessionStorage，刷新后能读回来；快过期时当作没有', () => {
    const storage = new MemoryStorage()
    let now = 1_000_000
    const tokens = new TokenStore(storage, () => now)

    tokens.set('t-1', 900)
    expect(new TokenStore(storage, () => now).get()).toBe('t-1')

    now += 871_000
    expect(tokens.get()).toBeNull()
    expect(storage.items.size).toBe(0)
  })

  it('sessionStorage 里的坏数据当作没登录', () => {
    const storage = new MemoryStorage()
    storage.setItem('lowaltitude.admin.token', '{"accessToken":1}')
    expect(new TokenStore(storage).get()).toBeNull()
  })
})

describe('登录状态', () => {
  let api: { login: ReturnType<typeof vi.fn>, me: ReturnType<typeof vi.fn>, logout: ReturnType<typeof vi.fn> }
  let tokens: TokenStore

  beforeEach(() => {
    setActivePinia(createPinia())
    api = {
      login: vi.fn(async () => ({ accessToken: 'jwt', tokenType: 'Bearer', expiresInSeconds: 900 })),
      me: vi.fn(async () => OPERATOR),
      logout: vi.fn(async () => undefined),
    }
    tokens = new TokenStore(new MemoryStorage())
  })

  function store() {
    return createSessionStore(api as unknown as AuthApi, tokens)()
  }

  it('登录后存令牌、读资料，按角色判断', async () => {
    const session = store()

    await session.login('dev-operator', 'pw')

    expect(tokens.get()).toBe('jwt')
    expect(session.signedIn).toBe(true)
    expect(session.can(['PLATFORM_OPERATOR'])).toBe(true)
    expect(session.can(['PLATFORM_SUPPORT'])).toBe(false)
  })

  it('没有运营端角色的账号（客户）登录后立刻退出', async () => {
    api.me.mockResolvedValue({ ...OPERATOR, roles: ['CUSTOMER'] })
    const session = store()

    await expect(session.login('dev-customer', 'pw')).rejects.toBeInstanceOf(NoAdminRoleError)
    expect(tokens.get()).toBeNull()
    expect(session.account).toBeNull()
  })

  it('刷新页面后用存着的令牌读一次资料；读失败就清掉', async () => {
    tokens.set('old', 900)
    const session = store()
    expect(await session.ensureAccount()).toBe(true)
    expect(await session.ensureAccount()).toBe(true)
    expect(api.me).toHaveBeenCalledTimes(1)

    session.logout()
    tokens.set('expired', 900)
    api.me.mockRejectedValue(new ApiRequestError(401, 'COMMON_UNAUTHORIZED', '', 't'))
    expect(await store().ensureAccount()).toBe(false)
    expect(tokens.get()).toBeNull()
  })

  it('密码校验成功后读资料短暂失败，重试即可进入后台', async () => {
    api.me.mockRejectedValueOnce(new ApiRequestError(503, 'COMMON_SERVICE_UNAVAILABLE', '', null))
    const session = store()
    await expect(session.login('dev-operator', 'pw')).rejects.toBeInstanceOf(ApiRequestError)
    expect(session.signedIn).toBe(false)
    expect(session.restoreError).toBeTruthy()
    expect(session.hasToken()).toBe(true)
    expect(await session.ensureAccount()).toBe(true)
    expect(api.login).toHaveBeenCalledTimes(1)
  })

  it('刷新时后端暂时不可用可用原登录重试', async () => {
    tokens.set('still-valid', 900)
    api.me.mockRejectedValueOnce(new ApiRequestError(503, 'COMMON_SERVICE_UNAVAILABLE', '', null))
    const session = store()
    expect(await session.ensureAccount()).toBe(false)
    expect(session.restoreError).toBeTruthy()
    expect(tokens.hasLogin()).toBe(true)
    expect(await session.ensureAccount()).toBe(true)
    expect(session.restoreError).toBe('')
    expect(api.logout).not.toHaveBeenCalled()
  })

  it('登录时存刷新令牌；访问令牌过期但能续期时仍算登录着；退出时请后端撤销这次登录', async () => {
    api.login.mockResolvedValue({
      accessToken: 'jwt', tokenType: 'Bearer', expiresInSeconds: 900,
      refreshToken: 'refresh-1', refreshExpiresInSeconds: 2_592_000,
    })
    const session = store()
    await session.login('dev-support', 'pw')
    expect(tokens.refreshToken()).toBe('refresh-1')
    expect(session.hasToken()).toBe(true)

    session.logout()
    expect(api.logout).toHaveBeenCalledWith('refresh-1')
    expect(tokens.hasLogin()).toBe(false)
    expect(session.hasToken()).toBe(false)
  })
})
