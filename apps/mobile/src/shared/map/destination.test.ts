import { describe, expect, it } from 'vitest'
import { destinationPageUrl, markerUrl, positionOf, readDestination, routeUrl } from './destination'

const place = {name:'体验馆 & A', address:'示例路 66 号', latitude:37.4487, longitude:118.5823}

describe('destination maps and routes', () => {
  it('passes the original database coordinates and unicode labels across the detail route', () => {
    const encoded = destinationPageUrl(place).split('?place=')[1]
    expect(readDestination(encoded)).toEqual(place)
    expect(readDestination(decodeURIComponent(encoded))).toEqual(place)
    expect(readDestination('{bad')).toBeNull()
    expect(readDestination('null')).toBeNull()
  })

  it('uses one GCJ-02 conversion for Chinese providers in their documented argument order', () => {
    const point = positionOf(place)!
    expect(point.longitude).toBeGreaterThan(place.longitude)
    for (const mobile of [true, false]) {
      const marker = new URL(markerUrl(place, mobile)!)
      expect(mobile ? marker.searchParams.get('lng') : marker.searchParams.get('position')?.split(',')[0]).toBe(String(point.longitude))
      expect(marker.searchParams.get('name')).toBe(place.name)
    }
    for (const native of [true, false]) {
      const baidu = new URL(routeUrl('baidu', place, native)!)
      expect(baidu.searchParams.get('coord_type')).toBe('gcj02')
      expect(baidu.searchParams.get('destination')).toBe(`latlng:${point.latitude},${point.longitude}|name:${place.name}`)
      const tencent = new URL(routeUrl('tencent', place, native)!)
      expect(tencent.searchParams.get('tocoord')).toBe(`${point.latitude},${point.longitude}`)
      expect(tencent.searchParams.get('to')).toBe(place.name)
    }
    expect(new URL(routeUrl('amap', place, false)!).searchParams.get('to')).toBe(`${point.longitude},${point.latitude},${place.name}`)
    expect(new URL(routeUrl('amap', place, true, true)!).protocol).toBe('iosamap:')
    expect(new URL(routeUrl('amap', place, true, false)!).protocol).toBe('androidamap:')
    expect(new URL(routeUrl('apple', place, false)!).searchParams.get('daddr')).toBe('37.4487,118.5823')
  })

  it('retains the address and offers no fictitious pin for missing or invalid coordinates', () => {
    for (const latitude of [null, NaN, 120]) {
      const incomplete = {...place, latitude}
      expect(positionOf(incomplete)).toBeNull()
      expect(markerUrl(incomplete, true)).toBeNull()
      expect(routeUrl('amap', incomplete, false)).toBeNull()
    }
  })
})
