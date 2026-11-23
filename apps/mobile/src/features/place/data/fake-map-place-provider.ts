import type { MapPlaceProvider } from '../application/map-place-provider'
import { movePoint } from '../application/map-projection'
import type { MapPlace, MapPoint } from '../domain/map-place'

/**
 * The local stand-in for a map service (plan 08 §5: 地图经端口，开发用模拟服务). It knows the
 * centres of a few cities of the platform city directory, makes up three sample places around a
 * point (every one marked 示例, words as on board MapPick) and finds them by name. The device
 * position comes from uni.getLocation in the App, which needs no map SDK; in a browser, or when it
 * is refused or unavailable, there is none (the page's 定位被拒 state), as the city locator does.
 */
const CITY_CENTRES: Record<string, MapPoint> = {
  '110100': { latitude: 39.9042, longitude: 116.4074 },
  '310100': { latitude: 31.2304, longitude: 121.4737 },
  '370100': { latitude: 36.6512, longitude: 117.1201 },
  '370200': { latitude: 36.0671, longitude: 120.3826 },
  '370500': { latitude: 37.4347, longitude: 118.6749 },
  '370700': { latitude: 36.7069, longitude: 119.1618 },
  '440100': { latitude: 23.1291, longitude: 113.2644 },
  '440300': { latitude: 22.5431, longitude: 114.0579 },
}

// Used for a city the fake has no centre for; the page says the map is a sketch anyway.
export const FALLBACK_CENTRE: MapPoint = CITY_CENTRES['370500']

const LOCATE_TIMEOUT_MS = 6000

function shortName(name: string): string {
  return name.length > 2 && name.endsWith('市') ? name.slice(0, -1) : name
}

// Offsets in pixels of the sketch map's scale, so the sample places stay near the pin.
const SAMPLES: ReadonlyArray<{ key: string; name: string; street: string; dx: number; dy: number }> = [
  { key: 'road-88', name: '示例路 88 号', street: '示例路 88 号', dx: -40, dy: -45 },
  { key: 'park-gate-3', name: '示例产业园 3 号门', street: '示例路 96 号', dx: 120, dy: 110 },
  { key: 'logistics', name: '示例物流园区', street: '示例港路 6 号', dx: -380, dy: 420 },
]

function samplePlaces(city: { code: string; name: string }, around: MapPoint): MapPlace[] {
  return SAMPLES.map((sample) => ({
    placeId: `sample:${city.code}:${sample.key}`,
    name: sample.name,
    address: `${city.name}${sample.street}（示例）`,
    point: movePoint(around, sample.dx, sample.dy),
    sample: true,
  }))
}

export function createFakeMapPlaceProvider(locate: () => Promise<MapPoint | null> = locateWithUni): MapPlaceProvider {
  return {
    center(city) {
      return CITY_CENTRES[city.code] ?? FALLBACK_CENTRE
    },
    async nearby(city, point) {
      return samplePlaces(city, point)
    },
    async search(city, keyword, near) {
      const key = keyword.replace(/\s+/g, '')
      if (!key) return samplePlaces(city, near)
      return samplePlaces(city, near).filter((place) =>
        place.name.replace(/\s+/g, '').includes(key)
        || place.address.replace(/\s+/g, '').includes(key)
        || shortName(city.name).includes(key))
    },
    locate,
  }
}

function isApp(): boolean {
  try {
    const info = uni.getAppBaseInfo() as { uniPlatform?: unknown } | undefined
    return info?.uniPlatform === 'app'
  } catch {
    return false
  }
}

function locateWithUni(): Promise<MapPoint | null> {
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
