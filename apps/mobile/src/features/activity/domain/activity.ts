// Mirrors contracts/openapi-activity.yaml. Statuses are worked out by the server; the app only shows them.

export type ActivityType = 'CONTEST' | 'TRAINING' | 'STUDY_TOUR' | 'OPEN_DAY'

export type ActivityPriceMode = 'PER_PERSON' | 'FREE' | 'INQUIRY'

export type ActivityEnrollmentStatus =
  | 'OPEN'
  | 'FULL_WAITLIST'
  | 'FULL'
  | 'CLOSED'
  | 'ENDED'

export interface ActivityImage {
  url: string
  alt: string
}

export interface ActivityPrice {
  mode: ActivityPriceMode
  amountMinor: number | null
}

export interface ActivityAgeRange {
  minAge: number | null
  maxAge: number | null
}

export interface ActivityNextSession {
  sessionId: string
  startsAt: string
  capacity: number
  seatsLeft: number
}

export interface ActivityCard {
  activityId: string
  type: ActivityType
  title: string
  coverImage: ActivityImage | null
  venueName: string
  scheduleNote: string | null
  price: ActivityPrice
  ageRange: ActivityAgeRange
  enrollmentStatus: ActivityEnrollmentStatus
  nextSession: ActivityNextSession | null
}

export interface ActivityPage {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: ActivityCard[]
}

export interface ActivityListQuery {
  cityCode: string
  type: ActivityType | null
  // Local date (Asia/Shanghai) as YYYY-MM-DD, or null for every date.
  date: string | null
  enrollingOnly: boolean
  freeOnly: boolean
}

export interface ActivitySession {
  sessionId: string
  label: string | null
  startsAt: string
  endsAt: string | null
  checkInOpensAt: string | null
  enrollmentClosesAt: string
  capacity: number
  seatsLeft: number
  status: ActivityEnrollmentStatus
}

export interface ActivityOrganizer {
  organizationId: string
  name: string
}

export interface ActivityLocation {
  name: string
  address: string
  latitude: number | null
  longitude: number | null
}

export interface ActivityDetail {
  activityId: string
  type: ActivityType
  title: string
  subtitle: string | null
  coverImage: ActivityImage | null
  price: ActivityPrice
  ageRange: ActivityAgeRange
  enrollmentStatus: ActivityEnrollmentStatus
  organizer: ActivityOrganizer
  coOrganizer: ActivityOrganizer | null
  location: ActivityLocation
  scheduleNote: string | null
  eligibilityNote: string | null
  seatNote: string | null
  refundNote: string | null
  waitlistAllowed: boolean
  teamEnrollmentAllowed: boolean
  sessions: ActivitySession[]
}

export const ACTIVITY_PAGE_SIZE = 10
