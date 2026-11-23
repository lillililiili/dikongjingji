import { homeRecommendApi } from '../../recommend/data/home-recommend-api-client'
import { inspectionCatalogApi } from '../../inspection/data/service-flow/uni-booking-http-client'
import {
  DEFAULT_CATALOG_FILTERS,
  DEFAULT_CATALOG_QUERY,
} from '../../inspection/presentation/catalog/model/inspection-catalog'
import { createCityOpeningLoader } from '../application/city-opening'

// The local resources are read from public lists the home page already uses: one inspection
// service, and the 推荐 blocks' activities and venues.
export const cityOpeningLoader = createCityOpeningLoader({
  // The directory has no opening status yet (plan 08 S4-4); read it here once it does.
  directoryStatus: async () => null,
  async localResources(cityCode) {
    const [inspection, recommend] = await Promise.all([
      inspectionCatalogApi.searchServices({
        cityCode,
        query: DEFAULT_CATALOG_QUERY,
        filters: DEFAULT_CATALOG_FILTERS,
        position: null,
        page: 1,
        size: 1,
      }),
      homeRecommendApi.load(cityCode),
    ])
    return {
      inspectionServices: !inspection.cityClosed && inspection.services.length > 0,
      activities: recommend.activities.length,
      venues: recommend.venues.length + (recommend.today ? 1 : 0),
    }
  },
})
