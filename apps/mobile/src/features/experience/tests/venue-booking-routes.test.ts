import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import {
  buildVenueBookUrl,
  buildVenueBookingUrl,
  buildVenueBookingsUrl,
  parseVenueBookTarget,
} from '../navigation/experience-routes'

const TARGET = { venueId: 'v1', projectId: 'p1', sessionId: 's1', date: '2026-11-21', cityCode: '370500' }

describe('venue booking routes', () => {
  it('carries only stable ids and the session day', () => {
    expect(buildVenueBookUrl(TARGET))
      .toBe('/pages/venue/book?venueId=v1&cityCode=370500&projectId=p1&sessionId=s1&date=2026-11-21')
    expect(buildVenueBookingUrl('b 1')).toBe('/pages/venue/booking?bookingId=b%201')
    expect(buildVenueBookingsUrl()).toBe('/pages/mine/venue-bookings?bucket=UPCOMING')
  })

  it('reads the confirm page target back, and refuses an incomplete one', () => {
    expect(parseVenueBookTarget({ ...TARGET })).toEqual(TARGET)
    expect(parseVenueBookTarget({ ...TARGET, date: '21/11' })).toBeNull()
    expect(parseVenueBookTarget({ ...TARGET, sessionId: undefined })).toBeNull()
    expect(parseVenueBookTarget(undefined)).toBeNull()
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    for (const url of [
      buildVenueBookUrl(TARGET),
      buildVenueBookingUrl('b1'),
      buildVenueBookingsUrl('CANCELLED'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/venue/booking')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/venue/booking?bookingId=b1&cityCode=370500&next=https://evil.example'))
      .toBe('/pages/venue/booking?bookingId=b1')
  })
})
