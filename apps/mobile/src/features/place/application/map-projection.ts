import type { MapPoint } from '../domain/map-place'

// The sketch map has one fixed scale: one CSS pixel is this many metres on the ground.
export const METRES_PER_PIXEL = 2
const METRES_PER_DEGREE = 111_320

/**
 * The point under the pin after the map was dragged by (dx, dy) pixels: dragging the map right
 * moves the pin west, dragging it down moves the pin north.
 */
export function movePoint(point: MapPoint, dx: number, dy: number, metresPerPixel = METRES_PER_PIXEL): MapPoint {
  const latitude = point.latitude + (dy * metresPerPixel) / METRES_PER_DEGREE
  const cos = Math.cos((point.latitude * Math.PI) / 180)
  const longitude = point.longitude - (dx * metresPerPixel) / (METRES_PER_DEGREE * Math.max(cos, 0.01))
  return {
    latitude: Math.min(90, Math.max(-90, Number(latitude.toFixed(6)))),
    longitude: Math.min(180, Math.max(-180, Number(longitude.toFixed(6)))),
  }
}

// Straight-line distance in metres (haversine).
export function distanceMetres(a: MapPoint, b: MapPoint): number {
  const radians = (degrees: number) => (degrees * Math.PI) / 180
  const dLat = radians(b.latitude - a.latitude)
  const dLng = radians(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(radians(a.latitude)) * Math.cos(radians(b.latitude)) * Math.sin(dLng / 2) ** 2
  return 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)))
}

// "120 米", "2.4 公里", "24 公里"
export function formatDistance(metres: number): string {
  if (metres < 1000) return `${Math.max(10, Math.round(metres / 10) * 10)} 米`
  const km = metres / 1000
  return km < 10 ? `${km.toFixed(1)} 公里` : `${Math.round(km)} 公里`
}

// "37.43470, 118.67490": the pin's place when no address is chosen yet.
export function formatPoint(point: MapPoint): string {
  return `${point.latitude.toFixed(5)}, ${point.longitude.toFixed(5)}`
}
