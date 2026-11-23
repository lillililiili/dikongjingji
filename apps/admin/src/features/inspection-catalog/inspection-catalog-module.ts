import { Aim } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 巡检服务目录（契约 openapi-ops-inspection-catalog.yaml）：类目和服务，只给平台运营。 */
export const inspectionCatalogModule: AdminModule = {
  key: 'inspection-catalog',
  menu: [
    { path: '/inspection-services', title: '巡检服务目录', icon: Aim, group: 'PLATFORM', roles: ROLES, order: 70 },
  ],
  routes: [
    {
      path: '/inspection-services',
      name: 'inspection-services',
      component: () => import('./InspectionServiceListPage.vue'),
      meta: { title: '巡检服务目录', roles: ROLES },
    },
    {
      path: '/inspection-services/new',
      name: 'inspection-service-new',
      component: () => import('./InspectionServiceEditPage.vue'),
      meta: { title: '新建巡检服务', roles: ROLES },
    },
    {
      path: '/inspection-services/:serviceId',
      name: 'inspection-service',
      component: () => import('./InspectionServiceEditPage.vue'),
      meta: { title: '巡检服务', roles: ROLES },
    },
  ],
}
