export function locateCurrentCityName(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      geocode: true,
      success: (result) => {
        const address = (result as unknown as {
          address?: { city?: string; province?: string }
        }).address
        const city = address?.city?.trim()
          || (address?.province?.trim().endsWith('市')
            ? address.province.trim()
            : '')
        city ? resolve(city) : reject(new Error('CITY_NAME_UNAVAILABLE'))
      },
      fail: () => reject(new Error('LOCATION_UNAVAILABLE')),
    })
  })
}
