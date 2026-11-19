import { DAY_MS, padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type {
  TradeCloseReason,
  TradeDeliveryMode,
  TradeMerchantKind,
  TradeOrder,
  TradeOrderTab,
  TradeRefundStatus,
} from '../domain/trade-order'

// Shipped orders are confirmed by the server this long after shipping (plan §19.7).
export const AUTO_RECEIPT_DAYS = 10

export const TRADE_TAB_LABELS: Record<TradeOrderTab, string> = {
  ALL: '全部',
  TO_PAY: '待付款',
  TO_SHIP: '待发货',
  TO_RECEIVE: '待收货',
  DONE: '已完成',
  REFUND: '退款',
}

export const TRADE_DELIVERY_LABELS: Record<TradeDeliveryMode, string> = {
  EXPRESS: '快递配送',
  PICKUP: '到店自提',
}

export const TRADE_MERCHANT_KIND_LABELS: Record<TradeMerchantKind, string> = {
  FACTORY_DIRECT: '厂家直营',
  AUTHORIZED_DEALER: '授权经销',
  LOCAL_STORE: '本地商家',
}

export const TRADE_REFUND_STATUS_LABELS: Record<TradeRefundStatus, string> = {
  REQUESTED: '退款处理中',
  PROCESSING: '退款处理中',
  SUCCEEDED: '已退回',
  CLOSED: '退款已关闭',
  ABNORMAL: '退款异常',
}

// The one payment method of this phase.
export const TRADE_PAYMENT_METHOD_LABEL = '微信支付'

export function formatTradeAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

// "29:45", or "1:05:09" past an hour. Never negative.
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.floor(remainingMs / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return hours > 0
    ? `${hours}:${padTwo(minutes)}:${padTwo(seconds)}`
    : `${padTwo(minutes)}:${padTwo(seconds)}`
}

// "11/03 15:09" in Beijing time.
export function formatTradeTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

// "2026-11-03 15:09" for the order information rows.
export function formatTradeDateTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${parts.year}-${padTwo(parts.month)}-${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

export function autoReceiptAt(shippedAt: string): string {
  return new Date(new Date(shippedAt).getTime() + AUTO_RECEIPT_DAYS * DAY_MS).toISOString()
}

export function paymentRemainingMs(order: TradeOrder, now: number): number {
  if (order.status !== 'PENDING_PAYMENT' || !order.payBefore) return 0
  return new Date(order.payBefore).getTime() - now
}

// Unpaid and past its deadline. The server closes such orders within a minute; until then the
// page already treats them as closed, so nobody starts a payment the server will refuse.
export function isPaymentOverdue(order: TradeOrder, now: number): boolean {
  return order.status === 'PENDING_PAYMENT' && paymentRemainingMs(order, now) <= 0
}

export function canPay(order: TradeOrder, now: number): boolean {
  return order.status === 'PENDING_PAYMENT' && paymentRemainingMs(order, now) > 0
}

// "订单已取消" or "订单已超时关闭" from the server's close reason; null when it does not say.
export function closedOrderTitle(reason: TradeCloseReason | null): string | null {
  if (reason === 'BUYER_CANCEL') return '订单已取消'
  if (reason === 'TIMEOUT') return '订单已超时关闭'
  return null
}

// The title and line of a closed order: the order page header and the pay result page.
export function describeClosedOrder(order: TradeOrder): { title: string; line: string } {
  switch (order.closeReason) {
    case 'BUYER_CANCEL':
      return { title: '订单已取消', line: '你已取消这笔订单，没有扣款' }
    case 'TIMEOUT':
      return { title: '订单已超时关闭', line: '超过支付时限未支付，订单已自动关闭，没有扣款' }
    default:
      // An older server does not say how it closed; the text fits both.
      return { title: '订单已关闭', line: '订单未支付，已关闭，没有扣款' }
  }
}

// The closed frame of the cashier. Unpaid past the deadline keeps the board's 支付已超时 frame
// (the server closes it within a minute); `cancelledHere` covers a server that sends no reason.
export function describeCashierClosed(
  order: TradeOrder,
  now: number,
  cancelledHere: boolean,
): { title: string; text: string } {
  if (isPaymentOverdue(order, now)) {
    return { title: '支付已超时', text: '超过支付时限未支付，订单将自动关闭' }
  }
  if (order.closeReason === 'BUYER_CANCEL' || (cancelledHere && order.closeReason === null)) {
    return { title: '订单已取消', text: '你已取消这笔订单，订单已关闭' }
  }
  if (order.closeReason === 'TIMEOUT') {
    return { title: '订单已超时关闭', text: '超过支付时限未支付，订单已自动关闭' }
  }
  return { title: '订单已关闭', text: '这笔订单没有支付，已关闭' }
}

export type TradeStatusTone = 'ACTION' | 'DONE' | 'MUTED' | 'ALERT'

// The short status on a list card.
export function describeOrderStatus(order: TradeOrder, now: number): { label: string; tone: TradeStatusTone } {
  switch (order.status) {
    case 'PENDING_PAYMENT':
      return isPaymentOverdue(order, now)
        ? { label: '已超时', tone: 'MUTED' }
        : { label: '待付款', tone: 'ACTION' }
    case 'PAID':
      return order.delivery.mode === 'PICKUP'
        ? { label: '待自提', tone: 'ACTION' }
        : { label: '待发货', tone: 'ACTION' }
    case 'SHIPPED':
      return { label: '已发货', tone: 'ACTION' }
    case 'COMPLETED':
      return { label: '已完成', tone: 'DONE' }
    case 'CLOSED':
      return { label: '已关闭', tone: 'MUTED' }
    case 'REFUNDING':
      return { label: '退款中', tone: 'ACTION' }
    case 'REFUNDED':
      return { label: '已退款', tone: 'MUTED' }
    case 'REFUND_FAILED':
      return { label: '退款异常', tone: 'ALERT' }
  }
}

// The big title and the line under it at the top of the order page.
export function describeOrderHeader(order: TradeOrder, now: number): { title: string; line: string } {
  switch (order.status) {
    case 'PENDING_PAYMENT':
      return isPaymentOverdue(order, now)
        ? { title: '支付已超时', line: '订单即将自动关闭，没有扣款' }
        : {
          title: '待付款',
          line: `请在 ${formatCountdown(paymentRemainingMs(order, now))} 内完成支付，超时订单自动取消`,
        }
    case 'PAID':
      return order.delivery.mode === 'PICKUP'
        ? { title: '待自提', line: '请凭取货码到自提点取货' }
        : { title: '待发货', line: '商家正在备货，发货后可在这里查看运单号' }
    case 'SHIPPED':
      return {
        title: '已发货',
        line: order.delivery.shippedAt
          ? `${formatTradeTime(autoReceiptAt(order.delivery.shippedAt))} 将自动确认收货（发货 ${AUTO_RECEIPT_DAYS} 天后）`
          : `发货 ${AUTO_RECEIPT_DAYS} 天后自动确认收货`,
      }
    case 'COMPLETED':
      return {
        title: '已完成',
        line: order.delivery.mode === 'PICKUP' ? '已取货，交易完成' : '已确认收货，交易完成',
      }
    case 'CLOSED':
      return describeClosedOrder(order)
    case 'REFUNDING':
      return { title: '退款中', line: '货款将原路退回，到账时间以微信支付为准' }
    case 'REFUNDED':
      return { title: '已退款', line: `${formatTradeAmount(order.amounts.refundedMinor)} 已原路退回` }
    case 'REFUND_FAILED':
      return { title: '退款异常', line: '退款没有成功，平台正在人工处理' }
  }
}

export type TradeOrderAction =
  // Unpaid: closes the order.
  | 'CANCEL'
  // Paid, not shipped: cancels and refunds the whole amount.
  | 'CANCEL_AND_REFUND'
  | 'PAY'
  | 'CONFIRM_RECEIPT'
  | 'VIEW'

export function listCardActions(order: TradeOrder, now: number): TradeOrderAction[] {
  if (canPay(order, now)) return ['CANCEL', 'PAY']
  if (order.status === 'SHIPPED') return ['VIEW', 'CONFIRM_RECEIPT']
  return ['VIEW']
}

export function detailActions(order: TradeOrder, now: number): TradeOrderAction[] {
  if (canPay(order, now)) return ['CANCEL', 'PAY']
  if (order.status === 'PAID' && order.cancellable) return ['CANCEL_AND_REFUND']
  if (order.status === 'SHIPPED') return ['CONFIRM_RECEIPT']
  return []
}

export type TradeCardHint =
  | { kind: 'COUNTDOWN'; text: string }
  | { kind: 'SHIPPED'; text: string }
  | { kind: 'NOTE'; text: string }

// The coloured line under the goods on a list card.
export function listCardHint(order: TradeOrder, now: number): TradeCardHint | null {
  if (canPay(order, now)) {
    return { kind: 'COUNTDOWN', text: `剩 ${formatCountdown(paymentRemainingMs(order, now))} 未支付将自动取消` }
  }
  if (order.status === 'SHIPPED' && order.delivery.shippedAt) {
    const carrier = order.delivery.carrierName ? ` · ${order.delivery.carrierName}` : ''
    return { kind: 'SHIPPED', text: `${formatTradeTime(order.delivery.shippedAt)} 已发货${carrier}` }
  }
  if (order.status === 'PAID' && order.delivery.mode === 'PICKUP' && order.delivery.pickupPoint) {
    return { kind: 'NOTE', text: `自提点 ${order.delivery.pickupPoint}` }
  }
  if (order.status === 'CLOSED') {
    return { kind: 'NOTE', text: closedOrderTitle(order.closeReason) ?? '订单未支付，已关闭' }
  }
  return null
}

export function pieceCount(order: TradeOrder): number {
  return order.lines.reduce((sum, line) => sum + line.quantity, 0)
}

// The first line stands for the order on a card; several lines are counted, not listed.
export function listCardGoods(order: TradeOrder): { name: string; spec: string } {
  const first = order.lines[0]
  const delivery = TRADE_DELIVERY_LABELS[order.delivery.mode]
  if (!first) return { name: '', spec: delivery }
  if (order.lines.length === 1) {
    return { name: first.productName, spec: `${first.skuName} × ${first.quantity} · ${delivery}` }
  }
  return {
    name: `${first.productName} 等 ${order.lines.length} 种商品`,
    spec: `共 ${pieceCount(order)} 件 · ${delivery}`,
  }
}

// One row of the cashier: "编程机器人探索套装 · 标准版 × 1、避障训练场地套件 · 标准版 × 1（共 2 件）"
export function cashierGoodsLine(order: TradeOrder): string {
  const text = order.lines.map((line) => `${line.productName} · ${line.skuName} × ${line.quantity}`).join('、')
  return order.lines.length > 1 ? `${text}（共 ${pieceCount(order)} 件）` : text
}

// The payment status row of the order page; null while unpaid.
export function describePayment(order: TradeOrder): string | null {
  if (!order.paidAt) return null
  const { paidMinor, refundedMinor } = order.amounts
  if (refundedMinor > 0 && refundedMinor >= paidMinor) return '已支付 · 已全额退款'
  if (refundedMinor > 0) return `已支付 · 已退 ${formatTradeAmount(refundedMinor)}`
  return '已支付'
}
