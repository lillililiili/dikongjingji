export type PlatformAvailability = 'AVAILABLE' | 'UNAVAILABLE'

export type PlatformAvailabilityAction = 'PROCEED' | 'SHOW_UNAVAILABLE'

export const resolvePlatformAvailabilityAction = (
  availability: PlatformAvailability,
): PlatformAvailabilityAction =>
  availability === 'UNAVAILABLE' ? 'SHOW_UNAVAILABLE' : 'PROCEED'
