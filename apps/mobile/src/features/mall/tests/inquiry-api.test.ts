import { describe, expect, it } from 'vitest'

import { createCartApi } from '../data/cart-api'
import { createMallInquiryApi, toMallInquiry, toMallInquiryQuote } from '../data/inquiry-api'
import { createTradeApi } from '../../trade/data/trade-api'
import { submitSignature } from '../../trade/application/trade-keys'

type Call = { url: string; method: string; data?: unknown; headers?: Record<string, string> }

function recorder(responses: unknown[]) {
  const calls: Call[] = []
  const request = async <T>(input: Call): Promise<T> => {
    calls.push(input)
    return responses.shift() as T
  }
  return { calls, request }
}

describe('mall inquiry api', () => {
  it('reads the merchants that can be asked, keeping the ones without online payment greyed', async () => {
    const { calls, request } = recorder([{ items: [
      { offerId: 'o-1', merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' }, deliveryNote: null, canInquire: true },
      { offerId: 'o-2', merchant: { organizationId: 'm-4', name: '示例无人机厂家', kind: 'FACTORY_DIRECT' }, deliveryNote: '按配置报价', canInquire: false },
    ] }])
    const offers = await createMallInquiryApi(request).listOffers('tok', 'p 1', '370500')
    expect(calls[0]).toEqual({
      url: '/api/v1/mall/inquiry-offers?productId=p%201&cityCode=370500',
      method: 'GET',
      headers: { Authorization: 'Bearer tok' },
    })
    expect(offers.map((offer) => [offer.offerId, offer.canInquire, offer.deliveryNote])).toEqual([
      ['o-1', true, null],
      ['o-2', false, '按配置报价'],
    ])
  })

  it('submits with an idempotency key, a trimmed requirement and a null date when none is given', async () => {
    const { calls, request } = recorder([{ inquiryId: 'i-1', status: 'OPEN', version: 0 }])
    const inquiry = await createMallInquiryApi(request).submit('tok', 'key-1', {
      productId: 'p-1', requirement: '  测绘版\n含培训  ', quantity: 2, cityCode: '370500', expectedDate: null, offerIds: ['o-1', 'o-2'],
    })
    expect(inquiry.status).toBe('OPEN')
    expect(calls[0]).toEqual({
      url: '/api/v1/mall/inquiries',
      method: 'POST',
      data: { productId: 'p-1', requirement: '测绘版\n含培训', quantity: 2, cityCode: '370500', expectedDate: null, offerIds: ['o-1', 'o-2'] },
      headers: { Authorization: 'Bearer tok', 'Idempotency-Key': 'key-1' },
    })
  })

  it('reads with a sort, chooses with If-Match and the quote version, lists by status and closes', async () => {
    const inquiry = { inquiryId: 'i-1', status: 'CHOSEN', version: 4 }
    const { calls, request } = recorder([
      { inquiry: { inquiryId: 'i-1', status: 'QUOTED', version: 3 }, sort: 'PRICE', quotes: [{ quoteId: 'q-1', status: 'ACTIVE', choosable: true }] },
      inquiry,
      { total: 0, hasMore: false, items: [] },
      { inquiryId: 'i-1', status: 'CLOSED', closeReason: 'CUSTOMER', version: 5 },
    ])
    const api = createMallInquiryApi(request)
    const detail = await api.read('tok', 'i 1', 'PRICE')
    expect(detail.sort).toBe('PRICE')
    expect(detail.quotes[0]).toMatchObject({ quoteId: 'q-1', status: 'ACTIVE', choosable: true, includes: null })
    expect((await api.choose('tok', 'i 1', 3, 'q-1', 2)).status).toBe('CHOSEN')
    await api.list('tok', 'QUOTED', 2, 20)
    expect((await api.close('tok', 'i-1')).closeReason).toBe('CUSTOMER')
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/v1/mall/inquiries/i%201?sort=PRICE',
      'POST /api/v1/mall/inquiries/i%201/choice',
      'GET /api/v1/mall/inquiries?status=QUOTED&page=2&size=20',
      'POST /api/v1/mall/inquiries/i-1/close',
    ])
    expect(calls[1]).toMatchObject({ data: { quoteId: 'q-1', quoteVersion: 2 }, headers: { Authorization: 'Bearer tok', 'If-Match': '"3"' } })
  })

  it('reads who the inquiry is recorded under from the account', async () => {
    const { request } = recorder([
      { displayName: '王女士', organizationName: '东营示例物流公司', organizationType: 'CUSTOMER' },
      { displayName: '李先生', organizationName: null, organizationType: null },
    ])
    const api = createMallInquiryApi(request)
    expect(await api.readBuyer('tok')).toEqual({ kind: 'ORGANIZATION', organizationName: '东营示例物流公司', contactName: '王女士' })
    expect(await api.readBuyer('tok')).toEqual({ kind: 'PERSONAL', organizationName: null, contactName: '李先生' })
  })

  it('maps missing fields to safe values and unknown statuses to closed', () => {
    const inquiry = toMallInquiry({
      inquiryId: 'i-1',
      status: 'SOMETHING',
      version: 1,
      merchants: [{ offerId: 'o-1', merchant: { name: '甲', kind: 'NEW_KIND' as never }, status: 'DECLINED', declineReason: '缺货' }],
      chosen: { quoteId: 'q-1', quoteVersion: 2, totalMinor: 100, orderId: null },
    })
    expect(inquiry.status).toBe('CLOSED')
    expect(inquiry.merchants[0]).toMatchObject({ status: 'DECLINED', declineReason: '缺货', merchant: { kind: 'LOCAL_STORE' } })
    expect(inquiry.chosen).toMatchObject({ quoteId: 'q-1', quoteVersion: 2, orderId: null })
    expect(toMallInquiryQuote({ quoteId: 'q', status: 'WHATEVER' as never }).status).toBe('LAPSED')
  })
})

describe('ordering a chosen quote (checkout quote mode)', () => {
  it('previews by the quote alone: no quantity, no cart lines', async () => {
    const { calls, request } = recorder([{
      merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
      lines: [], pieceCount: 2, deliveryOptions: [], amounts: { goodsMinor: 1, shippingMinor: 0, payableMinor: 1, currency: 'CNY' },
    }])
    await createCartApi(request).preview('tok', {
      cityCode: '370500', source: { kind: 'QUOTE', inquiryQuoteId: 'q 1' }, deliveryMode: 'EXPRESS', addressId: 'a-1',
    })
    expect(calls[0].url).toBe('/api/v1/mall/checkout-preview?cityCode=370500&inquiryQuoteId=q%201&deliveryMode=EXPRESS&addressId=a-1')
  })

  it('submits the quote as inquiryQuoteId and keys it by the quote', async () => {
    const { calls, request } = recorder([{ orderId: 't-1', lines: [], merchant: { organizationId: 'm', name: 'x' }, amounts: { goodsMinor: 1, shippingMinor: 0, payableMinor: 1, refundedMinor: 0 } }])
    const input = {
      cityCode: '370500',
      source: { kind: 'QUOTE' as const, inquiryQuoteId: 'q-1' },
      deliveryMode: 'EXPRESS' as const,
      addressId: 'a-1',
      buyerNote: '',
      expectedPayableMinor: 13_600_000,
    }
    await createTradeApi(request).submit('tok', input, 'key-1')
    expect(calls[0].data).toEqual({
      cityCode: '370500',
      deliveryMode: 'EXPRESS',
      expectedPayableMinor: 13_600_000,
      agreementAccepted: true,
      inquiryQuoteId: 'q-1',
      addressId: 'a-1',
    })
    expect(submitSignature(input)).toContain('quote:q-1')
    expect(submitSignature(input)).not.toBe(submitSignature({ ...input, source: { kind: 'QUOTE', inquiryQuoteId: 'q-2' } }))
  })
})
