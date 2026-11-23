/**
 * 城市的开通状态（openapi-platform-city.yaml 1.1.0，由运营端维护）：OPEN 已开通；PREPARING 筹备中（可先浏览全国商品与
 * 线上体验）；CLOSED 未开通（城市面板不列，只能搜索到）。
 */
export type CityServiceStatus = 'OPEN' | 'PREPARING' | 'CLOSED'

export const CITY_SERVICE_STATUS_LABELS: Record<CityServiceStatus, string> = {
  OPEN: '已开通',
  PREPARING: '筹备中',
  CLOSED: '未开通',
}

export interface PlatformCity {
  code: string
  name: string
  pinyin: string
  initials: string
  provinceCode: string
  provinceName: string
  /** 没有时（本机存的最近城市、1.0.0 的目录）当作未开通。 */
  serviceStatus?: CityServiceStatus
}

export interface PlatformProvince {
  code: string
  name: string
  pinyin: string
  initials: string
  cities: PlatformCity[]
}

export interface CityDirectory {
  hotCities: PlatformCity[]
  provinces: PlatformProvince[]
  /**
   * 已开通、筹备中两组（按城市拼音），App 城市面板只显示这两组（原型 CitySheet）。
   * 1.0.0 的目录没有这两项（当作都未开通）。
   */
  openCities?: PlatformCity[]
  preparingCities?: PlatformCity[]
}

export function cityServiceStatus(city: Pick<PlatformCity, 'serviceStatus'> | null | undefined): CityServiceStatus {
  return city?.serviceStatus ?? 'CLOSED'
}

interface SortableCityDirectoryItem {
  code: string
  pinyin: string
  initials: string
}

function compareDirectoryItems<T extends SortableCityDirectoryItem>(left: T, right: T): number {
  return left.initials.localeCompare(right.initials)
    || left.pinyin.localeCompare(right.pinyin)
    || left.code.localeCompare(right.code)
}

export function sortCities(cities: PlatformCity[]): PlatformCity[] {
  return [...cities].sort(compareDirectoryItems)
}

export function sortProvinces(provinces: PlatformProvince[]): PlatformProvince[] {
  return [...provinces].sort(compareDirectoryItems)
}

export function searchCities(
  directory: CityDirectory,
  rawKeyword: string,
): PlatformCity[] {
  const keyword = rawKeyword.trim().toLocaleLowerCase()
  if (!keyword) return []

  return sortCities(directory.provinces.flatMap((province) => province.cities)
    .filter((city) => [city.name, city.pinyin, city.initials]
      .some((value) => value.toLocaleLowerCase().includes(keyword))))
}

function removeCitySuffix(name: string): string {
  return name.endsWith('市') ? name.slice(0, -1) : name
}

export function findCityByLocatedName(
  directory: CityDirectory,
  rawName: string,
): PlatformCity | undefined {
  const locatedName = rawName.trim()
  if (!locatedName) return undefined

  return directory.provinces
    .flatMap((province) => province.cities)
    .find((city) => city.name === locatedName
      || removeCitySuffix(city.name) === removeCitySuffix(locatedName))
}
