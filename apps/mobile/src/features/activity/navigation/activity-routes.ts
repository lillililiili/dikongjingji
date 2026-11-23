import type { EnrollmentStatus } from '../domain/enrollment'

export const ACTIVITY_DETAIL_PATH = '/pages/activity/detail'
export const ACTIVITY_ENROLL_PATH = '/pages/activity/enroll'
export const ACTIVITY_PARTICIPANTS_PATH = '/pages/activity/participants'
export const ACTIVITY_TICKET_PATH = '/pages/activity/ticket'
export const ACTIVITY_ENROLLMENTS_PATH = '/pages/mine/activity-enrollments'
// R5 #4 / #5 (2026-11-20): the order of a paid sign-up and the page that refunds some of its people.
export const ACTIVITY_ORDER_PATH = '/pages/activity/order'
export const ACTIVITY_REFUND_PATH = '/pages/activity/refund'
// S5-3 (2026-11-23): waitlist, team sign-up and its order, the result record, refund/reschedule
// progress and the month calendar for picking a session.
export const ACTIVITY_WAITLIST_PATH = '/pages/activity/waitlist'
export const ACTIVITY_TEAM_SIGNUP_PATH = '/pages/activity/team-signup'
export const ACTIVITY_TEAM_ORDER_PATH = '/pages/activity/team-order'
export const ACTIVITY_RESULT_PATH = '/pages/activity/result'
export const ACTIVITY_CHANGE_PATH = '/pages/activity/change'
export const ACTIVITY_CALENDAR_PATH = '/pages/activity/calendar'

// Only stable ids travel in the URL; the page reads everything else from the server.
export function buildActivityDetailUrl(activityId: string, cityCode: string): string {
  return `${ACTIVITY_DETAIL_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

// `mode` 'waitlist' (S5-3): the same form joins the waitlist of a full session instead of signing up.
export function buildActivityEnrollUrl(
  activityId: string,
  sessionId: string,
  cityCode: string,
  mode?: 'waitlist',
): string {
  return `${ACTIVITY_ENROLL_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&sessionId=${encodeURIComponent(sessionId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
    + (mode ? `&mode=${mode}` : '')
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

export function buildActivityWaitlistUrl(waitlistId: string): string {
  return `${ACTIVITY_WAITLIST_PATH}?waitlistId=${encodeURIComponent(waitlistId)}`
}

export function buildActivityTeamSignupUrl(activityId: string, sessionId: string, cityCode: string): string {
  return `${ACTIVITY_TEAM_SIGNUP_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&sessionId=${encodeURIComponent(sessionId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildActivityTeamOrderUrl(enrollmentId: string): string {
  return `${ACTIVITY_TEAM_ORDER_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
}

export function buildActivityResultUrl(enrollmentId: string): string {
  return `${ACTIVITY_RESULT_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
}

export function buildActivityChangeUrl(enrollmentId: string, changeId: string): string {
  return `${ACTIVITY_CHANGE_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
    + `&changeId=${encodeURIComponent(changeId)}`
}

// The calendar lists one activity's sessions by day; `sessionId` is the one picked before (if any),
// `exceptSessionId` the one a reschedule moves away from. The choice comes back over the page's event
// channel (`picked` with the session id), so nothing but ids travel in the URL.
export function buildActivityCalendarUrl(
  activityId: string,
  cityCode: string,
  options: { sessionId?: string, exceptSessionId?: string, people?: number } = {},
): string {
  return `${ACTIVITY_CALENDAR_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
    + (options.sessionId ? `&sessionId=${encodeURIComponent(options.sessionId)}` : '')
    + (options.exceptSessionId ? `&exceptSessionId=${encodeURIComponent(options.exceptSessionId)}` : '')
    + (options.people ? `&people=${options.people}` : '')
}
