import { describe, expect, it, vi } from 'vitest'

import { createVenueBookingApi } from '../data/venue-booking-api'

const TOKEN = 'token-1'
const AUTH = { Authorization: 'Bearer token-1' }

// A booking as the server sends it; optional fields left out on purpose.
const BOOKING_DTO = {
  bookingId: 'b1',
  status: 'PENDING_PAYMENT',
  venue: { venueId: 'v1', name: '东营低空与机器人体验馆', cityCode: '370500', address: '东营区示例路 66 号' },
  project: { projectId: 'p1', name: '机器人避障闯关', durationMinutes: 30 },
  session: { sessionId: 's1', startsAt: '2026-11-21T02:00:00Z', endsAt: '2026-11-21T02:30:00Z' },
  people: 1,
  participants: [{ participantId: 'c1', name: '王小禾', relation: 'CHILD', age: 11 }],
  payment: { status: 'PENDING_PAYMENT', tradeOrderId: 'o1', amountMinor: 3900, unitAmountMinor: 3900, payBefore: '2026-11-20T02:15:00Z' },
  createdAt: '2026-11-20T02:00:00Z',
  cancellable: true,
  reschedulable: false,
  version: 0,
}

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createVenueBookingApi(request), request }
}

describe('venue booking api (contract 1.1.0)', () => {
  it('reads the participants of one session with their eligibility', async () => {
    const { api, request } = apiReturning({
      sessionId: 's1',
      seatsLeft: 5,
      maxPeople: 10,
      items: [
        { participantId: 'me', relation: 'SELF', name: '王女士', ageAtSession: null, eligible: true, reason: 'ELIGIBLE' },
        { participantId: 'c1', relation: 'CHILD', name: '王小满', ageAtSession: 5, eligible: false, reason: 'UNDER_MIN_AGE' },
      ],
    })

    const list = await api.loadParticipants(TOKEN, 's1')

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/venue-sessions/s1/participants',
      method: 'GET',
      headers: AUTH,
    })
    expect(list.seatsLeft).toBe(5)
    expect(list.items[1]).toEqual({
      participantId: 'c1', relation: 'CHILD', name: '王小满', ageAtSession: 5, eligible: false, reason: 'UNDER_MIN_AGE',
    })
  })

  it('books with an idempotency key, the amount the page showed and the notice agreed', async () => {
    const { api, request } = apiReturning(BOOKING_DTO)

    const booking = await api.book(TOKEN, 'key-1', { sessionId: 's1', participantIds: ['c1'], expectedAmountMinor: 3900 })

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/venue-bookings',
      method: 'POST',
      data: { sessionId: 's1', participantIds: ['c1'], expectedAmountMinor: 3900, noticeAgreed: true },
      headers: { ...AUTH, 'Idempotency-Key': 'key-1' },
    })
    // Fields the server left out read as null, never undefined.
    expect(booking).toMatchObject({
      status: 'PENDING_PAYMENT',
      code: null,
      refund: null,
      checkedInAt: null,
      cancelledAt: null,
      venue: { cityCode: '370500', phone: null },
      payment: { tradeOrderId: 'o1', payBefore: '2026-11-20T02:15:00Z' },
    })
  })

  it('lists one bucket, reads one booking, cancels without a body', async () => {
    const { api, request } = apiReturning({ page: 1, size: 10, total: 1, hasMore: false, items: [BOOKING_DTO] })
    const page = await api.list(TOKEN, 'CANCELLED', 2, 10)
    expect(request).toHaveBeenLastCalledWith({
      url: '/api/v1/venue-bookings?bucket=CANCELLED&page=2&size=10',
      method: 'GET',
      headers: AUTH,
    })
    expect(page).toMatchObject({ total: 1, hasMore: false })
    expect(page.items[0].bookingId).toBe('b1')

    request.mockResolvedValue(BOOKING_DTO)
    await api.read(TOKEN, 'b 1')
    expect(request).toHaveBeenLastCalledWith({ url: '/api/v1/venue-bookings/b%201', method: 'GET', headers: AUTH })

    request.mockResolvedValue({ ...BOOKING_DTO, status: 'CLOSED' })
    expect((await api.cancel(TOKEN, 'b1')).status).toBe('CLOSED')
    expect(request).toHaveBeenLastCalledWith({ url: '/api/v1/venue-bookings/b1/cancel', method: 'POST', headers: AUTH })
  })

  it('reschedules with the version it read as If-Match', async () => {
    const { api, request } = apiReturning({ ...BOOKING_DTO, status: 'CONFIRMED', code: 'VEN-AAAA-BBBB-CCCC', version: 4 })

    const moved = await api.reschedule(TOKEN, 'b1', 3, 's2')

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/venue-bookings/b1/reschedule',
      method: 'POST',
      data: { sessionId: 's2' },
      headers: { ...AUTH, 'If-Match': '"3"' },
    })
    expect(moved).toMatchObject({ code: 'VEN-AAAA-BBBB-CCCC', version: 4 })
  })
})
