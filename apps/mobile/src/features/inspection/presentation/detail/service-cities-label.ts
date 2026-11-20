import type { DetailServiceCity } from '../../domain/inspection-service-detail'

// The boards write the short name: 东营, not 东营市.
function shortCityName(name: string): string {
  const trimmed = name.trim()
  return trimmed.length > 2 && trimmed.endsWith('市') ? trimmed.slice(0, -1) : trimmed
}

// InspDetail board: “可服务城市 东营”, the city the page is open in. The server always lists that
// city; if it ever does not, the row names the first city and how many there are.
export function serviceCitiesLabel(
  cities: readonly DetailServiceCity[],
  cityCode: string,
): string {
  if (!cities.length) return ''
  const current = cities.find((city) => city.code === cityCode)
  if (current) return `可服务城市 ${shortCityName(current.name)}`
  const first = shortCityName(cities[0].name)
  return cities.length > 1
    ? `可服务城市 ${first}等 ${cities.length} 城`
    : `可服务城市 ${first}`
}
