import type { ActivityImage, ActivityLocation, ActivityType } from './activity'

// 契约 contracts/openapi-activity.yaml 1.2.0：参与人、报名单、凭证，以及收费报名的付款和退款。

export type ParticipantRelation = 'SELF' | 'CHILD'

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
}

export interface EnrollmentPage {
  total: number
  hasMore: boolean
  items: Enrollment[]
}

export const ENROLLMENT_PAGE_SIZE = 10

// The server allows this many people on one enrolment.
export const MAX_ENROLLMENT_PARTICIPANTS = 10
