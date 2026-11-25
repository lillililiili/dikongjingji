import { describe, expect, it, vi } from 'vitest'

import pagesJson from '../../../pages.json?raw'
import bookingPageSource from '../../inspection/presentation/booking/pages/InspectionBookingPage.vue?raw'
import demandPageSource from '../../demand/presentation/publish/DemandPublishPage.vue?raw'
import placeFlagSource from '../application/place-preview.ts?raw'
import { distanceMetres, formatDistance, formatPoint, movePoint } from '../application/map-projection'
import { createMapPlaceProvider } from '../data/map-place-api'
import { buildMapPickUrl, parseMapPickTarget } from '../navigation/place-routes'

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

const DONGYING = { code: '370500', name: '东营市' }

describe('map projection (board MapPick sketch map)', () => {
  it('moves the pin west when the map is dragged right and north when dragged down', () => {
    const start = { latitude: 37.4347, longitude: 118.6749 }
    const moved = movePoint(start, 100, 100)
    expect(moved.longitude).toBeLessThan(start.longitude)
    expect(moved.latitude).toBeGreaterThan(start.latitude)
    // 100 px at 2 m a pixel is about 200 m each way.
    expect(distanceMetres(start, { latitude: moved.latitude, longitude: start.longitude })).toBeCloseTo(200, -1)
    expect(movePoint(start, 0, 0)).toEqual(start)
  })

  it('writes distances and points the way the board does', () => {
    expect(formatDistance(118)).toBe('120 米')
    expect(formatDistance(2440)).toBe('2.4 公里')
    expect(formatDistance(24_300)).toBe('24 公里')
    expect(formatPoint({ latitude: 37.4347, longitude: 118.6749 })).toBe('37.43470, 118.67490')
  })
})

describe('platform map places API', () => {
  it('passes the current city, search and coordinates and uses the returned point', async () => {
    const request = vi.fn().mockResolvedValue([{
      placeId: '370500:office', name: '新地点', address: '仓库南门', latitude: 37, longitude: 118, sample: false,
    }])
    const provider = createMapPlaceProvider({ request })
    const places = await provider.list(DONGYING, 'A & B', { latitude: 37.5, longitude: 118.2 })
    expect(request.mock.calls[0][0].url).toBe('/api/v1/platform/map-places?cityCode=370500&keyword=A%20%26%20B&latitude=37.5&longitude=118.2')
    expect(places).toEqual([{
      placeId: '370500:office', name: '新地点', address: '仓库南门', point: { latitude: 37, longitude: 118 }, sample: false,
    }])
  })

  it('keeps empty results empty and propagates lookup failures', async () => {
    const request = vi.fn().mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('offline'))
    const provider = createMapPlaceProvider({ request })
    expect(await provider.list(DONGYING, '', null)).toEqual([])
    expect(request.mock.calls[0][0].url).toBe('/api/v1/platform/map-places?cityCode=370500')
    await expect(provider.list(DONGYING, '', null)).rejects.toThrow('offline')
  })
})

describe('map pick route and wiring', () => {
  it('carries the purpose, a request id and the city', () => {
    const url = buildMapPickUrl({ purpose: 'INSPECTION', requestId: 'abc123', cityCode: '370500', cityName: '东营市' })
    expect(url).toBe(`/pages/place/map-pick?purpose=INSPECTION&requestId=abc123&cityCode=370500&cityName=${encodeURIComponent('东营市')}`)
    const query: Record<string, string> = {}
    for (const part of url.split('?')[1].split('&')) {
      const [key, value] = part.split('=')
      query[key] = value
    }
    expect(parseMapPickTarget(query)).toEqual({ purpose: 'INSPECTION', requestId: 'abc123', cityCode: '370500', cityName: '东营市' })
    expect(parseMapPickTarget({ ...query, purpose: 'OTHER' })).toBeNull()
    expect(parseMapPickTarget({ ...query, cityCode: '东营' })).toBeNull()
  })

  it('opens from the booking and the demand form in preview builds only', () => {
    expect(pagesJson).toContain('"path": "pages/place/map-pick"')
    expect(flagExpression(placeFlagSource, 'PLACE_PREVIEW_ENABLED')).toBe('import.meta.env.DEV || __RELEASE_FEATURE_PLACES__')
    expect(bookingPageSource).toContain("purpose: 'INSPECTION'")
    expect(bookingPageSource).toContain('if (PLACE_PREVIEW_ENABLED) {')
    // Public builds keep the system picker.
    expect(bookingPageSource).toContain('uni.chooseLocation({')
    expect(demandPageSource).toContain("purpose: 'DEMAND'")
    expect(demandPageSource).toContain('v-if="PLACE_PREVIEW_ENABLED"')
  })
})
