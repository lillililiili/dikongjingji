import { Wallet } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 商家收款账户登记（契约 openapi-ops-catalog.yaml 的 merchant-accounts，交易模块）：只给平台运营。 */
export const opsMerchantAccountModule: AdminModule = {
  key: 'ops-merchant-account',
  menu: [
    { path: '/merchant-accounts', title: '收款账户', icon: Wallet, group: 'PLATFORM', roles: ROLES, order: 35 },
  ],
  routes: [
    {
      path: '/merchant-accounts',
      name: 'merchant-accounts',
      component: () => import('./MerchantAccountListPage.vue'),
      meta: { title: '收款账户', roles: ROLES },
    },
  ],
}
