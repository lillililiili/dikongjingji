import { describe, expect, it } from 'vitest'

import { wgs84ToGcj02 } from './map-coordinate'

function metresBetween(a: { latitude: number; longitude: number }, b: { latitude: number; longitude: number }): number {
  const toRadians = (degrees: number): number => (degrees * Math.PI) / 180
  const dLat = toRadians(b.latitude - a.latitude)
  const dLng = toRadians(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(toRadians(a.latitude)) * Math.cos(toRadians(b.latitude)) * Math.sin(dLng / 2) ** 2
  return 2 * 6371000 * Math.asin(Math.sqrt(h))
}

describe('wgs84ToGcj02', () => {
  it('moves a point in Dongying by a few hundred metres, to the east', () => {
    const wgs84 = { latitude: 37.4487, longitude: 118.5823 }
    const gcj02 = wgs84ToGcj02(wgs84)

    const shift = metresBetween(wgs84, gcj02)
    expect(shift).toBeGreaterThan(100)
    expect(shift).toBeLessThan(900)
    expect(gcj02.longitude).toBeGreaterThan(wgs84.longitude)
  })

  it('matches the published offset for central Beijing', () => {
    // Tiananmen: WGS84 39.90746, 116.39128 is drawn at about GCJ-02 39.90886, 116.39752.
    const gcj02 = wgs84ToGcj02({ latitude: 39.90746, longitude: 116.39128 })

    expect(gcj02.latitude).toBeCloseTo(39.90886, 3)
    expect(gcj02.longitude).toBeCloseTo(116.39752, 3)
  })

  it('leaves points outside mainland China unchanged', () => {
    expect(wgs84ToGcj02({ latitude: 40.7128, longitude: -74.006 }))
      .toEqual({ latitude: 40.7128, longitude: -74.006 })
  })
})
