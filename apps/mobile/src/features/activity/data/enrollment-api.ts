import type {
  ChangeDetail,
  ChangeReason,
  ChangeSummary,
  Enrollment,
  EnrollmentPage,
  EnrollmentPayment,
  EnrollmentStatus,
  EnrollmentTicket,
  Participant,
  TeamCheck,
  TeamMemberInput,
  Waitlist,
  WaitlistPage,
} from '../domain/enrollment'

type EnrollmentRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// A server before 1.2.0 sends neither payment nor refund; such an enrolment is free.
// A server before 1.4.0 sends none of kind, team, reschedule, changes, reschedulable or result.
type EnrollmentDto = Omit<Enrollment, 'payment' | 'tickets' | 'kind' | 'team' | 'reschedule' | 'changes'
  | 'reschedulable'> & {
  payment?: Partial<EnrollmentPayment> & Pick<EnrollmentPayment, 'status'> | null
  tickets: Array<Omit<EnrollmentTicket, 'refund' | 'result'> & {
    refund?: EnrollmentTicket['refund']
    result?: EnrollmentTicket['result']
  }>
  kind?: Enrollment['kind']
  team?: Enrollment['team']
  reschedule?: Enrollment['reschedule']
  changes?: ChangeSummary[]
  reschedulable?: boolean
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

export interface TeamEnrollInput {
  activityId: string
  sessionId: string
  teamName: string
  leaderName: string
  leaderPhone: string
  members: TeamMemberInput[]
  noticeAgreed: boolean
  guardianConsent: boolean
  expectedAmountMinor?: number
}

export interface JoinWaitlistInput {
  activityId: string
  sessionId: string
  participantIds: string[]
  noticeAgreed: boolean
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
  cancel(
    accessToken: string,
    enrollmentId: string,
    ticketIds?: string[],
    reason?: ChangeReason,
  ): Promise<Enrollment>
  // 1.4.0 (S5-3)
  reschedule(
    accessToken: string,
    enrollmentId: string,
    targetSessionId: string,
    reason?: ChangeReason,
  ): Promise<Enrollment>
  loadChange(accessToken: string, enrollmentId: string, changeId: string): Promise<ChangeDetail>
  checkTeam(accessToken: string, sessionId: string, members: TeamMemberInput[]): Promise<TeamCheck>
  enrollTeam(accessToken: string, idempotencyKey: string, input: TeamEnrollInput): Promise<Enrollment>
  joinWaitlist(accessToken: string, idempotencyKey: string, input: JoinWaitlistInput): Promise<Waitlist>
  loadWaitlists(accessToken: string, active: boolean, page: number, size: number): Promise<WaitlistPage>
  loadWaitlist(accessToken: string, waitlistId: string): Promise<Waitlist>
  abandonWaitlist(accessToken: string, waitlistId: string): Promise<Waitlist>
  confirmWaitlist(accessToken: string, waitlistId: string, expectedAmountMinor?: number): Promise<Waitlist>
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
    async cancel(accessToken, enrollmentId, ticketIds, reason) {
      const data: { ticketIds?: string[], reason?: ChangeReason } = {}
      if (ticketIds && ticketIds.length > 0) data.ticketIds = ticketIds
      if (reason) data.reason = reason
      return toEnrollment(await request<EnrollmentDto>({
        url: `${enrollmentUrl(enrollmentId)}/cancel`,
        method: 'POST',
        data,
        headers: auth(accessToken),
      }))
    },
    async reschedule(accessToken, enrollmentId, targetSessionId, reason) {
      return toEnrollment(await request<EnrollmentDto>({
        url: `${enrollmentUrl(enrollmentId)}/reschedule`,
        method: 'POST',
        data: reason ? { targetSessionId, reason } : { targetSessionId },
        headers: auth(accessToken),
      }))
    },
    async loadChange(accessToken, enrollmentId, changeId) {
      const dto = await request<ChangeDetail>({
        url: `${enrollmentUrl(enrollmentId)}/changes/${encodeURIComponent(changeId)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        change: toChange(dto.change),
        enrollmentId: dto.enrollmentId,
        activityTitle: dto.activityTitle,
        coverImage: dto.coverImage ?? null,
        organizerName: dto.organizerName,
        tickets: dto.tickets.map((ticket) => ({
          ticketId: ticket.ticketId,
          participantName: ticket.participantName,
          relation: ticket.relation,
          age: ticket.age ?? null,
          issued: Boolean(ticket.issued),
          ticketCode: ticket.ticketCode ?? null,
          status: ticket.status,
        })),
      }
    },
    async checkTeam(accessToken, sessionId, members) {
      const dto = await request<TeamCheck>({
        url: '/api/v1/activity-team-enrollments/check',
        method: 'POST',
        data: { sessionId, members },
        headers: auth(accessToken),
      })
      return {
        eligibleCount: dto.eligibleCount,
        members: dto.members.map((member) => ({
          participantId: member.participantId ?? null,
          name: member.name,
          birthMonth: member.birthMonth ?? null,
          ageAtSession: member.ageAtSession ?? null,
          eligible: Boolean(member.eligible),
          reason: member.reason,
        })),
      }
    },
    async enrollTeam(accessToken, idempotencyKey, input) {
      const { expectedAmountMinor, ...rest } = input
      return toEnrollment(await request<EnrollmentDto>({
        url: '/api/v1/activity-team-enrollments',
        method: 'POST',
        data: expectedAmountMinor === undefined ? rest : { ...rest, expectedAmountMinor },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async joinWaitlist(accessToken, idempotencyKey, input) {
      return toWaitlist(await request<Waitlist>({
        url: '/api/v1/activity-waitlists',
        method: 'POST',
        data: input,
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async loadWaitlists(accessToken, active, page, size) {
      const dto = await request<{ total: number, hasMore: boolean, items: Waitlist[] }>({
        url: `/api/v1/activity-waitlists?active=${active}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: dto.hasMore, items: dto.items.map(toWaitlist) }
    },
    async loadWaitlist(accessToken, waitlistId) {
      return toWaitlist(await request<Waitlist>({
        url: waitlistUrl(waitlistId),
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async abandonWaitlist(accessToken, waitlistId) {
      return toWaitlist(await request<Waitlist>({
        url: `${waitlistUrl(waitlistId)}/abandon`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async confirmWaitlist(accessToken, waitlistId, expectedAmountMinor) {
      return toWaitlist(await request<Waitlist>({
        url: `${waitlistUrl(waitlistId)}/confirm`,
        method: 'POST',
        data: expectedAmountMinor === undefined ? {} : { expectedAmountMinor },
        headers: auth(accessToken),
      }))
    },
  }
}

function waitlistUrl(waitlistId: string): string {
  return `/api/v1/activity-waitlists/${encodeURIComponent(waitlistId)}`
}

function toSessionRef(dto: ChangeSummary['fromSession']): ChangeSummary['fromSession'] {
  return { sessionId: dto.sessionId, label: dto.label ?? null, startsAt: dto.startsAt }
}

function toChange(dto: ChangeSummary): ChangeSummary {
  return {
    changeId: dto.changeId,
    kind: dto.kind,
    reason: dto.reason ?? null,
    people: dto.people,
    fromSession: toSessionRef(dto.fromSession),
    toSession: dto.toSession ? toSessionRef(dto.toSession) : null,
    refundAmountMinor: dto.refundAmountMinor ?? null,
    refundStatus: dto.refundStatus ?? null,
    refundSucceededAt: dto.refundSucceededAt ?? null,
    createdAt: dto.createdAt,
  }
}

function toWaitlist(dto: Waitlist): Waitlist {
  return {
    waitlistId: dto.waitlistId,
    status: dto.status,
    position: dto.position ?? null,
    ahead: dto.ahead ?? null,
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
      venueName: dto.session.venueName ?? null,
    },
    members: dto.members.map((member) => ({
      participantId: member.participantId,
      name: member.name,
      relation: member.relation,
      age: member.age ?? null,
    })),
    people: dto.people,
    unitAmountMinor: dto.unitAmountMinor ?? null,
    amountMinor: dto.amountMinor ?? null,
    holdUntil: dto.holdUntil ?? null,
    enrollmentId: dto.enrollmentId ?? null,
    tradeOrderId: dto.tradeOrderId ?? null,
    abandonable: Boolean(dto.abandonable),
    createdAt: dto.createdAt,
    offeredAt: dto.offeredAt ?? null,
    endedAt: dto.endedAt ?? null,
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
      result: ticket.result
        ? {
            completion: ticket.result.completion,
            scoreText: ticket.result.scoreText ?? null,
            certificateNote: ticket.result.certificateNote ?? null,
            recordedAt: ticket.result.recordedAt,
          }
        : null,
    })),
    payment: {
      status: dto.payment?.status ?? 'FREE',
      tradeOrderId: dto.payment?.tradeOrderId ?? null,
      amountMinor: dto.payment?.amountMinor ?? null,
      unitAmountMinor: dto.payment?.unitAmountMinor ?? null,
      payBefore: dto.payment?.payBefore ?? null,
    },
    kind: dto.kind ?? 'INDIVIDUAL',
    team: dto.team
      ? { teamName: dto.team.teamName, leaderName: dto.team.leaderName, leaderPhoneMasked: dto.team.leaderPhoneMasked ?? null }
      : null,
    reschedule: dto.reschedule
      ? { previousSession: toSessionRef(dto.reschedule.previousSession), rescheduledAt: dto.reschedule.rescheduledAt }
      : null,
    changes: (dto.changes ?? []).map(toChange),
    reschedulable: Boolean(dto.reschedulable),
  }
}
