// Anonymous platform API calls for every feature.
// The implementation (base URL rules, LAN discovery, one retry after a network error, the error
// type) still lives in the inspection HTTP client, as it does for shared/weather; features other
// than inspection import it from here so they do not reach into inspection's files.
export {
  ApiRequestError,
  publicRequest,
  // The absolute URL of an API path (for a file the App hands to the browser or the downloader).
  resolveInspectionApiRequestUrl as resolvePublicApiUrl,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'

export interface PublicApiHttpClient {
  request<T>(input: { url: string; method: 'GET' }): Promise<T>
}
