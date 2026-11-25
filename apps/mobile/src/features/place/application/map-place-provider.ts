import type { MapPlace, MapPoint } from '../domain/map-place'

export interface MapPlaceProvider {
  list(city: { code: string }, keyword: string, point: MapPoint | null): Promise<MapPlace[]>
  locate(): Promise<MapPoint | null>
}
