import type { TradeOrder } from '../domain/trade-order'
import { TRADE_COMMAND_CONFIRMS } from './order-commands'
import { cashierGoodsLine, formatCountdown, formatTradeAmount, paymentRemainingMs } from './order-view'

// What the cashier and the result page say differently for a business order (contract 0.2.0).
// A paid activity enrolment has no goods page and ships nothing: its page is the enrolment's ticket,
// and the order line is the activity (productName), the session (skuName) and the number of people.

export type TradeOrderDestination =
  | { kind: 'GOODS_ORDER'; orderId: string }
  | { kind: 'ACTIVITY_ENROLLMENT'; enrollmentId: string }

export function isEnrollmentOrder(order: TradeOrder): boolean {
  return order.source === 'ACTIVITY'
}

// Where "查看订单" leads: the goods order, or the ticket of the enrolment the order pays for.
export function orderDestination(order: TradeOrder): TradeOrderDestination {
  if (order.source === 'ACTIVITY' && order.sourceRef) {
    return { kind: 'ACTIVITY_ENROLLMENT', enrollmentId: order.sourceRef }
  }
  return { kind: 'GOODS_ORDER', orderId: order.orderId }
}

export interface CashierRow {
  key: string
  label: string
  value: string
}

// The rows under 订单号 / 收款方 on the cashier (board Cashier for goods, CashierEnroll for a sign-up).
export function cashierRows(order: TradeOrder): CashierRow[] {
  const line = order.lines[0]
  if (!isEnrollmentOrder(order) || !line) {
    return [{ key: 'goods', label: '商品', value: cashierGoodsLine(order) }]
  }
  return [
    { key: 'activity', label: '活动', value: line.productName },
    { key: 'session', label: '场次', value: line.skuName },
    { key: 'people', label: '人数', value: `${line.quantity} 人` },
  ]
}

// "报名费 2 人 × ¥120" under the amount; nothing for goods.
export function cashierDueNote(order: TradeOrder): string | null {
  const line = order.lines[0]
  if (!isEnrollmentOrder(order) || !line) return null
  return `报名费 ${line.quantity} 人 × ${formatTradeAmount(line.unitAmountMinor)}`
}

export function cashierCountdownLine(order: TradeOrder, now: number): string {
  const countdown = formatCountdown(paymentRemainingMs(order, now))
  return isEnrollmentOrder(order)
    ? `名额保留 ${countdown} · 超时自动释放`
    : `剩余支付时间 ${countdown} · 超时订单自动取消`
}

// The grey line of the closed frame.
export function cashierClosedNote(order: TradeOrder): string {
  return isEnrollmentOrder(order) ? '没有扣款，名额已释放' : '没有扣款，可以重新下单'
}

export function cancelUnpaidConfirm(order: TradeOrder): {
  title: string
  content: string
  confirmText: string
  cancelText: string
} {
  return isEnrollmentOrder(order)
    ? { title: '取消报名', content: '取消后报名关闭、名额释放，确定取消吗？', confirmText: '取消报名', cancelText: '再想想' }
    : TRADE_COMMAND_CONFIRMS.CANCEL
}

// The button that leaves for the order's own page.
export function orderLinkLabel(order: TradeOrder): string {
  if (!isEnrollmentOrder(order)) return '查看订单'
  return order.status === 'PENDING_PAYMENT' || order.status === 'CLOSED' ? '查看报名' : '查看凭证'
}

// The paid frame of the result page: "已支付", or "报名成功" with how many places it holds.
export function paidFrame(order: TradeOrder): { title: string; note: string | null } {
  const line = order.lines[0]
  if (!isEnrollmentOrder(order) || !line) return { title: '已支付', note: null }
  return { title: '报名成功', note: `${line.quantity} 个名额已确认 · 一人一码` }
}
