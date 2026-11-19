// 契约 contracts/openapi-experience.yaml 1.1.0：到馆预约（E10，计划 §21）。预约和付款的状态都以服务端为准。

export type VenueParticipantRelation = 'SELF' | 'CHILD'

// 可以约 / 场次当天不满最低年龄 / 这一场已有有效（或待付款）的预约。
export type VenueEligibilityReason = 'ELIGIBLE' | 'UNDER_MIN_AGE' | 'ALREADY_BOOKED'

export interface VenueParticipant {
  // Same id as the activity sign-up's participant.
  participantId: string
  relation: VenueParticipantRelation
  name: string
  // Full years on the session's day; null for the account holder.
  ageAtSession: number | null
  eligible: boolean
  reason: VenueEligibilityReason
}

export interface VenueParticipantList {
  sessionId: string
  seatsLeft: number
  maxPeople: number
  items: VenueParticipant[]
}

// 待付款 / 待到馆 / 已核销 / 已过期（按时间算）/ 已取消（付款后或免费的取消）/ 已关闭（没付款就关闭）。
export type VenueBookingStatus =
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'CLOSED'

// 我的到馆预约的页签：待到馆（含待付款）/ 已完成 / 已取消。
export type VenueBookingBucket = 'UPCOMING' | 'FINISHED' | 'CANCELLED'

export const VENUE_BOOKING_BUCKETS: readonly VenueBookingBucket[] = ['UPCOMING', 'FINISHED', 'CANCELLED']

// 免费 / 待付款 / 已付款（之后取消退款了仍是 PAID，看 refund）/ 没付款就关闭了。
export type VenuePaymentStatus = 'FREE' | 'PENDING_PAYMENT' | 'PAID' | 'CLOSED'

// Same as the trade module's refund status.
export type VenueRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface VenueBookingPayment {
  status: VenuePaymentStatus
  // The trade order the cashier pays; null on a free booking.
  tradeOrderId: string | null
  amountMinor: number
  unitAmountMinor: number
  payBefore: string | null
}

export interface VenueBookingRefund {
  status: VenueRefundStatus
  amountMinor: number
}

export interface VenueBookedParticipant {
  participantId: string
  name: string
  relation: VenueParticipantRelation
  age: number | null
}

export interface VenueBooking {
  bookingId: string
  status: VenueBookingStatus
  // cityCode: the venue's city; its sessions (reschedule) and coordinates (map) are read with it.
  venue: { venueId: string; name: string; cityCode: string; address: string; phone: string | null }
  project: { projectId: string; name: string; durationMinutes: number }
  // The current session (the new one after a reschedule).
  session: { sessionId: string; startsAt: string; endsAt: string }
  people: number
  participants: VenueBookedParticipant[]
  // One code for the whole booking; only while it is to be used or after it was used.
  code: string | null
  payment: VenueBookingPayment
  refund: VenueBookingRefund | null
  createdAt: string
  checkedInAt: string | null
  cancelledAt: string | null
  cancellable: boolean
  reschedulable: boolean
  // Sent back as If-Match on a reschedule.
  version: number
}

export interface VenueBookingPage {
  total: number
  hasMore: boolean
  items: VenueBooking[]
}

export interface VenueBookInput {
  sessionId: string
  participantIds: string[]
  // Unit price × people as the page showed it; 0 for a free project.
  expectedAmountMinor: number
}

// What the trade order behind a paid booking says: its number and its payee. The page host reads it
// from the trade module and hands it in, so this module does not depend on trade.
export interface VenueOrderInfo {
  orderNo: string
  payeeName: string
}

export const VENUE_BOOKING_PAGE_SIZE = 10

// The server takes at most this many people on one booking.
export const MAX_VENUE_BOOKING_PEOPLE = 10
