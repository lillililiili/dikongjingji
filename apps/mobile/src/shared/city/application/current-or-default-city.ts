import type { CurrentCity } from '../state/current-city'

/**
 * The shared current city, or — when a page is opened by a link before any page chose one — the
 * build's default city, as the home page does (VITE_INSPECTION_DEFAULT_CITY_*).
 */
export function currentOrDefaultCity(current: CurrentCity | null): CurrentCity | null {
  if (current) return current
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  return code && name ? { code, name } : null
}
