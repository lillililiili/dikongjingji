import type { PlatformProvince } from '../city/domain/city-directory'

// 省市选择 (board RegionPick) on the platform city directory: a province, then a prefecture-level
// city. Districts are not listed; they are written in the detailed address (M13). A municipality
// is a province with one city of its own name, so picking it picks the city too.

export interface RegionPickState {
  step: 'PROVINCE' | 'CITY'
  provinceCode: string | null
  cityCode: string | null
}

export function startRegionPick(provinces: readonly PlatformProvince[], cityCode: string | null): RegionPickState {
  const province = cityCode ? provinces.find((item) => item.cities.some((city) => city.code === cityCode)) : undefined
  return province
    ? { step: 'CITY', provinceCode: province.code, cityCode }
    : { step: 'PROVINCE', provinceCode: null, cityCode: null }
}

export function isMunicipality(province: PlatformProvince): boolean {
  return province.cities.length === 1 && province.cities[0].name === province.name
}

// Picking a province clears the city and moves on to the cities (board), except a municipality.
export function pickRegionProvince(state: RegionPickState, province: PlatformProvince): RegionPickState {
  if (isMunicipality(province)) return { step: 'PROVINCE', provinceCode: province.code, cityCode: province.cities[0].code }
  if (state.provinceCode === province.code && state.cityCode) return { ...state, step: 'CITY' }
  return { step: 'CITY', provinceCode: province.code, cityCode: null }
}

export function pickRegionCity(state: RegionPickState, cityCode: string): RegionPickState {
  return { ...state, cityCode }
}

// 已选：山东省 东营市 / 北京市 / 山东省 … / 还没有选择
export function describeRegionPick(provinces: readonly PlatformProvince[], state: RegionPickState): string {
  const province = provinces.find((item) => item.code === state.provinceCode)
  if (!province) return '还没有选择'
  const city = province.cities.find((item) => item.code === state.cityCode)
  if (!city) return `${province.name} …`
  return province.name === city.name ? city.name : `${province.name} ${city.name}`
}

// The located city, as "山东省 东营市", when it is in the directory.
export function locatedRegion(provinces: readonly PlatformProvince[], cityCode: string | null): { cityCode: string; text: string } | null {
  if (!cityCode) return null
  const province = provinces.find((item) => item.cities.some((city) => city.code === cityCode))
  const city = province?.cities.find((item) => item.code === cityCode)
  if (!province || !city) return null
  return { cityCode, text: province.name === city.name ? city.name : `${province.name} ${city.name}` }
}
