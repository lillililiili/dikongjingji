import { describe, expect, it } from 'vitest'

import { resolvePlatformSwipeTarget } from '../domain/platform-channel-swipe'

describe('resolvePlatformSwipeTarget', () => {
  it('rejects short and vertical-dominant gestures', () => {
    expect(resolvePlatformSwipeTarget('INSPECTION', -49, 0)).toBeNull()
    expect(resolvePlatformSwipeTarget('INSPECTION', -80, 70)).toBeNull()
  })

  it('moves across the service types in order', () => {
    expect(resolvePlatformSwipeTarget('INSPECTION', -70, 10)).toBe('AERIAL')
    expect(resolvePlatformSwipeTarget('AERIAL', -70, 10)).toBe('SURVEY')
    expect(resolvePlatformSwipeTarget('SURVEY', -70, 10)).toBe('MAINTENANCE')
    expect(resolvePlatformSwipeTarget('SUPPORT', 70, 10)).toBe('MAINTENANCE')
  })

  it('does not wrap past either end', () => {
    expect(resolvePlatformSwipeTarget('INSPECTION', 70, 10)).toBeNull()
    expect(resolvePlatformSwipeTarget('SUPPORT', -70, 10)).toBeNull()
  })
})
