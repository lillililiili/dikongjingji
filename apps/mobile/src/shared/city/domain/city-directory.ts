export interface PlatformCity {
  code: string
  name: string
  pinyin: string
  initials: string
  provinceCode: string
  provinceName: string
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
