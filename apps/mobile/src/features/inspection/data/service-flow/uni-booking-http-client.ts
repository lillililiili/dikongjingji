import { createBookingUuid } from '../../application/service-flow/booking-crypto'
import {
  beginBookingAuthenticationRecovery,
  getBookingAuthContext,
} from '../../application/service-flow/booking-auth-context'
import { createInspectionCatalogApi } from '../catalog/inspection-catalog-api'
import type { BookingHttpClient, InspectionBookingApi } from './inspection-booking-api'
import { createInspectionBookingApi } from './inspection-booking-api'
import { createInspectionPlanApi } from './inspection-plan-api'
import { resolveInspectionApiBaseUrl } from './inspection-lan-endpoint'

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
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
  const payload = data as { message?: unknown; code?: unknown }
  return new ApiRequestError(
    typeof payload?.message === 'string' ? payload.message : `请求失败（${status}）`,
    status,
    typeof payload?.code === 'string' ? payload.code : null,
  )
}

async function publicRequest<T>(input: {
  url: string
  method: 'GET' | 'POST'
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
  const url = await resolveInspectionApiRequestUrl(input.url)
  return new Promise((resolve, reject) => {
    uni.request({
      url, method: input.method, data: input.data as object,
      header: { Authorization: `Bearer ${token}`, 'X-Request-Id': createBookingUuid(), ...input.headers },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        const error = errorFromResponse(response.data, response.statusCode)
        if (response.statusCode === 401 || error.code === 'COMMON_UNAUTHORIZED') {
          beginBookingAuthenticationRecovery()
        }
        reject(error)
      },
      fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
    })
  })
}

export interface InspectionLoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
}

export const inspectionAuthApi = {
  login(loginName: string, password: string): Promise<InspectionLoginResponse> {
    return publicRequest<InspectionLoginResponse>({
      url: '/api/v1/auth/login',
      method: 'POST',
      data: { loginName, password },
    })
  },
}

function putFile(input: { url: string; filePath: string; headers: Record<string, string> }): Promise<{ etag: string }> {
  return new Promise((resolve, reject) => {
    const manager = uni.getFileSystemManager?.()
    if (!manager) { reject(new ApiRequestError('当前平台未配置文件上传能力', 0, 'FILE_UPLOAD_UNAVAILABLE')); return }
    manager.readFile({ filePath: input.filePath, success: ({ data }) => {
      uni.request({ url: input.url, method: 'PUT', data, header: input.headers, success: (response) => {
        if (response.statusCode < 200 || response.statusCode >= 300) { reject(errorFromResponse(response.data, response.statusCode)); return }
        const headers = response.header as Record<string, string>; const etag = headers.etag ?? headers.ETag
        etag ? resolve({ etag }) : reject(new ApiRequestError('附件上传未返回 ETag', response.statusCode, 'UPLOAD_ETAG_MISSING'))
      }, fail: () => reject(new ApiRequestError('附件上传失败，请重试', 0, 'NETWORK_ERROR')) })
    }, fail: () => reject(new ApiRequestError('无法读取待上传附件', 0, 'FILE_READ_FAILED')) })
  })
}

const http: BookingHttpClient = { request, putFile }
export const inspectionCatalogApi = createInspectionCatalogApi({ request: publicRequest })
export const inspectionBookingApi: InspectionBookingApi = createInspectionBookingApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
}, { request: publicRequest })
export const inspectionPlanApi = createInspectionPlanApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
