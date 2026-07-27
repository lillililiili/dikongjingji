import { defineStore } from 'pinia'

export interface CurrentCity {
  code: string
  name: string
  provinceCode?: string
}

interface CurrentCityState {
  currentCity: CurrentCity | null
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

