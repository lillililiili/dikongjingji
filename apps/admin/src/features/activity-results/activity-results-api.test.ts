import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  buildResultRequest,
  createActivityResultsApi,
  initialResultForm,
  type ResultForm,
  type ResultRosterEntry,
} from './activity-results-api'

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { api: createActivityResultsApi(http), fetchImpl }
}

const ENTRY: ResultRosterEntry = {
  ticketId: 't-1',
  holderName: '王**',
  age: 11,
  codeTail: '0928',
  checkedIn: true,
  checkedInAt: '2026-11-15T00:42:00Z',
  teamName: null,
  result: null,
  resultVersion: null,
}

function form(overrides: Partial<ResultForm> = {}): ResultForm {
  return { completion: 'COMPLETED', scoreText: ' 完成任务 5/5 ', certificateNote: '', ...overrides }
}

describe('活动结果接口', () => {
  it('场次分页、名单、录入结果（PUT，不带幂等键和 If-Match）', async () => {
    const { api, fetchImpl } = fakeHttp()
    await api.sessions(2, 20)
    await api.roster('s 1')
    const built = buildResultRequest(form(), ENTRY)
    if (!built.ok) throw new Error(built.message)
    await api.record('t-1', built.request)

    const calls = fetchImpl.mock.calls.map(([url, init]) => ({ url: String(url), init: init! }))
    expect(calls.map((item) => `${item.init.method} ${item.url}`)).toEqual([
      'GET /api/v1/provider/activity-sessions?page=2&size=20',
      'GET /api/v1/provider/activity-sessions/s%201/roster',
      'PUT /api/v1/provider/activity-tickets/t-1/result',
    ])
    expect(JSON.parse(String(calls[2]!.init.body))).toEqual({
      completion: 'COMPLETED',
      scoreText: '完成任务 5/5',
      certificateNote: null,
      expectedVersion: null,
    })
    for (const item of calls) {
      expect(item.init.headers).not.toHaveProperty('Idempotency-Key')
      expect(item.init.headers).not.toHaveProperty('If-Match')
    }
  })

  it('业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'ACT_RESULT_NOT_ALLOWED', 'x', 't'))).toContain('还没签到')
    expect(errorMessage(new ApiRequestError(409, 'ACT_RESULT_VERSION_CONFLICT', 'x', 't'))).toContain('刷新')
  })
})

describe('结果表单', () => {
  it.each([
    [{ scoreText: '分'.repeat(41) }, '成绩最多 40'],
    [{ certificateNote: '证'.repeat(101) }, '证书说明最多 100'],
    [{ scoreText: '第一轮\n第二轮' }, '换行'],
    [{ completion: '' as never }, '完成情况'],
  ] as Array<[Partial<ResultForm>, string]>)('不合规时给出第一条错误：%o', (overrides, message) => {
    const built = buildResultRequest(form(overrides), ENTRY)
    expect(built.ok).toBe(false)
    if (!built.ok) expect(built.message).toContain(message)
  })

  it('第一次录入默认“完成”、不带版本；修改用现在的结果预填并带 resultVersion', () => {
    expect(initialResultForm(ENTRY)).toEqual({ completion: 'COMPLETED', scoreText: '', certificateNote: '' })
    const recorded: ResultRosterEntry = {
      ...ENTRY,
      result: { completion: 'INCOMPLETE', scoreText: '4/5', certificateNote: null, recordedAt: '2026-11-15T08:00:00Z' },
      resultVersion: 2,
    }
    expect(initialResultForm(recorded)).toEqual({ completion: 'INCOMPLETE', scoreText: '4/5', certificateNote: '' })
    const built = buildResultRequest(initialResultForm(recorded), recorded)
    expect(built).toEqual({
      ok: true,
      request: { completion: 'INCOMPLETE', scoreText: '4/5', certificateNote: null, expectedVersion: 2 },
    })
  })
})
