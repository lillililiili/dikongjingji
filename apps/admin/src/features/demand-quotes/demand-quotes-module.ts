import { PriceTag } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 服务商的需求报价（契约 openapi-demand.yaml 的 /api/v1/provider/demands）：报价、改价、撤回。 */
export const demandQuotesModule: AdminModule = {
  key: 'demand-quotes',
  menu: [
    { path: '/workbench/demands', title: '需求报价', icon: PriceTag, group: 'WORKBENCH', roles: ROLES, order: 50 },
  ],
  routes: [
    {
      path: '/workbench/demands',
      name: 'workbench-demands',
      component: () => import('./DemandListPage.vue'),
      meta: { title: '需求报价', roles: ROLES },
    },
    {
      path: '/workbench/demands/:demandId',
      name: 'workbench-demand',
      component: () => import('./DemandDetailPage.vue'),
      meta: { title: '需求详情', roles: ROLES },
    },
  ],
}
