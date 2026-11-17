export const VENUE_DETAIL_PATH = '/pages/venue/detail'
export const VENUE_DEVICES_PATH = '/pages/venue/devices'

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
