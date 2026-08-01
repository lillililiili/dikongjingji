import { describe, expect, it } from 'vitest'

import {
  findCityByLocatedName,
  searchCities,
  sortCities,
  sortProvinces,
  type CityDirectory,
  type PlatformCity,
  type PlatformProvince,
} from './city-directory'

const jinan: PlatformCity = {
  code: '370100',
  name: '济南市',
  pinyin: 'jinanshi',
  initials: 'jns',
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

const dongying: PlatformCity = {
  code: '370500',
  name: '东营市',
  pinyin: 'dongyingshi',
  initials: 'dys',
  provinceCode: '370000',
  provinceName: '山东省',
}

const shandong: PlatformProvince = {
  code: '370000',
  name: '山东省',
  pinyin: 'shandongsheng',
  initials: 'sds',
  cities: [qingdao, jinan, dongying],
}

const anhui: PlatformProvince = {
  code: '340000',
  name: '安徽省',
  pinyin: 'anhuisheng',
  initials: 'ahs',
  cities: [],
}

const directory: CityDirectory = {
  hotCities: [jinan],
  provinces: [shandong],
}

describe('city directory domain', () => {
  it('sorts provinces and cities by initials, pinyin, then code', () => {
    expect(sortProvinces([shandong, anhui]).map((item) => item.name))
      .toEqual(['安徽省', '山东省'])
    expect(sortCities(shandong.cities).map((item) => item.name))
      .toEqual(['东营市', '济南市', '青岛市'])
  })

  it.each([
    ['济南', ['370100']],
    ['jinan', ['370100']],
    ['jns', ['370100']],
  ])('searches Chinese, pinyin, and initials: %s', (keyword, codes) => {
    expect(searchCities(directory, keyword).map((city) => city.code))
      .toEqual(codes)
  })

  it('matches located native city names without using telephone area codes', () => {
    expect(findCityByLocatedName(directory, '济南市')?.code).toBe('370100')
    expect(findCityByLocatedName(directory, '济南')?.code).toBe('370100')
  })
})
