import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CityDirectory, PlatformCity } from '../domain/city-directory'
import { useCurrentCityStore } from '../state/current-city'
import { useCitySheet, type CitySheetApi } from './use-city-sheet'

const jinan: PlatformCity = {
  code: '370100',
  name: '济南市',
  pinyin: 'jinan',
  initials: 'jn',
  provinceCode: '370000',
  provinceName: '山东省',
}

const directory = {
  hotCities: [],
  provinces: [{
    code: '370000',
    name: '山东省',
    pinyin: 'shandong',
    cities: [jinan],
  }],
} as unknown as CityDirectory

function createApi(): CitySheetApi & {
  load: ReturnType<typeof vi.fn>
  recordSearchSelection: ReturnType<typeof vi.fn>
} {
  return {
    load: vi.fn().mockResolvedValue(directory),
    recordSearchSelection: vi.fn().mockResolvedValue(undefined),
  }
}

describe('useCitySheet', () => {
  const showToast = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    showToast.mockReset()
    vi.stubGlobal('uni', {
      getStorageSync: () => '',
      setStorageSync: () => undefined,
      getAppBaseInfo: () => ({ uniPlatform: 'web' }),
      showToast,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the directory once and keeps it for the next opening', async () => {
    const api = createApi()
    const sheet = useCitySheet(api, () => undefined)

    await sheet.open()
    sheet.close()
    await sheet.open()

    expect(sheet.isOpen.value).toBe(true)
    expect(sheet.directory.value).toEqual(directory)
    expect(api.load).toHaveBeenCalledOnce()
  })

  it('shows recent cities saved by other pages each time it opens', async () => {
    let stored = ''
    vi.stubGlobal('uni', {
      getStorageSync: () => stored,
      setStorageSync: () => undefined,
      getAppBaseInfo: () => ({ uniPlatform: 'web' }),
      showToast,
    })
    const sheet = useCitySheet(createApi(), () => undefined)
    expect(sheet.recentCities.value).toEqual([])

    stored = JSON.stringify([jinan])
    await sheet.open()

    expect(sheet.recentCities.value).toEqual([jinan])
  })

  it('reports a directory failure inside the sheet so it can be retried', async () => {
    const api = createApi()
    api.load.mockRejectedValueOnce(new Error('offline'))
    const sheet = useCitySheet(api, () => undefined)

    await sheet.open()
    expect(sheet.error.value).toBe('城市目录加载失败，请稍后重试')

    await sheet.loadDirectory()
    expect(sheet.error.value).toBe('')
    expect(sheet.directory.value).toEqual(directory)
  })

  it('stores the chosen city, closes the sheet and tells the page to reload', () => {
    const api = createApi()
    const onCitySelected = vi.fn()
    const sheet = useCitySheet(api, onCitySelected)
    sheet.isOpen.value = true

    sheet.select({ city: jinan, source: 'TREE' })

    expect(useCurrentCityStore().currentCity).toEqual({
      code: '370100',
      name: '济南市',
      provinceCode: '370000',
    })
    expect(sheet.isOpen.value).toBe(false)
    expect(onCitySelected).toHaveBeenCalledOnce()
    expect(api.recordSearchSelection).not.toHaveBeenCalled()
  })

  it('records popularity only for cities found through search', () => {
    const api = createApi()
    const sheet = useCitySheet(api, () => undefined)

    sheet.select({ city: jinan, source: 'SEARCH' })

    expect(api.recordSearchSelection).toHaveBeenCalledWith('370100')
  })

  it('asks for a reload before relocating without a directory', async () => {
    const sheet = useCitySheet(createApi(), () => undefined)

    await sheet.relocate()

    expect(showToast).toHaveBeenCalledWith({
      title: '请先重新加载城市目录',
      icon: 'none',
    })
    expect(sheet.isRelocating.value).toBe(false)
  })

  it('keeps the current city when location is unavailable', async () => {
    const api = createApi()
    const onCitySelected = vi.fn()
    const sheet = useCitySheet(api, onCitySelected)
    await sheet.open()

    await sheet.relocate()

    expect(showToast).toHaveBeenCalledWith({
      title: '定位失败，请手动选择城市',
      icon: 'none',
    })
    expect(onCitySelected).not.toHaveBeenCalled()
    expect(sheet.isRelocating.value).toBe(false)
  })
})
