import type { VenueBooking } from '../domain/venue-booking'

// 2026-11-20 10:00 Beijing time.
export const NOW = Date.parse('2026-11-20T02:00:00Z')

// A paid booking for two children, tomorrow 10:00–10:30, as the server sends it after payment.
export function venueBooking(overrides: Partial<VenueBooking> = {}): VenueBooking {
  return {
    bookingId: 'b-1',
    status: 'CONFIRMED',
    venue: { venueId: 'v-1', name: '东营低空与机器人体验馆', cityCode: '370500', address: '东营区示例路 66 号', phone: '0546-8000 0000' },
    project: { projectId: 'p-1', name: '机器人避障闯关', durationMinutes: 30 },
    session: { sessionId: 's-1', startsAt: '2026-11-21T02:00:00Z', endsAt: '2026-11-21T02:30:00Z' },
    people: 2,
    participants: [
      { participantId: 'c-1', name: '王小禾', relation: 'CHILD', age: 11 },
      { participantId: 'c-2', name: '王小川', relation: 'CHILD', age: 9 },
    ],
    code: 'VEN-7K2M-QX9D-4HPA',
    payment: {
      status: 'PAID',
      tradeOrderId: 'o-1',
      amountMinor: 7800,
      unitAmountMinor: 3900,
      payBefore: null,
    },
    refund: null,
    createdAt: '2026-11-20T01:50:00Z',
    checkedInAt: null,
    cancelledAt: null,
    cancellable: true,
    reschedulable: true,
    version: 2,
    ...overrides,
  }
}

// Submitted, not paid yet: 14:45 left at NOW.
export function pendingBooking(overrides: Partial<VenueBooking> = {}): VenueBooking {
  return venueBooking({
    status: 'PENDING_PAYMENT',
    code: null,
    payment: {
      status: 'PENDING_PAYMENT',
      tradeOrderId: 'o-1',
      amountMinor: 7800,
      unitAmountMinor: 3900,
      payBefore: '2026-11-20T02:14:45Z',
    },
    reschedulable: false,
    ...overrides,
  })
}
