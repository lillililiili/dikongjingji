import type { PlatformWeatherHttpClient } from './platform-weather'

export interface PlatformWeatherAccessTokenPort {
  getAccessToken(): string | null
}

export class PlatformWeatherRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message)
  }
}

function apiUrl(path: string): string {
  const baseUrl = import.meta.env.VITE_INSPECTION_API_BASE_URL?.trim()
  if (!baseUrl) {
    throw new PlatformWeatherRequestError(
      '缺少 VITE_INSPECTION_API_BASE_URL，无法查询天气',
      0,
      'API_BASE_URL_MISSING',
    )
  }
  const insecureLoopbackAllowed = import.meta.env.DEV
    && /^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?(?:\/|$)/i.test(baseUrl)
  if (!/^https:\/\//i.test(baseUrl) && !insecureLoopbackAllowed) {
    throw new PlatformWeatherRequestError(
      '生产环境天气 API 必须使用 HTTPS',
      0,
      'INSECURE_API_BASE_URL',
    )
  }
  return `${baseUrl.replace(/\/$/, '')}${path}`
}

function responseError(data: unknown, status: number): PlatformWeatherRequestError {
  const payload = data as { message?: unknown; code?: unknown }
  return new PlatformWeatherRequestError(
    typeof payload?.message === 'string' ? payload.message : `天气查询失败（${status}）`,
    status,
    typeof payload?.code === 'string' ? payload.code : null,
  )
}

export function createUniPlatformWeatherHttpClient(
  accessToken: PlatformWeatherAccessTokenPort,
): PlatformWeatherHttpClient {
  return {
    request<T>(input: { url: string; method: 'GET' }): Promise<T> {
      const token = accessToken.getAccessToken()
      if (!token) {
        return Promise.reject(new PlatformWeatherRequestError(
          '请先登录',
          401,
          'COMMON_UNAUTHORIZED',
        ))
      }

      return new Promise((resolve, reject) => {
        uni.request({
          url: apiUrl(input.url),
          method: input.method,
          header: { Authorization: `Bearer ${token}` },
          success: (response) => {
            if (response.statusCode >= 200 && response.statusCode < 300) {
              resolve(response.data as T)
              return
            }
            reject(responseError(response.data, response.statusCode))
          },
          fail: () => reject(new PlatformWeatherRequestError(
            '网络连接失败，请稍后重试',
            0,
            'NETWORK_ERROR',
          )),
        })
      })
    },
  }
}
