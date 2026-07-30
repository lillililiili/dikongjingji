import type { PlatformCity } from '../domain/city-directory'

const MAX_RECENT_CITIES = 3
const STORAGE_KEY = 'platform.recent-cities'

export interface CityStorage {
  get(key: string): unknown
  set(key: string, value: string): void
}

export interface RecentCityStorage {
  load(): PlatformCity[]
  save(city: PlatformCity): void
}

function isPlatformCity(value: unknown): value is PlatformCity {
  if (!value || typeof value !== 'object') return false
  const city = value as Record<string, unknown>
  return typeof city.code === 'string'
    && typeof city.name === 'string'
    && typeof city.pinyin === 'string'
    && typeof city.initials === 'string'
    && typeof city.provinceCode === 'string'
    && typeof city.provinceName === 'string'
}

function parseCities(value: unknown): PlatformCity[] {
  if (typeof value !== 'string') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter(isPlatformCity) : []
  } catch {
    return []
  }
}

export function createRecentCityStorage(
  storage: CityStorage,
  key = STORAGE_KEY,
): RecentCityStorage {
  return {
    load(): PlatformCity[] {
      return parseCities(storage.get(key))
    },
    save(city: PlatformCity): void {
      const next = [
        city,
        ...this.load().filter((item) => item.code !== city.code),
      ].slice(0, MAX_RECENT_CITIES)
      storage.set(key, JSON.stringify(next))
    },
  }
}
