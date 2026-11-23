import type { MapPlace, MapPoint } from '../domain/map-place'

/**
 * The map adapter (AGENTS §14: external map services go through an adapter). The page only asks
 * for a city's centre, the places near a point, a place search and the device position; which map
 * service answers is decided where the provider is made (map-place-provider-client.ts). No map
 * SDK is in the App yet, so every build uses the local fake (data/fake-map-place-provider.ts).
 */
export interface MapPlaceProvider {
  // The centre to open on when there is no position yet.
  center(city: { code: string; name: string }): MapPoint
  nearby(city: { code: string; name: string }, point: MapPoint): Promise<MapPlace[]>
  search(city: { code: string; name: string }, keyword: string, near: MapPoint): Promise<MapPlace[]>
  // Null when the position is refused or unavailable (board MapPick, 定位被拒).
  locate(): Promise<MapPoint | null>
}
