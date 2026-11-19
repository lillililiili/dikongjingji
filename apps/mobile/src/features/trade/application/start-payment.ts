import type { PaymentLaunch } from '../domain/trade-order'
import type { PaymentLaunchOutcome, PaymentLauncherPort } from './payment-launcher'
import { createTradeKey } from './trade-keys'

// What an API failure carries (ApiRequestError from the shared HTTP client), read without
// importing it so this file stays free of uni-app.
export interface TradeFailure {
  status: number
  code: string | null
  message: string
}

export function readTradeFailure(error: unknown): TradeFailure | null {
  if (!error || typeof error !== 'object') return null
  const candidate = error as { status?: unknown; code?: unknown; message?: unknown }
  if (typeof candidate.status !== 'number') return null
  return {
    status: candidate.status,
    code: typeof candidate.code === 'string' ? candidate.code : null,
    message: typeof candidate.message === 'string' ? candidate.message : '',
  }
}

export function isUnauthorizedFailure(failure: TradeFailure | null): boolean {
  return failure !== null && (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED')
}

export type StartPaymentResult =
  // Go to the result page whatever the sheet said; the server decides. `unsupported` means the
  // sheet never opened (H5), so the page says where to pay first.
  | { kind: 'TO_RESULT'; outcome: PaymentLaunchOutcome }
  | { kind: 'CHANNEL_UNAVAILABLE' }
  // Expired, closed or already paid since the page was read: read the order again.
  | { kind: 'ORDER_CHANGED' }
  | { kind: 'UNAUTHORIZED' }
  | { kind: 'FAILED'; message: string }

/**
 * One tap on the pay button. Every tap uses a new idempotency key (contract: a key whose payment was
 * closed cannot be used again); the server reuses the payment in progress and signs fresh parameters.
 */
export async function startPayment(options: {
  launch: (idempotencyKey: string) => Promise<PaymentLaunch>
  launcher: PaymentLauncherPort
  createKey?: () => string
}): Promise<StartPaymentResult> {
  let launched: PaymentLaunch
  try {
    launched = await options.launch((options.createKey ?? createTradeKey)())
  } catch (error) {
    const failure = readTradeFailure(error)
    if (isUnauthorizedFailure(failure)) return { kind: 'UNAUTHORIZED' }
    if (failure?.status === 503 || failure?.code === 'TRADE_CHANNEL_UNAVAILABLE') return { kind: 'CHANNEL_UNAVAILABLE' }
    if (failure?.status === 409 || failure?.status === 404) return { kind: 'ORDER_CHANGED' }
    return { kind: 'FAILED', message: failure?.message || '发起支付失败，请重试' }
  }
  const outcome = await options.launcher.launchWechatApp(launched.appCall)
  return { kind: 'TO_RESULT', outcome }
}
