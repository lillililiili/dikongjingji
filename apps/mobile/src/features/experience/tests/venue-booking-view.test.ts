import { describe, expect, it } from 'vitest'

import {
  VENUE_PAYMENT_UNAVAILABLE_TEXT,
  createVenueBookKeyHolder,
  describeRescheduleSlot,
  describeVenueBookFailure,
  describeVenueBooking,
  describeVenueBookingCard,
  describeVenueBookingSteps,
  describeVenueCancel,
  describeVenueParticipant,
  describeVenueRefund,
  formatVenueSlotLine,
  resolveVenuePayMode,
  venueBookRules,
  venuePaymentRemainingMs,
  venueTotalMinor,
} from '../application/venue-booking-view'
import type { VenueSession } from '../domain/experience'
import { NOW, pendingBooking, venueBooking } from './venue-booking-fixtures'

describe('venue booking confirm page', () => {
  it('tells free, payable and not-yet-payable projects apart', () => {
    expect(resolveVenuePayMode({ priceAmountMinor: 0, onlinePayment: false })).toBe('FREE')
    expect(resolveVenuePayMode({ priceAmountMinor: 3900, onlinePayment: true })).toBe('ONLINE_PAYMENT')
    expect(resolveVenuePayMode({ priceAmountMinor: 3900, onlinePayment: false })).toBe('PAYMENT_UNAVAILABLE')
    expect(VENUE_PAYMENT_UNAVAILABLE_TEXT).toBe('场馆暂未开通在线支付')
    expect(venueTotalMinor(3900, 3)).toBe(11700)
  })

  it('writes the session as the board does, in Beijing time', () => {
    const session = { startsAt: '2026-11-20T06:00:00Z', endsAt: '2026-11-20T06:30:00Z' }
    expect(formatVenueSlotLine(session, NOW)).toBe('今天 11/20 周五 14:00–14:30')
    expect(formatVenueSlotLine({ startsAt: '2026-11-21T02:00:00Z', endsAt: '2026-11-21T02:30:00Z' }, NOW))
      .toBe('明天 11/21 周六 10:00–10:30')
    expect(formatVenueSlotLine({ startsAt: '2026-11-23T02:00:00Z', endsAt: '2026-11-23T02:45:00Z' }, NOW))
      .toBe('11/23 周一 10:00–10:45')
  })

  it('disables people who cannot come and says why', () => {
    const base = { participantId: 'x', relation: 'CHILD' as const, name: '王小禾', ageAtSession: 11, eligible: true, reason: 'ELIGIBLE' as const }
    expect(describeVenueParticipant(base)).toEqual({ selectable: true, ageText: '11 岁', stateText: '符合年龄' })
    expect(describeVenueParticipant({ ...base, relation: 'SELF', ageAtSession: null }))
      .toEqual({ selectable: true, ageText: '', stateText: '可以预约' })
    expect(describeVenueParticipant({ ...base, ageAtSession: 5, eligible: false, reason: 'UNDER_MIN_AGE' }))
      .toEqual({ selectable: false, ageText: '5 岁', stateText: '不满项目最低年龄' })
    expect(describeVenueParticipant({ ...base, eligible: false, reason: 'ALREADY_BOOKED' }).stateText).toBe('已约过这一场')
  })

  it('promises only what §21.1 does: no reminders, no venue-side cancellation', () => {
    const paid = venueBookRules('ONLINE_PAYMENT')
    expect(paid).toContain('取消后整单原路全额退款，名额同时释放')
    expect(paid).toContain('未到馆的场次结束后会过期，费用不退')
    expect(paid.join()).not.toMatch(/提醒|场馆调整/)
    expect(venueBookRules('FREE').join()).not.toMatch(/退款|付款/)
  })

  it('names each refusal and what to read again', () => {
    const failure = (code: string, status = 409) => describeVenueBookFailure({ status, code, message: '服务端原话' })
    expect(failure('VENUE_AMOUNT_CHANGED')).toEqual({ message: '体验费有调整，已更新为最新金额，请确认后再提交', reload: 'VENUE' })
    expect(failure('VENUE_PAYMENT_UNAVAILABLE')).toEqual({ message: '场馆暂未开通在线支付', reload: 'VENUE' })
    expect(failure('VENUE_SESSION_NOT_OPEN').reload).toBe('VENUE')
    expect(failure('VENUE_SEATS_NOT_ENOUGH')).toEqual({ message: '本场余位不够，请减少人数或换一场', reload: 'PEOPLE' })
    expect(failure('VENUE_PARTICIPANT_ALREADY_BOOKED').reload).toBe('PEOPLE')
    expect(failure('VENUE_PARTICIPANT_NOT_ELIGIBLE', 422).message).toBe('有参与人不满项目的最低年龄')
    expect(failure('TRADE_CHANNEL_UNAVAILABLE', 503).message).toBe('微信支付暂不可用，请稍后再试')
    expect(failure('VENUE_IDEMPOTENCY_KEY_REUSED')).toEqual({ message: '服务端原话', reload: 'NONE' })
    expect(describeVenueBookFailure(null)).toEqual({ message: '提交失败，请稍后重试', reload: 'NONE' })
  })

  it('keeps one idempotency key per session, people and amount', () => {
    let next = 0
    const keys = createVenueBookKeyHolder(() => `k${++next}`)
    expect(keys.keyFor('s1', ['a', 'b'], 7800)).toBe('k1')
    // A retry after a timeout, people in another order: the same request.
    expect(keys.keyFor('s1', ['b', 'a'], 7800)).toBe('k1')
    // The price changed on the server: a new request.
    expect(keys.keyFor('s1', ['a', 'b'], 8000)).toBe('k2')
    expect(keys.keyFor('s2', ['a', 'b'], 8000)).toBe('k3')
  })
})

describe('venue booking code page', () => {
  it('shows a booking to be used with one code for everyone', () => {
    expect(describeVenueBooking(venueBooking(), NOW)).toEqual({
      tone: 'LIVE',
      title: '预约成功 · 待到馆',
      subtitle: '请提前 10 分钟到馆，向工作人员出示核销码',
      stamp: null,
      hint: '到馆出示二维码核销 · 一单一码，含 2 人',
    })
    expect(describeVenueBooking(venueBooking(), NOW, true)).toMatchObject({
      title: '改期成功 · 待到馆',
      subtitle: '旧码已作废，请出示下面的新码',
    })
  })

  it('counts down an unpaid booking and waits for the server when time is up', () => {
    const booking = pendingBooking()
    expect(venuePaymentRemainingMs(booking, NOW)).toBe(885_000)
    expect(describeVenueBooking(booking, NOW)).toMatchObject({
      tone: 'PENDING',
      title: '待付款',
      subtitle: '请在 14:45 内付款，超时名额自动释放',
      hint: '付款成功后生成核销码',
    })
    expect(describeVenueBooking(booking, NOW + 900_000).subtitle).toBe('付款时间已到，正在确认订单状态')
    expect(venuePaymentRemainingMs(venueBooking(), NOW)).toBe(0)
  })

  it('words the ended states, with the refund of a cancelled paid booking', () => {
    expect(describeVenueBooking(venueBooking({ status: 'CHECKED_IN', checkedInAt: '2026-11-21T01:55:00Z' }), NOW))
      .toMatchObject({ tone: 'USED', subtitle: '11/21 09:55 到馆核销完成', stamp: '已核销' })
    expect(describeVenueBooking(venueBooking({ status: 'EXPIRED', code: null }), NOW))
      .toMatchObject({ title: '已过期', subtitle: '场次已结束，没有到馆核销，费用不退', stamp: '已过期' })
    const cancelled = venueBooking({ status: 'CANCELLED', code: null, refund: { status: 'PROCESSING', amountMinor: 7800 } })
    expect(describeVenueBooking(cancelled, NOW)).toMatchObject({
      title: '已取消 · 退款中',
      subtitle: '¥78 原路退回，到账时间以微信支付为准',
      stamp: '已失效',
    })
    expect(describeVenueBooking({ ...cancelled, refund: { status: 'SUCCEEDED', amountMinor: 7800 } }, NOW))
      .toMatchObject({ title: '已取消 · 已退款', subtitle: '¥78 已原路退回' })
    expect(describeVenueBooking(pendingBooking({ status: 'CLOSED', payment: { ...pendingBooking().payment, status: 'CLOSED' } }), NOW))
      .toMatchObject({ title: '已关闭，未付款', subtitle: '没有扣款，名额已释放' })
    const free = venueBooking({ status: 'CANCELLED', payment: { status: 'FREE', tradeOrderId: null, amountMinor: 0, unitAmountMinor: 0, payBefore: null } })
    expect(describeVenueBooking(free, NOW).subtitle).toBe('免费预约，不涉及退款')
    expect(describeVenueRefund({ status: 'ABNORMAL', amountMinor: 3900 })).toEqual({ text: '退款异常 ¥39，请联系平台客服', tone: 'ALERT' })
  })

  it('draws 已支付 → 预约成功 → 到馆核销, without the payment step for a free booking', () => {
    expect(describeVenueBookingSteps(venueBooking())).toEqual({
      steps: [
        { label: '已支付', state: 'DONE' },
        { label: '预约成功', state: 'DONE' },
        { label: '到馆核销', state: 'TODO' },
      ],
      doneShare: 0.5,
    })
    expect(describeVenueBookingSteps(venueBooking({ status: 'CHECKED_IN' })).doneShare).toBe(1)
    expect(describeVenueBookingSteps(venueBooking({ status: 'CANCELLED' })).steps[2]).toEqual({ label: '已取消', state: 'ENDED' })
    expect(describeVenueBookingSteps(pendingBooking()).steps.map((step) => step.label)).toEqual(['待付款', '预约成功', '到馆核销'])
    const free = venueBooking({ payment: { status: 'FREE', tradeOrderId: null, amountMinor: 0, unitAmountMinor: 0, payBefore: null } })
    expect(describeVenueBookingSteps(free).steps.map((step) => step.label)).toEqual(['预约成功', '到馆核销'])
  })

  it('states the refund amount before a paid booking is cancelled', () => {
    expect(describeVenueCancel(venueBooking())).toEqual({ lead: '取消后名额释放，核销码立即失效，整单原路全额退款。', refund: '¥78' })
    expect(describeVenueCancel(pendingBooking())).toEqual({ lead: '还没有付款，取消后预约关闭、名额释放，不会扣款。', refund: null })
  })

  it('offers only open sessions with room for everyone in the reschedule sheet', () => {
    const booking = venueBooking()
    const session = (overrides: Partial<VenueSession>): VenueSession => ({
      sessionId: 's-2', startsAt: '2026-11-21T06:00:00Z', endsAt: '2026-11-21T06:30:00Z', capacity: 8, seatsLeft: 5, status: 'OPEN', ...overrides,
    })
    expect(describeRescheduleSlot(session({}), booking)).toEqual({ selectable: true, sub: '余 5' })
    expect(describeRescheduleSlot(session({ sessionId: 's-1' }), booking)).toEqual({ selectable: false, sub: '当前场次' })
    expect(describeRescheduleSlot(session({ seatsLeft: 1 }), booking)).toEqual({ selectable: false, sub: '余 1，不够' })
    expect(describeRescheduleSlot(session({ seatsLeft: 0, status: 'FULL' }), booking).sub).toBe('已满')
    expect(describeRescheduleSlot(session({ status: 'ENDED' }), booking).sub).toBe('已结束')
  })
})

describe('我的 → 到馆预约', () => {
  it('puts status, time, people and money on the card', () => {
    expect(describeVenueBookingCard(venueBooking(), NOW)).toEqual({
      statusText: '待到馆',
      statusTone: 'LIVE',
      title: '机器人避障闯关 · 2 人',
      when: '明天 11/21 10:00',
      money: '¥78 已支付',
    })
    expect(describeVenueBookingCard(pendingBooking(), NOW)).toMatchObject({ statusText: '待付款', money: '¥78 待付款 · 14:45' })
    expect(describeVenueBookingCard(venueBooking({ status: 'CANCELLED', refund: { status: 'SUCCEEDED', amountMinor: 7800 } }), NOW))
      .toMatchObject({ statusText: '已取消', statusTone: 'OFF', money: '已退款 ¥78' })
    expect(describeVenueBookingCard(pendingBooking({ status: 'CLOSED' }), NOW).money).toBe('未付款，名额已释放')
  })
})
