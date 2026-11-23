import { Goods } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 商品与供给（契约 openapi-ops-catalog.yaml）：只给平台运营。 */
export const catalogModule: AdminModule = {
  key: 'catalog',
  menu: [
    { path: '/catalog/products', title: '商品与供给', icon: Goods, group: 'PLATFORM', roles: ROLES, order: 30 },
  ],
  routes: [
    {
      path: '/catalog/products',
      name: 'catalog-products',
      component: () => import('./ProductListPage.vue'),
      meta: { title: '商品与供给', roles: ROLES },
    },
    {
      path: '/catalog/products/:productId',
      name: 'catalog-product-detail',
      component: () => import('./ProductDetailPage.vue'),
      meta: { title: '商品详情', roles: ROLES },
    },
  ],
}
