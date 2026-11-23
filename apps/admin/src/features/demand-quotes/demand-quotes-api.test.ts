import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  budgetLabel,
  buildQuoteRequest,
  canQuote,
  canWithdraw,
  createDemandQuotesApi,
  initialQuoteForm,
  type ProviderDemand,
  type ProviderQuote,
  type QuoteForm,
  type QuoteStatus,
} from './demand-quotes-api'

const NOW = new Date('2026-11-23T02:00:00Z')

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { api: createDemandQuotesApi(http), fetchImpl }
}

const DEMAND: ProviderDemand = {
  demandId: 'd-1',
  status: 'OPEN',
  type: 'INSPECTION',
  title: '园区光伏板巡检',
  cityCode: '370500',
  cityName: '东营市',
  address: '东营区示例园区',
  scope: '约 2 万平方米屋顶光伏',
  dueDate: '2026-12-10',
  budgetMinMinor: 1000000,
  budgetMaxMinor: 1500000,
  deliverables: [{ code: 'INSPECTION_REPORT', label: '巡检报告' }, { code: 'HAZARD_LIST', label: '隐患清单' }],
  note: null,
  invited: true,
  submittedAt: '2026-11-22T02:00:00Z',
  quoteDeadline: '2026-11-25T02:00:00Z',
  quotingOpen: true,
  myQuote: null,
}

function quote(status: QuoteStatus): ProviderQuote {
  return {
    quoteId: 'q-1', version: 2, status, amountMinor: 1280050, method: '无人机双光', schedule: '3 天内进场',
    durationDays: 5, deliverables: '巡检报告', validUntil: '2026-11-30T00:00:00Z', submittedAt: '2026-11-22T03:00:00Z',
    revisedAt: '2026-11-22T04:00:00Z', lines: [], team: null, nightWork: null, reportHours: null, archiveDays: null,
    firstVisitDate: null,
  }
}

function form(overrides: Partial<QuoteForm> = {}): QuoteForm {
  return {
    amountYuan: '12800.5',
    method: ' 无人机双光 ',
    schedule: '3 天内进场',
    durationDays: 5,
    deliverables: '巡检报告、隐患清单',
    validUntil: new Date(NOW.getTime() + 7 * 86_400_000),
    lines: [],
    team: '',
    nightWork: '',
    reportHours: null,
    archiveDays: null,
    firstVisitDate: null,
    ...overrides,
  }
}

describe('需求报价接口', () => {
  it('列表按状态筛选；报价 PUT、撤回 DELETE，都不带幂等键和 If-Match', async () => {
    const { api, fetchImpl } = fakeHttp()
    await api.list({ status: 'QUOTED' }, 1, 20)
    await api.list({ status: '' }, 3, 10)
    await api.detail('d-1')
    const built = buildQuoteRequest(form(), NOW)
    if (!built.ok) throw new Error(built.message)
    await api.quote('d-1', built.request)
    await api.withdraw('d-1')

    const calls = fetchImpl.mock.calls.map(([url, init]) => ({ url: String(url), init: init! }))
    expect(calls.map((item) => `${item.init.method} ${item.url}`)).toEqual([
      'GET /api/v1/provider/demands?status=QUOTED&page=1&size=20',
      'GET /api/v1/provider/demands?page=3&size=10',
      'GET /api/v1/provider/demands/d-1',
      'PUT /api/v1/provider/demands/d-1/quote',
      'DELETE /api/v1/provider/demands/d-1/quote',
    ])
    expect(JSON.parse(String(calls[3]!.init.body))).toEqual({
      amountMinor: 1280050,
      method: '无人机双光',
      schedule: '3 天内进场',
      durationDays: 5,
      deliverables: '巡检报告、隐患清单',
      validUntil: '2026-11-30T02:00:00.000Z',
      lines: [],
      team: null,
      nightWork: null,
      reportHours: null,
      archiveDays: null,
      firstVisitDate: null,
    })
    for (const item of calls) {
      expect(item.init.headers).not.toHaveProperty('Idempotency-Key')
      expect(item.init.headers).not.toHaveProperty('If-Match')
    }
  })

  it('业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'DEMAND_QUOTE_LOCKED', 'x', 't'))).toContain('不能再改')
    expect(errorMessage(new ApiRequestError(409, 'DEMAND_QUOTING_CLOSED', 'x', 't'))).toContain('已截止')
  })
})

describe('报价表单', () => {
  it.each([
    [{ amountYuan: '0' }, '大于 0'],
    [{ amountYuan: '12.345' }, '大于 0'],
    [{ amountYuan: '100000001' }, '上限'],
    [{ method: '' }, '请填写做法'],
    [{ method: '法'.repeat(41) }, '做法最多 40'],
    [{ schedule: '第一周\n第二周' }, '换行'],
    [{ deliverables: '交'.repeat(121) }, '120'],
    [{ durationDays: 0 }, '工期'],
    [{ durationDays: 3651 }, '工期'],
    [{ validUntil: new Date(NOW.getTime()) }, '晚于现在'],
    [{ validUntil: new Date(NOW.getTime() + 31 * 86_400_000) }, '最长 30 天'],
  ] as Array<[Partial<QuoteForm>, string]>)('不合规时给出第一条错误：%o', (overrides, message) => {
    const built = buildQuoteRequest(form(overrides), NOW)
    expect(built.ok).toBe(false)
    if (!built.ok) expect(built.message).toContain(message)
  })

  it('第一次报价交付物按需求预填；改价用现在的报价；撤回过的不预填', () => {
    expect(initialQuoteForm(DEMAND, NOW)).toMatchObject({ amountYuan: '', deliverables: '巡检报告、隐患清单' })
    expect(initialQuoteForm({ ...DEMAND, myQuote: quote('ACTIVE') }, NOW))
      .toMatchObject({ amountYuan: '12800.50', method: '无人机双光', durationDays: 5 })
    expect(initialQuoteForm({ ...DEMAND, myQuote: quote('WITHDRAWN') }, NOW).amountYuan).toBe('')
  })
})

describe('按钮规则', () => {
  it('报价期开着、报价没锁定才能报价改价；有效或过期的报价能撤回', () => {
    expect(canQuote(DEMAND)).toBe(true)
    expect(canQuote({ ...DEMAND, quotingOpen: false })).toBe(false)
    for (const status of ['ACTIVE', 'EXPIRED', 'WITHDRAWN'] as QuoteStatus[]) {
      expect(canQuote({ ...DEMAND, myQuote: quote(status) }), status).toBe(true)
    }
    for (const status of ['CHOSEN', 'NOT_CHOSEN', 'CLOSED'] as QuoteStatus[]) {
      expect(canQuote({ ...DEMAND, myQuote: quote(status) }), status).toBe(false)
    }
    expect(canWithdraw(DEMAND)).toBe(false)
    expect(['ACTIVE', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'NOT_CHOSEN', 'CLOSED']
      .filter((status) => canWithdraw({ myQuote: quote(status as QuoteStatus) }))).toEqual(['ACTIVE', 'EXPIRED'])
  })

  it('预算区间', () => {
    expect(budgetLabel(DEMAND)).toBe('¥10,000.00–¥15,000.00')
    expect(budgetLabel({ budgetMinMinor: null, budgetMaxMinor: null })).toBe('未填预算')
    expect(budgetLabel({ budgetMinMinor: 50000, budgetMaxMinor: null })).toBe('¥500.00 起')
    expect(budgetLabel({ budgetMinMinor: null, budgetMaxMinor: 50000 })).toBe('¥500.00 以内')
  })
})
