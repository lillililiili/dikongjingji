import type {
  VenueBookInput,
  VenueBooking,
  VenueBookingBucket,
  VenueBookingPage,
  VenueParticipantList,
} from '../domain/venue-booking'

type VenueBookingRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The server sends null for empty fields; a field it leaves out reads the same.
type VenueBookingDto = Omit<VenueBooking, 'refund' | 'code' | 'checkedInAt' | 'cancelledAt' | 'payment' | 'venue'> & {
  venue: Omit<VenueBooking['venue'], 'phone'> & { phone?: string | null }
  code?: string | null
  refund?: VenueBooking['refund'] | null
  checkedInAt?: string | null
  cancelledAt?: string | null
  payment: Omit<VenueBooking['payment'], 'tradeOrderId' | 'payBefore'> & {
    tradeOrderId?: string | null
    payBefore?: string | null
  }
}

interface VenueBookingPageDto {
  total: number
  hasMore: boolean
  items: VenueBookingDto[]
}

export interface VenueBookingApi {
  loadParticipants(accessToken: string, sessionId: string): Promise<VenueParticipantList>
  book(accessToken: string, idempotencyKey: string, input: VenueBookInput): Promise<VenueBooking>
  list(accessToken: string, bucket: VenueBookingBucket, page: number, size: number): Promise<VenueBookingPage>
  read(accessToken: string, bookingId: string): Promise<VenueBooking>
  // `version` is the booking's version as last read; it goes out as If-Match.
  reschedule(accessToken: string, bookingId: string, version: number, sessionId: string): Promise<VenueBooking>
  cancel(accessToken: string, bookingId: string): Promise<VenueBooking>
}

// Every call carries the access token it was given; a rejected token comes back to the page as an
// error (status 401) and the page decides where the user goes.
export function createVenueBookingApi(request: VenueBookingRequest): VenueBookingApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const bookingUrl = (bookingId: string) => `/api/v1/venue-bookings/${encodeURIComponent(bookingId)}`

  return {
    async loadParticipants(accessToken, sessionId) {
      const dto = await request<VenueParticipantList>({
        url: `/api/v1/venue-sessions/${encodeURIComponent(sessionId)}/participants`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        sessionId: dto.sessionId,
        seatsLeft: dto.seatsLeft,
        maxPeople: dto.maxPeople,
        items: dto.items.map((item) => ({
          participantId: item.participantId,
          relation: item.relation,
          name: item.name,
          ageAtSession: item.ageAtSession ?? null,
          eligible: Boolean(item.eligible),
          reason: item.reason,
        })),
      }
    },
    async book(accessToken, idempotencyKey, input) {
      return toVenueBooking(await request<VenueBookingDto>({
        url: '/api/v1/venue-bookings',
        method: 'POST',
        data: {
          sessionId: input.sessionId,
          participantIds: input.participantIds,
          expectedAmountMinor: input.expectedAmountMinor,
          noticeAgreed: true,
        },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async list(accessToken, bucket, page, size) {
      const dto = await request<VenueBookingPageDto>({
        url: `/api/v1/venue-bookings?bucket=${bucket}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toVenueBooking) }
    },
    async read(accessToken, bookingId) {
      return toVenueBooking(await request<VenueBookingDto>({
        url: bookingUrl(bookingId),
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async reschedule(accessToken, bookingId, version, sessionId) {
      return toVenueBooking(await request<VenueBookingDto>({
        url: `${bookingUrl(bookingId)}/reschedule`,
        method: 'POST',
        data: { sessionId },
        // The ETag of a booking is its version in quotes.
        headers: { ...auth(accessToken), 'If-Match': `"${version}"` },
      }))
    },
    async cancel(accessToken, bookingId) {
      return toVenueBooking(await request<VenueBookingDto>({
        url: `${bookingUrl(bookingId)}/cancel`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
  }
}

export function toVenueBooking(dto: VenueBookingDto): VenueBooking {
  return {
    bookingId: dto.bookingId,
    status: dto.status,
    venue: {
      venueId: dto.venue.venueId,
      name: dto.venue.name,
      cityCode: dto.venue.cityCode,
      address: dto.venue.address,
      phone: dto.venue.phone ?? null,
    },
    project: {
      projectId: dto.project.projectId,
      name: dto.project.name,
      durationMinutes: dto.project.durationMinutes,
    },
    session: {
      sessionId: dto.session.sessionId,
      startsAt: dto.session.startsAt,
      endsAt: dto.session.endsAt,
    },
    people: dto.people,
    participants: dto.participants.map((person) => ({
      participantId: person.participantId,
      name: person.name,
      relation: person.relation,
      age: person.age ?? null,
    })),
    code: dto.code ?? null,
    payment: {
      status: dto.payment.status,
      tradeOrderId: dto.payment.tradeOrderId ?? null,
      amountMinor: dto.payment.amountMinor,
      unitAmountMinor: dto.payment.unitAmountMinor,
      payBefore: dto.payment.payBefore ?? null,
    },
    refund: dto.refund ? { status: dto.refund.status, amountMinor: dto.refund.amountMinor } : null,
    createdAt: dto.createdAt,
    checkedInAt: dto.checkedInAt ?? null,
    cancelledAt: dto.cancelledAt ?? null,
    cancellable: Boolean(dto.cancellable),
    reschedulable: Boolean(dto.reschedulable),
    version: dto.version,
  }
}
