import type { CityDirectory, CityServiceStatus, PlatformCity } from '@/shared/city/domain/city-directory'

/**
 * Whether the home page shows the city as open (board CityClosed, plan 08 S5-10). The platform
 * city directory carries each city's status, kept by the operators (openapi-platform-city.yaml
 * 1.1.0: OPEN / PREPARING / CLOSED). A city the directory does not know, a directory without the
 * status (an older backend) or a directory that cannot be read leaves the home page as it is
 * (UNKNOWN), never as "closed".
 */
export type CityOpening = CityServiceStatus | 'UNKNOWN'

function allCities(directory: CityDirectory): PlatformCity[] {
  return directory.provinces.flatMap((province) => province.cities)
}

export function resolveCityOpening(directory: CityDirectory | null, cityCode: string): CityOpening {
  if (!directory) return 'UNKNOWN'
  const city = allCities(directory).find((item) => item.code === cityCode)
  return city?.serviceStatus ?? 'UNKNOWN'
}

/** Not open: the home page keeps only the nationwide channels and shows the closed card. */
export function isCityClosed(opening: CityOpening): boolean {
  return opening === 'PREPARING' || opening === 'CLOSED'
}

/**
 * The open city a closed one offers to switch to (board: 切换到东营): the build's default city when
 * it is open, otherwise the first open city of the directory; null when there is none.
 */
export function openCityToSwitchTo(
  directory: CityDirectory | null,
  currentCode: string | null,
  defaultCity: { code: string; name: string },
): { code: string; name: string } | null {
  if (!directory) return null
  const open = allCities(directory).filter((city) => city.serviceStatus === 'OPEN' && city.code !== currentCode)
  const preferred = open.find((city) => city.code === defaultCity.code) ?? directory.openCities?.find((city) => city.code !== currentCode) ?? open[0]
  return preferred ? { code: preferred.code, name: preferred.name } : null
}

// The channels a closed city keeps (board CityClosed: 推荐、体验、商城, everything nationwide).
export const CLOSED_CITY_CHANNELS = ['RECOMMEND', 'EXPERIENCE', 'MALL'] as const

// "潍坊城市站筹备中" / "潍坊暂未开通"
export function cityClosedTitle(cityName: string, opening: CityOpening): string {
  const name = cityName.length > 2 && cityName.endsWith('市') ? cityName.slice(0, -1) : cityName
  return opening === 'CLOSED' ? `${name}暂未开通` : `${name}城市站筹备中`
}
