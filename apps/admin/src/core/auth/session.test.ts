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
  let api: { login: ReturnType<typeof vi.fn>, me: ReturnType<typeof vi.fn> }
  let tokens: TokenStore

  beforeEach(() => {
    setActivePinia(createPinia())
    api = {
      login: vi.fn(async () => ({ accessToken: 'jwt', tokenType: 'Bearer', expiresInSeconds: 900 })),
      me: vi.fn(async () => OPERATOR),
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
})
