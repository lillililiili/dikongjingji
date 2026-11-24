import { Goods } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/**
 * 商家工作台·我的商品（契约 openapi-merchant-catalog.yaml 1.0.0）：商家自己建商品、改资料、管规格和本家的供给、上下架。
 * 页面和运营端“商品与供给”是同一套（merchant 模式：接口前缀 /api/v1/provider/catalog、不选商家、不显示交易条款）。
 */
export const merchantCatalogModule: AdminModule = {
  key: 'merchant-catalog',
  menu: [
    { path: '/workbench/products', title: '我的商品', icon: Goods, group: 'WORKBENCH', roles: ROLES, order: 12 },
  ],
  routes: [
    {
      path: '/workbench/products',
      name: 'workbench-products',
      component: () => import('@/features/catalog/ProductListPage.vue'),
      props: { merchant: true },
      meta: { title: '我的商品', roles: ROLES },
    },
    {
      path: '/workbench/products/:productId',
      name: 'workbench-product-detail',
      component: () => import('@/features/catalog/ProductDetailPage.vue'),
      props: { merchant: true },
      meta: { title: '商品详情', roles: ROLES },
    },
  ],
}
