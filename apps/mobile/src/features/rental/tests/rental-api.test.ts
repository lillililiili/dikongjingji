import { describe, expect, it } from 'vitest'

import { createRentalApi, toOfferView, toRentalInquiry, toRentalOrder } from '../data/rental-api'

type Call = { url: string; method: string; data?: unknown; headers?: Record<string, string> }

function recorder(responses: unknown[]) {
  const calls: Call[] = []
  const request = async <T>(input: Call): Promise<T> => {
    calls.push(input)
    return responses.shift() as T
  }
  return { calls, api: createRentalApi(request) }
}

describe('rental api', () => {
  it('sends an inquiry with its idempotency key and a null note when empty', async () => {
    const { calls, api } = recorder([{ inquiryId: 'i-1', status: 'PENDING', version: 0 }])
    await api.submitInquiry('tok', 'key-1', {
      offerId: 'o-1', quantity: 2, startDate: '2026-11-22', days: 5,
      handoverMode: 'PICKUP', returnMode: 'DROP_OFF', addressId: 'a-1', note: '  ',
    })
    expect(calls[0]).toEqual({
      url: '/api/v1/rental-inquiries',
      method: 'POST',
      data: {
        offerId: 'o-1', quantity: 2, startDate: '2026-11-22', days: 5,
        handoverMode: 'PICKUP', returnMode: 'DROP_OFF', addressId: 'a-1', note: null,
      },
      headers: { Authorization: 'Bearer tok', 'Idempotency-Key': 'key-1' },
    })
  })

  it('confirms with the inquiry version as If-Match and the quote version in the body', async () => {
    const { calls, api } = recorder([{ inquiryId: 'i-1', rentalOrderId: 'r-1', tradeOrderId: 't-1', payBefore: 'x' }])
    const result = await api.confirmContract('tok', 'i 1', 3, 2)
    expect(result).toEqual({ inquiryId: 'i-1', rentalOrderId: 'r-1', tradeOrderId: 't-1', payBefore: 'x' })
    expect(calls[0]).toMatchObject({
      url: '/api/v1/rental-inquiries/i%201/confirm',
      method: 'POST',
      data: { quoteVersion: 2, agreed: true },
      headers: { Authorization: 'Bearer tok', 'If-Match': '"3"' },
    })
  })

  it('filters lists by status and reads the record commands', async () => {
    const page = { total: 0, hasMore: false, items: [] }
    const { calls, api } = recorder([page, page, { rentalOrderId: 'r-1', status: 'IN_USE', version: 1 }, { rentalOrderId: 'r-1', status: 'ENDED', version: 2 }])
    await api.listInquiries('tok', 'QUOTED', 2, 20)
    await api.listOrders('tok', null, 1, 20)
    expect((await api.confirmHandover('tok', 'r-1')).status).toBe('IN_USE')
    expect((await api.confirmReturn('tok', 'r-1')).status).toBe('ENDED')
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/v1/rental-inquiries?status=QUOTED&page=2&size=20',
      'GET /api/v1/rental-orders?page=1&size=20',
      'POST /api/v1/rental-orders/r-1/handover-confirmation',
      'POST /api/v1/rental-orders/r-1/return-confirmation',
    ])
  })

  it('reads the lessee as the server will record it', async () => {
    const { api } = recorder([
      { displayName: '王女士', organizationName: '东营港务物流', organizationType: 'CUSTOMER' },
      { displayName: '李先生', organizationName: '某服务商', organizationType: 'PROVIDER' },
    ])
    expect(await api.readLessee('tok')).toEqual({ kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' })
    expect(await api.readLessee('tok')).toEqual({ kind: 'PERSONAL', organizationName: null, contactName: '李先生' })
  })

  it('reads only a rental offer of the product', () => {
    const product = {
      productId: 'p-1',
      name: '巡检机器狗',
      saleMode: 'RENT',
      coverImage: null,
      offers: [
        { offerId: 'o-1', skuName: '标准版', merchant: { name: '示例机器人商家' }, amountMinor: 38_000, unit: 'DAY', fulfilledFrom: '东营仓', onlinePayment: true },
        { offerId: 'o-3', skuName: '标准版', merchant: { name: '示例设备租赁商' }, amountMinor: 36_000, unit: 'DAY', fulfilledFrom: null, onlinePayment: false },
      ],
    }
    expect(toOfferView(product, 'o-1')).toEqual({
      offerId: 'o-1', productId: 'p-1', productName: '巡检机器狗', skuName: '标准版', coverUrl: null,
      merchantName: '示例机器人商家', amountMinor: 38_000, unit: 'DAY', fulfilledFrom: '东营仓', onlinePayment: true,
    })
    // catalog 1.2.0: a lessor without online payment is known before the form is sent.
    expect(toOfferView(product, 'o-3')?.onlinePayment).toBe(false)
    expect(toOfferView(product, 'o-2')).toBeNull()
    expect(toOfferView({ ...product, saleMode: 'BUY' }, 'o-1')).toBeNull()
  })

  it('fills what an older or partial answer leaves out', () => {
    const inquiry = toRentalInquiry({ inquiryId: 'i-1', status: 'QUOTED', version: 4, quote: { version: 2, totalMinor: 100, fees: null } })
    expect(inquiry).toMatchObject({
      status: 'QUOTED',
      lessee: { kind: 'PERSONAL', organizationName: null },
      handoverMode: 'DELIVERY',
      returnMode: 'COLLECT',
      quote: { version: 2, totalMinor: 100, fees: [], scheduleAvailable: false, expired: false },
      order: null,
      confirmable: false,
    })
    const order = toRentalOrder({ rentalOrderId: 'r-1', status: 'UNKNOWN' as never, version: 0, handoverRecord: { note: 'x' }, refund: null })
    expect(order.status).toBe('CLOSED')
    // A record without its recording time is not a record.
    expect(order.handoverRecord).toBeNull()
    expect(order.refund).toBeNull()
  })
})
