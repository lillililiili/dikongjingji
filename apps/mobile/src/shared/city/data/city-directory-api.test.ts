import { describe, expect, it, vi } from 'vitest'

import { cityServiceStatus } from '../domain/city-directory'
import { createCityDirectoryApi } from './city-directory-api'

const dongying = {
  code: '370500',
  name: '东营市',
  pinyin: 'dongyingshi',
  initials: 'dys',
  provinceCode: '370000',
  provinceName: '山东省',
}

function apiReturning(response: unknown) {
  return createCityDirectoryApi({ request: vi.fn().mockResolvedValue(response) as never })
}

describe('平台城市目录（openapi-platform-city.yaml 1.1.0）', () => {
  it('读出每个城市的开通状态和已开通、筹备中两组', async () => {
    const jinan = { ...dongying, code: '370100', name: '济南市', serviceStatus: 'PREPARING' }
    const open = { ...dongying, serviceStatus: 'OPEN' }

    const directory = await apiReturning({
      hotCities: [open],
      provinces: [{ code: '370000', name: '山东省', pinyin: 'shandongsheng', initials: 'sds', cities: [jinan, open] }],
      openCities: [open],
      preparingCities: [jinan],
    }).load()

    expect(directory.openCities?.map((city) => [city.name, city.serviceStatus])).toEqual([['东营市', 'OPEN']])
    expect(directory.preparingCities?.map((city) => city.serviceStatus)).toEqual(['PREPARING'])
    expect(directory.provinces[0]!.cities.map((city) => city.serviceStatus)).toEqual(['PREPARING', 'OPEN'])
  })

  it('1.0.0 的目录（没有开通状态和两组）当作都未开通，不报错', async () => {
    const directory = await apiReturning({ hotCities: [dongying], provinces: [] }).load()

    expect(directory.openCities).toBeUndefined()
    expect(directory.preparingCities).toBeUndefined()
    expect(cityServiceStatus(directory.hotCities[0])).toBe('CLOSED')
  })

  it('不认识的开通状态算目录无效', async () => {
    await expect(apiReturning({
      hotCities: [{ ...dongying, serviceStatus: 'SOON' }],
      provinces: [],
    }).load()).rejects.toThrow('CITY_DIRECTORY_INVALID')
  })
})
