import { createApiClient, type ApiClient } from './api-client'

/** 测试用：记下每次请求的地址、方法、请求头和请求体，回一个固定的 JSON。只给单元测试用。 */
export interface RecordedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body: BodyInit | null | undefined
}

export function recordingClient(reply: unknown = {}): { http: ApiClient, requests: RecordedRequest[] } {
  const requests: RecordedRequest[] = []
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      method: init?.method ?? 'GET',
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body,
    })
    return new Response(JSON.stringify(reply), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  return {
    requests,
    http: createApiClient({ baseUrl: '', getToken: () => 't', onUnauthorized: () => undefined, fetchImpl }),
  }
}

export function jsonBody(request: RecordedRequest | undefined): unknown {
  return JSON.parse(String(request?.body))
}
