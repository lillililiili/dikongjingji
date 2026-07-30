import { describe, expect, it } from 'vitest'

import {
  createRecentCityStorage,
  type CityStorage,
} from './recent-city-storage'
import type { PlatformCity } from '../domain/city-directory'

const jinan: PlatformCity = {
  code: '370100',
  name: '济南市',
  pinyin: 'jinanshi',
  initials: 'jns',
  provinceCode: '370000',
  provinceName: '山东省',
}

const dongying: PlatformCity = {
  code: '370500',
  name: '东营市',
  pinyin: 'dongyingshi',
  initials: 'dys',
  provinceCode: '370000',
  provinceName: '山东省',
}

function createStorage(): CityStorage {
  const values = new Map<string, string>()
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => values.set(key, value),
  }
}

describe('recent city storage', () => {
  it('deduplicates selected cities and puts the latest city first', () => {
    const storage = createRecentCityStorage(createStorage())

    storage.save(dongying)
    storage.save(jinan)
    storage.save(dongying)

    expect(storage.load().map((city) => city.code))
      .toEqual(['370500', '370100'])
  })
})
