import { describe, expect, it } from 'vitest'

import type { PlatformProvince } from '../city/domain/city-directory'
import {
  describeRegionPick,
  locatedRegion,
  pickRegionCity,
  pickRegionProvince,
  startRegionPick,
} from './region-pick'

function city(code: string, name: string, provinceCode: string, provinceName: string) {
  return { code, name, pinyin: name, initials: name[0], provinceCode, provinceName }
}

const PROVINCES: PlatformProvince[] = [
  { code: '110000', name: '北京市', pinyin: 'beijing', initials: 'B', cities: [city('110100', '北京市', '110000', '北京市')] },
  {
    code: '370000',
    name: '山东省',
    pinyin: 'shandong',
    initials: 'S',
    cities: [city('370100', '济南市', '370000', '山东省'), city('370500', '东营市', '370000', '山东省')],
  },
]

describe('region pick (board RegionPick, M13: down to the city)', () => {
  it('opens on the chosen city or on the provinces', () => {
    expect(startRegionPick(PROVINCES, '370500')).toEqual({ step: 'CITY', provinceCode: '370000', cityCode: '370500' })
    expect(startRegionPick(PROVINCES, null)).toEqual({ step: 'PROVINCE', provinceCode: null, cityCode: null })
    expect(startRegionPick(PROVINCES, '999999')).toEqual({ step: 'PROVINCE', provinceCode: null, cityCode: null })
  })

  it('clears the city when another province is picked and picks a municipality at once', () => {
    const start = startRegionPick(PROVINCES, null)
    const shandong = pickRegionProvince(start, PROVINCES[1])
    expect(shandong).toEqual({ step: 'CITY', provinceCode: '370000', cityCode: null })
    expect(describeRegionPick(PROVINCES, shandong)).toBe('山东省 …')
    const dongying = pickRegionCity(shandong, '370500')
    expect(describeRegionPick(PROVINCES, dongying)).toBe('山东省 东营市')
    expect(pickRegionProvince(dongying, PROVINCES[1])).toEqual({ ...dongying, step: 'CITY' })
    const beijing = pickRegionProvince(dongying, PROVINCES[0])
    expect(beijing.cityCode).toBe('110100')
    expect(describeRegionPick(PROVINCES, beijing)).toBe('北京市')
    expect(describeRegionPick(PROVINCES, start)).toBe('还没有选择')
  })

  it('offers the current city when it is in the directory', () => {
    expect(locatedRegion(PROVINCES, '370500')).toEqual({ cityCode: '370500', text: '山东省 东营市' })
    expect(locatedRegion(PROVINCES, '110100')).toEqual({ cityCode: '110100', text: '北京市' })
    expect(locatedRegion(PROVINCES, '999999')).toBeNull()
    expect(locatedRegion(PROVINCES, null)).toBeNull()
  })
})
