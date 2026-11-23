import { School } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 场馆与设备（契约 openapi-ops-venue.yaml）：只给平台运营。 */
export const venueModule: AdminModule = {
  key: 'venue',
  menu: [
    { path: '/venues', title: '场馆与设备', icon: School, group: 'PLATFORM', roles: ROLES, order: 50 },
  ],
  routes: [
    {
      path: '/venues',
      name: 'venues',
      component: () => import('./VenueListPage.vue'),
      meta: { title: '场馆与设备', roles: ROLES },
    },
    {
      path: '/venues/new',
      name: 'venue-new',
      component: () => import('./VenueEditPage.vue'),
      meta: { title: '新建场馆', roles: ROLES },
    },
    {
      path: '/venues/:venueId',
      name: 'venue-detail',
      component: () => import('./VenueEditPage.vue'),
      meta: { title: '场馆详情', roles: ROLES },
    },
  ],
}
