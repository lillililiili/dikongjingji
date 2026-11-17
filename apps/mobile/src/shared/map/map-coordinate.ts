// The server stores venue coordinates in WGS84 (SRID 4326). Map apps in mainland China draw on GCJ-02,
// so a WGS84 point opened as-is lands a few hundred metres away. This is the usual published conversion.

const SEMI_MAJOR_AXIS = 6378245.0
const ECCENTRICITY_SQUARED = 0.00669342162296594323

export interface MapCoordinate {
  latitude: number
  longitude: number
}

// The offset only applies inside mainland China; this is the customary rough bounding box.
function isOutsideChina(latitude: number, longitude: number): boolean {
  return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271
}

function transformLatitude(x: number, y: number): number {
  let result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  result += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  result += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0
  result += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0
  return result
}

function transformLongitude(x: number, y: number): number {
  let result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  result += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  result += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0
  result += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0
  return result
}

export function wgs84ToGcj02(point: MapCoordinate): MapCoordinate {
  const { latitude, longitude } = point
  if (isOutsideChina(latitude, longitude)) return { latitude, longitude }

  const deltaLatitude = transformLatitude(longitude - 105.0, latitude - 35.0)
  const deltaLongitude = transformLongitude(longitude - 105.0, latitude - 35.0)
  const radians = (latitude / 180.0) * Math.PI
  const magic = 1 - ECCENTRICITY_SQUARED * Math.sin(radians) * Math.sin(radians)
  const sqrtMagic = Math.sqrt(magic)

  return {
    latitude: latitude + (deltaLatitude * 180.0)
      / (((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic)) * Math.PI),
    longitude: longitude + (deltaLongitude * 180.0)
      / ((SEMI_MAJOR_AXIS / sqrtMagic) * Math.cos(radians) * Math.PI),
  }
}
