import type { PublicApiHttpClient } from '@/shared/http/public-api-request'
import type { MapPlaceProvider } from '../application/map-place-provider'
import type { MapPlace, MapPoint } from '../domain/map-place'
import { locateWithUni } from './device-location'

type PlaceRow = Omit<MapPlace, 'point'> & MapPoint

export function createMapPlaceProvider(http: PublicApiHttpClient): MapPlaceProvider {
  return {
    locate: locateWithUni,
    async list(city, keyword, point) {
      const query = [`cityCode=${encodeURIComponent(city.code)}`]
      if (keyword) query.push(`keyword=${encodeURIComponent(keyword)}`)
      if (point) query.push(`latitude=${point.latitude}`, `longitude=${point.longitude}`)
      const rows = await http.request<PlaceRow[]>({ method: 'GET', url: `/api/v1/platform/map-places?${query.join('&')}` })
      return rows.map(({ placeId, name, address, latitude, longitude, sample }) => ({
        placeId, name, address, sample, point: { latitude, longitude },
      }))
    },
  }
}
