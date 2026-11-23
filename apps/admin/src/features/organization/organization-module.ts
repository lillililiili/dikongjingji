import { OfficeBuilding } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 机构入驻与资质审核（契约 openapi-ops-organization.yaml）：只给平台运营。 */
export const organizationModule: AdminModule = {
  key: 'organization',
  menu: [
    { path: '/organizations', title: '机构与资质', icon: OfficeBuilding, group: 'PLATFORM', roles: ROLES, order: 20 },
  ],
  routes: [
    {
      path: '/organizations',
      name: 'organizations',
      component: () => import('./OrganizationListPage.vue'),
      meta: { title: '机构与资质', roles: ROLES },
    },
    {
      path: '/organizations/:organizationId',
      name: 'organization-detail',
      component: () => import('./OrganizationDetailPage.vue'),
      meta: { title: '机构详情', roles: ROLES },
    },
  ],
}
