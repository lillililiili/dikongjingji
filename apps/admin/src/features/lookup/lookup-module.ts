import { Search, User } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR', 'PLATFORM_SUPPORT'] as const

/** 订单查询、用户查询（契约 openapi-ops-lookup.yaml）：平台运营和平台客服都能用，放在“客服”分组最前面。 */
export const lookupModule: AdminModule = {
  key: 'lookup',
  menu: [
    { path: '/lookup/orders', title: '订单查询', icon: Search, group: 'SUPPORT', roles: ROLES, order: 1 },
    { path: '/lookup/users', title: '用户查询', icon: User, group: 'SUPPORT', roles: ROLES, order: 2 },
  ],
  routes: [
    {
      path: '/lookup/orders',
      name: 'ops-orders',
      component: () => import('./OrderLookupPage.vue'),
      meta: { title: '订单查询', roles: ROLES },
    },
    {
      path: '/lookup/orders/:orderId',
      name: 'ops-order-detail',
      component: () => import('./OrderLookupDetailPage.vue'),
      meta: { title: '订单详情', roles: ROLES },
    },
    {
      path: '/lookup/users',
      name: 'ops-users',
      component: () => import('./UserLookupPage.vue'),
      meta: { title: '用户查询', roles: ROLES },
    },
    {
      path: '/lookup/users/:userId',
      name: 'ops-user-detail',
      component: () => import('./UserLookupDetailPage.vue'),
      meta: { title: '用户详情', roles: ROLES },
    },
  ],
}
