import { describe, expect, it, vi } from 'vitest'

import { buildUrl, createApiClient } from './api-client'
import { ApiRequestError, errorMessage, errorMessageWithTrace, registerErrorMessages } from './api-error'

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function client(fetchImpl: typeof fetch, token: string | null = 'token-1', onUnauthorized = vi.fn()) {
  return {
    onUnauthorized,
    api: createApiClient({
      baseUrl: '',
      getToken: () => token,
      onUnauthorized,
      fetchImpl,
      newRequestId: () => 'req-1',
    }),
  }
}

describe('接口客户端', () => {
  it('带上令牌、请求号，查询参数跳过空值', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(200, { page: 1 }))
    const { api } = client(fetchImpl)

    await expect(api.get('/api/v1/ops/cities', { keyword: '济南', serviceStatus: '', page: 2, size: undefined }))
      .resolves.toEqual({ page: 1 })

    const [url, init] = fetchImpl.mock.calls[0]!
    expect(url).toBe('/api/v1/ops/cities?keyword=%E6%B5%8E%E5%8D%97&page=2')
    expect(init?.method).toBe('GET')
    expect(init?.headers).toMatchObject({ Authorization: 'Bearer token-1', 'X-Request-Id': 'req-1' })
  })

  it('写请求发 JSON，带幂等键；204 返回空', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    const { api } = client(fetchImpl)

    await expect(api.post('/api/v1/ops/organizations', { name: 'x' }, { idempotencyKey: 'key-1' }))
      .resolves.toBeUndefined()

    const init = fetchImpl.mock.calls[0]![1]!
    expect(init.body).toBe('{"name":"x"}')
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json', 'Idempotency-Key': 'key-1' })
  })

  it('改状态的操作带 If-Match 强 ETag；DELETE 也能带幂等键', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, { version: 4 }))
    const { api } = client(fetchImpl)

    await api.post('/api/v1/provider/trade/orders/o-1/shipment', { trackingNo: 'SF1' },
      { idempotencyKey: 'key-2', ifMatch: 3 })
    expect(fetchImpl.mock.calls[0]![1]!.headers).toMatchObject({ 'If-Match': '"3"', 'Idempotency-Key': 'key-2' })

    await api.delete('/api/v1/provider/demands/d-1/quote', { idempotencyKey: 'key-3' })
    const init = fetchImpl.mock.calls[1]![1]!
    expect(init.method).toBe('DELETE')
    expect(init.headers).not.toHaveProperty('If-Match')
    expect(init.headers).toMatchObject({ 'Idempotency-Key': 'key-3' })
  })

  it('功能块登记的业务错误码有中文说明，通用说明不被覆盖', () => {
    registerErrorMessages({ TRADE_TEST_ONLY_CODE: '测试用的说明', COMMON_FORBIDDEN: '不该覆盖' })
    expect(errorMessage(new ApiRequestError(409, 'TRADE_TEST_ONLY_CODE', 'server', 't'))).toBe('测试用的说明')
    expect(errorMessage(new ApiRequestError(403, 'COMMON_FORBIDDEN', '', 't'))).toBe('你的账号没有这项操作的权限')
  })

  it('上传文件时不设 Content-Type（由浏览器带 multipart 边界）', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(201, { ok: true }))
    const { api } = client(fetchImpl)
    const form = new FormData()
    form.append('file', new Blob(['%PDF']), 'a.pdf')

    await api.request('POST', '/upload', { form })

    const init = fetchImpl.mock.calls[0]![1]!
    expect(init.body).toBe(form)
    expect(init.headers).not.toHaveProperty('Content-Type')
  })

  it('把统一错误体解析成 ApiRequestError，按错误码给中文说明', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(409,
      { code: 'OPS_VERSION_CONFLICT', message: '数据已被他人修改，请刷新后重试', traceId: 'trace-9' }))
    const { api, onUnauthorized } = client(fetchImpl)

    const failure = await api.put('/x', {}).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiRequestError)
    expect(failure).toMatchObject({ status: 409, code: 'OPS_VERSION_CONFLICT', traceId: 'trace-9' })
    expect(errorMessage(failure)).toBe('数据刚被别人修改过，请刷新后重试')
    expect(errorMessageWithTrace(failure)).toBe('数据刚被别人修改过，请刷新后重试（追踪号 trace-9）')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('没登记的错误码用后端的说明；没有错误体时按状态码给通用码', async () => {
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(409, { code: 'MALL_SOMETHING', message: '后端说明', traceId: 't' }))
      .mockResolvedValueOnce(new Response('<html>bad gateway</html>', { status: 502 }))
    const { api } = client(fetchImpl)

    expect(errorMessage(await api.get('/a').catch((e: unknown) => e))).toBe('后端说明')
    const gateway = await api.get('/b').catch((e: unknown) => e)
    expect(gateway).toMatchObject({ status: 502, code: 'COMMON_SERVICE_UNAVAILABLE' })
  })

  it('401 时通知登录失效', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(401,
      { code: 'COMMON_UNAUTHORIZED', message: '请先登录', traceId: 't' }))
    const { api, onUnauthorized } = client(fetchImpl)

    await expect(api.get('/a')).rejects.toMatchObject({ code: 'COMMON_UNAUTHORIZED' })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('网络断开时给网络错误码；没有令牌时不带 Authorization', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'))
    const { api } = client(fetchImpl, null)

    const failure = await api.get('/a').catch((e: unknown) => e)
    expect(failure).toMatchObject({ status: 0, code: 'CLIENT_NETWORK_ERROR' })
    expect(errorMessage(failure)).toBe('网络异常，请检查网络后重试')
    expect(fetchImpl.mock.calls[0]![1]!.headers).not.toHaveProperty('Authorization')
  })

  it('下载附件时取 Blob 和 UTF-8 文件名', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(new Response('%PDF', {
      status: 200,
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''%E8%90%A5%E4%B8%9A%E6%89%A7%E7%85%A7.pdf" },
    }))
    const { api } = client(fetchImpl)

    const file = await api.download('/file')

    expect(file.fileName).toBe('营业执照.pdf')
    expect(await file.blob.text()).toBe('%PDF')
  })

  it('拼地址', () => {
    expect(buildUrl('/base', '/p', { a: 0, b: false, c: null })).toBe('/base/p?a=0&b=false')
  })
})
