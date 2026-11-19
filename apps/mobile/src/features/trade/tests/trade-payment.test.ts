import { describe, expect, it, vi } from 'vitest'

import { checkPaymentUntilSettled, paymentCheckResult } from '../application/payment-check'
import type { PaymentLauncherPort } from '../application/payment-launcher'
import { startPayment } from '../application/start-payment'
import { createWechatPaymentLauncher, toWechatAppOrderInfo } from '../data/wechat-payment-launcher'
import type { PaymentLaunch, TradeOrder } from '../domain/trade-order'
import { paidOrder, tradeOrder } from './trade-fixtures'

const APP_CALL = {
  appId: 'wx8888888888888888',
  partnerId: '1900000100',
  prepayId: 'wx201410272009395522657a690389285100',
  packageValue: 'Sign=WXPay',
  nonceStr: '5K8264ILTKCH16CQ2502SI8ZNMTM67VS',
  timeStamp: '1414561699',
  sign: 'oR9d8PuhnIc+YZ8cBHFCwfgpaK9gd7vaRvkYD7rthRAZ',
}
const LAUNCH: PaymentLaunch = { paymentId: 'p-1', expiresAt: '2026-11-18T02:29:45Z', appCall: APP_CALL }
const failure = (status: number, code: string | null) => Object.assign(new Error('x'), { status, code })

describe('wechat payment launcher', () => {
  it('hands uni.requestPayment the server-signed values under all-lowercase keys', () => {
    expect(toWechatAppOrderInfo(APP_CALL)).toEqual({
      appid: 'wx8888888888888888',
      partnerid: '1900000100',
      prepayid: 'wx201410272009395522657a690389285100',
      package: 'Sign=WXPay',
      noncestr: '5K8264ILTKCH16CQ2502SI8ZNMTM67VS',
      timestamp: 1414561699,
      sign: 'oR9d8PuhnIc+YZ8cBHFCwfgpaK9gd7vaRvkYD7rthRAZ',
    })
  })

  it('is unsupported outside the App or without the payment API, and never calls it there', async () => {
    const requestPayment = vi.fn()
    expect(await createWechatPaymentLauncher({ isApp: false, requestPayment }).launchWechatApp(APP_CALL)).toBe('UNSUPPORTED')
    expect(await createWechatPaymentLauncher({ isApp: true, requestPayment: null }).launchWechatApp(APP_CALL)).toBe('UNSUPPORTED')
    expect(requestPayment).not.toHaveBeenCalled()
  })

  it('reports what the sheet said: paid, cancelled, failed, or thrown', async () => {
    const run = (behave: (options: { success: () => void; fail: (error: { errMsg?: string }) => void }) => void) =>
      createWechatPaymentLauncher({ isApp: true, requestPayment: behave as never }).launchWechatApp(APP_CALL)
    const seen: unknown[] = []
    expect(await run((options) => { seen.push(options); options.success() })).toBe('LAUNCHED')
    expect(seen[0]).toMatchObject({ provider: 'wxpay', orderInfo: { prepayid: APP_CALL.prepayId } })
    expect(await run((options) => options.fail({ errMsg: 'requestPayment:fail cancel' }))).toBe('CANCELLED')
    expect(await run((options) => options.fail({ errMsg: 'requestPayment:fail [payment微信:-1]General errors' }))).toBe('FAILED')
    expect(await run(() => { throw new Error('no module') })).toBe('FAILED')
  })
})

describe('start payment', () => {
  const launcher = (outcome: 'LAUNCHED' | 'UNSUPPORTED'): PaymentLauncherPort => ({
    launchWechatApp: vi.fn(async () => outcome),
  })

  it('uses a new idempotency key on every tap and always goes on to the result page', async () => {
    const keys: string[] = []
    let next = 0
    const launch = vi.fn(async (key: string) => { keys.push(key); return LAUNCH })
    const port = launcher('UNSUPPORTED')
    expect(await startPayment({ launch, launcher: port, createKey: () => `k-${++next}` }))
      .toEqual({ kind: 'TO_RESULT', outcome: 'UNSUPPORTED' })
    expect(await startPayment({ launch, launcher: launcher('LAUNCHED'), createKey: () => `k-${++next}` }))
      .toEqual({ kind: 'TO_RESULT', outcome: 'LAUNCHED' })
    expect(keys).toEqual(['k-1', 'k-2'])
    expect(port.launchWechatApp).toHaveBeenCalledWith(APP_CALL)
  })

  it('stops before the sheet when the server refuses', async () => {
    const run = (error: unknown) => startPayment({
      launch: async () => { throw error },
      launcher: launcher('LAUNCHED'),
    })
    expect(await run(failure(503, 'TRADE_CHANNEL_UNAVAILABLE'))).toEqual({ kind: 'CHANNEL_UNAVAILABLE' })
    expect(await run(failure(409, 'TRADE_ORDER_EXPIRED'))).toEqual({ kind: 'ORDER_CHANGED' })
    expect(await run(failure(409, 'TRADE_ORDER_STATE_INVALID'))).toEqual({ kind: 'ORDER_CHANGED' })
    expect(await run(failure(401, 'COMMON_UNAUTHORIZED'))).toEqual({ kind: 'UNAUTHORIZED' })
    expect(await run(Object.assign(new Error('网络连接失败，请稍后重试'), { status: 0, code: 'NETWORK_ERROR' })))
      .toEqual({ kind: 'FAILED', message: '网络连接失败，请稍后重试' })
  })
})

describe('payment check', () => {
  it('sorts the order the server returns', () => {
    expect(paymentCheckResult(tradeOrder())).toBe('UNPAID')
    expect(paymentCheckResult(paidOrder())).toBe('PAID')
    expect(paymentCheckResult(paidOrder({ status: 'REFUNDED' }))).toBe('PAID')
    expect(paymentCheckResult(tradeOrder({ status: 'CLOSED' }))).toBe('CLOSED')
  })

  it('asks again every 2 seconds while unpaid, at most 5 more times', async () => {
    const check = vi.fn(async () => tradeOrder())
    const wait = vi.fn(async () => undefined)
    const result = await checkPaymentUntilSettled({ check, wait, isCurrent: () => true })
    expect(result?.status).toBe('PENDING_PAYMENT')
    expect(check).toHaveBeenCalledTimes(6)
    expect(wait).toHaveBeenCalledTimes(5)
    expect(wait).toHaveBeenCalledWith(2000)
  })

  it('stops as soon as the payment is seen, or the order is closed', async () => {
    const answers: TradeOrder[] = [tradeOrder(), tradeOrder(), paidOrder()]
    const check = vi.fn(async () => answers.shift() as TradeOrder)
    const result = await checkPaymentUntilSettled({ check, wait: async () => undefined, isCurrent: () => true })
    expect(result?.status).toBe('PAID')
    expect(check).toHaveBeenCalledTimes(3)

    const closed = vi.fn(async () => tradeOrder({ status: 'CLOSED' }))
    await checkPaymentUntilSettled({ check: closed, wait: async () => undefined, isCurrent: () => true })
    expect(closed).toHaveBeenCalledTimes(1)
  })

  it('checks once for 刷新结果 and stops when the page is gone', async () => {
    const once = vi.fn(async () => tradeOrder())
    await checkPaymentUntilSettled({ check: once, wait: async () => undefined, isCurrent: () => true, retries: 0 })
    expect(once).toHaveBeenCalledTimes(1)

    let alive = true
    const check = vi.fn(async () => tradeOrder())
    const result = await checkPaymentUntilSettled({
      check,
      wait: async () => { alive = false },
      isCurrent: () => alive,
    })
    expect(result).toBeNull()
    expect(check).toHaveBeenCalledTimes(1)
  })

  it('passes a server error on to the page', async () => {
    const check = vi.fn(async () => { throw failure(503, 'TRADE_CHANNEL_UNAVAILABLE') })
    await expect(checkPaymentUntilSettled({ check, wait: async () => undefined, isCurrent: () => true }))
      .rejects.toMatchObject({ status: 503 })
  })
})
