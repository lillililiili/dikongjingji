import { useCurrentCityStore } from '../../../shared/city/state/current-city'
import { platformCityApi } from '../../inspection/data/service-flow/uni-booking-http-client'

/** A shared content link can be the first page opened, before a city has been selected. */
export async function openContentDiscover(cityCode?: string): Promise<void> {
  const store = useCurrentCityStore()
  if (!store.currentCity && cityCode) {
    const directory = await platformCityApi.load().catch(() => undefined)
    const city = directory?.provinces.flatMap(province => province.cities).find(item => item.code === cityCode)
    if (city && !store.currentCity) {
      store.setCurrentCity({ code: city.code, name: city.name, provinceCode: city.provinceCode })
    }
  }
  uni.switchTab({ url: '/pages/discover/index' })
}
