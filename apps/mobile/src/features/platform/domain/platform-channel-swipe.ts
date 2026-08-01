import type { PlatformChannelCode } from './platform-channel'

export const PLATFORM_SWIPE_CHANNELS = [
  'AERIAL',
  'INSPECTION',
  'FLIGHT',
  'ROBOT',
] as const satisfies readonly PlatformChannelCode[]

const MIN_HORIZONTAL_DISTANCE = 50
const HORIZONTAL_DOMINANCE_RATIO = 1.2

export function resolvePlatformSwipeTarget(
  current: PlatformChannelCode,
  deltaX: number,
  deltaY: number,
): PlatformChannelCode | null {
  const horizontalDistance = Math.abs(deltaX)
  const verticalDistance = Math.abs(deltaY)
  if (
    horizontalDistance < MIN_HORIZONTAL_DISTANCE
    || horizontalDistance <= verticalDistance * HORIZONTAL_DOMINANCE_RATIO
  ) {
    return null
  }

  const currentIndex = PLATFORM_SWIPE_CHANNELS.indexOf(
    current as (typeof PLATFORM_SWIPE_CHANNELS)[number],
  )
  if (currentIndex < 0) return null

  const targetIndex = currentIndex + (deltaX < 0 ? 1 : -1)
  return PLATFORM_SWIPE_CHANNELS[targetIndex] ?? null
}
