import type { TradeOrder } from '../domain/trade-order'
import { TRADE_COMMAND_CONFIRMS } from './order-commands'
import { cashierGoodsLine, formatCountdown, formatTradeAmount, paymentRemainingMs } from './order-view'

// What the cashier and the result page say differently for a business order (contract 0.2.0).
// A paid activity enrolment or a venue booking (E10) has no goods page and ships nothing: its page
// is the enrolment's ticket or the booking's code. The order line is the business item: for an
// enrolment the activity (productName) and the session (skuName); for a venue booking "venue
// project" (productName) and the session time (skuName); quantity is the number of people.

export type TradeOrderDestination =
  | { kind: 'GOODS_ORDER'; orderId: string }
  | { kind: 'ACTIVITY_ENROLLMENT'; enrollmentId: string }
  | { kind: 'VENUE_BOOKING'; bookingId: string }

export function isEnrollmentOrder(order: TradeOrder): boolean {
  return order.source === 'ACTIVITY'
}

export function isVenueOrder(order: TradeOrder): boolean {
  return order.source === 'VENUE'
}

// Any order that pays for a business record rather than goods.
export function isSourceOrder(order: TradeOrder): boolean {
  return order.source !== 'MALL'
}

// Where "查看订单" leads: the goods order, the ticket of the enrolment, or the code of the booking.
export function orderDestination(order: TradeOrder): TradeOrderDestination {
  if (order.source === 'ACTIVITY' && order.sourceRef) {
    return { kind: 'ACTIVITY_ENROLLMENT', enrollmentId: order.sourceRef }
  }
  if (order.source === 'VENUE' && order.sourceRef) {
    return { kind: 'VENUE_BOOKING', bookingId: order.sourceRef }
  }
  return { kind: 'GOODS_ORDER', orderId: order.orderId }
}

export interface CashierRow {
  key: string
  label: string
  value: string
}

// The rows under 订单号 / 收款方 on the cashier (board Cashier for goods, CashierEnroll for a
// sign-up, CashierVenue for a venue booking).
export function cashierRows(order: TradeOrder): CashierRow[] {
  const line = order.lines[0]
  if (!isSourceOrder(order) || !line) {
    return [{ key: 'goods', label: '商品', value: cashierGoodsLine(order) }]
  }
  if (isVenueOrder(order)) {
    return [
      { key: 'venue-item', label: '预约内容', value: `${line.productName} · ${line.skuName}` },
      { key: 'people', label: '人数', value: `${line.quantity} 人` },
    ]
  }
  return [
    { key: 'activity', label: '活动', value: line.productName },
    { key: 'session', label: '场次', value: line.skuName },
    { key: 'people', label: '人数', value: `${line.quantity} 人` },
  ]
}

// "报名费 2 人 × ¥120" / "到馆体验费 1 人 × ¥39" under the amount; nothing for goods.
export function cashierDueNote(order: TradeOrder): string | null {
  const line = order.lines[0]
  if (!isSourceOrder(order) || !line) return null
  const fee = isVenueOrder(order) ? '到馆体验费' : '报名费'
  return `${fee} ${line.quantity} 人 × ${formatTradeAmount(line.unitAmountMinor)}`
}

export function cashierCountdownLine(order: TradeOrder, now: number): string {
  const countdown = formatCountdown(paymentRemainingMs(order, now))
  if (isVenueOrder(order)) return `剩余支付时间 ${countdown} · 超时释放场次名额`
  return isEnrollmentOrder(order)
    ? `名额保留 ${countdown} · 超时自动释放`
    : `剩余支付时间 ${countdown} · 超时订单自动取消`
}

// The grey line of the closed frame.
export function cashierClosedNote(order: TradeOrder): string {
  return isSourceOrder(order) ? '没有扣款，名额已释放' : '没有扣款，可以重新下单'
}

export function cancelUnpaidConfirm(order: TradeOrder): {
  title: string
  content: string
  confirmText: string
  cancelText: string
} {
  if (isVenueOrder(order)) {
    return { title: '取消预约', content: '取消后预约关闭、名额释放，确定取消吗？', confirmText: '取消预约', cancelText: '再想想' }
  }
  return isEnrollmentOrder(order)
    ? { title: '取消报名', content: '取消后报名关闭、名额释放，确定取消吗？', confirmText: '取消报名', cancelText: '再想想' }
    : TRADE_COMMAND_CONFIRMS.CANCEL
}

// The toast after the buyer closed an unpaid order on the cashier.
export function cancelledToast(order: TradeOrder): string {
  if (isVenueOrder(order)) return '预约已取消'
  return isEnrollmentOrder(order) ? '报名已取消' : '订单已取消'
}

// The button that leaves for the order's own page.
export function orderLinkLabel(order: TradeOrder): string {
  if (!isSourceOrder(order)) return '查看订单'
  const unpaid = order.status === 'PENDING_PAYMENT' || order.status === 'CLOSED'
  if (isVenueOrder(order)) return unpaid ? '查看预约' : '查看核销码'
  return unpaid ? '查看报名' : '查看凭证'
}

// The paid frame of the result page: "已支付", "报名成功" with how many places it holds, or the
// venue booking's "预约成功 · 已支付".
export function paidFrame(order: TradeOrder): { title: string; note: string | null } {
  const line = order.lines[0]
  if (!isSourceOrder(order) || !line) return { title: '已支付', note: null }
  if (isVenueOrder(order)) return { title: '预约成功 · 已支付', note: '核销码已生成，到馆时出示' }
  return { title: '报名成功', note: `${line.quantity} 个名额已确认 · 一人一码` }
}

// The note of the failed frame: what is still held while the order waits for payment.
export function failedNote(order: TradeOrder): string {
  if (isVenueOrder(order)) return '预约仍为待支付，超时释放场次名额'
  return isEnrollmentOrder(order) ? '名额仍为你保留，超时自动释放' : '订单仍为待付款，超时订单自动取消'
}
