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
