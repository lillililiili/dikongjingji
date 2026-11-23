import { Shop } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家工作台·商家资料（契约 openapi-mall-merchant.yaml 1.0.0 的工作台接口）：填、提交，平台审核后显示在 App 商家主页。 */
export const merchantProfileModule: AdminModule = {
  key: 'merchant-profile',
  menu: [
    { path: '/workbench/merchant-profile', title: '商家资料', icon: Shop, group: 'WORKBENCH', roles: ROLES, order: 85 },
  ],
  routes: [
    {
      path: '/workbench/merchant-profile',
      name: 'workbench-merchant-profile',
      component: () => import('./MerchantProfilePage.vue'),
      meta: { title: '商家资料', roles: ROLES },
    },
  ],
}
