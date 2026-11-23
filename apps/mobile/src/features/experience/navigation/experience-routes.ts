import type { VenueBookingBucket } from '../domain/venue-booking'

export const VENUE_DETAIL_PATH = '/pages/venue/detail'
export const VENUE_DEVICES_PATH = '/pages/venue/devices'
export const VENUE_BOOK_PATH = '/pages/venue/book'
export const VENUE_BOOKING_PATH = '/pages/venue/booking'
export const VENUE_BOOKINGS_PATH = '/pages/mine/venue-bookings'

// Only stable ids travel in the URL; the pages read everything else from the server.
function withVenue(path: string, venueId: string, cityCode: string): string {
  return `${path}?venueId=${encodeURIComponent(venueId)}&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildVenueDetailUrl(venueId: string, cityCode: string): string {
  return withVenue(VENUE_DETAIL_PATH, venueId, cityCode)
}

export function buildVenueDevicesUrl(venueId: string, cityCode: string): string {
  return withVenue(VENUE_DEVICES_PATH, venueId, cityCode)
}

export interface VenueBookTarget {
  venueId: string
  projectId: string
  sessionId: string
  // The session's day (YYYY-MM-DD, Beijing): the sessions endpoint is read by day.
  date: string
  cityCode: string
}

// The confirm page. There is no endpoint for one session, so the venue, the project and the day
// travel with the session id; the page reads the session and its price from the server again.
export function buildVenueBookUrl(target: VenueBookTarget): string {
  return `${withVenue(VENUE_BOOK_PATH, target.venueId, target.cityCode)}`
    + `&projectId=${encodeURIComponent(target.projectId)}`
    + `&sessionId=${encodeURIComponent(target.sessionId)}`
    + `&date=${encodeURIComponent(target.date)}`
}

export function parseVenueBookTarget(query: Record<string, unknown> | undefined): VenueBookTarget | null {
  const read = (key: string) => (typeof query?.[key] === 'string' ? (query[key] as string) : '')
  const target = {
    venueId: read('venueId'),
    projectId: read('projectId'),
    sessionId: read('sessionId'),
    date: read('date'),
    cityCode: read('cityCode'),
  }
  return Object.values(target).every(Boolean) && /^\d{4}-\d{2}-\d{2}$/.test(target.date) ? target : null
}

// The booking's code page; the booking itself says which city its venue is in.
export function buildVenueBookingUrl(bookingId: string): string {
  return `${VENUE_BOOKING_PATH}?bookingId=${encodeURIComponent(bookingId)}`
}

export function buildVenueBookingsUrl(bucket: VenueBookingBucket = 'UPCOMING'): string {
  return `${VENUE_BOOKINGS_PATH}?bucket=${bucket}`
}

// ---- Online experiences (S5-4) ----

export const EXPERIENCE_SIMULATION_PATH = '/pages/experience/simulation'
export const EXPERIENCE_RESULT_PATH = '/pages/experience/result'
export const SOFTWARE_TRIAL_PATH = '/pages/experience/software-trial'
export const COURSE_TRIAL_PATH = '/pages/experience/course-trial'
export const EXPERIENCE_RECORDS_PATH = '/pages/mine/experience-records'

export type ExperienceRecordsTab = 'ALL' | 'ONLINE' | 'VENUE'
export const EXPERIENCE_RECORDS_TABS: readonly ExperienceRecordsTab[] = ['ALL', 'ONLINE', 'VENUE']

export function buildSimulationUrl(experienceId: string): string {
  return `${EXPERIENCE_SIMULATION_PATH}?experienceId=${encodeURIComponent(experienceId)}`
}

// A signed-in run is a record on the server; a guest's run is only on this device (localId).
export type SimulationResultTarget =
  | { experienceId: string; recordId: string }
  | { experienceId: string; localId: string }

export function buildSimulationResultUrl(target: SimulationResultTarget): string {
  const which = 'recordId' in target
    ? `recordId=${encodeURIComponent(target.recordId)}`
    : `localId=${encodeURIComponent(target.localId)}`
  return `${EXPERIENCE_RESULT_PATH}?experienceId=${encodeURIComponent(target.experienceId)}&${which}`
}

export function parseSimulationResultTarget(query: Record<string, unknown> | undefined): SimulationResultTarget | null {
  const read = (key: string) => (typeof query?.[key] === 'string' ? (query[key] as string).trim() : '')
  const experienceId = read('experienceId')
  if (!experienceId) return null
  if (read('recordId')) return { experienceId, recordId: read('recordId') }
  if (read('localId')) return { experienceId, localId: read('localId') }
  return null
}

// Without a record the page starts a trial (or goes back to the one still running); with one it
// shows that record, for example an expired trial opened from 我的体验.
export function buildSoftwareTrialUrl(experienceId: string, recordId?: string | null): string {
  return `${SOFTWARE_TRIAL_PATH}?experienceId=${encodeURIComponent(experienceId)}`
    + (recordId ? `&recordId=${encodeURIComponent(recordId)}` : '')
}

export function buildCourseTrialUrl(experienceId: string, recordId?: string | null): string {
  return `${COURSE_TRIAL_PATH}?experienceId=${encodeURIComponent(experienceId)}`
    + (recordId ? `&recordId=${encodeURIComponent(recordId)}` : '')
}

export function buildExperienceRecordsUrl(tab: ExperienceRecordsTab = 'ALL'): string {
  return `${EXPERIENCE_RECORDS_PATH}?tab=${tab}`
}
