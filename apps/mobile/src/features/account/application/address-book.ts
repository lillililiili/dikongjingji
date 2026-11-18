import type { PlatformProvince } from '@/shared/city/domain/city-directory'

// The two steps of the region picker. The platform directory stops at prefecture-level cities,
// so the district goes into the detail line.
export interface RegionChoice {
  provinceCode: string | null
  cityCode: string | null
}

export function describeRegion(
  provinces: PlatformProvince[],
  choice: RegionChoice,
): string | null {
  const province = provinces.find((item) => item.code === choice.provinceCode)
  const city = province?.cities.find((item) => item.code === choice.cityCode)
  if (!province || !city) return null
  return province.name === city.name ? city.name : `${province.name} ${city.name}`
}

export function regionOfCity(provinces: PlatformProvince[], cityCode: string): RegionChoice {
  const province = provinces.find((item) => item.cities.some((city) => city.code === cityCode))
  return { provinceCode: province?.code ?? null, cityCode: province ? cityCode : null }
}
