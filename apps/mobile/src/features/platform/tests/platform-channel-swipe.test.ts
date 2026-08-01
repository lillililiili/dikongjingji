import { describe, expect, it } from 'vitest'

import { resolvePlatformSwipeTarget } from '../domain/platform-channel-swipe'

describe('resolvePlatformSwipeTarget', () => {
  it('rejects short and vertical-dominant gestures', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', -49, 0)).toBeNull()
    expect(resolvePlatformSwipeTarget('AERIAL', -80, 70)).toBeNull()
  })

  it('moves only across the four content channels', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', -70, 10)).toBe('INSPECTION')
    expect(resolvePlatformSwipeTarget('INSPECTION', -70, 10)).toBe('FLIGHT')
    expect(resolvePlatformSwipeTarget('FLIGHT', -70, 10)).toBe('ROBOT')
    expect(resolvePlatformSwipeTarget('ROBOT', 70, 10)).toBe('FLIGHT')
  })

  it('does not wrap or enter more services', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', 70, 10)).toBeNull()
    expect(resolvePlatformSwipeTarget('ROBOT', -70, 10)).toBeNull()
    expect(resolvePlatformSwipeTarget('MORE', -70, 10)).toBeNull()
  })
})
