import {
  findCityByLocatedName,
  type CityDirectory,
} from '../domain/city-directory'
import type { CurrentCity } from '../state/current-city'

export interface ResolveCurrentCityOptions {
  getCurrentCity(): CurrentCity | null
  setCurrentCity(city: CurrentCity): void
  locateCityName(): Promise<string>
  loadDirectory(): Promise<CityDirectory>
  restoreDefaultCity(): CurrentCity | Promise<CurrentCity>
}

// Location only fills an empty context: a city chosen by hand must survive tab
// switches and a location result that arrives after the choice was made.
export async function resolveCurrentCity(
  options: ResolveCurrentCityOptions,
): Promise<CurrentCity> {
  const existingCity = options.getCurrentCity()
  if (existingCity) return existingCity

  try {
    const locatedCityName = await options.locateCityName()
    const directory = await options.loadDirectory()
    const cityChosenMeanwhile = options.getCurrentCity()
    if (cityChosenMeanwhile) return cityChosenMeanwhile

    const locatedCity = findCityByLocatedName(directory, locatedCityName)
    if (!locatedCity) throw new Error('CURRENT_CITY_UNAVAILABLE')
    const city: CurrentCity = {
      code: locatedCity.code,
      name: locatedCity.name,
      provinceCode: locatedCity.provinceCode,
    }
    options.setCurrentCity(city)
    return city
  } catch {
    return options.restoreDefaultCity()
  }
}
