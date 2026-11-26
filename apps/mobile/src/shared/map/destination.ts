import { wgs84ToGcj02 } from './map-coordinate'

export interface Destination {
  name: string
  address: string
  latitude: number | null
  longitude: number | null
}

export const mapApps = [
  { id: 'amap', label: '高德地图', pkg: 'com.autonavi.minimap', ios: 'iosamap://' },
  { id: 'baidu', label: '百度地图', pkg: 'com.baidu.BaiduMap', ios: 'baidumap://' },
  { id: 'tencent', label: '腾讯地图', pkg: 'com.tencent.map', ios: 'qqmap://' },
  { id: 'apple', label: '苹果地图', pkg: '', ios: 'maps://' },
] as const
export type MapApp = typeof mapApps[number]['id']

export function positionOf(place: Destination) {
  const { latitude, longitude } = place
  if (typeof latitude !== 'number' || !Number.isFinite(latitude) || Math.abs(latitude) > 90 ||
      typeof longitude !== 'number' || !Number.isFinite(longitude) || Math.abs(longitude) > 180) return null
  return wgs84ToGcj02({ latitude, longitude })
}

export function markerUrl(place: Destination, mobile: boolean): string | null {
  const point = positionOf(place)
  if (!point) return null
  const name = encodeURIComponent(place.name)
  const address = encodeURIComponent(place.address)
  // The mobile embed is compact. Desktop AMap's official marker URI opens a map centred at the same pin.
  return mobile
    ? `https://www.amap.com/ssr/embed/place?lng=${point.longitude}&lat=${point.latitude}&name=${name}&address=${address}&zoom=16&maptype=roadmap&platform=pc&src=feixiang`
    : `https://uri.amap.com/marker?position=${point.longitude},${point.latitude}&name=${name}&coordinate=gaode&src=feixiang&callnative=0`
}

export function routeUrl(app: MapApp, place: Destination, native: boolean, ios = false): string | null {
  const point = positionOf(place)
  if (!point) return null
  const latLng = `${point.latitude},${point.longitude}`
  const name = encodeURIComponent(place.name || place.address)
  if (app === 'apple') return `https://maps.apple.com/?daddr=${place.latitude},${place.longitude}&dirflg=d`
  if (app === 'baidu') {
    const prefix = native ? 'baidumap://map/direction' : 'https://api.map.baidu.com/direction'
    return `${prefix}?destination=latlng:${latLng}|name:${name}&coord_type=gcj02&mode=driving&src=feixiang&output=html`
  }
  if (app === 'tencent') {
    const prefix = native ? 'qqmap://map/routeplan' : 'https://apis.map.qq.com/uri/v1/routeplan'
    return `${prefix}?type=drive&to=${name}&tocoord=${latLng}&referer=feixiang`
  }
  if (native) {
    return `${ios ? 'iosamap' : 'androidamap'}://route/plan/?sourceApplication=feixiang&dlat=${point.latitude}&dlon=${point.longitude}&dname=${name}&dev=0&t=0`
  }
  return `https://uri.amap.com/navigation?to=${point.longitude},${point.latitude},${name}&mode=car&coordinate=gaode&src=feixiang&callnative=1`
}

export function destinationPageUrl(place: Destination): string {
  const { name, address, latitude, longitude } = place
  return '/pages/place/destination?place=' + encodeURIComponent(JSON.stringify({ name, address, latitude, longitude }))
}

export function readDestination(value: unknown): Destination | null {
  if (typeof value !== 'string' || value.length > 12000) return null
  try {
    const data = JSON.parse(value.startsWith('{') ? value : decodeURIComponent(value))
    if (!data || typeof data.name !== 'string' || typeof data.address !== 'string') return null
    const place = { name: data.name, address: data.address, latitude: data.latitude, longitude: data.longitude }
    if (!positionOf(place)) return { ...place, latitude: null, longitude: null }
    return place
  } catch {
    return null
  }
}
