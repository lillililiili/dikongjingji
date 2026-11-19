import type {
  Enrollment,
  EnrollmentPage,
  EnrollmentPayment,
  EnrollmentStatus,
  EnrollmentTicket,
  Participant,
} from '../domain/enrollment'

type EnrollmentRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// A server before 1.2.0 sends neither payment nor refund; such an enrolment is free.
type EnrollmentDto = Omit<Enrollment, 'payment' | 'tickets'> & {
  payment?: Partial<EnrollmentPayment> & Pick<EnrollmentPayment, 'status'> | null
  tickets: Array<Omit<EnrollmentTicket, 'refund'> & { refund?: EnrollmentTicket['refund'] }>
}

interface EnrollmentPageDto {
  total: number
  hasMore: boolean
  items: EnrollmentDto[]
}

export interface EnrollInput {
  activityId: string
  sessionId: string
  participantIds: string[]
  noticeAgreed: boolean
  // What the page showed (unit price × people) for a paid activity; left out for a free one.
  expectedAmountMinor?: number
}

export interface EnrollmentApi {
  loadParticipants(accessToken: string, sessionId?: string): Promise<Participant[]>
  addChild(
    accessToken: string,
    input: { name: string; birthMonth: string; guardianConfirmed: boolean },
  ): Promise<Participant>
  renameParticipant(accessToken: string, participantId: string, name: string): Promise<Participant>
  setPhotoUse(accessToken: string, participantId: string, authorized: boolean): Promise<Participant>
  deleteChild(accessToken: string, participantId: string): Promise<void>
  enroll(
    accessToken: string,
    idempotencyKey: string,
    input: EnrollInput,
  ): Promise<Enrollment>
  loadEnrollments(
    accessToken: string,
    status: EnrollmentStatus,
    page: number,
    size: number,
  ): Promise<EnrollmentPage>
  loadEnrollment(accessToken: string, enrollmentId: string): Promise<Enrollment>
  cancel(accessToken: string, enrollmentId: string, ticketIds?: string[]): Promise<Enrollment>
}

// Every call carries the access token it was given and uses the plain request helper, so a
// rejected token comes back to the page as an error (status 401) and the page decides where
// the user goes. The server answers with the contract's shapes, which the domain types repeat.
export function createEnrollmentApi(request: EnrollmentRequest): EnrollmentApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const participantUrl = (participantId: string) =>
    `/api/v1/activity-participants/${encodeURIComponent(participantId)}`
  const enrollmentUrl = (enrollmentId: string) =>
    `/api/v1/activity-enrollments/${encodeURIComponent(enrollmentId)}`

  return {
    async loadParticipants(accessToken, sessionId) {
      const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
      const dto = await request<{ items: Participant[] }>({
        url: `/api/v1/activity-participants${query}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return dto.items.map(toParticipant)
    },
    async addChild(accessToken, input) {
      return toParticipant(await request<Participant>({
        url: '/api/v1/activity-participants',
        method: 'POST',
        data: input,
        headers: auth(accessToken),
      }))
    },
    async renameParticipant(accessToken, participantId, name) {
      return toParticipant(await request<Participant>({
        url: participantUrl(participantId),
        method: 'PUT',
        data: { name },
        headers: auth(accessToken),
      }))
    },
    async setPhotoUse(accessToken, participantId, authorized) {
      return toParticipant(await request<Participant>({
        url: `${participantUrl(participantId)}/photo-use`,
        method: 'PUT',
        data: { authorized },
        headers: auth(accessToken),
      }))
    },
    async deleteChild(accessToken, participantId) {
      await request<void>({
        url: participantUrl(participantId),
        method: 'DELETE',
        headers: auth(accessToken),
      })
    },
    async enroll(accessToken, idempotencyKey, input) {
      const { expectedAmountMinor, ...rest } = input
      return toEnrollment(await request<EnrollmentDto>({
        url: '/api/v1/activity-enrollments',
        method: 'POST',
        data: expectedAmountMinor === undefined ? rest : { ...rest, expectedAmountMinor },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async loadEnrollments(accessToken, status, page, size) {
      const dto = await request<EnrollmentPageDto>({
        url: `/api/v1/activity-enrollments?status=${status}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: dto.hasMore, items: dto.items.map(toEnrollment) }
    },
    async loadEnrollment(accessToken, enrollmentId) {
      return toEnrollment(await request<EnrollmentDto>({
        url: enrollmentUrl(enrollmentId),
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async cancel(accessToken, enrollmentId, ticketIds) {
      return toEnrollment(await request<EnrollmentDto>({
        url: `${enrollmentUrl(enrollmentId)}/cancel`,
        method: 'POST',
        data: ticketIds && ticketIds.length > 0 ? { ticketIds } : {},
        headers: auth(accessToken),
      }))
    },
  }
}

// Optional fields may be left out of the JSON; pages only ever see null.
function toParticipant(dto: Participant): Participant {
  return {
    participantId: dto.participantId,
    relation: dto.relation,
    name: dto.name,
    birthMonth: dto.birthMonth ?? null,
    age: dto.age ?? null,
    photoUseAuthorized: Boolean(dto.photoUseAuthorized),
    eligibility: dto.eligibility
      ? {
          eligible: dto.eligibility.eligible,
          reason: dto.eligibility.reason,
          ageAtSession: dto.eligibility.ageAtSession ?? null,
        }
      : null,
  }
}

function toEnrollment(dto: EnrollmentDto): Enrollment {
  return {
    enrollmentId: dto.enrollmentId,
    status: dto.status,
    activity: {
      activityId: dto.activity.activityId,
      type: dto.activity.type,
      title: dto.activity.title,
      coverImage: dto.activity.coverImage ?? null,
      cityCode: dto.activity.cityCode,
      organizerName: dto.activity.organizerName,
    },
    session: {
      sessionId: dto.session.sessionId,
      label: dto.session.label ?? null,
      startsAt: dto.session.startsAt,
      endsAt: dto.session.endsAt ?? null,
      checkInOpensAt: dto.session.checkInOpensAt ?? null,
    },
    location: {
      name: dto.location.name,
      address: dto.location.address,
      latitude: dto.location.latitude ?? null,
      longitude: dto.location.longitude ?? null,
    },
    createdAt: dto.createdAt,
    cancellableUntil: dto.cancellableUntil,
    cancellable: Boolean(dto.cancellable),
    tickets: dto.tickets.map((ticket) => ({
      ticketId: ticket.ticketId,
      participantId: ticket.participantId,
      participantName: ticket.participantName,
      relation: ticket.relation,
      age: ticket.age ?? null,
      ticketCode: ticket.ticketCode ?? null,
      status: ticket.status,
      checkedInAt: ticket.checkedInAt ?? null,
      cancelledAt: ticket.cancelledAt ?? null,
      refund: ticket.refund ? { status: ticket.refund.status, amountMinor: ticket.refund.amountMinor } : null,
    })),
    payment: {
      status: dto.payment?.status ?? 'FREE',
      tradeOrderId: dto.payment?.tradeOrderId ?? null,
      amountMinor: dto.payment?.amountMinor ?? null,
      unitAmountMinor: dto.payment?.unitAmountMinor ?? null,
      payBefore: dto.payment?.payBefore ?? null,
    },
  }
}
