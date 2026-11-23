import { describe, expect, it, vi } from 'vitest'

import { TokenStore, type KeyValueStorage } from '@/core/auth/token-store'

import { createApiClient } from './api-client'
import { ApiRequestError } from './api-error'

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

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const UNAUTHORIZED = { code: 'COMMON_UNAUTHORIZED', message: '请先登录', traceId: 't' }

function renewed(n: number) {
  return {
    accessToken: `access-${n}`, tokenType: 'Bearer', expiresInSeconds: 900,
    refreshToken: `refresh-${n}`, refreshExpiresInSeconds: 2_592_000,
  }
}

/** 一个假后端：只认 validToken；/auth/refresh 按 refreshAnswer 回答，记下收到的刷新令牌。 */
function setup(options: { refreshAnswer: () => Promise<Response> }) {
  const tokens = new TokenStore(new MemoryStorage())
  tokens.set('access-0', 900, 'refresh-0', 2_592_000)
  let validToken = 'access-1'
  const refreshBodies: unknown[] = []
  const refreshHeaders: Array<Record<string, string>> = []
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
    const url = String(input)
    const headers = (init?.headers ?? {}) as Record<string, string>
    if (url === '/api/v1/auth/refresh') {
      refreshBodies.push(JSON.parse(String(init?.body)))
      refreshHeaders.push(headers)
      const response = await options.refreshAnswer()
      if (response.ok) validToken = 'access-1'
      return response
    }
    if (headers.Authorization !== `Bearer ${validToken}`) return json(401, UNAUTHORIZED)
    return json(200, { url })
  })
  const onUnauthorized = vi.fn(() => tokens.clear())
  const http = createApiClient({
    getToken: () => tokens.get(),
    onUnauthorized,
    renewal: { getRefreshToken: () => tokens.refreshToken(), onRenewed: (issued) => tokens.save(issued) },
    fetchImpl,
    newRequestId: () => 'r',
  })
  return { http, tokens, fetchImpl, onUnauthorized, refreshBodies, refreshHeaders }
}

describe('访问令牌过期后续期', () => {
  it('并发的三个请求都回 401 时只刷新一次，换到的新令牌重发，三个都成功', async () => {
    let release: (response: Response) => void = () => undefined
    const { http, tokens, fetchImpl, onUnauthorized, refreshBodies, refreshHeaders } = setup({
      refreshAnswer: () => new Promise<Response>((resolve) => {
        release = resolve
      }),
    })

    const calls = Promise.all([
      http.get('/api/v1/ops/cities'),
      http.get('/api/v1/ops/organizations'),
      http.post('/api/v1/ops/import/batches', { kind: 'X' }),
    ])
    await vi.waitFor(() => expect(refreshBodies).toHaveLength(1))
    release(json(200, renewed(1)))

    await expect(calls).resolves.toEqual([
      { url: '/api/v1/ops/cities' },
      { url: '/api/v1/ops/organizations' },
      { url: '/api/v1/ops/import/batches' },
    ])
    expect(refreshBodies).toEqual([{ refreshToken: 'refresh-0' }])
    // 续期请求不带过期的访问令牌。
    expect(refreshHeaders[0]).not.toHaveProperty('Authorization')
    expect(tokens.get()).toBe('access-1')
    expect(tokens.refreshToken()).toBe('refresh-1')
    expect(onUnauthorized).not.toHaveBeenCalled()
    // 3 个原请求 + 1 次续期 + 3 个重发。
    expect(fetchImpl).toHaveBeenCalledTimes(7)
  })

  it('别的请求已经换过令牌时，晚回来的 401 直接用新令牌重发，不再刷新', async () => {
    const { http, tokens, refreshBodies } = setup({ refreshAnswer: async () => json(200, renewed(1)) })
    await http.get('/api/v1/ops/cities')
    expect(refreshBodies).toHaveLength(1)

    // 模拟一个用旧令牌发出、续期后才回来的请求：发出时拿到的是 access-0。
    const stale = createApiClient({
      getToken: (() => {
        let first = true
        return () => {
          if (first) {
            first = false
            return 'access-0'
          }
          return tokens.get()
        }
      })(),
      onUnauthorized: vi.fn(),
      renewal: { getRefreshToken: () => tokens.refreshToken(), onRenewed: (issued) => tokens.save(issued) },
      fetchImpl: async (input, init) => {
        const headers = (init?.headers ?? {}) as Record<string, string>
        if (String(input) === '/api/v1/auth/refresh') throw new Error('不应再刷新')
        return headers.Authorization === 'Bearer access-1' ? json(200, { ok: true }) : json(401, UNAUTHORIZED)
      },
    })
    await expect(stale.get('/api/v1/ops/cities')).resolves.toEqual({ ok: true })
    expect(refreshBodies).toHaveLength(1)
  })

  it('刷新失败（刷新令牌过期或被撤销）：清掉登录、通知回登录页，几个请求都失败', async () => {
    const { http, tokens, onUnauthorized, refreshBodies } = setup({
      refreshAnswer: async () => json(401, UNAUTHORIZED),
    })

    const results = await Promise.allSettled([http.get('/api/v1/ops/cities'), http.get('/api/v1/ops/organizations')])

    expect(results.map((result) => result.status)).toEqual(['rejected', 'rejected'])
    const reason = (results[0] as PromiseRejectedResult).reason as ApiRequestError
    expect(reason.status).toBe(401)
    expect(reason.code).toBe('COMMON_UNAUTHORIZED')
    expect(refreshBodies).toHaveLength(1)
    expect(onUnauthorized).toHaveBeenCalled()
    expect(tokens.hasLogin()).toBe(false)
  })

  it('续期时断网：不清登录，这次请求按网络错误失败', async () => {
    const { http, tokens, onUnauthorized } = setup({
      refreshAnswer: async () => {
        throw new TypeError('Failed to fetch')
      },
    })

    await expect(http.get('/api/v1/ops/cities')).rejects.toMatchObject({ code: 'CLIENT_NETWORK_ERROR' })
    expect(onUnauthorized).not.toHaveBeenCalled()
    expect(tokens.refreshToken()).toBe('refresh-0')
  })

  it('没有刷新令牌时 401 直接算登录失效；登录接口自己的 401 不续期', async () => {
    const { http, tokens, onUnauthorized, refreshBodies } = setup({ refreshAnswer: async () => json(200, renewed(1)) })

    await expect(http.post('/api/v1/auth/login', { loginName: 'x', password: 'y' })).rejects.toMatchObject({ status: 401 })
    expect(refreshBodies).toHaveLength(0)

    tokens.set('access-0', 900)
    await expect(http.get('/api/v1/ops/cities')).rejects.toMatchObject({ status: 401 })
    expect(refreshBodies).toHaveLength(0)
    expect(onUnauthorized).toHaveBeenCalledTimes(2)
  })

  it('重发后还是 401（比如账号停用）：不再刷新第二次，算登录失效', async () => {
    const { http, onUnauthorized, refreshBodies, fetchImpl } = setup({
      refreshAnswer: async () => json(200, { ...renewed(2) }),
    })

    await expect(http.get('/api/v1/ops/cities')).rejects.toMatchObject({ status: 401 })
    expect(refreshBodies).toHaveLength(1)
    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})

describe('令牌存放', () => {
  it('访问令牌过期但刷新令牌还在：不清登录；两个都过期才清', () => {
    const storage = new MemoryStorage()
    let now = 1_000_000
    const tokens = new TokenStore(storage, () => now)
    tokens.set('a', 900, 'r', 1800)

    now += 900_000
    expect(tokens.get()).toBeNull()
    expect(tokens.refreshToken()).toBe('r')
    expect(tokens.hasLogin()).toBe(true)
    expect(new TokenStore(storage, () => now).refreshToken()).toBe('r')

    now += 900_000
    expect(tokens.hasLogin()).toBe(false)
    expect(storage.items.size).toBe(0)
  })
})
