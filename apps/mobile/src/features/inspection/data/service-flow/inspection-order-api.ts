import type {
  InspectionOrderFilter,
  InspectionOrderPage,
} from '../../domain/service-flow/order-center'
import type {
  AccessTokenPort,
  BookingHttpClient,
} from './inspection-booking-api'

export interface InspectionOrderApi {
  list(input: {
    stage: InspectionOrderFilter
    page: number
    size: number
  }): Promise<InspectionOrderPage>
}

export function createInspectionOrderApi(
  http: BookingHttpClient,
  accessToken: AccessTokenPort = { getAccessToken: () => null },
): InspectionOrderApi {
  return {
    list(input) {
      if (!Number.isInteger(input.page) || input.page < 1
          || !Number.isInteger(input.size) || input.size < 1 || input.size > 50) {
        return Promise.reject(new Error('INVALID_ORDER_PAGE'))
      }
      const token = accessToken.getAccessToken()
      const query = [
        `stage=${encodeURIComponent(input.stage)}`,
        `page=${input.page}`,
        `size=${input.size}`,
      ].join('&')
      return http.request<InspectionOrderPage>({
        url: `/api/v1/inspection/reservations?${query}`,
        method: 'GET',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
    },
  }
}
