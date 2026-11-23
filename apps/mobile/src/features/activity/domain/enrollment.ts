import type { ActivityImage, ActivityLocation, ActivityType } from './activity'

// 契约 contracts/openapi-activity.yaml 1.2.0：参与人、报名单、凭证，以及收费报名的付款和退款。
// 1.4.0（S5-3）：团队报名、改期、退改记录、结果记录、候补。

// MEMBER (1.4.0): a team member the leader typed in; only shows on a team sign-up's tickets.
export type ParticipantRelation = 'SELF' | 'CHILD' | 'MEMBER'

export type ParticipantEligibilityReason =
  | 'ELIGIBLE'
  | 'UNDER_MIN_AGE'
  | 'OVER_MAX_AGE'
  | 'ALREADY_ENROLLED'

export interface ParticipantEligibility {
  eligible: boolean
  reason: ParticipantEligibilityReason
  ageAtSession: number | null
}

export interface Participant {
  participantId: string
  relation: ParticipantRelation
  name: string
  // "2015-01"; only children have one.
  birthMonth: string | null
  age: number | null
  photoUseAuthorized: boolean
  // Present only when the list was read for one session.
  eligibility: ParticipantEligibility | null
}

export type EnrollmentStatus = 'UPCOMING' | 'FINISHED' | 'CANCELLED'

export type TicketStatus = 'PENDING_PAYMENT' | 'ACTIVE' | 'CHECKED_IN' | 'EXPIRED' | 'CANCELLED'

// 免费 / 待付款 / 已付款（之后退了几位也仍是 PAID，看凭证的 refund）/ 没付款就关闭了。
export type EnrollmentPaymentStatus = 'FREE' | 'PENDING_PAYMENT' | 'PAID' | 'CLOSED'

// Same as the trade module's refund status.
export type TicketRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface TicketRefund {
  status: TicketRefundStatus
  // What this one person gets back (the unit price at sign-up).
  amountMinor: number
}

export interface EnrollmentPayment {
  status: EnrollmentPaymentStatus
  // The trade order the cashier pays; null on a free enrolment.
  tradeOrderId: string | null
  amountMinor: number | null
  unitAmountMinor: number | null
  payBefore: string | null
}

export interface EnrollmentTicket {
  ticketId: string
  participantId: string
  participantName: string
  relation: ParticipantRelation
  age: number | null
  // Null until a paid sign-up is paid, and on a ticket voided before payment.
  ticketCode: string | null
  status: TicketStatus
  checkedInAt: string | null
  cancelledAt: string | null
  refund: TicketRefund | null
  // 1.4.0: what the organiser recorded after check-in; null until then.
  result: TicketResult | null
}

export type ResultCompletion = 'COMPLETED' | 'INCOMPLETE'

export interface TicketResult {
  completion: ResultCompletion
  scoreText: string | null
  certificateNote: string | null
  recordedAt: string
}

export type EnrollmentKind = 'INDIVIDUAL' | 'TEAM'

export interface EnrollmentTeam {
  teamName: string
  leaderName: string
  // 138****2026
  leaderPhoneMasked: string | null
}

export interface EnrollmentSessionRef {
  sessionId: string
  label: string | null
  startsAt: string
}

export type ChangeKind = 'CANCEL' | 'RESCHEDULE'

export type ChangeReason = 'TIME_CONFLICT' | 'UNWELL' | 'PLAN_CHANGED' | 'OTHER'

export interface ChangeSummary {
  changeId: string
  kind: ChangeKind
  reason: ChangeReason | null
  people: number
  fromSession: EnrollmentSessionRef
  // Where a reschedule went; null on a cancellation.
  toSession: EnrollmentSessionRef | null
  // A paid cancellation's refund (from the trade module); null otherwise.
  refundAmountMinor: number | null
  refundStatus: TicketRefundStatus | null
  refundSucceededAt: string | null
  createdAt: string
}

export interface ChangeTicket {
  ticketId: string
  participantName: string
  relation: ParticipantRelation
  age: number | null
  // True: a new ticket this reschedule issued; false: one it cancelled or voided.
  issued: boolean
  ticketCode: string | null
  status: TicketStatus
}

export interface ChangeDetail {
  change: ChangeSummary
  enrollmentId: string
  activityTitle: string
  coverImage: ActivityImage | null
  organizerName: string
  tickets: ChangeTicket[]
}

export interface Enrollment {
  enrollmentId: string
  status: EnrollmentStatus
  activity: {
    activityId: string
    type: ActivityType
    title: string
    coverImage: ActivityImage | null
    cityCode: string
    organizerName: string
  }
  session: {
    sessionId: string
    label: string | null
    startsAt: string
    endsAt: string | null
    checkInOpensAt: string | null
  }
  location: ActivityLocation
  createdAt: string
  cancellableUntil: string
  cancellable: boolean
  tickets: EnrollmentTicket[]
  payment: EnrollmentPayment
  // 1.4.0
  kind: EnrollmentKind
  team: EnrollmentTeam | null
  // The last reschedule: the session before it ("原时间").
  reschedule: { previousSession: EnrollmentSessionRef, rescheduledAt: string } | null
  // Newest first.
  changes: ChangeSummary[]
  reschedulable: boolean
}

export interface EnrollmentPage {
  total: number
  hasMore: boolean
  items: Enrollment[]
}

export const ENROLLMENT_PAGE_SIZE = 10

// The server allows this many people on one enrolment.
export const MAX_ENROLLMENT_PARTICIPANTS = 10

// ---- 1.4.0 team sign-up ----

// 2–20 people on one team sign-up; the roster check takes up to 40 rows (with the ones to remove).
export const MIN_TEAM_SIZE = 2
export const MAX_TEAM_SIZE = 20
export const MAX_TEAM_ROSTER_ROWS = 40

export type TeamMemberReason = 'ELIGIBLE' | 'UNDER_MIN_AGE' | 'OVER_MAX_AGE' | 'ALREADY_ENROLLED' | 'DUPLICATED'

// An existing participant goes by id; a typed-in one by name and birth month ("2015-03").
export interface TeamMemberInput {
  participantId?: string
  name?: string
  birthMonth?: string
}

export interface TeamMemberCheck {
  participantId: string | null
  name: string
  birthMonth: string | null
  ageAtSession: number | null
  eligible: boolean
  reason: TeamMemberReason
}

export interface TeamCheck {
  members: TeamMemberCheck[]
  eligibleCount: number
}

// ---- 1.4.0 waitlist ----

export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'ENROLLED' | 'EXPIRED' | 'ENDED' | 'ABANDONED'

export interface WaitlistMember {
  participantId: string
  name: string
  relation: ParticipantRelation
  age: number | null
}

export interface Waitlist {
  waitlistId: string
  status: WaitlistStatus
  position: number | null
  ahead: number | null
  activity: Enrollment['activity']
  session: {
    sessionId: string
    label: string | null
    startsAt: string
    endsAt: string | null
    venueName: string | null
  }
  members: WaitlistMember[]
  people: number
  unitAmountMinor: number | null
  amountMinor: number | null
  // While OFFERED: the seats are held until then (the payment deadline of a paid one).
  holdUntil: string | null
  enrollmentId: string | null
  // A paid offer that went to the cashier and is not paid yet.
  tradeOrderId: string | null
  abandonable: boolean
  createdAt: string
  offeredAt: string | null
  endedAt: string | null
}

export interface WaitlistPage {
  total: number
  hasMore: boolean
  items: Waitlist[]
}
