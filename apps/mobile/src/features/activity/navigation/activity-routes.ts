import type { EnrollmentStatus } from '../domain/enrollment'

export const ACTIVITY_DETAIL_PATH = '/pages/activity/detail'
export const ACTIVITY_ENROLL_PATH = '/pages/activity/enroll'
export const ACTIVITY_PARTICIPANTS_PATH = '/pages/activity/participants'
export const ACTIVITY_TICKET_PATH = '/pages/activity/ticket'
export const ACTIVITY_ENROLLMENTS_PATH = '/pages/mine/activity-enrollments'
// R5 #4 / #5 (2026-11-20): the order of a paid sign-up and the page that refunds some of its people.
export const ACTIVITY_ORDER_PATH = '/pages/activity/order'
export const ACTIVITY_REFUND_PATH = '/pages/activity/refund'

// Only stable ids travel in the URL; the page reads everything else from the server.
export function buildActivityDetailUrl(activityId: string, cityCode: string): string {
  return `${ACTIVITY_DETAIL_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildActivityEnrollUrl(
  activityId: string,
  sessionId: string,
  cityCode: string,
): string {
  return `${ACTIVITY_ENROLL_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&sessionId=${encodeURIComponent(sessionId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildActivityTicketUrl(enrollmentId: string): string {
  return `${ACTIVITY_TICKET_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
}

export function buildActivityEnrollmentsUrl(status: EnrollmentStatus = 'UPCOMING'): string {
  return `${ACTIVITY_ENROLLMENTS_PATH}?status=${status}`
}

export function buildActivityOrderUrl(enrollmentId: string): string {
  return `${ACTIVITY_ORDER_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
}

// `ticketId` ticks one person to start with (opened from that person's ticket); without it every
// ticket that can still be refunded is ticked, like the board EnrollRefund.
export function buildActivityRefundUrl(enrollmentId: string, ticketId?: string): string {
  return `${ACTIVITY_REFUND_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
    + (ticketId ? `&ticketId=${encodeURIComponent(ticketId)}` : '')
}
