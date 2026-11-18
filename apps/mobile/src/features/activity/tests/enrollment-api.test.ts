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
})
