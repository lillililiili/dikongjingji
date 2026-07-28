import { createBookingUuid } from '../../application/service-flow/booking-crypto'
import { getBookingAuthContext } from '../../application/service-flow/booking-auth-context'
import { createInspectionCatalogApi } from '../catalog/inspection-catalog-api'
import type { BookingHttpClient, InspectionBookingApi } from './inspection-booking-api'
import { createInspectionBookingApi } from './inspection-booking-api'
import { createInspectionPlanApi } from './inspection-plan-api'

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) { super(message) }
}

function apiUrl(path: string): string {
  const baseUrl = import.meta.env.VITE_INSPECTION_API_BASE_URL?.trim()
  if (!baseUrl) throw new ApiRequestError('缺少 VITE_INSPECTION_API_BASE_URL，无法连接巡检服务', 0, 'API_BASE_URL_MISSING')
  const insecureLoopbackAllowed = import.meta.env.VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API === 'true'
    && /^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?(?:\/|$)/i.test(baseUrl)
  if (!/^https:\/\//i.test(baseUrl) && !import.meta.env.DEV && !insecureLoopbackAllowed) {
    throw new ApiRequestError('生产环境巡检 API 必须使用 HTTPS', 0, 'INSECURE_API_BASE_URL')
  }
  return `${baseUrl.replace(/\/$/, '')}${path}`
}

function errorFromResponse(data: unknown, status: number): ApiRequestError {
  const payload = data as { message?: unknown; code?: unknown }
  return new ApiRequestError(
    typeof payload?.message === 'string' ? payload.message : `请求失败（${status}）`,
    status,
    typeof payload?.code === 'string' ? payload.code : null,
  )
}

function request<T>(input: { url: string; method: 'GET' | 'POST'; data?: unknown; headers?: Record<string, string> }): Promise<T> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) return Promise.reject(new ApiRequestError('请先登录后再进行预约操作', 401, 'COMMON_UNAUTHORIZED'))
  return new Promise((resolve, reject) => {
    uni.request({
      url: apiUrl(input.url), method: input.method, data: input.data as object,
      header: { Authorization: `Bearer ${token}`, 'X-Request-Id': createBookingUuid(), ...input.headers },
      success: (response) => response.statusCode >= 200 && response.statusCode < 300
        ? resolve(response.data as T)
        : reject(errorFromResponse(response.data, response.statusCode)),
      fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
    })
  })
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
export const inspectionCatalogApi = createInspectionCatalogApi(http)
export const inspectionBookingApi: InspectionBookingApi = createInspectionBookingApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
export const inspectionPlanApi = createInspectionPlanApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
