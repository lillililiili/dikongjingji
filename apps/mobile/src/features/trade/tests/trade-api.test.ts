import { describe, expect, it, vi } from 'vitest'

import { createTradeApi, toTradeOrder } from '../data/trade-api'
import { tradeOrder } from './trade-fixtures'

function recorder(response: unknown) {
  const request = vi.fn(async (_input: unknown) => response)
  return { request, api: createTradeApi(request as never) }
}

describe('trade api', () => {
  it('submits a buy-now order with the expected amount, the address and a trimmed note', async () => {
    const { request, api } = recorder(tradeOrder())
    await api.submit('token', {
      cityCode: '370500',
      source: { kind: 'OFFER', offerId: 'f-1', quantity: 2 },
      deliveryMode: 'EXPRESS',
      addressId: 'a-1',
      buyerNote: '  工作日送货 ',
      expectedPayableMinor: 259800,
    }, 'key-1')
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/trade/orders',
      method: 'POST',
      data: {
        cityCode: '370500',
        deliveryMode: 'EXPRESS',
        expectedPayableMinor: 259800,
        agreementAccepted: true,
        offerId: 'f-1',
        quantity: 2,
        addressId: 'a-1',
        buyerNote: '工作日送货',
      },
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'key-1' },
    })
  })

  it('sends cart lines, and neither an address nor an empty note for pickup', async () => {
    const { request, api } = recorder(tradeOrder())
    await api.submit('token', {
      cityCode: '370500',
      source: { kind: 'CART', cartItemIds: ['c1', 'c2'] },
      deliveryMode: 'PICKUP',
      addressId: 'a-1',
      buyerNote: '   ',
      expectedPayableMinor: 175900,
    }, 'key-2')
    expect(request.mock.calls[0][0]).toMatchObject({
      data: {
        cityCode: '370500',
        deliveryMode: 'PICKUP',
        expectedPayableMinor: 175900,
        agreementAccepted: true,
        cartItemIds: ['c1', 'c2'],
      },
    })
    const data = (request.mock.calls[0][0] as { data: Record<string, unknown> }).data
    expect(data).not.toHaveProperty('addressId')
    expect(data).not.toHaveProperty('buyerNote')
    expect(data).not.toHaveProperty('offerId')
  })

  it('sends the order version as a quoted If-Match with a fresh key on cancel and receipt', async () => {
    const { request, api } = recorder(tradeOrder())
    await api.cancel('token', 'o/1', { idempotencyKey: 'k-cancel', version: 7 })
    await api.confirmReceipt('token', 'o-1', { idempotencyKey: 'k-receipt', version: 8 })
    expect(request.mock.calls[0][0]).toEqual({
      url: '/api/v1/trade/orders/o%2F1/cancel',
      method: 'POST',
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'k-cancel', 'If-Match': '"7"' },
    })
    expect(request.mock.calls[1][0]).toEqual({
      url: '/api/v1/trade/orders/o-1/receipt',
      method: 'POST',
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'k-receipt', 'If-Match': '"8"' },
    })
  })

  it('lists by tab and page, and asks the server to check the payment', async () => {
    const { request, api } = recorder({ items: [tradeOrder()], hasMore: true, total: 11, page: 1, size: 10 })
    const page = await api.list('token', 'TO_PAY', 2, 10)
    expect(request.mock.calls[0][0]).toMatchObject({ url: '/api/v1/trade/orders?tab=TO_PAY&page=2&size=10', method: 'GET' })
    expect(page).toMatchObject({ hasMore: true, total: 11 })
    expect(page.items[0].orderNo).toBe('MO2026111812345678')

    const check = recorder(tradeOrder())
    await check.api.checkPayment('token', 'o-1')
    expect(check.request.mock.calls[0][0]).toEqual({
      url: '/api/v1/trade/orders/o-1/payment-check',
      method: 'POST',
      headers: { Authorization: 'Bearer token' },
    })
  })

  it('launches a WeChat App payment with the key it is given', async () => {
    const appCall = {
      appId: 'wx1', partnerId: '1900000100', prepayId: 'wx201', packageValue: 'Sign=WXPay',
      nonceStr: 'n1', timeStamp: '1700000000', sign: 's1',
    }
    const { request, api } = recorder({ paymentId: 'p-1', channel: 'WECHAT_APP', expiresAt: '2026-11-18T02:29:45Z', appCall })
    const launched = await api.launchPayment('token', 'o-1', 'k-pay')
    expect(request.mock.calls[0][0]).toEqual({
      url: '/api/v1/trade/orders/o-1/payments',
      method: 'POST',
      data: { channel: 'WECHAT_APP' },
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'k-pay' },
    })
    expect(launched).toEqual({ paymentId: 'p-1', expiresAt: '2026-11-18T02:29:45Z', appCall })
  })

  it('maps missing optional fields to null and leaves out the full phone number', () => {
    const dto = {
      ...tradeOrder(),
      refunds: undefined,
      buyerNote: undefined,
      lines: [{ ...tradeOrder().lines[0], coverImage: undefined }],
      delivery: { mode: 'PICKUP', pickupPoint: '体验馆自提点', pickupCode: '123456', recipientPhone: '13800002026' },
      amounts: { goodsMinor: 100, shippingMinor: 0, payableMinor: 100, currency: 'CNY' },
    }
    const order = toTradeOrder(dto as never)
    expect(order.refunds).toEqual([])
    expect(order.buyerNote).toBeNull()
    expect(order.lines[0].coverImage).toBeNull()
    expect(order.amounts).toEqual({ goodsMinor: 100, shippingMinor: 0, payableMinor: 100, paidMinor: 0, refundedMinor: 0 })
    expect(order.delivery).toEqual({
      mode: 'PICKUP',
      recipientName: null,
      phoneMasked: null,
      addressLine: null,
      pickupPoint: '体验馆自提点',
      pickupCode: '123456',
      carrierName: null,
      trackingNo: null,
      shippedAt: null,
    })
    expect(JSON.stringify(order)).not.toContain('13800002026')
    // An older server sends no close reason.
    expect(order.closeReason).toBeNull()
  })

  it('keeps the close reason of a closed order and only the two the contract names', () => {
    const closed = { ...tradeOrder(), status: 'CLOSED', payBefore: null, closedAt: '2026-11-18T02:30:00Z' }
    expect(toTradeOrder({ ...closed, closeReason: 'BUYER_CANCEL' } as never).closeReason).toBe('BUYER_CANCEL')
    expect(toTradeOrder({ ...closed, closeReason: 'TIMEOUT' } as never).closeReason).toBe('TIMEOUT')
    expect(toTradeOrder({ ...closed, closeReason: null } as never).closeReason).toBeNull()
    expect(toTradeOrder({ ...closed, closeReason: 'SOMETHING_NEW' } as never).closeReason).toBeNull()
    // Only a closed order has one, whatever is sent.
    expect(toTradeOrder({ ...tradeOrder(), closeReason: 'BUYER_CANCEL' } as never).closeReason).toBeNull()
  })
})
