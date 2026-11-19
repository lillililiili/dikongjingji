import {
  DAY_MS,
  formatBeijingTime,
  padTwo,
  toBeijingDate,
  toBeijingParts,
} from '../../../shared/time/beijing-time'

import type { VenueProject, VenueSession } from '../domain/experience'
import type {
  VenueBooking,
  VenueBookingBucket,
  VenueBookingRefund,
  VenueParticipant,
} from '../domain/venue-booking'

// What the confirm page, the code page and the list say about a venue booking (plan §21). Every
// state comes from the server; the page only words it.

export type VenuePayMode = 'FREE' | 'ONLINE_PAYMENT' | 'PAYMENT_UNAVAILABLE'

export const VENUE_PAYMENT_UNAVAILABLE_TEXT = '场馆暂未开通在线支付'

// A priced project needs the venue's WeChat account; a free one is booked on submit.
export function resolveVenuePayMode(project: Pick<VenueProject, 'priceAmountMinor' | 'onlinePayment'>): VenuePayMode {
  if (project.priceAmountMinor === 0) return 'FREE'
  return project.onlinePayment ? 'ONLINE_PAYMENT' : 'PAYMENT_UNAVAILABLE'
}

export function venueTotalMinor(unitAmountMinor: number, people: number): number {
  return unitAmountMinor * people
}

// "¥39", "¥39.50", "¥1,299"
export function formatVenueAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

// "今天 11/20 周五" / "明天 11/21 周六" / "11/22 周日", in Beijing time.
export function formatVenueDay(iso: string, now: number): string {
  const parts = toBeijingParts(new Date(iso))
  const day = toBeijingDate(new Date(iso))
  const date = `${padTwo(parts.month)}/${padTwo(parts.day)} ${parts.weekday}`
  if (day === toBeijingDate(new Date(now))) return `今天 ${date}`
  if (day === toBeijingDate(new Date(now + DAY_MS))) return `明天 ${date}`
  return date
}

// "今天 11/20 周五 10:00–10:30"
export function formatVenueSlotLine(session: { startsAt: string; endsAt: string }, now: number): string {
  return `${formatVenueDay(session.startsAt, now)} ${formatBeijingTime(session.startsAt)}–${formatBeijingTime(session.endsAt)}`
}

// "11/20 10:00"
export function formatVenueShortTime(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

export interface VenueParticipantRow {
  selectable: boolean
  ageText: string
  stateText: string
}

export function describeVenueParticipant(person: VenueParticipant): VenueParticipantRow {
  const ageText = person.ageAtSession !== null ? `${person.ageAtSession} 岁` : ''
  if (person.reason === 'ALREADY_BOOKED') return { selectable: false, ageText, stateText: '已约过这一场' }
  if (!person.eligible || person.reason === 'UNDER_MIN_AGE') {
    return { selectable: false, ageText, stateText: '不满项目最低年龄' }
  }
  return { selectable: true, ageText, stateText: person.relation === 'SELF' ? '可以预约' : '符合年龄' }
}

// What §21.1 actually does; nothing about reminders or venue-side cancellations, which are not built.
export function venueBookRules(payMode: VenuePayMode): string[] {
  if (payMode === 'FREE') {
    return [
      '改期只能换同一项目的其他场次，旧码作废、换新码',
      '取消后名额同时释放',
      '未到馆的场次结束后会过期',
    ]
  }
  return [
    '提交后 15 分钟内未付款，名额自动释放',
    '改期只能换同一项目的其他场次，同价不补差价，旧码作废、换新码',
    '取消后整单原路全额退款，名额同时释放',
    '未到馆的场次结束后会过期，费用不退',
  ]
}

export interface ApiFailure {
  status: number
  code: string | null
  message: string
}

export type VenueBookFailure = {
  message: string
  // What the page reads again: the venue (price, payment, session) or the participants (seats, eligibility).
  reload: 'VENUE' | 'PEOPLE' | 'NONE'
}

export function describeVenueBookFailure(failure: ApiFailure | null): VenueBookFailure {
  if (!failure) return { message: '提交失败，请稍后重试', reload: 'NONE' }
  switch (failure.code) {
    case 'VENUE_AMOUNT_CHANGED':
      return { message: '体验费有调整，已更新为最新金额，请确认后再提交', reload: 'VENUE' }
    case 'VENUE_PAYMENT_UNAVAILABLE':
      return { message: VENUE_PAYMENT_UNAVAILABLE_TEXT, reload: 'VENUE' }
    case 'VENUE_SESSION_NOT_OPEN':
      return { message: '这一场已经开始，请换一场', reload: 'VENUE' }
    case 'VENUE_SEATS_NOT_ENOUGH':
      return { message: '本场余位不够，请减少人数或换一场', reload: 'PEOPLE' }
    case 'VENUE_PARTICIPANT_ALREADY_BOOKED':
      return { message: '有参与人已经约过这一场', reload: 'PEOPLE' }
    case 'VENUE_PARTICIPANT_NOT_ELIGIBLE':
      return { message: '有参与人不满项目的最低年龄', reload: 'PEOPLE' }
    case 'TRADE_CHANNEL_UNAVAILABLE':
      return { message: '微信支付暂不可用，请稍后再试', reload: 'NONE' }
    default:
      break
  }
  if (failure.status === 503) return { message: '微信支付暂不可用，请稍后再试', reload: 'NONE' }
  if (failure.status === 404) return { message: '场次已下线，请换一场', reload: 'VENUE' }
  return { message: failure.message || '提交失败，请稍后重试', reload: 'NONE' }
}

// A random UUID (version 4 layout) for the Idempotency-Key header.
export function createVenueBookKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

/**
 * One idempotency key per distinct booking. Tapping submit again after a timeout sends the same
 * session, people and amount with the same key, so the server answers with the booking it already
 * made; another session, other people or a new amount (the price changed) is a new booking.
 */
export function createVenueBookKeyHolder(createKey: () => string = createVenueBookKey) {
  let signature = ''
  let key = ''
  return {
    keyFor(sessionId: string, participantIds: string[], expectedAmountMinor: number): string {
      const next = `${sessionId}|${[...participantIds].sort().join(',')}|${expectedAmountMinor}`
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}

// --- the code page ---

export type VenueBookingTone = 'PENDING' | 'LIVE' | 'USED' | 'EXPIRED' | 'CANCELLED' | 'CLOSED'

export function venueBookingTone(booking: VenueBooking): VenueBookingTone {
  switch (booking.status) {
    case 'PENDING_PAYMENT': return 'PENDING'
    case 'CONFIRMED': return 'LIVE'
    case 'CHECKED_IN': return 'USED'
    case 'EXPIRED': return 'EXPIRED'
    case 'CANCELLED': return 'CANCELLED'
    case 'CLOSED': return 'CLOSED'
  }
}

export function venuePaymentRemainingMs(booking: VenueBooking, now: number): number {
  if (booking.payment.status !== 'PENDING_PAYMENT' || !booking.payment.payBefore) return 0
  return Math.max(0, Date.parse(booking.payment.payBefore) - now)
}

// "14:45"
export function formatVenueCountdown(remainingMs: number): string {
  const seconds = Math.max(0, Math.ceil(remainingMs / 1000))
  return `${padTwo(Math.floor(seconds / 60))}:${padTwo(seconds % 60)}`
}

export function describeVenueRefund(refund: VenueBookingRefund): { text: string; tone: 'PENDING' | 'DONE' | 'ALERT' } {
  const amount = formatVenueAmount(refund.amountMinor)
  switch (refund.status) {
    case 'SUCCEEDED': return { text: `已退款 ${amount}`, tone: 'DONE' }
    case 'CLOSED':
    case 'ABNORMAL': return { text: `退款异常 ${amount}，请联系平台客服`, tone: 'ALERT' }
    default: return { text: `退款中 ${amount}`, tone: 'PENDING' }
  }
}

export interface VenueBookingView {
  tone: VenueBookingTone
  title: string
  subtitle: string
  // The round stamp over a code that can no longer be used.
  stamp: string | null
  // The line under the code.
  hint: string
}

export function describeVenueBooking(
  booking: VenueBooking,
  now: number,
  rescheduledHere = false,
): VenueBookingView {
  const tone = venueBookingTone(booking)
  const people = `一单一码，含 ${booking.people} 人`
  switch (tone) {
    case 'PENDING': {
      const remaining = venuePaymentRemainingMs(booking, now)
      return {
        tone,
        title: '待付款',
        subtitle: remaining > 0
          ? `请在 ${formatVenueCountdown(remaining)} 内付款，超时名额自动释放`
          : '付款时间已到，正在确认订单状态',
        stamp: null,
        hint: '付款成功后生成核销码',
      }
    }
    case 'LIVE':
      return {
        tone,
        title: rescheduledHere ? '改期成功 · 待到馆' : '预约成功 · 待到馆',
        subtitle: rescheduledHere ? '旧码已作废，请出示下面的新码' : '请提前 10 分钟到馆，向工作人员出示核销码',
        stamp: null,
        hint: `到馆出示二维码核销 · ${people}`,
      }
    case 'USED':
      return {
        tone,
        title: '已核销',
        subtitle: booking.checkedInAt ? `${formatVenueShortTime(booking.checkedInAt)} 到馆核销完成` : '到馆核销完成',
        stamp: '已核销',
        hint: '这张码已使用，不能重复核销',
      }
    case 'EXPIRED':
      return {
        tone,
        title: '已过期',
        subtitle: booking.payment.status === 'PAID'
          ? '场次已结束，没有到馆核销，费用不退'
          : '场次已结束，没有到馆核销',
        stamp: '已过期',
        hint: '这张码已失效',
      }
    case 'CANCELLED': {
      const refund = booking.refund
      if (!refund) {
        return { tone, title: '已取消', subtitle: '免费预约，不涉及退款', stamp: '已失效', hint: '这张码已失效' }
      }
      const done = refund.status === 'SUCCEEDED'
      return {
        tone,
        title: done ? '已取消 · 已退款' : '已取消 · 退款中',
        subtitle: done
          ? `${formatVenueAmount(refund.amountMinor)} 已原路退回`
          : `${formatVenueAmount(refund.amountMinor)} 原路退回，到账时间以微信支付为准`,
        stamp: '已失效',
        hint: '这张码已失效',
      }
    }
    case 'CLOSED':
      return {
        tone,
        title: '已关闭，未付款',
        subtitle: '没有扣款，名额已释放',
        stamp: '已关闭',
        hint: '没有生成核销码',
      }
  }
}

export type VenueStepState = 'DONE' | 'TODO' | 'ENDED'

export interface VenueBookingSteps {
  steps: Array<{ label: string; state: VenueStepState }>
  // How far the orange line runs, as a share of the line between the first and the last step.
  doneShare: number
}

// 已支付 → 预约成功 → 到馆核销 (a free booking has no payment step).
export function describeVenueBookingSteps(booking: VenueBooking): VenueBookingSteps {
  const tone = venueBookingTone(booking)
  const paid = booking.payment.status !== 'FREE'
  const lastLabel = ({
    PENDING: '到馆核销',
    LIVE: '到馆核销',
    USED: '已核销',
    EXPIRED: '已过期',
    CANCELLED: '已取消',
    CLOSED: '已关闭',
  } as const)[tone]
  const lastState: VenueStepState = tone === 'USED' ? 'DONE' : tone === 'PENDING' || tone === 'LIVE' ? 'TODO' : 'ENDED'
  if (tone === 'PENDING' || tone === 'CLOSED') {
    return {
      steps: [
        { label: tone === 'PENDING' ? '待付款' : '未付款', state: tone === 'CLOSED' ? 'ENDED' : 'TODO' },
        { label: '预约成功', state: 'TODO' },
        { label: lastLabel, state: 'TODO' },
      ],
      doneShare: 0,
    }
  }
  const steps: VenueBookingSteps['steps'] = [
    ...(paid ? [{ label: '已支付', state: 'DONE' as const }] : []),
    { label: '预约成功', state: 'DONE' },
    { label: lastLabel, state: lastState },
  ]
  const done = steps.filter((step) => step.state === 'DONE').length
  return { steps, doneShare: steps.length > 1 ? Math.max(0, (done - 1) / (steps.length - 1)) : 0 }
}

// The lead of the cancel sheet.
export function describeVenueCancel(booking: VenueBooking): { lead: string; refund: string | null } {
  if (booking.status === 'PENDING_PAYMENT') {
    return { lead: '还没有付款，取消后预约关闭、名额释放，不会扣款。', refund: null }
  }
  if (booking.payment.status === 'FREE') {
    return { lead: '取消后名额释放，核销码立即失效。', refund: null }
  }
  return {
    lead: '取消后名额释放，核销码立即失效，整单原路全额退款。',
    refund: formatVenueAmount(booking.payment.amountMinor),
  }
}

// One session in the reschedule sheet: only open sessions of the same project with room for everyone.
export function describeRescheduleSlot(
  session: VenueSession,
  booking: VenueBooking,
): { selectable: boolean; sub: string } {
  if (session.sessionId === booking.session.sessionId) return { selectable: false, sub: '当前场次' }
  if (session.status === 'ENDED') return { selectable: false, sub: '已结束' }
  if (session.status === 'FULL' || session.seatsLeft === 0) return { selectable: false, sub: '已满' }
  if (session.seatsLeft < booking.people) return { selectable: false, sub: `余 ${session.seatsLeft}，不够` }
  return { selectable: true, sub: `余 ${session.seatsLeft}` }
}

// --- 我的 → 到馆预约 ---

export const VENUE_BOOKING_TABS: ReadonlyArray<{ bucket: VenueBookingBucket; label: string }> = [
  { bucket: 'UPCOMING', label: '待到馆' },
  { bucket: 'FINISHED', label: '已完成' },
  { bucket: 'CANCELLED', label: '已取消' },
]

export interface VenueBookingCard {
  statusText: string
  statusTone: 'PENDING' | 'LIVE' | 'DONE' | 'OFF'
  title: string
  when: string
  money: string
}

export function describeVenueBookingCard(booking: VenueBooking, now: number): VenueBookingCard {
  const tone = venueBookingTone(booking)
  const statusText = ({
    PENDING: '待付款',
    LIVE: '待到馆',
    USED: '已核销',
    EXPIRED: '已过期',
    CANCELLED: '已取消',
    CLOSED: '已关闭',
  } as const)[tone]
  const statusTone: VenueBookingCard['statusTone'] = tone === 'PENDING'
    ? 'PENDING'
    : tone === 'LIVE' ? 'LIVE' : tone === 'USED' ? 'DONE' : 'OFF'
  const amount = formatVenueAmount(booking.payment.amountMinor)
  let money: string
  if (booking.payment.status === 'FREE') {
    money = '免费'
  } else if (tone === 'PENDING') {
    const remaining = venuePaymentRemainingMs(booking, now)
    money = remaining > 0 ? `${amount} 待付款 · ${formatVenueCountdown(remaining)}` : `${amount} 待付款`
  } else if (tone === 'CLOSED') {
    money = '未付款，名额已释放'
  } else if (booking.refund) {
    money = describeVenueRefund(booking.refund).text
  } else {
    money = `${amount} 已支付`
  }
  return {
    statusText,
    statusTone,
    title: `${booking.project.name} · ${booking.people} 人`,
    when: `${formatVenueDay(booking.session.startsAt, now).replace(/ 周.$/, '')} ${formatBeijingTime(booking.session.startsAt)}`,
    money,
  }
}
