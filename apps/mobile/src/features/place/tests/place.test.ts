import { describe, expect, it } from 'vitest'

import pagesJson from '../../../pages.json?raw'
import bookingPageSource from '../../inspection/presentation/booking/pages/InspectionBookingPage.vue?raw'
import demandPageSource from '../../demand/presentation/publish/DemandPublishPage.vue?raw'
import placeFlagSource from '../application/place-preview.ts?raw'
import { distanceMetres, formatDistance, formatPoint, movePoint } from '../application/map-projection'
import { FALLBACK_CENTRE, createFakeMapPlaceProvider } from '../data/fake-map-place-provider'
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

describe('fake map provider', () => {
  it('opens on the city centre, or a fallback for a city it does not know', () => {
    const provider = createFakeMapPlaceProvider(async () => null)
    expect(provider.center(DONGYING)).toEqual({ latitude: 37.4347, longitude: 118.6749 })
    expect(provider.center({ code: '999999', name: '某市' })).toEqual(FALLBACK_CENTRE)
  })

  it('makes three sample places marked 示例 and finds them by name', async () => {
    const provider = createFakeMapPlaceProvider(async () => null)
    const centre = provider.center(DONGYING)
    const places = await provider.nearby(DONGYING, centre)
    expect(places.map((place) => place.name)).toEqual(['示例路 88 号', '示例产业园 3 号门', '示例物流园区'])
    expect(places.every((place) => place.sample && place.address.endsWith('（示例）'))).toBe(true)
    expect((await provider.search(DONGYING, '产业园', centre)).map((place) => place.name)).toEqual(['示例产业园 3 号门'])
    expect(await provider.search(DONGYING, '激光雷达', centre)).toEqual([])
    expect(await provider.locate()).toBeNull()
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
