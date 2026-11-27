import { describe, expect, it } from 'vitest'

import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import { classifyOrderCommandFailure } from '../application/order-commands'
import { classifySubmitFailure } from '../application/submit-order'
import { createSubmitKeyHolder, createTradeKey } from '../application/trade-keys'
import tradeFlagSource from '../application/trade-preview.ts?raw'
import type { SubmitOrderInput } from '../domain/trade-order'

const failure = (status: number, code: string | null, message = '服务端说明') =>
  Object.assign(new Error(message), { status, code })

const INPUT: SubmitOrderInput = {
  cityCode: '370500',
  source: { kind: 'CART', cartItemIds: ['c2', 'c1'] },
  deliveryMode: 'EXPRESS',
  addressId: 'a-1',
  buyerNote: '工作日送货',
  expectedPayableMinor: 175900,
}

describe('submit idempotency key', () => {
  it('reuses the key for the same content, so a retry after a network failure makes no second order', () => {
    let next = 0
    const holder = createSubmitKeyHolder(() => `key-${++next}`)
    expect(holder.keyFor(INPUT)).toBe('key-1')
    expect(holder.keyFor({ ...INPUT })).toBe('key-1')
    // The order of cart lines and the spaces around the note do not make another order.
    expect(holder.keyFor({ ...INPUT, source: { kind: 'CART', cartItemIds: ['c1', 'c2'] }, buyerNote: ' 工作日送货 ' })).toBe('key-1')
  })

  it('takes a new key when the delivery, the address, the note or the amount changes', () => {
    let next = 0
    const holder = createSubmitKeyHolder(() => `key-${++next}`)
    const keys = [
      holder.keyFor(INPUT),
      holder.keyFor({ ...INPUT, deliveryMode: 'PICKUP', addressId: null }),
      holder.keyFor(INPUT),
      holder.keyFor({ ...INPUT, addressId: 'a-2' }),
      holder.keyFor({ ...INPUT, addressId: 'a-2', buyerNote: '放门卫' }),
      holder.keyFor({ ...INPUT, addressId: 'a-2', buyerNote: '放门卫', expectedPayableMinor: 180900 }),
    ]
    expect(keys).toEqual(['key-1', 'key-2', 'key-3', 'key-4', 'key-5', 'key-6'])
  })

  it('ignores the address on pickup orders', () => {
    let next = 0
    const holder = createSubmitKeyHolder(() => `key-${++next}`)
    const pickup = { ...INPUT, deliveryMode: 'PICKUP' as const }
    expect(holder.keyFor({ ...pickup, addressId: 'a-1' })).toBe(holder.keyFor({ ...pickup, addressId: null }))
  })

  it('makes version 4 UUIDs', () => {
    const key = createTradeKey()
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(createTradeKey()).not.toBe(key)
  })
})

describe('submit failures', () => {
  it('sorts the trade and mall refusals into what the confirm page does next', () => {
    expect(classifySubmitFailure(failure(409, 'TRADE_AMOUNT_CHANGED')))
      .toEqual({ kind: 'AMOUNT_CHANGED', message: '商品价格有变化，请确认金额后再提交' })
    expect(classifySubmitFailure(failure(409, 'TRADE_MERCHANT_PAYMENT_UNAVAILABLE')))
      .toEqual({ kind: 'MERCHANT_PAYMENT_UNAVAILABLE', message: '该商家暂未开通在线支付' })
    expect(classifySubmitFailure(failure(409, 'TRADE_ADDRESS_REQUIRED')).kind).toBe('DELIVERY_CHANGED')
    expect(classifySubmitFailure(failure(409, 'TRADE_DELIVERY_UNAVAILABLE')).kind).toBe('DELIVERY_CHANGED')
    expect(classifySubmitFailure(failure(409, 'MALL_CHECKOUT_ITEM_UNAVAILABLE', '有商品已失效')))
      .toEqual({ kind: 'STOCK_CHANGED', message: '商品可售数量或在售状态已变化，请重新核对商品与购买数量。' })
    expect(classifySubmitFailure(failure(409, 'MALL_CHECKOUT_MULTIPLE_MERCHANTS')).kind).toBe('GOODS_CHANGED')
    expect(classifySubmitFailure(failure(409, 'MALL_CART_NOT_PURCHASABLE')).kind).toBe('GOODS_CHANGED')
    expect(classifySubmitFailure(failure(401, 'COMMON_UNAUTHORIZED'))).toEqual({ kind: 'UNAUTHORIZED' })
    expect(classifySubmitFailure(failure(0, 'NETWORK_ERROR', '网络连接失败，请稍后重试')))
      .toEqual({ kind: 'RETRYABLE', message: '网络连接失败，请稍后重试' })
    expect(classifySubmitFailure(failure(500, 'COMMON_INTERNAL')).kind).toBe('RETRYABLE')
    expect(classifySubmitFailure(new Error('boom')).kind).toBe('RETRYABLE')
    expect(classifySubmitFailure(failure(400, 'COMMON_INVALID_ARGUMENT', '参数无效')))
      .toEqual({ kind: 'FAILED', message: '参数无效' })
  })
})

describe('order command failures', () => {
  it('reads the order again after 412 or 409 and names WeChat when the channel is down', () => {
    expect(classifyOrderCommandFailure(failure(412, 'TRADE_VERSION_CONFLICT')))
      .toEqual({ kind: 'ORDER_CHANGED', message: '订单已更新，请查看最新状态' })
    expect(classifyOrderCommandFailure(failure(409, 'TRADE_ORDER_STATE_INVALID')).kind).toBe('ORDER_CHANGED')
    expect(classifyOrderCommandFailure(failure(503, 'TRADE_CHANNEL_UNAVAILABLE')))
      .toEqual({ kind: 'FAILED', message: '微信支付暂不可用，请稍后再试' })
    expect(classifyOrderCommandFailure(failure(401, null))).toEqual({ kind: 'UNAUTHORIZED' })
    expect(classifyOrderCommandFailure(failure(0, 'NETWORK_ERROR', '网络连接失败')).kind).toBe('FAILED')
  })
})

describe('trade preview flag', () => {
  it('reads its own release switch', () => {
    const expression = (source: string, name: string) =>
      new RegExp(`export const ${name} = (.+)`).exec(source)?.[1].trim()
    expect(expression(tradeFlagSource, 'TRADE_PREVIEW_ENABLED'))
      .toBe(moduleFlagExpression('TRADE'))
  })
})
