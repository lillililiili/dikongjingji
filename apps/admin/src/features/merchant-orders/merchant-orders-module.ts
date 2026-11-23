import { Box } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家工作台·商城订单（契约 openapi-trade.yaml 0.5.0 的商家接口）：只看本机构卖出的商城订单。 */
export const merchantOrdersModule: AdminModule = {
  key: 'merchant-orders',
  menu: [
    { path: '/workbench/mall-orders', title: '商城订单', icon: Box, group: 'WORKBENCH', roles: ROLES, order: 10 },
  ],
  routes: [
    {
      path: '/workbench/mall-orders',
      name: 'workbench-mall-orders',
      component: () => import('./MerchantOrderListPage.vue'),
      meta: { title: '商城订单', roles: ROLES },
    },
    {
      path: '/workbench/mall-orders/:orderId',
      name: 'workbench-mall-order',
      component: () => import('./MerchantOrderDetailPage.vue'),
      meta: { title: '订单详情', roles: ROLES },
    },
  ],
}
