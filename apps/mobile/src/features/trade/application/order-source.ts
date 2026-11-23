import type { TradeOrder } from '../domain/trade-order'
import { TRADE_COMMAND_CONFIRMS } from './order-commands'
import { cashierGoodsLine, formatCountdown, formatTradeAmount, paymentRemainingMs } from './order-view'

// What the cashier and the result page say differently for a business order (contract 0.2.0).
// A paid activity enrolment or a venue booking (E10) has no goods page and ships nothing: its page
// is the enrolment's ticket or the booking's code. The order line is the business item: for an
// enrolment the activity (productName) and the session (skuName); for a venue booking "venue
// project" (productName) and the session time (skuName); quantity is the number of people. An
// equipment rental (F3) pays the confirmed contract: its first line is the rent of the device
// (productName), the period (skuName) and the units (quantity); its page is the rental order.
// An online course (S5-5, contract 0.9.0) is one line: the course (productName), 单人账号 (skuName)
// and quantity 1; its page is the course's learning page.

export type TradeOrderDestination =
  | { kind: 'GOODS_ORDER'; orderId: string }
  | { kind: 'ACTIVITY_ENROLLMENT'; enrollmentId: string }
  | { kind: 'VENUE_BOOKING'; bookingId: string }
  | { kind: 'RENTAL_ORDER'; rentalOrderId: string }
  // S4-1: an inspection payment node; its order page, or its fulfillment.
  | { kind: 'INSPECTION_ORDER'; serviceOrderId: string }
  | { kind: 'INSPECTION_FULFILLMENT'; serviceOrderId: string }
  | { kind: 'COURSE_ENROLLMENT'; enrollmentId: string }

/**
 * What the business module behind an order adds to the cashier and the result page (S4-1). The trade
 * pages ask the page host for it; the host asks the business module. Every field is optional: without
 * it the page falls back to what the trade order alone says.
 */
export interface SourceOrderView {
  // The pill under the amount (board CashierFirst: 付款节点 1 · 首付 30%（方案 v2 已确认）).
  dueChip?: string
  // Rows after 收款方, in order (付款主体, 付款节点).
  rows?: CashierRow[]
  // Rows after 收款方 on the result page (board CashierFirst's paid frame: 服务订单, 付款主体).
  resultRows?: CashierRow[]
  paidTitle?: string
  paidNote?: string
  failedNote?: string
  // After payment: the primary button, and an optional secondary one instead of 返回首页.
  next?: { label: string; destination: TradeOrderDestination }
  back?: { label: string; destination: TradeOrderDestination } | null
}

export type DescribeSourceOrder = (order: TradeOrder) => Promise<SourceOrderView | null>

export function isInspectionOrder(order: TradeOrder): boolean {
  return order.source === 'INSPECTION'
}

export function isEnrollmentOrder(order: TradeOrder): boolean {
  return order.source === 'ACTIVITY'
}

export function isVenueOrder(order: TradeOrder): boolean {
  return order.source === 'VENUE'
}

export function isRentalOrder(order: TradeOrder): boolean {
  return order.source === 'RENTAL'
}

export function isCourseOrder(order: TradeOrder): boolean {
  return order.source === 'COURSE'
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
  if (order.source === 'RENTAL' && order.sourceRef) {
    return { kind: 'RENTAL_ORDER', rentalOrderId: order.sourceRef }
  }
  // The line's productId is the inspection service order (the sourceRef is the payment attempt).
  if (order.source === 'INSPECTION' && order.lines[0]?.productId) {
    return { kind: 'INSPECTION_ORDER', serviceOrderId: order.lines[0].productId }
  }
  if (order.source === 'COURSE' && order.sourceRef) {
    return { kind: 'COURSE_ENROLLMENT', enrollmentId: order.sourceRef }
  }
  return { kind: 'GOODS_ORDER', orderId: order.orderId }
}

export interface CashierRow {
  key: string
  label: string
  value: string
}

// The rows under 订单号 / 收款方 on the cashier (board Cashier for goods, CashierEnroll for a
// sign-up, CashierVenue for a venue booking, CashierRental for a rental contract).
export function cashierRows(order: TradeOrder): CashierRow[] {
  const line = order.lines[0]
  if (!isSourceOrder(order) || !line) {
    return [{ key: 'goods', label: '商品', value: cashierGoodsLine(order) }]
  }
  if (isInspectionOrder(order)) {
    return [
      { key: 'inspection-order', label: '服务订单', value: line.productName },
      { key: 'inspection-node', label: '付款节点', value: `${line.skuName} ${formatTradeAmount(order.amounts.payableMinor)}` },
    ]
  }
  if (isRentalOrder(order)) {
    return [
      { key: 'rental-contract', label: '租赁合同', value: `${line.productName} ×${line.quantity} · ${line.skuName}` },
      { key: 'rental-node', label: '付款节点', value: `租金 ${formatTradeAmount(order.amounts.payableMinor)} · 合同确认后一次付清` },
    ]
  }
  if (isCourseOrder(order)) {
    return [
      { key: 'course', label: '课程', value: line.productName },
      { key: 'course-option', label: '规格', value: `${line.skuName} · 付款后立即开通` },
    ]
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

// The order of the cashier's rows, as each board draws them: goods and a sign-up start with the
// order number and the payee; a venue booking starts with what is booked and ends with the order
// number; a rental starts with the contract, then the payee and the payment node.
export type CashierLine = { key: 'order-no' } | { key: 'payee' } | CashierRow

export function cashierLayout(order: TradeOrder): CashierLine[] {
  const rows = cashierRows(order)
  const orderNo = { key: 'order-no' } as const
  const payee = { key: 'payee' } as const
  if (isRentalOrder(order) && rows.length === 2) return [rows[0], payee, rows[1], orderNo]
  // Boards CashierFirst / CashierFinal: 服务订单, 收款方, then 付款主体 and 付款节点.
  if (isInspectionOrder(order) && rows.length === 2) return [rows[0], payee, rows[1]]
  if (isVenueOrder(order)) return [...rows, payee, orderNo]
  return [orderNo, payee, ...rows]
}

// The money of a business order goes to the organization through the platform's channel (C2); the
// boards CashierEnroll, CashierVenue and CashierRental say so after the payee, Cashier does not.
export function cashierPayeeSuffix(order: TradeOrder): string | null {
  return isSourceOrder(order) ? ' · 经平台支付渠道' : null
}

// "报名费 2 人 × ¥120" under the amount (board CashierEnroll); a rental says when its rent is paid.
// Goods and a venue booking (board CashierVenue) have nothing there.
export function cashierDueNote(order: TradeOrder): string | null {
  const line = order.lines[0]
  if (!isSourceOrder(order) || !line || isVenueOrder(order) || isInspectionOrder(order)) return null
  if (isRentalOrder(order)) return '租金 · 合同确认后一次付清'
  if (isCourseOrder(order)) return '课程费 · 线上课不发货'
  return `报名费 ${line.quantity} 人 × ${formatTradeAmount(line.unitAmountMinor)}`
}

export function cashierCountdownLine(order: TradeOrder, now: number): string {
  const countdown = formatCountdown(paymentRemainingMs(order, now))
  if (isVenueOrder(order)) return `剩余支付时间 ${countdown} · 超时释放场次名额`
  if (isRentalOrder(order)) return `剩余支付时间 ${countdown} · 超时订单关闭，需重新询价`
  if (isInspectionOrder(order)) return `剩余支付时间 ${countdown} · 超时可重新发起`
  if (isCourseOrder(order)) return `剩余支付时间 ${countdown} · 超时订单自动取消`
  return isEnrollmentOrder(order)
    ? `名额保留 ${countdown} · 超时自动释放`
    : `剩余支付时间 ${countdown} · 超时订单自动取消`
}

// The grey line of the closed frame.
export function cashierClosedNote(order: TradeOrder): string {
  if (isRentalOrder(order)) return '没有扣款，报价已失效，要租需要重新询价'
  if (isInspectionOrder(order)) return '没有扣款，这一笔仍待支付，可以重新发起'
  if (isCourseOrder(order)) return '没有扣款，可以重新报名'
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
  if (isInspectionOrder(order)) {
    return {
      title: '取消支付',
      content: '取消后这一笔仍为待支付，可以稍后再付，确定取消吗？',
      confirmText: '取消支付',
      cancelText: '再想想',
    }
  }
  if (isCourseOrder(order)) {
    return { title: '取消支付', content: '取消后这次报名关闭，确定取消吗？', confirmText: '取消支付', cancelText: '再想想' }
  }
  if (isRentalOrder(order)) {
    return {
      title: '取消支付',
      content: '取消后租赁订单关闭、报价失效，要租需要重新询价，确定取消吗？',
      confirmText: '取消支付',
      cancelText: '再想想',
    }
  }
  return isEnrollmentOrder(order)
    ? { title: '取消报名', content: '取消后报名关闭、名额释放，确定取消吗？', confirmText: '取消报名', cancelText: '再想想' }
    : TRADE_COMMAND_CONFIRMS.CANCEL
}

// The toast after the buyer closed an unpaid order on the cashier.
export function cancelledToast(order: TradeOrder): string {
  if (isVenueOrder(order)) return '预约已取消'
  if (isRentalOrder(order)) return '租赁订单已关闭'
  if (isInspectionOrder(order)) return '已取消支付'
  if (isCourseOrder(order)) return '报名已取消'
  return isEnrollmentOrder(order) ? '报名已取消' : '订单已取消'
}

// The button that leaves for the order's own page.
export function orderLinkLabel(order: TradeOrder): string {
  if (!isSourceOrder(order)) return '查看订单'
  if (isRentalOrder(order)) return '查看租赁订单'
  if (isInspectionOrder(order)) return '查看服务订单'
  if (isCourseOrder(order)) return '查看课程'
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
  if (isRentalOrder(order)) return { title: '租金已支付', note: '商家将按约定时间交接设备，交接后由你确认' }
  if (isInspectionOrder(order)) return { title: `${line.skuName}已支付`, note: null }
  if (isCourseOrder(order)) return { title: '报名成功 · 课程已开通', note: '可以开始学习了，学到哪里自动记住' }
  return { title: '报名成功', note: `${line.quantity} 个名额已确认 · 一人一码` }
}

// The note of the failed frame: what is still held while the order waits for payment.
export function failedNote(order: TradeOrder): string {
  if (isVenueOrder(order)) return '预约仍为待支付，超时释放场次名额'
  if (isRentalOrder(order)) return '租金仍为待支付，超时订单关闭、报价失效'
  if (isInspectionOrder(order)) return `${line0Name(order)}仍为待支付`
  if (isCourseOrder(order)) return '订单仍为待付款，超时订单自动取消'
  return isEnrollmentOrder(order) ? '名额仍为你保留，超时自动释放' : '订单仍为待付款，超时订单自动取消'
}

function line0Name(order: TradeOrder): string {
  return order.lines[0]?.skuName ?? '这一笔'
}
