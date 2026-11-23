// Anonymous platform API calls for every feature.
// The implementation (base URL rules, LAN discovery, one retry after a network error, the error
// type) still lives in the inspection HTTP client, as it does for shared/weather; features other
// than inspection import it from here so they do not reach into inspection's files.
export {
  ApiRequestError,
  publicRequest,
  // The full address of an API path (base URL and HTTPS rules), for the few calls that are not a
  // plain JSON request: multipart uploads and binary reads (trade after-sales pictures).
  resolveInspectionApiRequestUrl as resolvePublicApiUrl,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'

export interface PublicApiHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}
