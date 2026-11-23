import { ApiRequestError, CLIENT_ERROR_CODES, type ApiErrorBody } from './api-error'

export type QueryValue = string | number | boolean | null | undefined
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface RequestOptions {
  query?: Record<string, QueryValue>
  /** JSON 请求体。 */
  body?: unknown
  /** multipart 请求体（上传文件）。和 body 二选一。 */
  form?: FormData
  /** 写操作的幂等键（契约要求 Idempotency-Key 的接口才带）。 */
  idempotencyKey?: string
  /** 改状态的操作带的版本号（契约要求 If-Match 的接口才带），发出去是强 ETag：`"3"`。 */
  ifMatch?: number
  signal?: AbortSignal
}

/** POST /api/v1/auth/refresh 的回答（契约 openapi-inspection-service-flow.yaml 2.4.0 LoginResponse）。 */
export interface RenewedLogin {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  refreshToken: string
  refreshExpiresInSeconds: number
}

/** 访问令牌过期时怎么续期：用刷新令牌换一次新的两个令牌。不给就不续期，401 直接算登录失效。 */
export interface LoginRenewal {
  /** 现在的刷新令牌；没有时不续期。 */
  getRefreshToken: () => string | null
  /** 续期成功：存新的访问令牌和新的刷新令牌（旧的刷新令牌已作废）。 */
  onRenewed: (renewed: RenewedLogin) => void
}

export interface ApiClientOptions {
  /** 接口前缀；开发时 Vite 把 /api 代理到后端，生产由 Nginx 同源转发。 */
  baseUrl?: string
  getToken: () => string | null
  /** 登录失效（401 且续不了期）：清掉登录状态、回登录页。 */
  onUnauthorized: () => void
  renewal?: LoginRenewal
  fetchImpl?: typeof fetch
  newRequestId?: () => string
}

/** 登录、续期、退出这三个接口自己回 401 时不再续期（不然会拿刷新令牌去换刷新令牌）。 */
const AUTH_PATHS = new Set(['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'])
const REFRESH_PATH = '/api/v1/auth/refresh'

type RenewalOutcome = 'RENEWED' | 'REJECTED' | 'OFFLINE' | 'UNAVAILABLE'

export interface ApiClient {
  request<T>(method: HttpMethod, path: string, options?: RequestOptions): Promise<T>
  get<T>(path: string, query?: Record<string, QueryValue>, signal?: AbortSignal): Promise<T>
  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>): Promise<T>
  put<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>): Promise<T>
  delete<T>(path: string, options?: Omit<RequestOptions, 'body'>): Promise<T>
  /** 下载文件（受控附件）：带登录令牌取回 Blob，由页面交给浏览器保存。 */
  download(path: string): Promise<{ blob: Blob, fileName: string | null }>
}

export function buildUrl(baseUrl: string, path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === '') continue
    params.append(key, String(value))
  }
  const search = params.toString()
  return `${baseUrl}${path}${search ? `?${search}` : ''}`
}

function isErrorBody(value: unknown): value is ApiErrorBody {
  return !!value && typeof value === 'object'
    && typeof (value as ApiErrorBody).code === 'string'
    && typeof (value as ApiErrorBody).message === 'string'
}

/** 后端没给错误体时（网关、代理挂了）按状态码给一个通用编码。 */
function fallbackCode(status: number): string {
  if (status === 401) return 'COMMON_UNAUTHORIZED'
  if (status === 403) return 'COMMON_FORBIDDEN'
  if (status === 404) return 'COMMON_NOT_FOUND'
  if (status === 429) return 'COMMON_RATE_LIMITED'
  if (status >= 500) return 'COMMON_SERVICE_UNAVAILABLE'
  return 'COMMON_INVALID_ARGUMENT'
}

async function toError(response: Response): Promise<ApiRequestError> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    body = null
  }
  if (isErrorBody(body)) {
    return new ApiRequestError(response.status, body.code, body.message, body.traceId ?? null, body.details ?? null)
  }
  return new ApiRequestError(response.status, fallbackCode(response.status), '',
    response.headers.get('X-Request-Id'))
}

function fileNameFrom(disposition: string | null): string | null {
  if (!disposition) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      return null
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? null
}

function defaultRequestId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseUrl = options.baseUrl ?? ''
  const fetchImpl = options.fetchImpl ?? ((input, init) => fetch(input, init))
  const newRequestId = options.newRequestId ?? defaultRequestId

  async function sendOnce(method: HttpMethod, path: string, request: RequestOptions, accept: string,
    token: string | null): Promise<Response> {
    const headers: Record<string, string> = { Accept: accept, 'X-Request-Id': newRequestId() }
    if (token) headers.Authorization = `Bearer ${token}`
    if (request.idempotencyKey) headers['Idempotency-Key'] = request.idempotencyKey
    if (request.ifMatch !== undefined) headers['If-Match'] = `"${request.ifMatch}"`
    let body: BodyInit | undefined
    if (request.form) {
      body = request.form
    } else if (request.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(request.body)
    }
    let response: Response
    try {
      response = await fetchImpl(buildUrl(baseUrl, path, request.query), {
        method,
        headers,
        body,
        signal: request.signal,
        credentials: 'same-origin',
      })
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === 'AbortError') throw failure
      throw new ApiRequestError(0, CLIENT_ERROR_CODES.network, '', null)
    }
    return response
  }

  // 一起过期的几个请求共用同一次续期：刷新令牌只能用一次，同一个令牌交两次后端会当作被盗、整次登录撤销。
  let renewalInFlight: Promise<RenewalOutcome> | null = null

  function renewOnce(): Promise<RenewalOutcome> {
    if (!renewalInFlight) {
      renewalInFlight = renew().finally(() => {
        renewalInFlight = null
      })
    }
    return renewalInFlight
  }

  async function renew(): Promise<RenewalOutcome> {
    const renewal = options.renewal
    const refreshToken = renewal?.getRefreshToken()
    if (!renewal || !refreshToken) return 'REJECTED'
    let response: Response
    try {
      // 不带 Authorization：过期的访问令牌会让后端先拒绝，看不到请求体里的刷新令牌。
      response = await sendOnce('POST', REFRESH_PATH, { body: { refreshToken } }, 'application/json', null)
    } catch {
      // 断网：登录也许还能续，不清；这次请求按网络错误失败。
      return 'OFFLINE'
    }
    // 后端或网关出错：登录也许还能续，不清；其余（401 等）说明刷新令牌已用过、过期或被撤销，登录结束。
    if (!response.ok) return response.status >= 500 ? 'UNAVAILABLE' : 'REJECTED'
    try {
      const renewed = await response.json() as RenewedLogin
      if (!renewed || typeof renewed.accessToken !== 'string' || !renewed.accessToken) return 'REJECTED'
      renewal.onRenewed(renewed)
      return 'RENEWED'
    } catch {
      return 'REJECTED'
    }
  }

  /**
   * 发一个请求。回 401 时（登录、续期、退出接口除外）：别的请求已经换过令牌就直接用新的重发；否则用刷新令牌续期一次
   * （并发的请求共用这一次）再重发。续不了期，或重发还是 401，才算登录失效（onUnauthorized 回登录页）。
   */
  async function send(method: HttpMethod, path: string, request: RequestOptions, accept: string): Promise<Response> {
    const usedToken = options.getToken()
    let response = await sendOnce(method, path, request, accept, usedToken)
    if (response.status === 401 && !AUTH_PATHS.has(path) && options.renewal) {
      const current = options.getToken()
      let retryToken: string | null = null
      if (current && current !== usedToken) {
        retryToken = current
      } else {
        const outcome = await renewOnce()
        if (outcome === 'OFFLINE') throw new ApiRequestError(0, CLIENT_ERROR_CODES.network, '', null)
        if (outcome === 'UNAVAILABLE') throw new ApiRequestError(503, 'COMMON_SERVICE_UNAVAILABLE', '', null)
        if (outcome === 'RENEWED') retryToken = options.getToken()
      }
      if (retryToken) response = await sendOnce(method, path, request, accept, retryToken)
    }
    if (!response.ok) {
      const error = await toError(response)
      if (response.status === 401) options.onUnauthorized()
      throw error
    }
    return response
  }

  async function request<T>(method: HttpMethod, path: string, request: RequestOptions = {}): Promise<T> {
    const response = await send(method, path, request, 'application/json')
    if (response.status === 204) return undefined as T
    const text = await response.text()
    if (!text) return undefined as T
    try {
      return JSON.parse(text) as T
    } catch {
      throw new ApiRequestError(response.status, CLIENT_ERROR_CODES.invalidResponse, '',
        response.headers.get('X-Request-Id'))
    }
  }

  return {
    request,
    get: (path, query, signal) => request('GET', path, { query, signal }),
    post: (path, body, extra) => request('POST', path, { ...extra, body }),
    put: (path, body, extra) => request('PUT', path, { ...extra, body }),
    delete: (path, extra) => request('DELETE', path, { ...extra }),
    async download(path) {
      const response = await send('GET', path, {}, '*/*')
      return { blob: await response.blob(), fileName: fileNameFrom(response.headers.get('Content-Disposition')) }
    },
  }
}

/** 新的幂等键：一次“填写-提交”用同一个，重试不会重复建。 */
export function newIdempotencyKey(): string {
  return defaultRequestId()
}
