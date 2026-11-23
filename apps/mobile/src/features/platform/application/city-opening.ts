/**
 * Whether a city is open (board CityClosed, plan 08 S5-10). The platform city directory has no
 * opening status yet (plan 08 S4-4 adds one, kept by the operators). Until it does, a city counts
 * as open when it has something local: inspection services, activities or venues. Products are not
 * counted, since goods shipped nationwide are sold in every city (the board keeps them open).
 *
 * The status, once the directory carries it, is read by `directoryStatus` and wins over the guess.
 * Anything that cannot be read leaves the home page as it is (UNKNOWN), never as "closed".
 */
export type CityOpening = 'OPEN' | 'PREPARING' | 'UNKNOWN'

export interface CityLocalResources {
  inspectionServices: boolean
  activities: number
  venues: number
}

export function resolveCityOpening(
  directoryStatus: 'OPEN' | 'PREPARING' | null,
  resources: CityLocalResources | null,
): CityOpening {
  if (directoryStatus) return directoryStatus
  if (!resources) return 'UNKNOWN'
  return resources.inspectionServices || resources.activities > 0 || resources.venues > 0 ? 'OPEN' : 'PREPARING'
}

export interface CityOpeningSources {
  // The directory's own status; null while the directory has none (today).
  directoryStatus(cityCode: string): Promise<'OPEN' | 'PREPARING' | null>
  localResources(cityCode: string): Promise<CityLocalResources>
}

export function createCityOpeningLoader(sources: CityOpeningSources): { load(cityCode: string): Promise<CityOpening> } {
  return {
    async load(cityCode) {
      try {
        const status = await sources.directoryStatus(cityCode)
        if (status) return status
      } catch {
        // Fall back to the local resources.
      }
      try {
        return resolveCityOpening(null, await sources.localResources(cityCode))
      } catch {
        return 'UNKNOWN'
      }
    },
  }
}

// The channels a closed city keeps (board CityClosed: 推荐、体验、商城, everything nationwide).
export const CLOSED_CITY_CHANNELS = ['RECOMMEND', 'EXPERIENCE', 'MALL'] as const
