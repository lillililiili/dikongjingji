import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  buildQuoteRequest,
  buildRecordRequest,
  canCancel,
  canDecline,
  canQuote,
  canRecordHandover,
  canRecordReturn,
  createRentalWorkbenchApi,
  initialQuoteForm,
  quoteTotals,
  reasonError,
  type QuoteForm,
  type RentalInquiry,
  type RentalInquiryStatus,
  type RentalOrderStatus,
} from './rental-api'

const NOW = new Date('2026-11-23T02:00:00Z')

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { api: createRentalWorkbenchApi(http), fetchImpl }
}

function call(fetchImpl: ReturnType<typeof fakeHttp>['fetchImpl'], index = 0) {
  const [url, init] = fetchImpl.mock.calls[index]!
  return {
    url: String(url),
    method: init?.method,
    headers: init?.headers as Record<string, string>,
    body: init?.body ? JSON.parse(String(init.body)) as unknown : undefined,
  }
}

const INQUIRY: RentalInquiry = {
  inquiryId: 'i-1',
  status: 'PENDING',
  closeReason: null,
  declineReason: null,
  lessee: { kind: 'ORGANIZATION', organizationName: '东营示例物流公司', contactName: '王女士' },
  merchant: { merchantId: 'm-1', name: '示例机器人商家', qualificationReviewed: true },
  item: {
    offerId: 'o-1', productId: 'p-1', productName: '巡检机器狗', skuName: '标准版', coverUrl: null,
    referenceAmountMinor: 38000, referenceUnit: 'DAY',
  },
  quantity: 2,
  startDate: '2026-11-28',
  endDate: '2026-11-30',
  days: 3,
  handoverMode: 'DELIVERY',
  returnMode: 'COLLECT',
  place: {
    addressId: null, recipientName: '王女士', phone: '138****1234', provinceName: '山东省', cityCode: '370500',
    cityName: '东营市', detail: '东营区示例路 1 号',
  },
  note: null,
  submittedAt: '2026-11-22T02:00:00Z',
  quoteDeadline: '2026-11-29T02:00:00Z',
  quote: null,
  confirmable: false,
  closable: false,
  order: null,
  updatedAt: '2026-11-22T02:00:00Z',
  version: 0,
}

function form(overrides: Partial<QuoteForm> = {}): QuoteForm {
  return {
    dailyRentYuan: '380',
    days: 3,
    quantity: 2,
    fees: [{ key: 1, name: '运输费', note: '东营市内', amountYuan: '120.5' }],
    scheduleAvailable: true,
    validUntil: new Date(NOW.getTime() + 2 * 86_400_000),
    terms: '押金线下收取',
    ...overrides,
  }
}

describe('租赁接口', () => {
  it('列表按状态筛选、分页；空状态不带参数', async () => {
    const { api, fetchImpl } = fakeHttp()
    await api.inquiries({ status: 'QUOTED' }, 2, 20)
    await api.orders({ status: '' }, 1, 10)
    expect(call(fetchImpl, 0).url).toBe('/api/v1/provider/rental-inquiries?status=QUOTED&page=2&size=20')
    expect(call(fetchImpl, 1).url).toBe('/api/v1/provider/rental-orders?page=1&size=10')
  })

  it('报价是 PUT，拒绝、交接、归还、取消是 POST；都不带幂等键和 If-Match（契约没有）', async () => {
    const { api, fetchImpl } = fakeHttp()
    const built = buildQuoteRequest(form(), NOW)
    if (!built.ok) throw new Error(built.message)
    await api.quote('i/1', built.request)
    await api.decline('i-1', '  档期已满 ')
    await api.recordHandover('r-1', { occurredAt: null, note: '编号 RD-1' })
    await api.recordReturn('r-1', { occurredAt: '2026-11-23T01:00:00.000Z', note: '完好' })
    await api.cancel('r-1', '设备故障')

    expect(call(fetchImpl, 0)).toMatchObject({ url: '/api/v1/provider/rental-inquiries/i%2F1/quote', method: 'PUT' })
    expect(call(fetchImpl, 0).body).toEqual({
      dailyRentMinor: 38000,
      days: 3,
      quantity: 2,
      fees: [{ name: '运输费', note: '东营市内', amountMinor: 12050 }],
      scheduleAvailable: true,
      validUntil: '2026-11-25T02:00:00.000Z',
      terms: '押金线下收取',
    })
    expect(call(fetchImpl, 1)).toMatchObject({
      url: '/api/v1/provider/rental-inquiries/i-1/decline', method: 'POST', body: { reason: '档期已满' },
    })
    expect(call(fetchImpl, 2)).toMatchObject({
      url: '/api/v1/provider/rental-orders/r-1/handover', body: { occurredAt: null, note: '编号 RD-1' },
    })
    expect(call(fetchImpl, 3).url).toBe('/api/v1/provider/rental-orders/r-1/return')
    expect(call(fetchImpl, 4)).toMatchObject({ url: '/api/v1/provider/rental-orders/r-1/cancel', body: { reason: '设备故障' } })
    for (let index = 0; index < 5; index++) {
      expect(call(fetchImpl, index).headers).not.toHaveProperty('Idempotency-Key')
      expect(call(fetchImpl, index).headers).not.toHaveProperty('If-Match')
    }
  })

  it('业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'RENTAL_STATE_INVALID', 'x', 't'))).toContain('状态已经变了')
    expect(errorMessage(new ApiRequestError(409, 'RENTAL_REFUND_NOT_ALLOWED', 'x', 't'))).toContain('不能退款')
  })
})

describe('报价表单', () => {
  it('合计 = 日租金 × 天数 × 台数 + 其他费用（整数分）', () => {
    expect(quoteTotals(form())).toEqual({ rentMinor: 228000, feesMinor: 12050, totalMinor: 240050 })
    expect(quoteTotals(form({ fees: [] })).totalMinor).toBe(228000)
    expect(quoteTotals(form({ dailyRentYuan: '0.1', days: 3, quantity: 1, fees: [] })).totalMinor).toBe(30)
    expect(quoteTotals(form({ dailyRentYuan: 'abc' })).totalMinor).toBeNull()
    expect(quoteTotals(form({ fees: [{ key: 1, name: 'x', note: '', amountYuan: '1.234' }] })).totalMinor).toBeNull()
  })

  it.each([
    [{ dailyRentYuan: '0' }, '日租金'],
    [{ days: 91 }, '天数'],
    [{ quantity: 0 }, '台数'],
    [{ fees: [{ key: 1, name: '租金', note: '', amountYuan: '1' }] }, '不能叫“租金”'],
    [{ fees: [{ key: 1, name: '运费', note: '', amountYuan: '1' }, { key: 2, name: '运费', note: '', amountYuan: '2' }] }, '重复'],
    [{ fees: [{ key: 1, name: '', note: '', amountYuan: '1' }] }, '名称'],
    [{ fees: [{ key: 1, name: '运费', note: '', amountYuan: '-1' }] }, '金额格式不对'],
    [{ fees: Array.from({ length: 6 }, (_, key) => ({ key, name: `费${key}`, note: '', amountYuan: '0' })) }, '最多 5 行'],
    [{ validUntil: null }, '有效期'],
    [{ validUntil: new Date(NOW.getTime() - 1000) }, '晚于现在'],
    [{ validUntil: new Date(NOW.getTime() + 8 * 86_400_000) }, '最长 7 天'],
    [{ terms: '  ' }, '合同条款'],
    [{ terms: '条'.repeat(2001) }, '最多 2000'],
  ] as Array<[Partial<QuoteForm>, string]>)('不合规时给出第一条错误：%o', (overrides, message) => {
    const built = buildQuoteRequest(form(overrides), NOW)
    expect(built.ok).toBe(false)
    if (!built.ok) expect(built.message).toContain(message)
  })

  it('改价用最新一版预填，第一次用询价的天数和台数', () => {
    expect(initialQuoteForm(INQUIRY, NOW)).toMatchObject({ dailyRentYuan: '', days: 3, quantity: 2, fees: [], terms: '' })
    const quoted: RentalInquiry = {
      ...INQUIRY,
      status: 'QUOTED',
      quote: {
        version: 2, dailyRentMinor: 35050, days: 5, quantity: 1, rentMinor: 175250,
        fees: [{ name: '运输费', note: null, amountMinor: 0 }], totalMinor: 175250, scheduleAvailable: false,
        validUntil: '2026-11-24T00:00:00Z', expired: false, terms: '条款', quotedAt: '2026-11-22T00:00:00Z',
      },
    }
    const prefilled = initialQuoteForm(quoted, NOW)
    expect(prefilled).toMatchObject({ dailyRentYuan: '350.50', days: 5, quantity: 1, scheduleAvailable: false, terms: '条款' })
    expect(prefilled.fees[0]).toMatchObject({ name: '运输费', note: '', amountYuan: '0.00' })
  })
})

describe('什么时候能做什么', () => {
  it('询价单：待报价、待确认、已过期能报价和拒绝', () => {
    const allowed: RentalInquiryStatus[] = ['PENDING', 'QUOTED', 'EXPIRED']
    for (const status of ['PENDING', 'QUOTED', 'EXPIRED', 'CONFIRMED', 'DECLINED', 'CLOSED'] as RentalInquiryStatus[]) {
      expect(canQuote({ status }), status).toBe(allowed.includes(status))
      expect(canDecline({ status }), status).toBe(allowed.includes(status))
    }
  })

  it('订单：待交接能录交接、取消；使用中能录归还；客户确认前可以再录', () => {
    const all: RentalOrderStatus[] = ['PENDING_PAYMENT', 'CLOSED', 'AWAITING_HANDOVER', 'HANDOVER_RECORDED', 'IN_USE',
      'RETURN_RECORDED', 'ENDED', 'CANCELLED']
    expect(all.filter((status) => canRecordHandover({ status }))).toEqual(['AWAITING_HANDOVER', 'HANDOVER_RECORDED'])
    expect(all.filter((status) => canCancel({ status }))).toEqual(['AWAITING_HANDOVER', 'HANDOVER_RECORDED'])
    expect(all.filter((status) => canRecordReturn({ status }))).toEqual(['IN_USE', 'RETURN_RECORDED'])
  })

  it('原因 1–80 字；记录说明 1–200 字、时间不晚于现在', () => {
    expect(reasonError(' ')).toBe('请填写原因')
    expect(reasonError('因'.repeat(81))).toContain('80')
    expect(reasonError('档期已满')).toBeNull()
    expect(buildRecordRequest('', null, NOW).ok).toBe(false)
    expect(buildRecordRequest('说'.repeat(201), null, NOW).ok).toBe(false)
    expect(buildRecordRequest('完好', new Date(NOW.getTime() + 60_000), NOW)).toEqual({ ok: false, message: '时间不能晚于现在' })
    expect(buildRecordRequest(' 完好 ', new Date('2026-11-23T01:00:00Z'), NOW))
      .toEqual({ ok: true, request: { occurredAt: '2026-11-23T01:00:00.000Z', note: '完好' } })
  })
})
