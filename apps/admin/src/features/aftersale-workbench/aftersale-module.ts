import { RefreshLeft } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家工作台·售后（契约 openapi-trade-aftersale.yaml 1.0.0 的商家接口）：本机构卖出的商品的售后单和默认寄回地址。 */
export const aftersaleWorkbenchModule: AdminModule = {
  key: 'aftersale-workbench',
  menu: [
    { path: '/workbench/aftersales', title: '售后', icon: RefreshLeft, group: 'WORKBENCH', roles: ROLES, order: 15 },
  ],
  routes: [
    {
      path: '/workbench/aftersales',
      name: 'workbench-aftersales',
      component: () => import('./AftersaleListPage.vue'),
      meta: { title: '售后', roles: ROLES },
    },
    {
      path: '/workbench/aftersales/:aftersaleId',
      name: 'workbench-aftersale',
      component: () => import('./AftersaleDetailPage.vue'),
      meta: { title: '售后详情', roles: ROLES },
    },
  ],
}
