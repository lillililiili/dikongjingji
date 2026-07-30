function stringValue(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function currentUniPlatform(): string {
  const appBaseInfo: unknown = uni.getAppBaseInfo()
  if (!appBaseInfo || typeof appBaseInfo !== 'object') return ''
  return stringValue((appBaseInfo as { uniPlatform?: unknown }).uniPlatform)
}

function cityNameFromLocationResult(result: unknown): string {
  if (!result || typeof result !== 'object') return ''
  const address = (result as { address?: unknown }).address
  if (!address || typeof address !== 'object') return ''
  const addressRecord = address as { city?: unknown; province?: unknown }
  const city = stringValue(addressRecord.city)
  if (city) return city

  const province = stringValue(addressRecord.province)
  return province.endsWith('市') ? province : ''
}

export function locateCurrentCityName(): Promise<string> {
  if (currentUniPlatform() !== 'app') {
    return Promise.reject(new Error('CITY_LOCATION_UNSUPPORTED'))
  }

  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      geocode: true,
      success: (result) => {
        const city = cityNameFromLocationResult(result)
        city ? resolve(city) : reject(new Error('CITY_NAME_UNAVAILABLE'))
      },
      fail: () => reject(new Error('LOCATION_UNAVAILABLE')),
    })
  })
}
