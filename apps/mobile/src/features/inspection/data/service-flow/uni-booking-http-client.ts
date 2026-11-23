import { createBookingUuid } from '../../application/service-flow/booking-crypto'
import { createCityDirectoryApi } from '../../../../shared/city/data/city-directory-api'
import {
  beginBookingAuthenticationRecovery,
  clearBookingAuthSession,
  getBookingAuthContext,
  readBookingRefreshToken,
  saveBookingAuthSession,
} from '../../application/service-flow/booking-auth-context'
import { createInspectionCatalogApi } from '../catalog/inspection-catalog-api'
import { createInspectionServiceDetailApi } from '../detail/inspection-service-detail-api'
import type { BookingHttpClient, InspectionBookingApi } from './inspection-booking-api'
import { createInspectionBookingApi } from './inspection-booking-api'
import { createInspectionOrderApi } from './inspection-order-api'
import { createInspectionAccountApi } from './inspection-account-api'
import { createInspectionPlanApi } from './inspection-plan-api'
import {
  invalidateInspectionApiBaseUrl,
  resolveInspectionApiBaseUrl,
} from './inspection-lan-endpoint'
import { BookingFileAccessError, uniBookingFileAccessPort } from './booking-file-access'

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    // The ApiError's details, when the server sent some (for example the field of a 422).
    readonly details: Record<string, unknown> | null = null,
  ) { super(message) }
}

function isPrivateLanIpv4(hostname: string): boolean {
  const octets = hostname.split('.')
  if (octets.length !== 4 || octets.some((octet) => !/^\d{1,3}$/.test(octet)
    || (octet.length > 1 && octet.startsWith('0')))) return false

  const [first, second, third, fourth] = octets.map(Number)
  if ([first, second, third, fourth].some((octet) => octet > 255)) return false
  return first === 10 || (first === 172 && second >= 16 && second <= 31) || (first === 192 && second === 168)
}

function parseApiBaseUrl(baseUrl: string): { protocol: string; hostname: string } | null {
  const match = /^(https?):\/\/([^/?#]+)(?:[/?#].*)?$/i.exec(baseUrl)
  if (!match || /[\s\\]/.test(baseUrl) || match[2].includes('@')) return null

  const authority = /^(\[[0-9a-f:.]+\]|[^:]+)(?::(\d+))?$/i.exec(match[2])
  if (!authority || (authority[2] && Number(authority[2]) > 65_535)) return null
  return { protocol: `${match[1].toLowerCase()}:`, hostname: authority[1].toLowerCase() }
}

export async function resolveInspectionApiRequestUrl(path: string): Promise<string> {
  const configuredBaseUrl = import.meta.env.VITE_INSPECTION_API_BASE_URL?.trim()
  if (!configuredBaseUrl) throw new ApiRequestError('缺少 VITE_INSPECTION_API_BASE_URL，无法连接巡检服务', 0, 'API_BASE_URL_MISSING')
  const baseUrl = await resolveInspectionApiBaseUrl(configuredBaseUrl)
  assertAllowedApiBaseUrl(baseUrl)
  return `${baseUrl.replace(/\/$/, '')}${path}`
}

function assertAllowedApiBaseUrl(baseUrl: string): void {
  const parsedBaseUrl = parseApiBaseUrl(baseUrl)
  if (!parsedBaseUrl) throw new ApiRequestError('API base URL is invalid', 0, 'INSECURE_API_BASE_URL')
  const insecureLoopbackAllowed = import.meta.env.VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API === 'true'
    && parsedBaseUrl.protocol === 'http:'
    && (parsedBaseUrl.hostname === '127.0.0.1' || parsedBaseUrl.hostname === 'localhost')
  const insecureLanAllowed = import.meta.env.VITE_INSPECTION_ALLOW_INSECURE_LAN_API === 'true'
    && parsedBaseUrl.protocol === 'http:'
    && isPrivateLanIpv4(parsedBaseUrl.hostname)
  if (parsedBaseUrl.protocol !== 'https:' && !import.meta.env.DEV && !insecureLoopbackAllowed && !insecureLanAllowed) {
    throw new ApiRequestError('生产环境巡检 API 必须使用 HTTPS', 0, 'INSECURE_API_BASE_URL')
  }
}

function errorFromResponse(data: unknown, status: number): ApiRequestError {
  const payload = data as { message?: unknown; code?: unknown; details?: unknown }
  return new ApiRequestError(
    typeof payload?.message === 'string' ? payload.message : `请求失败（${status}）`,
    status,
    typeof payload?.code === 'string' ? payload.code : null,
    payload?.details && typeof payload.details === 'object' && !Array.isArray(payload.details)
      ? payload.details as Record<string, unknown>
      : null,
  )
}

type PublicRequestInput = {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}

// Exported for shared/http/public-api-request.ts, the entry every feature uses: anonymous calls, and
// calls that carry the signed-in account's token in an Authorization header.
//
// Login renewal lives here so every feature gets it: a call with a Bearer token always goes out with
// the session's current access token (a page may still hold one that was renewed since), and a 401
// is answered by renewing the login once with the refresh token and sending the call again. Only when
// that fails does the 401 reach the page, which then sends the user to the login page as before.
export async function publicRequest<T>(input: PublicRequestInput): Promise<T> {
  const usedToken = bearerToken(input.headers)
  const sent = usedToken ? withAccessToken(input, currentAccessTokenOr(usedToken)) : input
  try {
    return await sendWithEndpointRetry<T>(sent)
  } catch (error) {
    if (!usedToken || !isUnauthorized(error)) throw error
    const renewed = await accessTokenAfterUnauthorized(bearerToken(sent.headers) ?? usedToken)
    if (!renewed) throw error
    return sendWithEndpointRetry<T>(withAccessToken(input, renewed))
  }
}

async function sendWithEndpointRetry<T>(input: PublicRequestInput): Promise<T> {
  try {
    return await sendPublicRequest<T>(input)
  } catch (error) {
    if (!(error instanceof ApiRequestError) || error.code !== 'NETWORK_ERROR') throw error
    invalidateInspectionApiBaseUrl()
    return sendPublicRequest<T>(input)
  }
}

function bearerToken(headers: Record<string, string> | undefined): string | null {
  const value = headers?.Authorization ?? headers?.authorization
  const match = typeof value === 'string' ? /^Bearer (\S+)$/.exec(value.trim()) : null
  return match ? match[1] : null
}

function withAccessToken(input: PublicRequestInput, token: string): PublicRequestInput {
  const headers = Object.fromEntries(Object.entries(input.headers ?? {})
    .filter(([name]) => name.toLowerCase() !== 'authorization'))
  return { ...input, headers: { ...headers, Authorization: `Bearer ${token}` } }
}

function currentAccessTokenOr(fallback: string): string {
  return getBookingAuthContext().getAccessToken() ?? fallback
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

let renewalInFlight: Promise<string | null> | null = null

/**
 * The access token to retry with after a call made with {@code rejectedToken} got a 401: the
 * session's newer token when another call renewed it meanwhile, otherwise a freshly renewed one.
 * Calls that fail together share one renewal: a refresh token works once, and using it twice would
 * look like theft to the server and end the login. Null when the login cannot be renewed.
 */
export async function accessTokenAfterUnauthorized(rejectedToken: string): Promise<string | null> {
  if (renewalInFlight) return renewalInFlight
  const current = getBookingAuthContext().getAccessToken()
  if (current && current !== rejectedToken) return current
  renewalInFlight = renewLogin().finally(() => { renewalInFlight = null })
  return renewalInFlight
}

async function renewLogin(): Promise<string | null> {
  const refreshToken = readBookingRefreshToken()
  if (!refreshToken) return null
  try {
    // No Authorization header: the server would reject the expired access token before looking at the body.
    const renewed = await sendWithEndpointRetry<InspectionLoginResponse>({
      url: '/api/v1/auth/refresh',
      method: 'POST',
      data: { refreshToken },
    })
    saveBookingAuthSession(renewed)
    return renewed.accessToken
  } catch (error) {
    // Offline: the login may still be renewable later, keep it. Any answer from the server means the
    // refresh token is spent, expired or withdrawn: the login is over.
    if (!(error instanceof ApiRequestError) || error.code !== 'NETWORK_ERROR') clearBookingAuthSession()
    return null
  }
}

async function sendPublicRequest<T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}): Promise<T> {
  const url = await resolveInspectionApiRequestUrl(input.url)
  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method: input.method,
      data: input.data as object,
      header: { 'X-Request-Id': createBookingUuid(), ...input.headers },
      success: (response) => response.statusCode >= 200 && response.statusCode < 300
        ? resolve(response.data as T)
        : reject(errorFromResponse(response.data, response.statusCode)),
      fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
    })
  })
}

async function request<T>(input: { url: string; method: 'GET' | 'POST'; data?: unknown; headers?: Record<string, string> }): Promise<T> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    beginBookingAuthenticationRecovery()
    throw new ApiRequestError('请先登录后再进行预约操作', 401, 'COMMON_UNAUTHORIZED')
  }
  try {
    return await sendAuthorized<T>(input, token)
  } catch (error) {
    if (!isUnauthorized(error)) throw error
    // A 401 is answered before the request is handled, so sending it again once is safe.
    const renewed = await accessTokenAfterUnauthorized(token)
    if (renewed) {
      try {
        return await sendAuthorized<T>(input, renewed)
      } catch (retryError) {
        if (!isUnauthorized(retryError)) throw retryError
      }
    }
    beginBookingAuthenticationRecovery()
    throw error
  }
}

function sendAuthorized<T>(
  input: { url: string; method: 'GET' | 'POST'; data?: unknown; headers?: Record<string, string> },
  token: string,
): Promise<T> {
  return resolveInspectionApiRequestUrl(input.url).then((url) => sendAuthorizedTo<T>(url, input, token))
}

function sendAuthorizedTo<T>(
  url: string,
  input: { method: 'GET' | 'POST'; data?: unknown; headers?: Record<string, string> },
  token: string,
): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url, method: input.method, data: input.data as object,
      // The session's token goes last: callers also put their copy of it in the headers, and after a
      // renewal that copy is the old one.
      header: { 'X-Request-Id': createBookingUuid(), ...input.headers, Authorization: `Bearer ${token}` },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        reject(errorFromResponse(response.data, response.statusCode))
      },
      fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
    })
  })
}

export interface InspectionLoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  refreshToken?: string
  refreshExpiresInSeconds?: number
}

export const inspectionAuthApi = {
  // device (contract 2.4.0): which device signs in, for 登录设备 (S5-8); preview builds send it.
  login(loginName: string, password: string, device?: Record<string, unknown>): Promise<InspectionLoginResponse> {
    return publicRequest<InspectionLoginResponse>({
      url: '/api/v1/auth/login',
      method: 'POST',
      data: device ? { loginName, password, device } : { loginName, password },
    })
  },
}

/**
 * Signs out on this device: the local tokens go at once, then the server is told to withdraw the
 * login (its refresh token). A failed call does not keep the user signed in; the server's copy then
 * runs out by itself.
 */
export function signOutBookingSession(): void {
  const refreshToken = readBookingRefreshToken()
  clearBookingAuthSession()
  if (!refreshToken) return
  void sendWithEndpointRetry<void>({
    url: '/api/v1/auth/logout',
    method: 'POST',
    data: { refreshToken },
  }).catch(() => undefined)
}

async function putFile(input: { url: string; filePath: string; headers: Record<string, string> }): Promise<{ etag: string }> {
  let bytes: Uint8Array
  try {
    bytes = await uniBookingFileAccessPort.readBytes(input.filePath)
  } catch (error) {
    if (error instanceof BookingFileAccessError && error.code === 'UNAVAILABLE') {
      throw new ApiRequestError('当前平台未配置文件上传能力', 0, 'FILE_UPLOAD_UNAVAILABLE')
    }
    throw new ApiRequestError('无法读取待上传附件', 0, 'FILE_READ_FAILED')
  }

  const body = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  return new Promise((resolve, reject) => {
    uni.request({ url: input.url, method: 'PUT', data: body, header: input.headers, success: (response) => {
      if (response.statusCode < 200 || response.statusCode >= 300) { reject(errorFromResponse(response.data, response.statusCode)); return }
      const headers = response.header as Record<string, string>; const etag = headers.etag ?? headers.ETag
      etag ? resolve({ etag }) : reject(new ApiRequestError('附件上传未返回 ETag', response.statusCode, 'UPLOAD_ETAG_MISSING'))
    }, fail: () => reject(new ApiRequestError('附件上传失败，请重试', 0, 'NETWORK_ERROR')) })
  })
}

const http: BookingHttpClient = { request, putFile }
export const inspectionCatalogApi = createInspectionCatalogApi({ request: publicRequest })
export const inspectionServiceDetailApi = createInspectionServiceDetailApi({ request: publicRequest })
export const platformCityApi = createCityDirectoryApi({ request: publicRequest })
export const inspectionAccountApi = createInspectionAccountApi(publicRequest)
export const inspectionBookingApi: InspectionBookingApi = createInspectionBookingApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
}, { request: publicRequest })
export const inspectionPlanApi = createInspectionPlanApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
export const inspectionOrderApi = createInspectionOrderApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
