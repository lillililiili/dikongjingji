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

const qingdao: PlatformCity = {
  code: '370200',
  name: '青岛市',
  pinyin: 'qingdaoshi',
  initials: 'qds',
  provinceCode: '370000',
  provinceName: '山东省',
}

const yantai: PlatformCity = {
  code: '370600',
  name: '烟台市',
  pinyin: 'yantaishi',
  initials: 'yts',
  provinceCode: '370000',
  provinceName: '山东省',
}

function createStorage(): { storage: CityStorage; values: Map<string, string> } {
  const values = new Map<string, string>()
  return {
    storage: {
      get: (key) => values.get(key) ?? null,
      set: (key, value) => values.set(key, value),
    },
    values,
  }
}

describe('recent city storage', () => {
  it('deduplicates selected cities and puts the latest city first', () => {
    const { storage: cityStorage } = createStorage()
    const storage = createRecentCityStorage(cityStorage)

    storage.save(dongying)
    storage.save(jinan)
    storage.save(dongying)

    expect(storage.load().map((city) => city.code))
      .toEqual(['370500', '370100'])
  })

  it('normalizes persisted cities by keeping the first unique three entries', () => {
    const { storage: cityStorage, values } = createStorage()
    values.set('platform.recent-cities', JSON.stringify([
      jinan,
      dongying,
      jinan,
      qingdao,
      yantai,
    ]))

    expect(createRecentCityStorage(cityStorage).load().map((city) => city.code))
      .toEqual(['370100', '370500', '370200'])
  })

  it('treats invalid JSON and invalid persisted entries as empty', () => {
    const { storage: cityStorage, values } = createStorage()
    const storage = createRecentCityStorage(cityStorage)

    values.set('platform.recent-cities', '{')
    expect(storage.load()).toEqual([])

    values.set('platform.recent-cities', JSON.stringify([{ code: '370100' }, '济南市']))
    expect(storage.load()).toEqual([])
  })
})
