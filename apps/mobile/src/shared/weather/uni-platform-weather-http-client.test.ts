import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createUniPlatformWeatherHttpClient } from './uni-platform-weather-http-client'

vi.mock('../../features/inspection/data/service-flow/uni-booking-http-client', () => ({
  resolveInspectionApiRequestUrl: vi.fn(async (path: string) => `https://api.example.test${path}`),
}))

describe('uni platform weather HTTP client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('loads public city weather without a login token', async () => {
    const request = vi.fn((input: {
      success(response: { statusCode: number; data: { cityName: string } }): void
    }) => {
      input.success({ statusCode: 200, data: { cityName: '济南市' } })
    })
    vi.stubGlobal('uni', { request })

    const client = createUniPlatformWeatherHttpClient({
      getAccessToken: () => null,
    })

    await expect(client.request<{ cityName: string }>({
      url: '/api/v1/platform/weather/current?cityName=%E6%B5%8E%E5%8D%97%E5%B8%82',
      method: 'GET',
    })).resolves.toEqual({ cityName: '济南市' })
    expect(request).toHaveBeenCalledOnce()
    expect(request.mock.calls[0]?.[0]).toMatchObject({ header: {} })
  })
})
