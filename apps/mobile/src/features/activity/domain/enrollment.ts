import type { ActivityImage, ActivityLocation, ActivityType } from './activity'

// 契约 contracts/openapi-activity.yaml 1.1.0：参与人、报名单、凭证。

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

export type TicketStatus = 'ACTIVE' | 'CHECKED_IN' | 'EXPIRED' | 'CANCELLED'

export interface EnrollmentTicket {
  ticketId: string
  participantId: string
  participantName: string
  relation: ParticipantRelation
  age: number | null
  ticketCode: string
  status: TicketStatus
  checkedInAt: string | null
  cancelledAt: string | null
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
}

export interface EnrollmentPage {
  total: number
  hasMore: boolean
  items: Enrollment[]
}

export const ENROLLMENT_PAGE_SIZE = 10

// The server allows this many people on one enrolment.
export const MAX_ENROLLMENT_PARTICIPANTS = 10
