import { OfficeBuilding } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 机构工作台·入驻资料（契约 openapi-organization-signup.yaml 1.0.0 的工作台接口）：资质资料、附件和审核结果。 */
export const organizationSelfModule: AdminModule = {
  key: 'organization-self',
  menu: [
    { path: '/workbench/organization', title: '入驻资料', icon: OfficeBuilding, group: 'WORKBENCH', roles: ROLES, order: 83 },
  ],
  routes: [
    {
      path: '/workbench/organization',
      name: 'workbench-organization',
      component: () => import('./OrganizationSelfPage.vue'),
      meta: { title: '入驻资料', roles: ROLES },
    },
  ],
}
