import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

export interface PlaceInput {
  cityCode: string
  name: string
  address: string
  latitude: number | undefined
  longitude: number | undefined
  sortOrder: number | undefined
  published: boolean
  version: number | null
}
export interface Place extends PlaceInput {
  id: string
  cityName: string
  latitude: number
  longitude: number
  sortOrder: number
  version: number
  sample: boolean
}
export interface PlaceChange { version: number; changedAt: string; operatorName: string; previous: Place | null; current: Place }
export interface PlaceFilters { cityCode: string; keyword: string; status: '' | 'on' | 'off' }

export function newPlaceId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // LAN preview over HTTP may expose getRandomValues but not randomUUID.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const view = new DataView(bytes.buffer)
  view.setUint8(6, (view.getUint8(6) & 0x0f) | 0x40)
  view.setUint8(8, (view.getUint8(8) & 0x3f) | 0x80)
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}

export function createMapPlacesApi(http: ApiClient) {
  const base = '/api/v1/ops/map-places'
  return {
    list(filters: PlaceFilters, page: number, size: number) {
      return http.get<Page<Place>>(base, { cityCode: filters.cityCode, keyword: filters.keyword.trim(),
        published: filters.status === '' ? undefined : filters.status === 'on', page, size })
    },
    save(id: string, values: PlaceInput) { return http.put<Place>(base + '/' + encodeURIComponent(id), values) },
    changes(id: string) { return http.get<PlaceChange[]>(base + '/' + encodeURIComponent(id) + '/changes') },
  }
}
