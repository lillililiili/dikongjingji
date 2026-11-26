import type { MapPoint } from '../domain/map-place'

const LOCATE_TIMEOUT_MS = 6000

function isApp(): boolean {
  try {
    const info = uni.getAppBaseInfo() as { uniPlatform?: unknown } | undefined
    return info?.uniPlatform === 'app'
  } catch {
    return false
  }
}

export function locateWithUni(): Promise<MapPoint | null> {
  if (!isApp()) return Promise.resolve(null)
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), LOCATE_TIMEOUT_MS)
    try {
      uni.getLocation({
        type: 'wgs84',
        success: (result) => {
          clearTimeout(timer)
          resolve(Number.isFinite(result.latitude) && Number.isFinite(result.longitude)
            ? { latitude: result.latitude, longitude: result.longitude }
            : null)
        },
        fail: () => {
          clearTimeout(timer)
          resolve(null)
        },
      })
    } catch {
      clearTimeout(timer)
      resolve(null)
    }
  })
}
