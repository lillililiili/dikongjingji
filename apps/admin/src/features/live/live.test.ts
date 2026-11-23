import { describe, expect, it, vi } from 'vitest'

import type { ApiClient } from '@/core/http/api-client'

import { createLiveApi, replayProblem } from './live-api'
import { grantLiveHostRoles } from './live-hosts'

function fakeClient() {
  const calls: Array<{ method: string, path: string, body?: unknown, query?: unknown }> = []
  const client: ApiClient = {
    request: vi.fn(),
    get: vi.fn(async (path, query) => {
      calls.push({ method: 'GET', path, query })
      return [] as never
    }),
    post: vi.fn(async (path, body) => {
      calls.push({ method: 'POST', path, body })
      return {} as never
    }),
    put: vi.fn(async (path, body) => {
      calls.push({ method: 'PUT', path, body })
      return {} as never
    }),
    delete: vi.fn(),
    download: vi.fn(),
  }
  return { client, calls }
}

describe('直播间接口拼法（openapi-ops-live.yaml）', () => {
  it('列表带筛选，强制结束带原因和版本号', async () => {
    const { client, calls } = fakeClient()
    const api = createLiveApi(client)
    await api.rooms({ keyword: ' 黄河口 ', status: 'LIVE', hostOrganizationId: '' }, 2, 20)
    await api.end('f4000000-0000-4000-8000-000000000001', '  画面违规 ', 4)
    expect(calls[0]).toEqual({
      method: 'GET',
      path: '/api/v1/ops/live/rooms',
      query: { keyword: '黄河口', status: 'LIVE', hostOrganizationId: '', page: 2, size: 20 },
    })
    expect(calls[1]).toEqual({
      method: 'POST',
      path: '/api/v1/ops/live/rooms/f4000000-0000-4000-8000-000000000001/end',
      body: { note: '画面违规', version: 4 },
    })
  })

  it('回看两项都清空时发 null（去掉回看）', async () => {
    const { client, calls } = fakeClient()
    await createLiveApi(client).replay('r1', { replayUrl: ' ', replayPlatformName: '' }, 3)
    await createLiveApi(client).replay('r1', { replayUrl: ' https://replay.example.com/1 ', replayPlatformName: '视频号' }, 4)
    expect(calls[0]!.body).toEqual({ replayUrl: null, replayPlatformName: null, version: 3 })
    expect(calls[1]!.body).toEqual({ replayUrl: 'https://replay.example.com/1', replayPlatformName: '视频号', version: 4 })
  })
})

describe('回看校验', () => {
  it('两项一起填或一起空，地址只收 https，平台名称最多 20 字', () => {
    expect(replayProblem({ replayUrl: '', replayPlatformName: '' })).toBeNull()
    expect(replayProblem({ replayUrl: 'https://replay.example.com/1', replayPlatformName: '视频号' })).toBeNull()
    expect(replayProblem({ replayUrl: 'https://replay.example.com/1', replayPlatformName: '' })).toContain('一起填')
    expect(replayProblem({ replayUrl: 'http://replay.example.com/1', replayPlatformName: '视频号' })).toContain('https')
    expect(replayProblem({ replayUrl: 'https://replay.example.com/1', replayPlatformName: '名'.repeat(21) }))
      .toContain('20')
  })
})

describe('开通直播主办', () => {
  it('保留账号现有的可授角色，再加上直播主办；已经有了不重复', () => {
    expect(grantLiveHostRoles(['PROVIDER_OPERATOR'])).toEqual(['PROVIDER_OPERATOR', 'LIVE_HOST'])
    expect(grantLiveHostRoles([])).toEqual(['LIVE_HOST'])
    expect(grantLiveHostRoles(['LIVE_HOST', 'PROVIDER_OPERATOR'])).toEqual(['PROVIDER_OPERATOR', 'LIVE_HOST'])
    expect(grantLiveHostRoles(['PLATFORM_OPERATOR', 'PROVIDER_OPERATOR'])).toEqual(['PROVIDER_OPERATOR', 'LIVE_HOST'])
  })
})
