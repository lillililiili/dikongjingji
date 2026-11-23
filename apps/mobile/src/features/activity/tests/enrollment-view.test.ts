import { describe, expect, it } from 'vitest'

import {
  cancelRefundMinor,
  childBirthMonthRange,
  createEnrollmentKey,
  createEnrollmentKeyHolder,
  describeCancelConfirm,
  describeEnrollmentCard,
  describeParticipantAge,
  describeParticipantState,
  describeTicket,
  describeTicketRefund,
  describeTicketSteps,
  enrollmentNoticeLead,
  enrollmentPriceText,
  formatBirthMonth,
  formatPayCountdown,
  paymentRemainingMs,
  pickInitialTicket,
} from '../application/enrollment-view'
import type {
  Enrollment,
  EnrollmentTicket,
  Participant,
  ParticipantEligibilityReason,
} from '../domain/enrollment'

function person(
  relation: Participant['relation'],
  reason: ParticipantEligibilityReason | null,
  ageAtSession: number | null = null,
): Participant {
  return {
    participantId: `${relation}-${reason}`,
    relation,
    name: relation === 'SELF' ? '王女士' : '王小禾',
    birthMonth: relation === 'CHILD' ? '2015-01' : null,
    age: relation === 'CHILD' ? 11 : null,
    photoUseAuthorized: false,
    eligibility: reason ? { eligible: reason === 'ELIGIBLE', reason, ageAtSession } : null,
  }
}

function ticket(status: EnrollmentTicket['status'], name = '王小禾'): EnrollmentTicket {
  return {
    ticketId: `ticket-${name}-${status}`,
    participantId: `p-${name}`,
    participantName: name,
    relation: 'CHILD',
    age: 11,
    ticketCode: 'ACT-1234-5678-9ABC',
    status,
    checkedInAt: status === 'CHECKED_IN' ? '2026-11-29T05:42:00Z' : null,
    cancelledAt: status === 'CANCELLED' ? '2026-11-20T02:00:00Z' : null,
    refund: null,
    result: null,
  }
}

const FREE: Enrollment['payment'] = {
  status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null,
}

// Two people at ¥168, paid.
const PAID: Enrollment['payment'] = {
  status: 'PAID', tradeOrderId: 'o1', amountMinor: 33600, unitAmountMinor: 16800, payBefore: null,
}

// 2026-11-19 10:00 Beijing time; the payment is due 14:45 later.
const NOW = Date.parse('2026-11-19T02:00:00Z')
const UNPAID: Enrollment['payment'] = {
  status: 'PENDING_PAYMENT', tradeOrderId: 'o1', amountMinor: 33600, unitAmountMinor: 16800, payBefore: '2026-11-19T02:14:45Z',
}

function withPayment(base: Enrollment, payment: Enrollment['payment']): Enrollment {
  return { ...base, payment }
}

function refunded(base: EnrollmentTicket, status: NonNullable<EnrollmentTicket['refund']>['status']): EnrollmentTicket {
  return { ...base, ticketCode: null, refund: { status, amountMinor: 16800 } }
}

function enrollment(
  status: Enrollment['status'],
  tickets: EnrollmentTicket[],
  cancellable = false,
): Enrollment {
  return {
    enrollmentId: 'e1',
    status,
    activity: {
      activityId: 'a1',
      type: 'OPEN_DAY',
      title: '无人机开放体验日',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    session: {
      sessionId: 's1',
      label: '下午',
      // 14:00 on 29 November in Beijing.
      startsAt: '2026-11-29T06:00:00Z',
      endsAt: '2026-11-29T08:00:00Z',
      checkInOpensAt: '2026-11-29T05:30:00Z',
    },
    location: { name: '体验馆', address: '示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-18T01:00:00Z',
    cancellableUntil: '2026-11-29T06:00:00Z',
    cancellable,
    tickets,
    payment: FREE,
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: false,
  }
}

describe('participant wording', () => {
  const kids = { minAge: 8, maxAge: 15 }
  const anyone = { minAge: null, maxAge: null }

  it('says why someone cannot be chosen', () => {
    expect(describeParticipantState(person('CHILD', 'UNDER_MIN_AGE'), kids))
      .toEqual({ selectable: false, text: '未满 8 岁' })
    expect(describeParticipantState(person('CHILD', 'OVER_MAX_AGE'), kids))
      .toEqual({ selectable: false, text: '超过 15 岁' })
    expect(describeParticipantState(person('CHILD', 'ALREADY_ENROLLED'), kids))
      .toEqual({ selectable: false, text: '已报名本场' })
  })

  it('tells the account holder that a parent only comes along', () => {
    expect(describeParticipantState(person('SELF', 'OVER_MAX_AGE'), kids))
      .toEqual({ selectable: false, text: '陪同不用报名' })
  })

  it('mentions age only when the activity has an age range', () => {
    expect(describeParticipantState(person('CHILD', 'ELIGIBLE'), kids))
      .toEqual({ selectable: true, text: '符合年龄' })
    expect(describeParticipantState(person('SELF', 'ELIGIBLE'), anyone))
      .toEqual({ selectable: true, text: '可报名' })
  })

  it('treats a list read without a session as selectable', () => {
    expect(describeParticipantState(person('CHILD', null), anyone).selectable).toBe(true)
  })

  it('shows the age on the day of the session, and none for the account holder', () => {
    expect(describeParticipantAge(person('CHILD', 'ELIGIBLE', 12))).toBe('12 岁')
    expect(describeParticipantAge(person('CHILD', null))).toBe('11 岁')
    expect(describeParticipantAge(person('SELF', 'ELIGIBLE'))).toBe('')
  })

  it('lets a guardian speak only when a child is signed up', () => {
    expect(enrollmentNoticeLead([person('SELF', 'ELIGIBLE')])).toBe('我已阅读并同意')
    expect(enrollmentNoticeLead([person('SELF', 'ELIGIBLE'), person('CHILD', 'ELIGIBLE')]))
      .toBe('我是参与人监护人，已阅读并同意')
  })

  it('formats a birth month', () => {
    expect(formatBirthMonth('2015-01')).toBe('2015 年 1 月')
    expect(formatBirthMonth('2019-11')).toBe('2019 年 11 月')
  })

  it('offers only the months of someone under 18', () => {
    expect(childBirthMonthRange(new Date(2026, 10, 17))).toEqual({ start: '2008-12', end: '2026-11' })
    expect(childBirthMonthRange(new Date(2026, 11, 5))).toEqual({ start: '2009-01', end: '2026-12' })
    // 11/30 20:00 UTC is still November on the device (New York) but already December in Beijing.
    expect(childBirthMonthRange(new Date('2026-11-30T20:00:00Z'))).toEqual({ start: '2009-01', end: '2026-12' })
  })
})

describe('ticket wording', () => {
  it('describes a ticket that can still be used', () => {
    const live = ticket('ACTIVE')
    expect(describeTicket(live, enrollment('UPCOMING', [live]))).toMatchObject({
      tone: 'LIVE',
      title: '报名成功 · 待参加',
      signText: '待签到 · 13:30 起',
      stamp: null,
      checkInStep: { label: '签到', state: 'TODO' },
    })
  })

  it('describes a checked-in ticket with the time it was used', () => {
    const used = ticket('CHECKED_IN')
    expect(describeTicket(used, enrollment('UPCOMING', [used]))).toMatchObject({
      tone: 'USED',
      title: '已签到',
      signText: '已签到 · 13:42',
      hint: '已于 11/29 13:42 核销，不能重复使用',
      stamp: '已核销',
      checkInStep: { label: '签到', state: 'DONE' },
    })
  })

  it('describes tickets that can no longer be used', () => {
    const expired = ticket('EXPIRED')
    const cancelled = ticket('CANCELLED')
    expect(describeTicket(expired, enrollment('FINISHED', [expired]))).toMatchObject({
      tone: 'EXPIRED', title: '已过期', stamp: '已过期', checkInStep: { label: '未签到', state: 'ENDED' },
    })
    expect(describeTicket(cancelled, enrollment('CANCELLED', [cancelled]))).toMatchObject({
      tone: 'CANCELLED', title: '报名已取消', stamp: '已取消', checkInStep: { label: '已取消', state: 'ENDED' },
    })
  })

  it('opens on a ticket that can still be used', () => {
    const cancelled = ticket('CANCELLED', '王小川')
    const live = ticket('ACTIVE', '王小禾')
    expect(pickInitialTicket(enrollment('UPCOMING', [cancelled, live]))).toBe(live)
    expect(pickInitialTicket(enrollment('CANCELLED', [cancelled]))).toBe(cancelled)
    expect(pickInitialTicket(enrollment('CANCELLED', []))).toBeNull()
  })
})

describe('enrolment card', () => {
  it('lists who still goes and until when it can be cancelled', () => {
    const card = describeEnrollmentCard(enrollment(
      'UPCOMING', [ticket('ACTIVE', '王小禾'), ticket('CANCELLED', '王小川')], true))
    expect(card).toEqual({
      statusText: '待参加',
      statusTone: 'UPCOMING',
      who: '参与人：王小禾',
      when: '11/29 14:00 · 下午',
      note: '11/29 14:00 前可取消',
      noteTone: 'PLAIN',
    })
  })

  it('tells attended from missed once the session is over', () => {
    expect(describeEnrollmentCard(enrollment('FINISHED', [ticket('CHECKED_IN'), ticket('EXPIRED', '王小川')])))
      .toMatchObject({ statusText: '已完成', statusTone: 'DONE', note: '1 人已签到' })
    expect(describeEnrollmentCard(enrollment('FINISHED', [ticket('EXPIRED')])))
      .toMatchObject({ statusText: '未到场', statusTone: 'MUTED', note: '没有签到记录' })
  })

  it('keeps everyone on a cancelled card', () => {
    expect(describeEnrollmentCard(enrollment('CANCELLED', [ticket('CANCELLED', '王小禾'), ticket('CANCELLED', '王小川')])))
      .toMatchObject({ statusText: '已取消', statusTone: 'MUTED', who: '参与人：王小禾、王小川', note: '' })
  })
})

describe('paid sign-up', () => {
  it('shows an unpaid ticket without a code and opens on it', () => {
    const unpaid = { ...ticket('PENDING_PAYMENT'), ticketCode: null }
    const record = withPayment(enrollment('UPCOMING', [unpaid], true), UNPAID)
    expect(describeTicket(unpaid, record)).toMatchObject({ tone: 'PENDING', title: '待付款', stamp: null })
    expect(pickInitialTicket(record)).toBe(unpaid)
    expect(describeTicketSteps(unpaid, record)).toEqual({
      steps: [
        { label: '待付款', state: 'TODO' },
        { label: '报名成功', state: 'TODO' },
        { label: '签到', state: 'TODO' },
        { label: '完成', state: 'TODO' },
      ],
      doneWidth: '0',
    })
  })

  it('counts down to the deadline and never below zero', () => {
    const record = withPayment(enrollment('UPCOMING', [], true), UNPAID)
    expect(formatPayCountdown(paymentRemainingMs(record, NOW))).toBe('14:45')
    expect(paymentRemainingMs(record, NOW + 20 * 60_000)).toBeLessThan(0)
    expect(formatPayCountdown(paymentRemainingMs(record, NOW + 20 * 60_000))).toBe('00:00')
    // Only an unpaid sign-up has a deadline.
    expect(paymentRemainingMs(withPayment(record, PAID), NOW)).toBe(0)
  })

  it('tells a sign-up closed before payment from one cancelled after it', () => {
    const voided = { ...ticket('CANCELLED'), ticketCode: null }
    const closed = withPayment(enrollment('CANCELLED', [voided]), { ...UNPAID, status: 'CLOSED' })
    expect(describeTicket(voided, closed)).toMatchObject({ tone: 'CANCELLED', title: '已关闭，未付款', stamp: '已关闭' })
    expect(describeTicketSteps(voided, closed).steps[0]).toEqual({ label: '未付款', state: 'ENDED' })

    const back = refunded(ticket('CANCELLED'), 'SUCCEEDED')
    expect(describeTicket(back, withPayment(enrollment('CANCELLED', [back]), PAID)))
      .toMatchObject({ title: '报名已取消', subtitle: '名额已释放，报名费已原路退回' })
  })

  it('words each refund by its progress', () => {
    expect(describeTicketRefund({ status: 'REQUESTED', amountMinor: 16800 })).toEqual({ text: '退款中 ¥168', tone: 'PENDING' })
    expect(describeTicketRefund({ status: 'PROCESSING', amountMinor: 16800 })).toEqual({ text: '退款中 ¥168', tone: 'PENDING' })
    expect(describeTicketRefund({ status: 'SUCCEEDED', amountMinor: 16800 })).toEqual({ text: '已退款 ¥168', tone: 'DONE' })
    expect(describeTicketRefund({ status: 'ABNORMAL', amountMinor: 16800 })).toEqual({ text: '退款异常 ¥168', tone: 'ALERT' })
    expect(describeTicketRefund({ status: 'CLOSED', amountMinor: 16800 })).toEqual({ text: '退款异常 ¥168', tone: 'ALERT' })
  })

  it('puts 已支付 in front of the steps once paid', () => {
    const live = ticket('ACTIVE')
    expect(describeTicketSteps(live, withPayment(enrollment('UPCOMING', [live], true), PAID))).toEqual({
      steps: [
        { label: '已支付', state: 'DONE' },
        { label: '报名成功', state: 'DONE' },
        { label: '签到', state: 'TODO' },
        { label: '完成', state: 'TODO' },
      ],
      doneWidth: '25.0%',
    })
    // Free keeps its three steps; a finished one runs the line to the end.
    const used = ticket('CHECKED_IN')
    expect(describeTicketSteps(used, enrollment('FINISHED', [used])).doneWidth).toBe('66.7%')
  })

  it('says how much each person gets back when cancelling', () => {
    const paid = withPayment(enrollment('UPCOMING', [ticket('ACTIVE'), ticket('ACTIVE', '王小川')], true), PAID)
    expect(cancelRefundMinor(paid, 2)).toBe(33600)
    expect(describeCancelConfirm(paid, 1)).toBe('取消后名额会让给别人，原路退回 ¥168。')
    expect(describeCancelConfirm(paid, 2)).toBe('取消后名额会让给别人，每人原路退回 ¥168，共 ¥336。')
    expect(describeCancelConfirm(withPayment(paid, UNPAID), 2)).toBe('还没有付款，取消后报名关闭、名额释放，不会扣款。')
    expect(cancelRefundMinor(withPayment(paid, FREE), 1)).toBeNull()
    expect(describeCancelConfirm(withPayment(paid, FREE), 1)).toBe('取消后名额会让给别人，需要时可以重新报名。')
  })

  it('marks unpaid, closed and refunded cards on 我的报名 and shows the amount', () => {
    const unpaid = withPayment(enrollment('UPCOMING', [{ ...ticket('PENDING_PAYMENT'), ticketCode: null }], true), UNPAID)
    expect(describeEnrollmentCard(unpaid)).toMatchObject({
      statusText: '待付款', statusTone: 'ACTION', note: '请在 11/19 10:14 前付款', noteTone: 'PENDING',
    })
    expect(enrollmentPriceText(unpaid)).toBe('¥336')

    const closed = withPayment(enrollment('CANCELLED', [ticket('CANCELLED')]), { ...UNPAID, status: 'CLOSED' })
    expect(describeEnrollmentCard(closed)).toMatchObject({ statusText: '已关闭', note: '未付款，名额已释放' })

    const halfBack = withPayment(enrollment('UPCOMING', [ticket('ACTIVE'), refunded(ticket('CANCELLED', '王小川'), 'PROCESSING')], true), PAID)
    expect(describeEnrollmentCard(halfBack)).toMatchObject({
      statusText: '待参加', who: '参与人：王小禾', note: '退款中 ¥168 · 原路退回', noteTone: 'PENDING',
    })

    const allBack = withPayment(enrollment('CANCELLED', [
      refunded(ticket('CANCELLED'), 'SUCCEEDED'), refunded(ticket('CANCELLED', '王小川'), 'SUCCEEDED'),
    ]), PAID)
    expect(describeEnrollmentCard(allBack)).toMatchObject({ statusText: '已取消', note: '¥336 已原路退回', noteTone: 'DONE' })

    const stuck = withPayment(enrollment('CANCELLED', [refunded(ticket('CANCELLED'), 'ABNORMAL')]), PAID)
    expect(describeEnrollmentCard(stuck)).toMatchObject({ note: '退款异常，平台正在人工处理', noteTone: 'ALERT' })
    expect(enrollmentPriceText(enrollment('UPCOMING', []))).toBe('免费')
  })
})

describe('idempotency key', () => {
  it('has the layout of a random UUID', () => {
    expect(createEnrollmentKey())
      .toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(createEnrollmentKey()).not.toBe(createEnrollmentKey())
  })

  it('keeps the key for the same submission and changes it with the request', () => {
    let counter = 0
    const holder = createEnrollmentKeyHolder(() => `key-${++counter}`)

    expect(holder.keyFor('s1', ['b', 'a'])).toBe('key-1')
    // The same people in another order are the same request.
    expect(holder.keyFor('s1', ['a', 'b'])).toBe('key-1')
    expect(holder.keyFor('s1', ['a'])).toBe('key-2')
    expect(holder.keyFor('s2', ['a'])).toBe('key-3')
    // A paid activity whose price changed is a new request.
    expect(holder.keyFor('s2', ['a'], 16800)).toBe('key-4')
    expect(holder.keyFor('s2', ['a'], 16800)).toBe('key-4')
    expect(holder.keyFor('s2', ['a'], 18800)).toBe('key-5')
  })
})
