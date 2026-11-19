import { wgs84ToGcj02 } from '@/shared/map/map-coordinate'

export interface VenuePlace {
  name: string
  address: string
  // WGS84; null when the venue gave no coordinates.
  latitude: number | null
  longitude: number | null
}

function copyAddress(address: string): void {
  uni.setClipboardData({
    data: address,
    success: () => uni.showToast({ title: '已复制地址', icon: 'none' }),
  })
}

// 导航 on the venue detail and on a booking's code page: the system map at the venue, or the
// address on the clipboard when there are no coordinates or no map on this device.
export function openVenueNavigation(place: VenuePlace): void {
  if (place.latitude === null || place.longitude === null) {
    copyAddress(place.address)
    return
  }
  // The server keeps WGS84; maps in China expect GCJ-02.
  const point = wgs84ToGcj02({ latitude: place.latitude, longitude: place.longitude })
  uni.openLocation({
    latitude: point.latitude,
    longitude: point.longitude,
    name: place.name,
    address: place.address,
    // Without a map on this device the address is still useful in another app.
    fail: () => copyAddress(place.address),
  })
}
