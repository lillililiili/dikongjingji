import { ref } from 'vue'

import { createRecentCityStorage } from '../data/recent-city-storage'
import { locateCurrentCityName } from '../data/uni-city-location'
import {
  findCityByLocatedName,
  type CityDirectory,
  type PlatformCity,
} from '../domain/city-directory'
import { useCurrentCityStore } from '../state/current-city'
import type { CitySelectionSource } from './CitySelectionSheet.vue'

export interface CitySheetApi {
  load(): Promise<CityDirectory>
  recordSearchSelection(cityCode: string): Promise<unknown>
}

export function useCitySheet(api: CitySheetApi, onCitySelected: () => void) {
  const currentCityStore = useCurrentCityStore()
  const recentCityStorage = createRecentCityStorage({
    get: (key) => uni.getStorageSync(key),
    set: (key, value) => uni.setStorageSync(key, value),
  })
  const isOpen = ref(false)
  const directory = ref<CityDirectory | null>(null)
  const isLoading = ref(false)
  const error = ref('')
  const recentCities = ref<PlatformCity[]>(loadRecentCities())
  const isRelocating = ref(false)
  let relocationRevision = 0

  function loadRecentCities(): PlatformCity[] {
    try {
      return recentCityStorage.load()
    } catch {
      return []
    }
  }

  function showToast(title: string): void {
    uni.showToast({ title, icon: 'none' })
  }

  function cancelRelocation(): void {
    relocationRevision += 1
    isRelocating.value = false
  }

  function applyCity(city: PlatformCity, source: CitySelectionSource): void {
    currentCityStore.setCurrentCity({
      code: city.code,
      name: city.name,
      provinceCode: city.provinceCode,
    })

    try {
      recentCityStorage.save(city)
      recentCities.value = loadRecentCities()
    } catch {
      showToast('最近城市保存失败')
    }

    isOpen.value = false
    if (source === 'SEARCH') {
      void api.recordSearchSelection(city.code).catch(() => undefined)
    }
    onCitySelected()
  }

  async function loadDirectory(): Promise<void> {
    if (isLoading.value) return

    isLoading.value = true
    error.value = ''
    try {
      directory.value = await api.load()
    } catch {
      error.value = '城市目录加载失败，请稍后重试'
    } finally {
      isLoading.value = false
    }
  }

  async function open(): Promise<void> {
    isOpen.value = true
    recentCities.value = loadRecentCities()
    if (!directory.value) await loadDirectory()
  }

  function close(): void {
    cancelRelocation()
    isOpen.value = false
  }

  function select(payload: {
    city: PlatformCity
    source: CitySelectionSource
  }): void {
    cancelRelocation()
    applyCity(payload.city, payload.source)
  }

  async function relocate(): Promise<void> {
    if (isRelocating.value) return

    const loadedDirectory = directory.value
    if (!loadedDirectory) {
      showToast('请先重新加载城市目录')
      return
    }

    const revision = ++relocationRevision
    isRelocating.value = true
    try {
      const locatedName = await locateCurrentCityName()
      if (revision !== relocationRevision) return
      const city = findCityByLocatedName(loadedDirectory, locatedName)
      if (!city) throw new Error('CITY_NOT_IN_DIRECTORY')
      applyCity(city, 'CURRENT')
    } catch {
      if (revision === relocationRevision) showToast('定位失败，请手动选择城市')
    } finally {
      if (revision === relocationRevision) isRelocating.value = false
    }
  }

  return {
    isOpen,
    directory,
    isLoading,
    error,
    recentCities,
    isRelocating,
    open,
    close,
    select,
    relocate,
    loadDirectory,
  }
}
