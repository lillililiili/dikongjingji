import { describe, expect, it } from 'vitest'

import type { CityDirectory, CityServiceStatus, PlatformCity } from '@/shared/city/domain/city-directory'
import homeSource from '../../../pages/home/index.vue?raw'
import {
  cityClosedTitle,
  isCityClosed,
  openCityToSwitchTo,
  resolveCityOpening,
  restoreHomeCity,
} from '../application/city-opening'

function city(code: string, name: string, serviceStatus?: CityServiceStatus): PlatformCity {
  return { code, name, pinyin: name, initials: name[0], provinceCode: '370000', provinceName: '山东省', serviceStatus }
}

function directory(cities: PlatformCity[], openCities?: PlatformCity[]): CityDirectory {
  return {
    hotCities: [],
    provinces: [{ code: '370000', name: '山东省', pinyin: 'shandong', initials: 'S', cities }],
    openCities,
  }
}

const SHANDONG = directory([
  city('370100', '济南市', 'OPEN'),
  city('370500', '东营市', 'OPEN'),
  city('370700', '潍坊市', 'PREPARING'),
  city('370800', '济宁市', 'CLOSED'),
  city('370900', '泰安市'),
])

describe('city opening (board CityClosed, openapi-platform-city 1.1.0)', () => {
  it('restores the manual city from the current directory before the configured fallback', async () => {
    const configured = { code: '370500', name: '东营市' }
    await expect(restoreHomeCity('370700', configured, async () => SHANDONG)).resolves.toMatchObject({ code: '370700', name: '潍坊市' })
    await expect(restoreHomeCity('removed-city', configured, async () => SHANDONG)).resolves.toEqual(configured)
    await expect(restoreHomeCity(undefined, null, async () => SHANDONG)).resolves.toEqual({ code: '370100', name: '济南市' })
    const unavailable = async (): Promise<CityDirectory> => { throw new Error('city directory offline') }
    await expect(restoreHomeCity('370700', configured, unavailable)).resolves.toEqual(configured)
    await expect(restoreHomeCity('370700', null, unavailable)).rejects.toThrow('city directory offline')
  })

  it('reads the status the operators keep in the city directory', () => {
    expect(resolveCityOpening(SHANDONG, '370500')).toBe('OPEN')
    expect(resolveCityOpening(SHANDONG, '370700')).toBe('PREPARING')
    expect(resolveCityOpening(SHANDONG, '370800')).toBe('CLOSED')
    expect(isCityClosed('PREPARING')).toBe(true)
    expect(isCityClosed('CLOSED')).toBe(true)
    expect(isCityClosed('OPEN')).toBe(false)
  })

  it('leaves the home page as it is without a status, an unknown city or a directory', () => {
    expect(resolveCityOpening(SHANDONG, '370900')).toBe('UNKNOWN')
    expect(resolveCityOpening(SHANDONG, '999999')).toBe('UNKNOWN')
    expect(resolveCityOpening(null, '370500')).toBe('UNKNOWN')
    expect(isCityClosed('UNKNOWN')).toBe(false)
  })

  it('offers the default city when it is open, else another open city', () => {
    const dongying = { code: '370500', name: '东营市' }
    expect(openCityToSwitchTo(SHANDONG, '370700', dongying)).toEqual(dongying)
    expect(openCityToSwitchTo(SHANDONG, '370500', dongying)).toEqual({ code: '370100', name: '济南市' })
    const weifangDefault = { code: '370700', name: '潍坊市' }
    expect(openCityToSwitchTo(SHANDONG, '370800', weifangDefault)).toEqual({ code: '370100', name: '济南市' })
    expect(openCityToSwitchTo(directory([city('370700', '潍坊市', 'PREPARING')]), '370700', dongying)).toBeNull()
    expect(openCityToSwitchTo(null, '370700', dongying)).toBeNull()
  })

  it('says 筹备中 or 未开通 in the card title', () => {
    expect(cityClosedTitle('潍坊市', 'PREPARING')).toBe('潍坊城市站筹备中')
    expect(cityClosedTitle('济宁市', 'CLOSED')).toBe('济宁暂未开通')
  })

  it('shows the panel and keeps only the nationwide channels in preview builds', () => {
    expect(homeSource).toContain('PLACE_PREVIEW_ENABLED && isCityClosed(cityOpening.value)')
    expect(homeSource).toContain('<CityClosedPanel')
    expect(homeSource).toContain(':channels="headerChannels"')
  })
})
