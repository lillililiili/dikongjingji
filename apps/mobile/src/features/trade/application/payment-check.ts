import { hasBeenPaid, type TradeOrder } from '../domain/trade-order'

export type PaymentCheckResult = 'PAID' | 'UNPAID' | 'CLOSED'

// After WeChat the order is still unpaid until WeChat's notification arrives or the server's query
// sees the payment, which can take a few seconds. The result page asks again this often.
export const PAYMENT_CHECK_INTERVAL_MS = 2000
export const PAYMENT_CHECK_RETRIES = 5

export function paymentCheckResult(order: TradeOrder): PaymentCheckResult {
  if (hasBeenPaid(order)) return 'PAID'
  return order.status === 'CLOSED' ? 'CLOSED' : 'UNPAID'
}

/**
 * Asks the server to query WeChat once, then again every interval while the order is still unpaid,
 * at most `retries` more times. Stops early when the page is gone (`isCurrent` false); an error
 * from the server ends the round and reaches the caller.
 */
export async function checkPaymentUntilSettled(options: {
  check: () => Promise<TradeOrder>
  wait: (ms: number) => Promise<void>
  isCurrent: () => boolean
  retries?: number
  intervalMs?: number
}): Promise<TradeOrder | null> {
  const retries = options.retries ?? PAYMENT_CHECK_RETRIES
  const interval = options.intervalMs ?? PAYMENT_CHECK_INTERVAL_MS
  let order = await options.check()
  for (let attempt = 0; attempt < retries; attempt += 1) {
    if (!options.isCurrent()) return null
    if (paymentCheckResult(order) !== 'UNPAID') return order
    await options.wait(interval)
    if (!options.isCurrent()) return null
    order = await options.check()
  }
  return options.isCurrent() ? order : null
}
