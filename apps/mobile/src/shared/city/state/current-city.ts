import { defineStore } from 'pinia'

export interface CurrentCity {
  code: string
  name: string
  provinceCode?: string
}

interface CurrentCityState {
  currentCity: CurrentCity | null
}

export function resolveLocatedCityName(address: unknown): string {
  if (!address || typeof address !== 'object') {
    throw new Error('CURRENT_CITY_UNAVAILABLE')
  }

  const value = address as Record<string, unknown>
  const city = typeof value.city === 'string' ? value.city.trim() : ''
  const province = typeof value.province === 'string' ? value.province.trim() : ''
  const cityName = city || (province.endsWith('市') ? province : '')

  if (cityName.length < 2 || cityName.length > 40) {
    throw new Error('CURRENT_CITY_UNAVAILABLE')
  }
  return cityName
}

export const useCurrentCityStore = defineStore('current-city', {
  state: (): CurrentCityState => ({
    currentCity: null,
  }),
  actions: {
    setCurrentCity(city: CurrentCity): void {
      this.currentCity = city
    },
    clearCurrentCity(): void {
      this.currentCity = null
    },
  },
})

