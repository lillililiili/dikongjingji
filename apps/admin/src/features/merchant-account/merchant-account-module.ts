import { Wallet } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家工作台·收款账户（契约 openapi-trade.yaml 0.5.0 readMyMerchantAccount）：只读。 */
export const merchantAccountModule: AdminModule = {
  key: 'merchant-account',
  menu: [
    {
      path: '/workbench/merchant-account',
      title: '收款账户',
      icon: Wallet,
      group: 'WORKBENCH',
      roles: ROLES,
      order: 90,
    },
  ],
  routes: [
    {
      path: '/workbench/merchant-account',
      name: 'workbench-merchant-account',
      component: () => import('./MerchantAccountPage.vue'),
      meta: { title: '收款账户', roles: ROLES },
    },
  ],
}
