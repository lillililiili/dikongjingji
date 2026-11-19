import { isUnauthorizedFailure, readTradeFailure } from './start-payment'

export type OrderCommandFailure =
  | { kind: 'UNAUTHORIZED' }
  // 412 or 409: the order changed since the page read it. Read it again and say so.
  | { kind: 'ORDER_CHANGED'; message: string }
  | { kind: 'FAILED'; message: string }

export const TRADE_CHANNEL_UNAVAILABLE_TEXT = '微信支付暂不可用，请稍后再试'

export function classifyOrderCommandFailure(error: unknown): OrderCommandFailure {
  const failure = readTradeFailure(error)
  if (isUnauthorizedFailure(failure)) return { kind: 'UNAUTHORIZED' }
  if (failure?.status === 412 || failure?.code === 'TRADE_VERSION_CONFLICT') {
    return { kind: 'ORDER_CHANGED', message: '订单已更新，请查看最新状态' }
  }
  if (failure?.status === 409) return { kind: 'ORDER_CHANGED', message: '订单状态已变化，请查看最新状态' }
  if (failure?.status === 503 || failure?.code === 'TRADE_CHANNEL_UNAVAILABLE') {
    return { kind: 'FAILED', message: TRADE_CHANNEL_UNAVAILABLE_TEXT }
  }
  return { kind: 'FAILED', message: failure?.message || '操作失败，请重试' }
}

// The confirm dialogs. Cancelling a paid order refunds it, so it says where the money goes.
export const TRADE_COMMAND_CONFIRMS = {
  CANCEL: { title: '取消订单', content: '取消后订单将关闭，确定取消吗？', confirmText: '取消订单', cancelText: '再想想' },
  CANCEL_AND_REFUND: {
    title: '取消订单',
    content: '订单已付款，取消后货款将原路退回，确定取消吗？',
    confirmText: '取消并退款',
    cancelText: '再想想',
  },
  CONFIRM_RECEIPT: { title: '确认收货', content: '请确认已经收到商品，确认后交易完成。', confirmText: '确认收货', cancelText: '还没收到' },
} as const
