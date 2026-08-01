import { resolveInspectionApiRequestUrl } from '../../features/inspection/data/service-flow/uni-booking-http-client'

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
    async request<T>(input: { url: string; method: 'GET' }): Promise<T> {
      const token = accessToken.getAccessToken()
      const url = await resolveInspectionApiRequestUrl(input.url)

      return new Promise((resolve, reject) => {
        uni.request({
          url,
          method: input.method,
          header: token ? { Authorization: `Bearer ${token}` } : {},
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
