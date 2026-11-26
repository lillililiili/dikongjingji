import { HOME_ROOT_PATH } from '../../../app-shell/navigation/root-tabs'
import type { MapPickPurpose } from '../domain/map-place'

export const MAP_PICK_PATH = '/pages/place/map-pick'

export function leavePlacePage(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.switchTab({ url: HOME_ROOT_PATH })
}

export interface MapPickTarget {
  purpose: MapPickPurpose
  requestId: string
  cityCode: string
  cityName: string
}

// The city travels so the map opens on it; the form keeps its own values.
export function buildMapPickUrl(target: MapPickTarget): string {
  return `${MAP_PICK_PATH}?${[
    `purpose=${target.purpose}`,
    `requestId=${encodeURIComponent(target.requestId)}`,
    `cityCode=${encodeURIComponent(target.cityCode)}`,
    `cityName=${encodeURIComponent(target.cityName)}`,
  ].join('&')}`
}

function read(query: Record<string, unknown> | undefined, key: string): string {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

export function parseMapPickTarget(query: Record<string, unknown> | undefined): MapPickTarget | null {
  const purpose = read(query, 'purpose')
  const requestId = read(query, 'requestId')
  const cityCode = read(query, 'cityCode')
  if ((purpose !== 'INSPECTION' && purpose !== 'DEMAND') || !/^[A-Za-z0-9]{4,32}$/.test(requestId) || !/^[A-Z0-9]{4,16}$/.test(cityCode)) {
    return null
  }
  return { purpose, requestId, cityCode, cityName: read(query, 'cityName').slice(0, 20) }
}
