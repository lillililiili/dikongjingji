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
  signal?: AbortSignal
}

export interface ApiClientOptions {
  /** 接口前缀；开发时 Vite 把 /api 代理到后端，生产由 Nginx 同源转发。 */
  baseUrl?: string
  getToken: () => string | null
  /** 后端回 401：登录失效，清掉登录状态、回登录页。 */
  onUnauthorized: () => void
  fetchImpl?: typeof fetch
  newRequestId?: () => string
}

export interface ApiClient {
  request<T>(method: HttpMethod, path: string, options?: RequestOptions): Promise<T>
  get<T>(path: string, query?: Record<string, QueryValue>, signal?: AbortSignal): Promise<T>
  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>): Promise<T>
  put<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>): Promise<T>
  delete<T>(path: string): Promise<T>
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

  async function send(method: HttpMethod, path: string, request: RequestOptions, accept: string): Promise<Response> {
    const headers: Record<string, string> = { Accept: accept, 'X-Request-Id': newRequestId() }
    const token = options.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    if (request.idempotencyKey) headers['Idempotency-Key'] = request.idempotencyKey
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
    delete: (path) => request('DELETE', path),
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
