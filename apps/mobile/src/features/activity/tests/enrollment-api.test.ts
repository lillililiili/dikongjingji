import { describe, expect, it, vi } from 'vitest'

import { createEnrollmentApi } from '../data/enrollment-api'

const TOKEN = 'token-1'
const AUTH = { Authorization: 'Bearer token-1' }

const ENROLLMENT_DTO = {
  enrollmentId: 'e1',
  status: 'UPCOMING',
  activity: {
    activityId: 'a1',
    type: 'OPEN_DAY',
    title: '无人机开放体验日',
    cityCode: '370500',
    organizerName: '东营低空与机器人体验馆',
  },
  session: { sessionId: 's1', startsAt: '2026-11-29T06:00:00Z' },
  location: { name: '体验馆', address: '示例路 66 号' },
  createdAt: '2026-11-18T01:00:00Z',
  cancellableUntil: '2026-11-29T06:00:00Z',
  cancellable: true,
  tickets: [{
    ticketId: 't1',
    participantId: 'p1',
    participantName: '王女士',
    relation: 'SELF',
    ticketCode: 'ACT-1234-5678-9ABC',
    status: 'ACTIVE',
  }],
}

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createEnrollmentApi(request), request }
}

describe('enrolment api', () => {
  it('reads participants for one session with the caller\'s token', async () => {
    const { api, request } = apiReturning({
      items: [{ participantId: 'p1', relation: 'SELF', name: '王女士', photoUseAuthorized: false }],
    })

    const people = await api.loadParticipants(TOKEN, 'session 1')

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/activity-participants?sessionId=session%201',
      method: 'GET',
      headers: AUTH,
    })
    // Fields the server leaves out arrive as null.
    expect(people).toEqual([{
      participantId: 'p1',
      relation: 'SELF',
      name: '王女士',
      birthMonth: null,
      age: null,
      photoUseAuthorized: false,
      eligibility: null,
    }])
  })

  it('reads all participants without a session', async () => {
    const { api, request } = apiReturning({ items: [] })
    await api.loadParticipants(TOKEN)
    expect(request.mock.calls[0][0].url).toBe('/api/v1/activity-participants')
  })

  it('adds, renames, authorises and removes a child', async () => {
    const child = { participantId: 'p 2', relation: 'CHILD', name: '王小禾', birthMonth: '2015-01', age: 11, photoUseAuthorized: true }
    const { api, request } = apiReturning(child)

    await api.addChild(TOKEN, { name: '王小禾', birthMonth: '2015-01', guardianConfirmed: true })
    await api.renameParticipant(TOKEN, 'p 2', '王小和')
    await api.setPhotoUse(TOKEN, 'p 2', true)
    await api.deleteChild(TOKEN, 'p 2')

    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data])).toEqual([
      ['POST', '/api/v1/activity-participants', { name: '王小禾', birthMonth: '2015-01', guardianConfirmed: true }],
      ['PUT', '/api/v1/activity-participants/p%202', { name: '王小和' }],
      ['PUT', '/api/v1/activity-participants/p%202/photo-use', { authorized: true }],
      ['DELETE', '/api/v1/activity-participants/p%202', undefined],
    ])
    expect(request.mock.calls.every(([input]) => input.headers.Authorization === AUTH.Authorization)).toBe(true)
  })

  it('submits an enrolment with its idempotency key', async () => {
    const { api, request } = apiReturning(ENROLLMENT_DTO)
    const input = { activityId: 'a1', sessionId: 's1', participantIds: ['p1'], noticeAgreed: true }

    const enrollment = await api.enroll(TOKEN, 'key-1', input)

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/activity-enrollments',
      method: 'POST',
      data: input,
      headers: { ...AUTH, 'Idempotency-Key': 'key-1' },
    })
    expect(enrollment.session).toEqual({
      sessionId: 's1', label: null, startsAt: '2026-11-29T06:00:00Z', endsAt: null, checkInOpensAt: null,
    })
    expect(enrollment.location).toEqual({ name: '体验馆', address: '示例路 66 号', latitude: null, longitude: null })
    expect(enrollment.activity.coverImage).toBeNull()
    expect(enrollment.tickets[0]).toMatchObject({ age: null, checkedInAt: null, cancelledAt: null })
  })

  it('reads an enrolment of an older server as free, without refunds', async () => {
    const enrollment = await apiReturning(ENROLLMENT_DTO).api.loadEnrollment(TOKEN, 'e1')
    expect(enrollment.payment).toEqual({
      status: 'FREE', tradeOrderId: null, amountMinor: null, unitAmountMinor: null, payBefore: null,
    })
    expect(enrollment.tickets[0].refund).toBeNull()
  })

  it('sends the amount the page showed for a paid activity and reads the payment and refunds', async () => {
    const paidDto = {
      ...ENROLLMENT_DTO,
      payment: {
        status: 'PAID', tradeOrderId: 'o1', amountMinor: 33600, unitAmountMinor: 16800, payBefore: null,
      },
      tickets: [
        { ...ENROLLMENT_DTO.tickets[0], refund: null },
        {
          ...ENROLLMENT_DTO.tickets[0],
          ticketId: 't2',
          ticketCode: null,
          status: 'CANCELLED',
          refund: { status: 'PROCESSING', amountMinor: 16800 },
        },
      ],
    }
    const { api, request } = apiReturning(paidDto)
    const input = { activityId: 'a1', sessionId: 's1', participantIds: ['p1', 'p2'], noticeAgreed: true, expectedAmountMinor: 33600 }

    const enrollment = await api.enroll(TOKEN, 'key-2', input)

    expect(request.mock.calls[0][0].data).toEqual(input)
    expect(enrollment.payment).toEqual(paidDto.payment)
    expect(enrollment.tickets[1]).toMatchObject({ ticketCode: null, refund: { status: 'PROCESSING', amountMinor: 16800 } })
  })

  it('reads a page of one status and one enrolment', async () => {
    const { api, request } = apiReturning({ page: 2, size: 10, total: 11, hasMore: false, items: [ENROLLMENT_DTO] })

    const page = await api.loadEnrollments(TOKEN, 'FINISHED', 2, 10)

    expect(request.mock.calls[0][0].url).toBe('/api/v1/activity-enrollments?status=FINISHED&page=2&size=10')
    expect(page).toMatchObject({ total: 11, hasMore: false })
    expect(page.items).toHaveLength(1)

    const single = apiReturning(ENROLLMENT_DTO)
    await single.api.loadEnrollment(TOKEN, 'e 1')
    expect(single.request.mock.calls[0][0].url).toBe('/api/v1/activity-enrollments/e%201')
  })

  it('cancels chosen tickets, or everything when none is named', async () => {
    const { api, request } = apiReturning(ENROLLMENT_DTO)

    await api.cancel(TOKEN, 'e1', ['t1'])
    await api.cancel(TOKEN, 'e1')

    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data])).toEqual([
      ['POST', '/api/v1/activity-enrollments/e1/cancel', { ticketIds: ['t1'] }],
      ['POST', '/api/v1/activity-enrollments/e1/cancel', {}],
    ])
  })

  // ---- 1.4.0 (S5-3) ----

  it('reads an old server\'s enrolment as an individual one without changes or results', async () => {
    const { api } = apiReturning(ENROLLMENT_DTO)
    const enrollment = await api.loadEnrollment(TOKEN, 'e1')
    expect(enrollment).toMatchObject({ kind: 'INDIVIDUAL', team: null, reschedule: null, changes: [], reschedulable: false })
    expect(enrollment.tickets[0].result).toBeNull()
  })

  it('cancels with a reason and reschedules to another session', async () => {
    const { api, request } = apiReturning({
      ...ENROLLMENT_DTO,
      kind: 'TEAM',
      team: { teamName: '东营港务物流职工子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' },
      reschedule: { previousSession: { sessionId: 's0', startsAt: '2026-11-22T06:00:00Z' }, rescheduledAt: '2026-11-20T01:00:00Z' },
      changes: [{
        changeId: 'c1', kind: 'RESCHEDULE', reason: 'PLAN_CHANGED', people: 1,
        fromSession: { sessionId: 's0', startsAt: '2026-11-22T06:00:00Z' },
        toSession: { sessionId: 's1', label: '下午', startsAt: '2026-11-29T06:00:00Z' },
        createdAt: '2026-11-20T01:00:00Z',
      }],
      reschedulable: true,
    })

    await api.cancel(TOKEN, 'e1', ['t1'], 'UNWELL')
    const moved = await api.reschedule(TOKEN, 'e1', 's1', 'PLAN_CHANGED')
    await api.reschedule(TOKEN, 'e1', 's1')

    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data])).toEqual([
      ['POST', '/api/v1/activity-enrollments/e1/cancel', { ticketIds: ['t1'], reason: 'UNWELL' }],
      ['POST', '/api/v1/activity-enrollments/e1/reschedule', { targetSessionId: 's1', reason: 'PLAN_CHANGED' }],
      ['POST', '/api/v1/activity-enrollments/e1/reschedule', { targetSessionId: 's1' }],
    ])
    expect(moved.kind).toBe('TEAM')
    expect(moved.reschedule?.previousSession).toEqual({ sessionId: 's0', label: null, startsAt: '2026-11-22T06:00:00Z' })
    expect(moved.changes[0]).toMatchObject({ kind: 'RESCHEDULE', refundStatus: null, refundAmountMinor: null })
  })

  it('reads one change with its tickets', async () => {
    const { api, request } = apiReturning({
      change: {
        changeId: 'c 1', kind: 'CANCEL', reason: null, people: 1,
        fromSession: { sessionId: 's1', label: null, startsAt: '2026-11-29T06:00:00Z' }, toSession: null,
        refundAmountMinor: 12000, refundStatus: 'SUCCEEDED', refundSucceededAt: '2026-11-21T01:10:00Z',
        createdAt: '2026-11-20T01:00:00Z',
      },
      enrollmentId: 'e1', activityTitle: '挑战赛', organizerName: '体验馆',
      tickets: [{ ticketId: 't1', participantName: '王小禾', relation: 'CHILD', issued: false, status: 'CANCELLED' }],
    })

    const detail = await api.loadChange(TOKEN, 'e1', 'c 1')

    expect(request.mock.calls[0][0].url).toBe('/api/v1/activity-enrollments/e1/changes/c%201')
    expect(detail.change.refundSucceededAt).toBe('2026-11-21T01:10:00Z')
    expect(detail.tickets[0]).toEqual({
      ticketId: 't1', participantName: '王小禾', relation: 'CHILD', age: null, issued: false, ticketCode: null, status: 'CANCELLED',
    })
    expect(detail.coverImage).toBeNull()
  })

  it('checks a roster and signs a team up with its own idempotency key', async () => {
    const { api, request } = apiReturning({
      eligibleCount: 1,
      members: [{ name: '李明轩', birthMonth: '2014-06', ageAtSession: 12, eligible: true, reason: 'ELIGIBLE' }],
    })

    const check = await api.checkTeam(TOKEN, 's1', [{ name: '李明轩', birthMonth: '2014-06' }])
    expect(check.members[0].participantId).toBeNull()
    expect(request.mock.calls[0][0]).toMatchObject({
      url: '/api/v1/activity-team-enrollments/check', method: 'POST',
      data: { sessionId: 's1', members: [{ name: '李明轩', birthMonth: '2014-06' }] },
    })

    const team = apiReturning(ENROLLMENT_DTO)
    await team.api.enrollTeam(TOKEN, 'key-1', {
      activityId: 'a1', sessionId: 's1', teamName: '队', leaderName: '王女士', leaderPhone: '13800002026',
      members: [{ participantId: 'p1' }, { name: '李明轩', birthMonth: '2014-06' }],
      noticeAgreed: true, guardianConsent: true,
    })
    expect(team.request.mock.calls[0][0]).toMatchObject({
      url: '/api/v1/activity-team-enrollments',
      method: 'POST',
      headers: { ...AUTH, 'Idempotency-Key': 'key-1' },
    })
    expect(team.request.mock.calls[0][0].data).not.toHaveProperty('expectedAmountMinor')
  })

  it('joins, reads, abandons and confirms a waitlist', async () => {
    const dto = {
      waitlistId: 'w1', status: 'OFFERED', activity: ENROLLMENT_DTO.activity,
      session: { sessionId: 's1', startsAt: '2026-11-29T06:00:00Z' },
      members: [{ participantId: 'p1', name: '王小禾', relation: 'CHILD', age: 10 }],
      people: 1, unitAmountMinor: 12000, amountMinor: 12000, holdUntil: '2026-11-20T01:15:00Z',
      abandonable: true, createdAt: '2026-11-20T01:00:00Z',
    }
    const { api, request } = apiReturning(dto)

    const joined = await api.joinWaitlist(TOKEN, 'key-2', { activityId: 'a1', sessionId: 's1', participantIds: ['p1'], noticeAgreed: true })
    await api.loadWaitlist(TOKEN, 'w 1')
    await api.abandonWaitlist(TOKEN, 'w1')
    await api.confirmWaitlist(TOKEN, 'w1', 12000)
    await api.confirmWaitlist(TOKEN, 'w1')

    expect(joined).toMatchObject({ position: null, ahead: null, tradeOrderId: null, enrollmentId: null })
    expect(joined.session).toEqual({ sessionId: 's1', label: null, startsAt: '2026-11-29T06:00:00Z', endsAt: null, venueName: null })
    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data, input.headers['Idempotency-Key']])).toEqual([
      ['POST', '/api/v1/activity-waitlists', { activityId: 'a1', sessionId: 's1', participantIds: ['p1'], noticeAgreed: true }, 'key-2'],
      ['GET', '/api/v1/activity-waitlists/w%201', undefined, undefined],
      ['POST', '/api/v1/activity-waitlists/w1/abandon', undefined, undefined],
      ['POST', '/api/v1/activity-waitlists/w1/confirm', { expectedAmountMinor: 12000 }, undefined],
      ['POST', '/api/v1/activity-waitlists/w1/confirm', {}, undefined],
    ])

    const page = apiReturning({ total: 1, hasMore: false, items: [dto] })
    await page.api.loadWaitlists(TOKEN, true, 1, 20)
    expect(page.request.mock.calls[0][0].url).toBe('/api/v1/activity-waitlists?active=true&page=1&size=20')
  })
})
