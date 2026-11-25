// 地图选点 (board MapPick). Coordinates are WGS84 (SRID 4326), as the server stores them.
export interface MapPoint {
  latitude: number
  longitude: number
}

export interface MapPlace {
  placeId: string
  name: string
  address: string
  point: MapPoint
  // Supplied by the database for development examples.
  sample: boolean
}

// What the picker hands back to the form that opened it.
export interface PickedPlace {
  name: string
  address: string
  point: MapPoint
}

// Who opened the picker: the two forms with a 地图选点 (boards InspBooking, Demand).
export type MapPickPurpose = 'INSPECTION' | 'DEMAND'

export const MAP_PICK_TITLES: Record<MapPickPurpose, string> = {
  INSPECTION: '选择巡检地点',
  DEMAND: '选择服务地点',
}

// Board MapPick, the 巡检地点 frame.
export const MAP_PICK_TIPS: Record<MapPickPurpose, string> = {
  INSPECTION: '选中的位置将填入预约页的巡检地点',
  DEMAND: '',
}

export const MAP_ADDRESS_MAX = 120
