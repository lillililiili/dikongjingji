import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import { createLiveHostApi } from './live-host-api'
import {
  draftProblem,
  emptyDraft,
  itemsProblem,
  maskPushUrl,
  parseItemRef,
  replayProblem,
  requestOf,
} from './live-rules'

const ID = 'c2000000-0000-4000-8000-000000000101'

function apiWith(fetchImpl: typeof fetch) {
  return createLiveHostApi(createApiClient({ getToken: () => 'jwt', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' }))
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('接口拼法', () => {
  it('列表按状态筛，每页最多 20 条；建直播间带幂等键，201、200 都算成功', async () => {
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(200, { items: [], page: 1, size: 20, total: 0, hasMore: false }))
      .mockResolvedValueOnce(json(201, { room: { roomId: 'a' }, pushUrl: 'rtmp://x/y' }))
      .mockResolvedValueOnce(json(200, { room: { roomId: 'a' }, pushUrl: 'rtmp://x/y' }))
    const api = apiWith(fetchImpl)

    await api.list({ status: 'LIVE' }, 1, 50)
    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/live-host/rooms?status=LIVE&page=1&size=20')

    const draft = requestOf({ ...emptyDraft(), title: '编程机器人入门' })
    await expect(api.create(draft, 'k1')).resolves.toMatchObject({ room: { roomId: 'a' } })
    await expect(api.create(draft, 'k1')).resolves.toMatchObject({ room: { roomId: 'a' } })
    expect(fetchImpl.mock.calls[1]![1]!.headers).toMatchObject({ 'Idempotency-Key': 'k1' })
  })

  it('讲解清单、当前讲解项、回看、隐藏弹幕、读弹幕的路径和请求体', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => json(200, {}))
    const api = apiWith(fetchImpl)
    await api.setItems('room-1', [{ type: 'PRODUCT', refId: ID, title: '套装' }], 1)
    await api.setCurrentItem('room-1', null)
    await api.setReplay('room-1', 'https://live.example.com/r', '示例直播平台')
    await api.hideComment('room-1', 'c-9')
    await api.comments('room-1')

    const calls = fetchImpl.mock.calls.map(([url, init]) => [url, init!.method, init!.body ? JSON.parse(init!.body as string) : null])
    expect(calls).toEqual([
      ['/api/v1/live-host/rooms/room-1/items', 'PUT', { items: [{ type: 'PRODUCT', refId: ID, title: '套装' }], currentSeq: 1 }],
      ['/api/v1/live-host/rooms/room-1/current-item', 'PUT', { seq: null }],
      ['/api/v1/live-host/rooms/room-1/replay', 'PUT', { replayUrl: 'https://live.example.com/r', replayPlatformName: '示例直播平台' }],
      ['/api/v1/live-host/rooms/room-1/comments/c-9/hide', 'POST', null],
      ['/api/v1/live/rooms/room-1/comments?size=50', 'GET', null],
    ])
  })

  it('直播的业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'LIVE_ROOM_NOT_SCHEDULED', '', 't'))).toBe('直播已经开始或结束，预告不能再改')
  })
})

describe('推流地址', () => {
  it('默认只露协议和主机，路径和流密钥打码', () => {
    expect(maskPushUrl('rtmp://127.0.0.1:1935/live/fake-seed-night-patrol')).toBe('rtmp://127.0.0.1:1935/********')
    expect(maskPushUrl('rtmps://push.example.com/app/key?auth=secret')).toBe('rtmps://push.example.com/********')
    expect(maskPushUrl('secret-without-scheme')).toBe('********')
    expect(maskPushUrl('rtmp://127.0.0.1:1935/live/fake-seed-night-patrol')).not.toContain('night-patrol')
  })
})

describe('讲解项', () => {
  it('直接粘 ID，或从 App 分享链接里按类型取 ID', () => {
    expect(parseItemRef('PRODUCT', ` ${ID.toUpperCase()} `)).toBe(ID)
    expect(parseItemRef('PRODUCT', `https://app.example.test/#/pages/mall/product?productId=${ID}&cityCode=370500`)).toBe(ID)
    expect(parseItemRef('VENUE', `/pages/venue/detail?venueId=${ID}&cityCode=370500`)).toBe(ID)
    expect(parseItemRef('ACTIVITY', `/pages/mall/product?productId=${ID}`)).toBeNull()
    expect(parseItemRef('PRODUCT', 'not-an-id')).toBeNull()
  })

  it('清单最多 10 项、标题 1–40 字、同一对象不重复', () => {
    const item = { type: 'PRODUCT' as const, ref: ID, title: '套装' }
    expect(itemsProblem([item])).toBeNull()
    expect(itemsProblem([item, { ...item, ref: `/pages/mall/product?productId=${ID}` }])).toContain('重复')
    expect(itemsProblem([{ ...item, title: '' }])).toContain('标题')
    expect(itemsProblem(Array.from({ length: 11 }, () => item))).toBe('讲解清单最多 10 项')
  })
})

describe('预告和回看', () => {
  const now = new Date('2026-11-23T00:00:00Z')

  it('标题 ≤30 字、开播时间在现在之后 90 天以内、讲什么 ≤5 条', () => {
    const ok = { ...emptyDraft(now), title: '编程机器人入门' }
    expect(draftProblem(ok, now)).toBeNull()
    expect(draftProblem({ ...ok, title: '' }, now)).toContain('标题')
    expect(draftProblem({ ...ok, scheduledAt: '2026-11-22T00:00:00Z' }, now)).toBe('开播时间要在现在之后')
    expect(draftProblem({ ...ok, scheduledAt: '2027-03-01T00:00:00Z' }, now)).toBe('开播时间要在 90 天以内')
    expect(draftProblem({ ...ok, cityCode: '3705' }, now)).toContain('6 位数字')
    expect(draftProblem({ ...ok, topics: 'a\nb\nc\nd\ne\nf' }, now)).toContain('最多 5 条')
    expect(requestOf({ ...ok, topics: ' 拼装 \n\n编程 ' })).toMatchObject({ topics: ['拼装', '编程'], cityCode: null })
  })

  it('回看链接和平台名一起给或一起不给，链接要 https', () => {
    expect(replayProblem('', '')).toBeNull()
    expect(replayProblem('https://x.example.com/r', '')).toContain('一起填')
    expect(replayProblem('http://x.example.com/r', '平台')).toContain('https')
    expect(replayProblem('https://x.example.com/r', '平台')).toBeNull()
  })
})
