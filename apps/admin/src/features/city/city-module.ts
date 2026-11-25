import { Location } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 城市开通（契约 openapi-ops-city.yaml）：只给平台运营。 */
export const cityModule: AdminModule = {
  key: 'city',
  menu: [
    { path: '/cities', title: '城市开通', icon: Location, group: 'PLATFORM', roles: ROLES, order: 10 },
    { path: '/cities/map-places', title: '地图地点', icon: Location, group: 'PLATFORM', roles: ROLES, order: 11 },
  ],
  routes: [
    {
      path: '/cities/map-places', name: 'city-map-places',
      component: () => import('./MapPlacesPage.vue'),
      meta: { title: '地图地点', roles: ROLES },
    },
    {
      path: '/cities',
      name: 'cities',
      component: () => import('./CityListPage.vue'),
      meta: { title: '城市开通', roles: ROLES },
    },
  ],
}
