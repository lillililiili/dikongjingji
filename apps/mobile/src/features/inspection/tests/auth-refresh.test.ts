import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as authContext from '../application/service-flow/booking-auth-context'
import * as bookingHttp from '../data/service-flow/uni-booking-http-client'
import { createNotificationExtrasApi } from '../../message/data/notification-extras-api'

// 登录续期：访问令牌 900 秒，过期后公共请求层用刷新令牌静默续期一次再重试；续期失败才回到登录。
interface SentRequest {
  url: string
  method: string
  data?: unknown
  header: Record<string, string>
}

type Reply = { statusCode: number; data: unknown }

describe('silent login renewal', () => {
  const storage = new Map<string, unknown>()
  const session = new Map<string, string>()
  let sent: SentRequest[]
  let reply: (request: SentRequest) => Reply
  let redirects: string[]

  beforeEach(() => {
    storage.clear()
    session.clear()
    sent = []
    redirects = []
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-22T02:00:00Z'))
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://127.0.0.1:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => session.get(key) ?? null,
      setItem: (key: string, value: string) => session.set(key, value),
      removeItem: (key: string) => session.delete(key),
    })
    vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/mine/goods-orders', options: {} }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      setStorageSync: (key: string, value: unknown) => storage.set(key, value),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: (options: { url: string }) => redirects.push(options.url),
      reLaunch: (options: { url: string }) => redirects.push(options.url),
      request: (options: SentRequest & {
        success: (response: Reply & { header: Record<string, string> }) => void
        fail: () => void
      }) => {
        const request = { url: options.url, method: options.method, data: options.data, header: options.header }
        sent.push(request)
        const answer = reply(request)
        // Answer on a later turn, as the network does, so concurrent calls really overlap.
        void Promise.resolve().then(() => options.success({ ...answer, header: {} }))
      },
    })
    reply = () => ({ statusCode: 500, data: {} })
  })

  afterEach(() => {
    authContext.clearBookingAuthSession()
    authContext.completeBookingAuthenticationRecovery()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  const signIn = (accessToken = 'access-1', refreshToken = 'refresh-1') =>
    authContext.saveBookingAuthSession({ accessToken, expiresInSeconds: 900, refreshToken })

  const unauthorized: Reply = { statusCode: 401, data: { code: 'COMMON_UNAUTHORIZED', message: '请先登录' } }
  const renewedReply = (n: number): Reply => ({
    statusCode: 200,
    data: {
      accessToken: `access-${n}`,
      tokenType: 'Bearer',
      expiresInSeconds: 900,
      refreshToken: `refresh-${n}`,
      refreshExpiresInSeconds: 2_592_000,
    },
  })
  const isRefresh = (request: SentRequest) => request.url.endsWith('/api/v1/auth/refresh')
  const bearer = (request: SentRequest) => request.header.Authorization
  const jwt = (account: number, version = 1) => 'header.' + btoa(JSON.stringify({
    sub: '30000000-0000-4000-8000-' + String(account).padStart(12, '0'), version,
  })).replace(/=+$/, '') + '.signature'

  for (const client of ['public', 'inspection']) {
    for (const change of ['switch', 'logout']) {
      it(`does not replay or redirect a pending ${client} request after ${change}`, async () => {
        signIn(jwt(1))
        reply = () => {
          if (change === 'switch') signIn(jwt(2), 'refresh-2')
          else authContext.clearBookingAuthSession()
          return unauthorized
        }
        const pending = client === 'public'
          ? bookingHttp.publicRequest({ url: '/write', method: 'PUT', headers: { Authorization: 'Bearer ' + jwt(1) } })
          : bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 })
        await expect(pending).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
        expect(sent.map(bearer)).toEqual(['Bearer ' + jwt(1)])
        expect(authContext.getBookingAuthContext().getAccessToken()).toBe(change === 'switch' ? jwt(2) : null)
        expect(redirects).toEqual([])
      })
    }
  }

  it('rejects a stale write before the first send if another account is active', async () => {
    signIn(jwt(2), 'refresh-2')
    await expect(bookingHttp.publicRequest({ url: '/write', method: 'PUT', headers: { Authorization: 'Bearer ' + jwt(1) } }))
      .rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(sent).toHaveLength(0)
  })

  it('preserves the current account after a late second 401 on a protected call', async () => {
    signIn(jwt(1))
    reply = request => {
      if (isRefresh(request)) return { statusCode: 200, data: { accessToken: jwt(1, 2), refreshToken: 'refresh-rotated', expiresInSeconds: 900 } }
      if (bearer(request) === 'Bearer ' + jwt(1, 2)) signIn(jwt(2), 'refresh-b')
      return unauthorized
    }
    await expect(bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 }))
      .rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(authContext.readBookingRefreshToken()).toBe('refresh-b')
    expect(redirects).toEqual([])
  })

  it('keeps separate refreshes in flight when another account signs in', async () => {
    signIn(jwt(1), 'refresh-a')
    let pendingB!: Promise<string | null>
    reply = request => {
      const first = (request.data as { refreshToken: string }).refreshToken === 'refresh-a'
      if (first) {
        signIn(jwt(2), 'refresh-b')
        pendingB = bookingHttp.accessTokenAfterUnauthorized(jwt(2))
      }
      return { statusCode: 200, data: { accessToken: jwt(first ? 1 : 2, 2), refreshToken: 'rotated', expiresInSeconds: 900 } }
    }
    expect(await bookingHttp.accessTokenAfterUnauthorized(jwt(1))).toBeNull()
    expect(await pendingB).toBe(jwt(2, 2))
    expect(sent.filter(isRefresh).map(r => r.data)).toEqual([{ refreshToken: 'refresh-a' }, { refreshToken: 'refresh-b' }])
    expect(authContext.getBookingAuthContext().getPrincipalId()).toBe(authContext.bookingTokenPrincipal(jwt(2)))
  })

  it('preserves the other tab login when this tab holds the previous account refresh token', async () => {
    signIn(jwt(1), 'refresh-a')
    storage.set('inspection.accessToken', jwt(2))
    reply = () => unauthorized
    await expect(bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 }))
      .rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(sent.filter(isRefresh)).toHaveLength(0)
    expect(storage.get('inspection.accessToken')).toBe(jwt(2))
    expect(redirects).toEqual([])
  })

  it('revokes only the captured push registration even when it gets a 401 after a switch', async () => {
    signIn(jwt(2), 'refresh-b')
    reply = () => unauthorized
    const api = createNotificationExtrasApi(bookingHttp.publicRequest)
    await expect(api.unregisterPushDevice(jwt(1), { platform: 'ANDROID', pushClientId: 'device' }))
      .rejects.toMatchObject({ status: 401 })
    expect(sent.map(bearer)).toEqual(['Bearer ' + jwt(1)])
    expect(authContext.readBookingRefreshToken()).toBe('refresh-b')
  })

  it('keeps the refresh token out of persistent storage on H5', () => {
    signIn()

    expect(storage.get('inspection.accessToken')).toBe('access-1')
    expect([...storage.values()]).not.toContain('refresh-1')
    expect(session.get('inspection.refreshToken')).toBe('refresh-1')
    expect(authContext.readBookingRefreshToken()).toBe('refresh-1')
  })

  it('still counts an expired access token as signed in while it can be renewed', () => {
    signIn()
    vi.advanceTimersByTime(901_000)
    expect(authContext.getBookingAuthContext().getAccessToken()).toBe('access-1')

    authContext.saveBookingAuthSession({ accessToken: 'access-2', expiresInSeconds: 900 })
    vi.advanceTimersByTime(901_000)
    expect(authContext.getBookingAuthContext().getAccessToken()).toBeNull()
  })

  it('recognizes the same account after renewal and a different account after sign-in', () => {
    const a = '30000000-0000-4000-8000-000000000001'
    const b = '30000000-0000-4000-8000-000000000002'
    const token = (sub: string, exp: number) => 'header.' + btoa(JSON.stringify({ sub, exp })).replace(/=+$/, '') + '.signature'
    const auth = authContext.getBookingAuthContext()
    for (const [sub, exp] of [[a, 1], [a, 2], [b, 3]] as const) {
      authContext.saveBookingAuthSession({ accessToken: token(sub, exp), expiresInSeconds: 900 })
      expect(auth.getPrincipalId()).toBe(sub)
    }
    authContext.saveBookingAuthSession({ accessToken: 'legacy-token', expiresInSeconds: 900 })
    expect(auth.getPrincipalId()).toBeNull()
    authContext.clearBookingAuthSession()
    expect(auth.getPrincipalId()).toBeNull()
  })

  it('renews once on a 401 and sends the call again with the new token', async () => {
    signIn()
    vi.advanceTimersByTime(901_000)
    reply = (request) => {
      if (isRefresh(request)) return renewedReply(2)
      return bearer(request) === 'Bearer access-2' ? { statusCode: 200, data: { ok: true } } : unauthorized
    }

    const result = await bookingHttp.publicRequest<{ ok: boolean }>({
      url: '/api/v1/trade/orders',
      method: 'GET',
      headers: { Authorization: 'Bearer access-1' },
    })

    expect(result).toEqual({ ok: true })
    expect(sent.map((request) => request.url.replace('http://127.0.0.1:8080', ''))).toEqual([
      '/api/v1/trade/orders',
      '/api/v1/auth/refresh',
      '/api/v1/trade/orders',
    ])
    const refresh = sent.find(isRefresh)!
    expect(refresh.method).toBe('POST')
    expect(refresh.data).toEqual({ refreshToken: 'refresh-1' })
    // The expired access token is not sent along: the server would refuse it before reading the body.
    expect(bearer(refresh)).toBeUndefined()
    expect(storage.get('inspection.accessToken')).toBe('access-2')
    expect(authContext.readBookingRefreshToken()).toBe('refresh-2')
  })

  it('shares one renewal between calls that fail together, so the token is used only once', async () => {
    signIn()
    reply = (request) => {
      if (isRefresh(request)) return renewedReply(2)
      return bearer(request) === 'Bearer access-2' ? { statusCode: 200, data: request.url } : unauthorized
    }

    const results = await Promise.all(['/a', '/b', '/c'].map((url) => bookingHttp.publicRequest<string>({
      url,
      method: 'GET',
      headers: { Authorization: 'Bearer access-1' },
    })))

    expect(results).toHaveLength(3)
    expect(sent.filter(isRefresh)).toHaveLength(1)
  })

  it('discards a late renewal after clearing the session on password change', async () => {
    signIn()
    reply = () => {
      authContext.clearBookingAuthSession()
      return renewedReply(2)
    }
    expect(await bookingHttp.accessTokenAfterUnauthorized('access-1')).toBeNull()
    expect(authContext.readBookingRefreshToken()).toBeNull()
    expect(authContext.getBookingAuthContext().getAccessToken()).toBeNull()
  })

  it('keeps a fresh login when a previous session finishes a failed renewal', async () => {
    signIn()
    reply = () => {
      signIn('new-access', 'new-refresh')
      return unauthorized
    }
    expect(await bookingHttp.accessTokenAfterUnauthorized('access-1')).toBeNull()
    expect(authContext.readBookingRefreshToken()).toBe('new-refresh')
  })

  it('sends later calls with the renewed token even when the page still holds the old one', async () => {
    signIn(jwt(1, 2), 'refresh-2')
    reply = (request) => ({ statusCode: 200, data: bearer(request) })

    const used = await bookingHttp.publicRequest<string>({
      url: '/api/v1/auth/me',
      method: 'GET',
      headers: { Authorization: 'Bearer ' + jwt(1) },
    })

    expect(used).toBe('Bearer ' + jwt(1, 2))
    expect(sent.filter(isRefresh)).toHaveLength(0)
  })

  it('ends the login when the refresh token is refused, and the page gets the 401', async () => {
    signIn()
    reply = () => unauthorized

    await expect(bookingHttp.publicRequest({
      url: '/api/v1/trade/orders',
      method: 'GET',
      headers: { Authorization: 'Bearer access-1' },
    })).rejects.toMatchObject({ status: 401 })

    expect(sent.filter(isRefresh)).toHaveLength(1)
    expect(sent).toHaveLength(2)
    expect(storage.has('inspection.accessToken')).toBe(false)
    expect(authContext.readBookingRefreshToken()).toBeNull()
  })

  it('keeps the login when the renewal could not reach the server', async () => {
    signIn()
    vi.stubGlobal('uni', {
      ...(globalThis as unknown as { uni: object }).uni,
      request: (options: SentRequest & { success: (r: Reply & { header: object }) => void; fail: () => void }) => {
        sent.push({ url: options.url, method: options.method, data: options.data, header: options.header })
        if (options.url.endsWith('/api/v1/auth/refresh')) void Promise.resolve().then(() => options.fail())
        else void Promise.resolve().then(() => options.success({ ...unauthorized, header: {} }))
      },
    })

    await expect(bookingHttp.publicRequest({
      url: '/api/v1/trade/orders',
      method: 'GET',
      headers: { Authorization: 'Bearer access-1' },
    })).rejects.toMatchObject({ code: 'NETWORK_ERROR' })

    expect(authContext.readBookingRefreshToken()).toBe('refresh-1')
    expect(sent.filter(isRefresh)).toHaveLength(1)
  })

  it.each([429, 503])('preserves and successfully renews both clients after a temporary refresh %i', async status => {
    for (const client of ['public', 'inspection']) {
      signIn()
      let retry = false
      reply = request => {
        if (isRefresh(request)) return retry ? renewedReply(2) : { statusCode: status, data: {} }
        return bearer(request) === 'Bearer access-2' ? { statusCode: 200, data: {} } : unauthorized
      }
      const read = () => client === 'public'
        ? bookingHttp.publicRequest({ url: '/me', method: 'GET', headers: { Authorization: 'Bearer access-1' } })
        : bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 })
      await expect(read()).rejects.toMatchObject({ status })
      expect(authContext.readBookingRefreshToken()).toBe('refresh-1')
      expect(redirects).toEqual([])
      retry = true
      await expect(read()).resolves.toEqual({})
      expect(authContext.readBookingRefreshToken()).toBe('refresh-2')
    }
  })

  it('does not renew anonymous calls', async () => {
    signIn()
    reply = () => unauthorized

    await expect(bookingHttp.publicRequest({ url: '/api/v1/inspection/portal', method: 'GET' }))
      .rejects.toMatchObject({ status: 401 })
    expect(sent.filter(isRefresh)).toHaveLength(0)
  })

  it('renews protected inspection calls before sending the user to the login page', async () => {
    signIn()
    reply = (request) => {
      if (isRefresh(request)) return renewedReply(2)
      return bearer(request) === 'Bearer access-2'
        ? { statusCode: 200, data: { items: [], page: 1, size: 20, total: 0 } }
        : unauthorized
    }

    await bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 }).catch(() => undefined)

    expect(sent.filter(isRefresh)).toHaveLength(1)
    expect(bearer(sent[sent.length - 1])).toBe('Bearer access-2')
    expect(redirects).toEqual([])
  })

  it('sends the user to login only when renewal fails for a protected inspection call', async () => {
    signIn()
    reply = () => unauthorized

    await bookingHttp.inspectionOrderApi.list({ stage: 'ALL' as never, page: 1, size: 20 }).catch(() => undefined)

    expect(redirects).toHaveLength(1)
    expect(redirects[0]).toContain('/pages/service/inspection/login')
  })

  it('signs out on the server with the refresh token and forgets both tokens', async () => {
    signIn()
    reply = () => ({ statusCode: 204, data: '' })

    bookingHttp.signOutBookingSession()
    await vi.waitFor(() => expect(sent).toHaveLength(1))

    expect(sent[0].url).toContain('/api/v1/auth/logout')
    expect(sent[0].data).toEqual({ refreshToken: 'refresh-1' })
    expect(bearer(sent[0])).toBeUndefined()
    expect(storage.has('inspection.accessToken')).toBe(false)
    expect(authContext.readBookingRefreshToken()).toBeNull()
  })

  it.each([false, true])('withdraws only the signing-out session after device release or timeout (%s)', async timeout => {
    signIn(jwt(1), 'refresh-a')
    let finish!: () => void
    const device = new Promise<void>(resolve => { finish = resolve })
    reply = () => ({ statusCode: 204, data: null })
    bookingHttp.signOutBookingSession(() => device)
    expect(authContext.getBookingAuthContext().getAccessToken()).toBeNull()
    expect(sent).toHaveLength(0)
    signIn(jwt(2), 'refresh-b')
    if (timeout) await vi.advanceTimersByTimeAsync(3000)
    else finish()
    await vi.waitFor(() => expect(sent).toHaveLength(1))
    finish()
    await vi.advanceTimersByTimeAsync(4000)
    expect(sent).toHaveLength(1)
    expect(sent[0].data).toEqual({ refreshToken: 'refresh-a' })
    expect(authContext.readBookingRefreshToken()).toBe('refresh-b')
  })
})
