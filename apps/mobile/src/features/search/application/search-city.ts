import { currentOrDefaultCity } from '@/shared/city/application/current-or-default-city'
import type { CurrentCity } from '@/shared/city/state/current-city'

// The city the search pages search in (see currentOrDefaultCity).
export function searchCityOrDefault(current: CurrentCity | null): CurrentCity | null {
  return currentOrDefaultCity(current)
}
