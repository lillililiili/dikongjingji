import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as authContext from '../application/service-flow/booking-auth-context'
import * as bookingHttp from '../data/service-flow/uni-booking-http-client'
import * as inspectionRoutes from '../navigation/inspection-routes'
import pagesSource from '../../../pages.json?raw'
import bookingHttpSource from '../data/service-flow/uni-booking-http-client.ts?raw'
import loginPageSource from '../presentation/auth/pages/InspectionLoginPage.vue?raw'

const DELIVERY_URL =
  '/pages/service/inspection/delivery?serviceOrderId=60000000-0000-4000-8000-000000000001'
describe('inspection authentication session', () => {
  const storage = new Map<string, unknown>()

  beforeEach(() => {
    storage.clear()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-29T02:00:00Z'))
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      setStorageSync: (key: string, value: unknown) => storage.set(key, value),
      removeStorageSync: (key: string) => storage.delete(key),
    })
  })

  afterEach(() => {
    authContext.completeBookingAuthenticationRecovery()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('persists a real login token and removes it after its server lifetime', () => {
    const saveSession = (
      authContext as typeof authContext & {
        saveBookingAuthSession?: (
          response: { accessToken: string; expiresInSeconds: number },
        ) => void
      }
    ).saveBookingAuthSession

    expect(typeof saveSession).toBe('function')
    if (!saveSession) return

    saveSession({ accessToken: 'real-jwt', expiresInSeconds: 900 })
    expect(authContext.getBookingAuthContext().getAccessToken()).toBe('real-jwt')

    vi.advanceTimersByTime(901_000)
    expect(authContext.getBookingAuthContext().getAccessToken()).toBeNull()
    expect(storage.has('inspection.accessToken')).toBe(false)
  })

  it('uses the public backend login endpoint without hardcoded credentials', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://127.0.0.1:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      setStorageSync: (key: string, value: unknown) => storage.set(key, value),
      removeStorageSync: (key: string) => storage.delete(key),
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({
          statusCode: 200,
          data: {
            accessToken: 'real-jwt',
            tokenType: 'Bearer',
            expiresInSeconds: 900,
          },
          header: {},
        })
      },
    })
    const authApi = (
      bookingHttp as typeof bookingHttp & {
        inspectionAuthApi?: {
          login(
            loginName: string,
            password: string,
          ): Promise<{
            accessToken: string
            tokenType: 'Bearer'
            expiresInSeconds: number
          }>
        }
      }
    ).inspectionAuthApi

    expect(authApi).toBeDefined()
    if (!authApi) return

    await expect(authApi.login('customer', 'secret')).resolves.toMatchObject({
      accessToken: 'real-jwt',
      expiresInSeconds: 900,
    })
    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatchObject({
      url: 'http://127.0.0.1:8080/api/v1/auth/login',
      method: 'POST',
      data: { loginName: 'customer', password: 'secret' },
    })
  })

  it('allows the Android acceptance build to call an explicitly enabled private LAN API', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://192.168.3.32:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LAN_API', 'true')
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      setStorageSync: (key: string, value: unknown) => storage.set(key, value),
      removeStorageSync: (key: string) => storage.delete(key),
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({
          statusCode: 200,
          data: { accessToken: 'real-jwt', tokenType: 'Bearer', expiresInSeconds: 900 },
          header: {},
        })
      },
    })

    await expect(bookingHttp.inspectionAuthApi.login('customer', 'secret')).resolves.toMatchObject({
      accessToken: 'real-jwt',
    })
    expect(requests[0]).toMatchObject({
      url: 'http://192.168.3.32:8080/api/v1/auth/login',
    })
  })

  it('calls the private LAN login API when the App runtime has no global URL constructor', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://192.168.3.32:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LAN_API', 'true')
    vi.stubGlobal('URL', undefined)
    vi.stubGlobal('uni', {
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({
          statusCode: 200,
          data: { accessToken: 'real-jwt', tokenType: 'Bearer', expiresInSeconds: 900 },
          header: {},
        })
      },
    })

    await expect(bookingHttp.inspectionAuthApi.login('customer', 'secret')).resolves.toMatchObject({
      accessToken: 'real-jwt',
    })
    expect(requests[0]).toMatchObject({
      url: 'http://192.168.3.32:8080/api/v1/auth/login',
    })
  })

  it.each([
    ['http://10.0.0.0:8080', 'true'],
    ['http://10.255.255.255:8080', 'true'],
    ['http://172.16.0.0:8080', 'true'],
    ['http://172.31.255.255:8080', 'true'],
    ['https://api.example.com', 'false'],
    ['https://api.example.com/users/@me', 'false'],
  ])('allows a production API at the RFC1918 boundary or over HTTPS without a LAN flag: %s', async (baseUrl, lanFlag) => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', baseUrl)
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LAN_API', lanFlag)
    vi.stubGlobal('uni', {
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({
          statusCode: 200,
          data: { accessToken: 'real-jwt', tokenType: 'Bearer', expiresInSeconds: 900 },
          header: {},
        })
      },
    })

    await expect(bookingHttp.inspectionAuthApi.login('customer', 'secret')).resolves.toMatchObject({
      accessToken: 'real-jwt',
    })
    expect(requests[0]).toMatchObject({ url: `${baseUrl}/api/v1/auth/login` })
  })

  it.each([
    ['http://127.0.0.1:8080', true],
    ['http://localhost:8080', true],
    ['http://127.0.0.2:8080', false],
  ])('allows only the production loopback hosts when the local flag is enabled: %s', async (baseUrl, shouldAllow) => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', baseUrl)
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
    vi.stubGlobal('uni', {
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      }) => options.success({
        statusCode: 200,
        data: { accessToken: 'real-jwt', tokenType: 'Bearer', expiresInSeconds: 900 },
        header: {},
      }),
    })

    const login = bookingHttp.inspectionAuthApi.login('customer', 'secret')
    if (shouldAllow) {
      await expect(login).resolves.toMatchObject({ accessToken: 'real-jwt' })
      return
    }
    await expect(login).rejects.toMatchObject({ code: 'INSECURE_API_BASE_URL' })
  })

  it.each([
    ['http://8.8.8.8:8080', 'true'],
    ['http://192.168.3.32:8080', 'false'],
    ['http://192.168.3.32:8080', 'TRUE'],
    ['http://192.168.3.32:8080', '1'],
    ['http://customer@192.168.3.32:8080', 'true'],
    ['http://192.168.3.32:99999', 'true'],
    ['http://010.0.0.1:8080', 'true'],
    ['http://192.168.003.032:8080', 'true'],
    ['http://192.168.3.32.evil.example:8080', 'true'],
    ['http://172.15.255.255:8080', 'true'],
    ['http://172.32.0.0:8080', 'true'],
    ['http://[::1]:8080', 'true'],
    ['not-a-url', 'true'],
  ])('rejects a production HTTP API that is public, disabled, or a non-literal host: %s', async (baseUrl, lanFlag) => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', baseUrl)
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LAN_API', lanFlag)
    vi.stubGlobal('uni', { request: vi.fn() })

    await expect(bookingHttp.inspectionAuthApi.login('customer', 'secret')).rejects.toMatchObject({
      code: 'INSECURE_API_BASE_URL',
    })
  })

  it('redirects to login once and preserves only the current stable business ID', () => {
    const redirects: string[] = []
    authContext.completeBookingAuthenticationRecovery()
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/delivery',
      options: {
        serviceOrderId: '60000000-0000-4000-8000-000000000001',
        ignored: 'must-not-leak',
      },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: ({ url }: { url: string }) => redirects.push(url),
      reLaunch: vi.fn(),
    })

    authContext.beginBookingAuthenticationRecovery()
    authContext.beginBookingAuthenticationRecovery()

    expect(redirects).toEqual([
      inspectionRoutes.buildInspectionLoginUrl(DELIVERY_URL),
    ])
    expect(redirects[0]).not.toContain('ignored')
  })

  it('restores the stable business ID from the Android App-Plus page object', () => {
    const redirects: string[] = []
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/delivery',
      $page: {
        options: {
          serviceOrderId: '60000000-0000-4000-8000-000000000001',
        },
      },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: ({ url }: { url: string }) => redirects.push(url),
      reLaunch: vi.fn(),
    })

    authContext.beginBookingAuthenticationRecovery()

    expect(redirects).toEqual([
      inspectionRoutes.buildInspectionLoginUrl(DELIVERY_URL),
    ])
  })

  it('does not turn a public catalog error into a protected-page login redirect', () => {
    const redirects: string[] = []
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/catalog',
      options: { cityCode: '370500' },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: ({ url }: { url: string }) => redirects.push(url),
      reLaunch: vi.fn(),
    })

    authContext.beginBookingAuthenticationRecovery()

    expect(redirects).toEqual([])
  })

  it('loads the public inspection catalog without a stored access token or login redirect', async () => {
    const redirects: string[] = []
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://127.0.0.1:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/catalog',
      options: { cityCode: '370500' },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: ({ url }: { url: string }) => redirects.push(url),
      reLaunch: vi.fn(),
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({ statusCode: 200, data: { items: [], hasMore: false }, header: {} })
      },
    })

    await expect(
      bookingHttp.inspectionCatalogApi.searchServices({
        cityCode: '370500',
        query: { keyword: '', category: 'POWER', sort: 'COMPREHENSIVE' },
        filters: { method: 'ALL', priceMode: 'ALL', includesReport: false },
        position: null,
        page: 1,
        size: 5,
      }),
    ).resolves.toEqual({ services: [], hasMore: false, cityClosed: false })
    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatchObject({
      url: 'http://127.0.0.1:8080/api/v1/inspection/services?cityCode=370500&categoryCode=POWER&sort=COMPREHENSIVE&page=1&size=5',
      method: 'GET',
    })
    expect(redirects).toEqual([])
  })

  it('loads a public service snapshot without a stored access token', async () => {
    const request = vi.fn((options: {
      success: (response: {
        statusCode: number
        data: unknown
        header: Record<string, string>
      }) => void
    }) => options.success({
      statusCode: 200,
      data: {
        serviceId: 'service-id',
        category: { name: '电力巡检' },
        name: '输电线路无人机巡检',
        summary: '专业无人机电力巡检服务',
        coverImage: null,
        inspectionItems: [{ code: 'LINE', name: '线路本体巡检' }],
        price: { mode: 'STARTING_PRICE', startingAmountMinor: 69_900, unitLabel: '公里起' },
      },
      header: {},
    }))
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://127.0.0.1:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/detail',
      options: { serviceId: 'service-id', cityCode: '370500' },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: vi.fn(),
      reLaunch: vi.fn(),
      request,
    })

    await expect(
      bookingHttp.inspectionBookingApi.loadServiceSnapshot('service-id', '370500'),
    ).resolves.toMatchObject({
      serviceId: 'service-id',
      title: '输电线路无人机巡检',
      cityCode: '370500',
    })
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0]?.[0]).toMatchObject({
      url: 'http://127.0.0.1:8080/api/v1/inspection/services/service-id?cityCode=370500',
      method: 'GET',
    })
  })

  it('still rejects protected reservation reads and writes without a stored access token', async () => {
    const request = vi.fn()
    vi.stubGlobal('getCurrentPages', () => [{
      route: 'pages/service/inspection/booking',
      options: { serviceId: 'service-id' },
    }])
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      removeStorageSync: (key: string) => storage.delete(key),
      redirectTo: vi.fn(),
      reLaunch: vi.fn(),
      request,
    })

    await expect(
      bookingHttp.inspectionBookingApi.getReservation('reservation-id'),
    ).rejects.toMatchObject({ status: 401, code: 'COMMON_UNAUTHORIZED' })
    await expect(
      bookingHttp.inspectionBookingApi.createReservation({
        serviceId: 'service-id',
        cityCode: '370500',
        location: { address: '东营市测试地点', longitude: 118.67, latitude: 37.43 },
        lineLengthMeters: 1000,
        preferredServiceDate: '2026-08-01',
        preferredTimeSlotCode: 'MORNING',
        inspectionItemCodes: ['LINE'],
        attachmentFileIds: [],
        note: null,
        contact: { name: '测试联系人', mobile: '13800138000' },
        agreementVersion: '2026-07',
        agreementAccepted: true,
      }, 'idempotency-key'),
    ).rejects.toMatchObject({ status: 401, code: 'COMMON_UNAUTHORIZED' })
    expect(request).not.toHaveBeenCalled()
  })
})

describe('inspection login route and recovery', () => {
  it('registers a real login page and only restores approved protected routes', () => {
    const routes = inspectionRoutes as typeof inspectionRoutes & {
      buildInspectionLoginUrl?: (returnUrl: string) => string
      normalizeInspectionReturnUrl?: (returnUrl: string) => string
    }
    const pages = JSON.parse(pagesSource) as {
      pages: Array<{ path: string; style?: Record<string, string> }>
    }

    expect(
      pages.pages.find(
        (page) => page.path === 'pages/service/inspection/login',
      ),
    ).toMatchObject({ style: { navigationStyle: 'custom' } })
    expect(typeof routes.buildInspectionLoginUrl).toBe('function')
    expect(typeof routes.normalizeInspectionReturnUrl).toBe('function')
    if (!routes.buildInspectionLoginUrl || !routes.normalizeInspectionReturnUrl) {
      return
    }

    expect(routes.buildInspectionLoginUrl(DELIVERY_URL)).toBe(
      `/pages/service/inspection/login?returnUrl=${encodeURIComponent(DELIVERY_URL)}`,
    )
    expect(routes.normalizeInspectionReturnUrl(DELIVERY_URL)).toBe(DELIVERY_URL)
    expect(
      routes.normalizeInspectionReturnUrl('https://evil.example/steal'),
    ).toBe('/pages/home/index')
  })

  it('lets the Mine, Message and Cart tabs be login return targets, without any parameter', async () => {
    const routes = await import('../navigation/inspection-routes')

    expect(routes.normalizeInspectionReturnUrl('/pages/mine/index')).toBe('/pages/mine/index')
    expect(routes.normalizeInspectionReturnUrl('/pages/message/index')).toBe('/pages/message/index')
    // Parameters on a tab are dropped; other root pages are still not accepted.
    expect(routes.normalizeInspectionReturnUrl('/pages/mine/index?next=https://evil.example'))
      .toBe('/pages/mine/index')
    // The cart offers a login of its own since step D.
    expect(routes.normalizeInspectionReturnUrl('/pages/cart/index')).toBe('/pages/cart/index')
    expect(routes.normalizeInspectionReturnUrl('/pages/discover/index')).toBe('/pages/home/index')
    expect(routes.normalizeInspectionReturnUrl('/pages/message/index/../../evil')).toBe('/pages/home/index')

    expect(routes.isRootTabReturnUrl('/pages/home/index')).toBe(true)
    expect(routes.isRootTabReturnUrl('/pages/mine/index')).toBe(true)
    expect(routes.isRootTabReturnUrl('/pages/message/index')).toBe(true)
    expect(routes.isRootTabReturnUrl('/pages/cart/index')).toBe(true)
    expect(routes.isRootTabReturnUrl('/pages/mine/inspection-orders?stage=ALL')).toBe(false)
    expect(loginPageSource).toContain('isRootTabReturnUrl(target)')
  })

  it('provides credential submission, token persistence and original-route recovery', () => {
    expect(loginPageSource).toContain('inspectionAuthApi.login(')
    expect(loginPageSource).toContain('saveBookingAuthSession(')
    expect(loginPageSource).toContain('completeBookingAuthenticationRecovery()')
    expect(loginPageSource).toContain('normalizeInspectionReturnUrl(')
    expect(loginPageSource).toContain('账号登录')
    expect(loginPageSource).toContain("{{ isSubmitting ? '正在登录…' : '登录' }}")
    expect(loginPageSource).not.toContain('test-password')
    expect(loginPageSource).not.toContain('smoke-customer')
  })

  it('redirects a missing or expired session to login instead of retrying anonymously', () => {
    expect(bookingHttpSource).toContain('beginBookingAuthenticationRecovery()')
    expect(bookingHttpSource).toContain(
      "error.status === 401 || error.code === 'COMMON_UNAUTHORIZED'",
    )
    // A 401 first tries one silent renewal with the refresh token (auth-refresh.test.ts).
    expect(bookingHttpSource).toContain('renewRequestToken(token)')
  })

})
