import { PriceTag } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家工作台·商城询价（契约 openapi-mall-inquiry.yaml 1.1.0 的商家接口）：只看发给本机构的询价单。 */
export const mallInquiryModule: AdminModule = {
  key: 'mall-inquiry',
  menu: [
    { path: '/workbench/mall-inquiries', title: '商城询价', icon: PriceTag, group: 'WORKBENCH', roles: ROLES, order: 20 },
  ],
  routes: [
    {
      path: '/workbench/mall-inquiries',
      name: 'workbench-mall-inquiries',
      component: () => import('./MallInquiryListPage.vue'),
      meta: { title: '商城询价', roles: ROLES },
    },
    {
      path: '/workbench/mall-inquiries/:inquiryId',
      name: 'workbench-mall-inquiry',
      component: () => import('./MallInquiryDetailPage.vue'),
      meta: { title: '询价单', roles: ROLES },
    },
  ],
}
