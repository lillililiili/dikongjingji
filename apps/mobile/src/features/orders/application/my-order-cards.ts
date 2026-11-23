import {
  describeEnrollmentCard,
  enrollmentPriceText,
  paymentRemainingMs as enrollmentPaymentRemainingMs,
} from '../../activity/application/enrollment-view'
import { resolveActivityPreviewCover } from '../../activity/data/preview/activity-preview-covers'
import type { Enrollment } from '../../activity/domain/enrollment'
import {
  buildActivityOrderUrl,
  buildActivityTicketUrl,
} from '../../activity/navigation/activity-routes'
import {
  describeVenueBookingCard,
  describeVenueRefund,
  formatVenueAmount,
  venuePaymentRemainingMs,
} from '../../experience/application/venue-booking-view'
import { resolveExperiencePreviewCover } from '../../experience/data/preview/experience-preview-covers'
import type { VenueBooking } from '../../experience/domain/venue-booking'
import { buildVenueBookingUrl } from '../../experience/navigation/experience-routes'
import { resolveMallPreviewCover } from '../../mall/data/preview/mall-preview-covers'
import {
  beijingToday,
  daysBetween,
  describeRentalLessee,
  formatMonthDay,
} from '../../rental/application/rental-format'
import {
  RENTAL_ORDER_STATUS_LABELS,
  describeRentalOrderCard,
  rentalOrderTone,
} from '../../rental/application/rental-order-view'
import type { RentalOrder } from '../../rental/domain/rental'
import { buildRentalOrderUrl } from '../../rental/navigation/rental-routes'
import { formatServiceAmount } from '../../serviceorder/application/service-order-format'
import { describeServiceOrderCard } from '../../serviceorder/application/service-order-view'
import { resolveServiceOrderPreviewCover } from '../../serviceorder/data/preview/serviceorder-preview-covers'
import type { ServiceOrderSummary } from '../../serviceorder/domain/service-order'
import {
  buildServiceOrderDeliveryUrl,
  buildServiceOrderDetailUrl,
  buildServiceOrderPlanUrl,
} from '../../serviceorder/navigation/service-order-routes'
import {
  describeOrderStatus,
  formatTradeAmount,
  listCardActions,
  listCardGoods,
  listCardHint,
  type TradeStatusTone,
} from '../../trade/application/order-view'
import type { TradeOrder } from '../../trade/domain/trade-order'
import { buildCashierUrl, buildGoodsOrderUrl } from '../../trade/navigation/trade-routes'
import type { MyOrderItem } from './my-order-sources'

/**
 * One card of 我的订单 for goods, rentals, sign-ups and venue bookings (board OrderList): a head
 * row (kind tag, merchant or place, status), a 72px picture with the title and amount, grey and
 * slate lines under it, and the buttons on the right. Inspection orders keep their own card
 * (features/inspection/presentation/orders/components/InspectionOrderCard.vue), drawn the same way.
 * Every value comes from each module's own describing functions, so a card says what the
 * module's own list says.
 */
export type MyOrderTagTone = 'ORANGE' | 'PURPLE' | 'GREEN'
export type MyOrderTone = 'ORANGE' | 'GREEN' | 'GREY' | 'BLUE' | 'PURPLE' | 'RED' | 'SLATE'
export type MyOrderLineTone = 'MUTED' | 'PLAIN' | 'COUNTDOWN' | 'SHIPPED' | 'ORANGE' | 'GREEN' | 'RED'

export interface MyOrderCardLine {
  key: string
  text: string
  tone: MyOrderLineTone
}

export type MyOrderCardTarget =
  | { kind: 'URL'; url: string }
  // Goods order commands, run on the list like 商品订单 does.
  | { kind: 'GOODS_CANCEL' }
  | { kind: 'GOODS_RECEIPT' }

export interface MyOrderCardAction {
  key: string
  label: string
  style: 'OUTLINE' | 'PRIMARY' | 'ACCENT'
  target: MyOrderCardTarget
}

export interface MyOrderCardView {
  tag: { label: string; tone: MyOrderTagTone }
  head: string
  status: { text: string; tone: MyOrderTone }
  cover: string | null
  coverAlt: string
  title: string
  // A bracketed ending such as "（东营站）" that is kept on one line.
  titleTail: string
  amount: string
  lines: MyOrderCardLine[]
  // Where the card itself leads.
  url: string
  actions: MyOrderCardAction[]
}

const TRADE_TONES: Record<TradeStatusTone, MyOrderTone> = {
  ACTION: 'ORANGE',
  DONE: 'GREEN',
  MUTED: 'GREY',
  ALERT: 'RED',
}

function goodsCard(order: TradeOrder, now: number): MyOrderCardView {
  const first = order.lines[0]
  const goods = listCardGoods(order)
  const hint = listCardHint(order, now)
  const status = describeOrderStatus(order, now)
  const lines: MyOrderCardLine[] = [{ key: 'spec', text: goods.spec, tone: 'MUTED' }]
  if (hint) {
    const tone: MyOrderLineTone = hint.kind === 'COUNTDOWN'
      ? 'COUNTDOWN'
      : hint.kind === 'SHIPPED' ? 'SHIPPED' : order.status === 'CLOSED' ? 'MUTED' : 'PLAIN'
    lines.push({ key: 'hint', text: hint.text, tone })
  }
  const detail = buildGoodsOrderUrl(order.orderId)
  const actions = listCardActions(order, now).map((action): MyOrderCardAction => {
    switch (action) {
      case 'CANCEL':
      case 'CANCEL_AND_REFUND':
        return { key: 'cancel', label: '取消订单', style: 'OUTLINE', target: { kind: 'GOODS_CANCEL' } }
      case 'PAY':
        return { key: 'pay', label: '继续支付', style: 'PRIMARY', target: { kind: 'URL', url: buildCashierUrl(order.orderId) } }
      case 'CONFIRM_RECEIPT':
        return { key: 'receipt', label: '确认收货', style: 'ACCENT', target: { kind: 'GOODS_RECEIPT' } }
      case 'VIEW':
        return { key: 'view', label: '查看订单', style: 'OUTLINE', target: { kind: 'URL', url: detail } }
    }
  })
  return {
    tag: { label: '商品', tone: 'ORANGE' },
    head: order.merchant.name,
    status: { text: status.label, tone: TRADE_TONES[status.tone] },
    cover: first ? first.coverImage?.url ?? resolveMallPreviewCover(first.productId)?.url ?? null : null,
    coverAlt: first?.productName ?? '',
    title: goods.name,
    titleTail: '',
    amount: formatTradeAmount(order.amounts.payableMinor),
    lines,
    url: detail,
    actions,
  }
}

// Board OrderList: "租期 11/01–11/05 · 还有 2 天到期" while in use, else the number of days.
function rentalPeriodLine(order: RentalOrder, now: number): string {
  const contract = order.contract
  const range = `租期 ${formatMonthDay(contract.startDate)}–${formatMonthDay(contract.endDate)}`
  if (order.status !== 'IN_USE') return `${range} · 共 ${contract.days} 天`
  const left = daysBetween(beijingToday(new Date(now)), contract.endDate)
  return `${range} · ${left > 0 ? `还有 ${left} 天到期` : left === 0 ? '今天到期' : '已过到期日'}`
}

function rentalCard(order: RentalOrder, cover: string | null, now: number): MyOrderCardView {
  const view = describeRentalOrderCard(order, new Date(now))
  const tone = rentalOrderTone(order.status)
  const detail = buildRentalOrderUrl(order.rentalOrderId)
  return {
    tag: { label: '租赁', tone: 'ORANGE' },
    head: order.contract.lessor.name,
    status: {
      text: RENTAL_ORDER_STATUS_LABELS[order.status],
      tone: tone === 'DONE' ? 'GREEN' : tone === 'MUTED' ? 'GREY' : 'ORANGE',
    },
    cover,
    coverAlt: order.contract.productName,
    title: view.title,
    titleTail: '',
    amount: view.amount,
    lines: [
      { key: 'lessee', text: describeRentalLessee(order.contract.lessee).badge, tone: 'MUTED' },
      { key: 'period', text: rentalPeriodLine(order, now), tone: 'PLAIN' },
      // A cancelled rental says where the rent went.
      ...(order.status === 'CANCELLED' ? [{ key: 'state', text: view.line, tone: 'PLAIN' as const }] : []),
    ],
    url: detail,
    actions: [
      { key: 'view', label: '查看详情', style: 'OUTLINE', target: { kind: 'URL', url: detail } },
      ...(view.pay
        ? [{ key: 'pay', label: '去付款', style: 'PRIMARY' as const, target: { kind: 'URL' as const, url: buildCashierUrl(order.payment.tradeOrderId) } }]
        : []),
    ],
  }
}

const SERVICE_TONES = { ACTION: 'ORANGE', DONE: 'GREEN', MUTED: 'GREY' } as const

// A general service order (S4-3), drawn like the board's 服务 card: the provider, the status, the
// demand's scene, who ordered and where the order stands, and the one thing to do next.
function serviceCard(order: ServiceOrderSummary): MyOrderCardView {
  const view = describeServiceOrderCard(order)
  const detail = buildServiceOrderDetailUrl(order.orderId)
  return {
    tag: { label: '服务', tone: 'GREEN' },
    head: order.provider.name,
    status: { text: view.statusText, tone: SERVICE_TONES[view.tone] },
    cover: resolveServiceOrderPreviewCover(order.type),
    coverAlt: order.title,
    title: order.title,
    titleTail: '',
    amount: formatServiceAmount(order.amountMinor),
    lines: view.lines.map((line) => ({ key: line.key, text: line.text, tone: line.tone })),
    url: detail,
    actions: view.buttons.map((button): MyOrderCardAction => {
      switch (button.kind) {
        case 'VIEW': return { key: 'view', label: button.label, style: 'OUTLINE', target: { kind: 'URL', url: detail } }
        // Confirming and paying both happen on the plan page (the payment needs the confirmed plan).
        case 'CONFIRM_PLAN': return { key: 'plan', label: button.label, style: 'PRIMARY', target: { kind: 'URL', url: buildServiceOrderPlanUrl(order.orderId) } }
        case 'PAY': return { key: 'pay', label: button.label, style: 'PRIMARY', target: { kind: 'URL', url: buildServiceOrderPlanUrl(order.orderId) } }
        case 'ACCEPT': return { key: 'accept', label: button.label, style: 'PRIMARY', target: { kind: 'URL', url: buildServiceOrderDeliveryUrl(order.orderId) } }
      }
    }),
  }
}

// "青少年无人机编程挑战赛（东营站）": the bracketed part never breaks across lines (board OrderList).
function splitTitle(title: string): { main: string; tail: string } {
  const at = title.lastIndexOf('（')
  return at > 0 && title.endsWith('）') ? { main: title.slice(0, at), tail: title.slice(at) } : { main: title, tail: '' }
}

const ENROLL_STATUS_TONES = { UPCOMING: 'PURPLE', ACTION: 'ORANGE', DONE: 'GREEN', MUTED: 'SLATE' } as const
const NOTE_TONES = { PLAIN: 'PLAIN', PENDING: 'ORANGE', DONE: 'GREEN', ALERT: 'RED' } as const

function enrollCard(enrollment: Enrollment, now: number): MyOrderCardView {
  const view = describeEnrollmentCard(enrollment)
  const title = splitTitle(enrollment.activity.title)
  const ticket = buildActivityTicketUrl(enrollment.enrollmentId)
  // A paid sign-up opens on its order (as 我的报名 does); others on the ticket.
  const detail = enrollment.payment.status === 'PAID' ? buildActivityOrderUrl(enrollment.enrollmentId) : ticket
  // As on 我的报名: the card shows no "前可取消" line; refunds, payment and check-ins still say something.
  const showNote = Boolean(view.note) && !(view.statusText === '待参加' && view.noteTone === 'PLAIN')
  const lines: MyOrderCardLine[] = [
    { key: 'who', text: view.who, tone: 'MUTED' },
    { key: 'when', text: [view.when, enrollment.location.name].filter(Boolean).join(' · '), tone: 'PLAIN' },
  ]
  if (showNote) lines.push({ key: 'note', text: view.note, tone: NOTE_TONES[view.noteTone] })
  const actions: MyOrderCardAction[] = []
  if (enrollment.payment.status === 'PENDING_PAYMENT' && enrollment.status !== 'CANCELLED') {
    // Past the deadline the server is about to close the order; the ticket page says what happened.
    const orderId = enrollment.payment.tradeOrderId
    const url = orderId && enrollmentPaymentRemainingMs(enrollment, now) > 0 ? buildCashierUrl(orderId) : ticket
    actions.push({ key: 'pay', label: '去付款', style: 'PRIMARY', target: { kind: 'URL', url } })
  } else {
    actions.push({
      key: 'ticket',
      label: '查看凭证',
      style: enrollment.status === 'UPCOMING' ? 'PRIMARY' : 'OUTLINE',
      target: { kind: 'URL', url: ticket },
    })
  }
  return {
    tag: { label: '报名', tone: 'PURPLE' },
    head: enrollment.activity.organizerName || enrollment.location.name,
    status: { text: view.statusText, tone: ENROLL_STATUS_TONES[view.statusTone] },
    cover: enrollment.activity.coverImage?.url ?? resolveActivityPreviewCover(enrollment.activity.activityId),
    coverAlt: enrollment.activity.title,
    title: title.main,
    titleTail: title.tail,
    amount: enrollmentPriceText(enrollment),
    lines,
    url: detail,
    actions,
  }
}

const VENUE_STATUS_TONES = { PENDING: 'ORANGE', LIVE: 'PURPLE', DONE: 'GREEN', OFF: 'GREY' } as const

function venueCard(booking: VenueBooking, now: number): MyOrderCardView {
  const view = describeVenueBookingCard(booking, now)
  const detail = buildVenueBookingUrl(booking.bookingId)
  const names = booking.participants.map((person) => person.name).join('、')
  const lines: MyOrderCardLine[] = [
    { key: 'who', text: names ? `参与人：${names}` : `${booking.people} 人`, tone: 'MUTED' },
    { key: 'when', text: view.when, tone: 'PLAIN' },
  ]
  if (booking.status === 'PENDING_PAYMENT' || booking.status === 'CLOSED') {
    lines.push({ key: 'money', text: view.money, tone: booking.status === 'CLOSED' ? 'MUTED' : 'ORANGE' })
  } else if (booking.refund) {
    const refund = describeVenueRefund(booking.refund)
    lines.push({ key: 'money', text: refund.text, tone: refund.tone === 'DONE' ? 'GREEN' : refund.tone === 'ALERT' ? 'RED' : 'ORANGE' })
  }
  const actions: MyOrderCardAction[] = []
  if (booking.status === 'PENDING_PAYMENT') {
    const orderId = booking.payment.tradeOrderId
    const url = orderId && venuePaymentRemainingMs(booking, now) > 0 ? buildCashierUrl(orderId) : detail
    actions.push({ key: 'pay', label: '去付款', style: 'PRIMARY', target: { kind: 'URL', url } })
  } else if (booking.status === 'CONFIRMED') {
    actions.push({ key: 'code', label: '出示核销码', style: 'PRIMARY', target: { kind: 'URL', url: detail } })
  } else {
    actions.push({ key: 'view', label: '查看详情', style: 'OUTLINE', target: { kind: 'URL', url: detail } })
  }
  return {
    tag: { label: '体验', tone: 'PURPLE' },
    head: booking.venue.name,
    status: { text: view.statusText, tone: VENUE_STATUS_TONES[view.statusTone] },
    cover: resolveExperiencePreviewCover(booking.project.projectId) ?? resolveExperiencePreviewCover(booking.venue.venueId),
    coverAlt: booking.project.name,
    title: view.title,
    titleTail: '',
    amount: booking.payment.status === 'FREE' ? '免费' : formatVenueAmount(booking.payment.amountMinor),
    lines,
    url: detail,
    actions,
  }
}

// Null for an inspection order, which is drawn by its own card.
export function describeMyOrderCard(
  item: MyOrderItem,
  now: number,
  rentalCovers: Record<string, string | null> = {},
): MyOrderCardView | null {
  switch (item.kind) {
    case 'GOODS': return goodsCard(item.order, now)
    case 'RENTAL': return rentalCard(item.order, rentalCovers[item.order.inquiryId] ?? null, now)
    case 'ENROLL': return enrollCard(item.enrollment, now)
    case 'VENUE': return venueCard(item.booking, now)
    case 'SERVICE': return serviceCard(item.order)
    case 'INSPECTION': return null
  }
}
