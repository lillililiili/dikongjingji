import { Postcard } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 服务商主页资料（契约 openapi-proservice.yaml 1.2.0 的 /api/v1/provider/profile）：编辑并提交审核，看审核进度。 */
export const providerProfileModule: AdminModule = {
  key: 'provider-profile',
  menu: [
    { path: '/workbench/provider-profile', title: '服务商资料', icon: Postcard, group: 'WORKBENCH', roles: ROLES, order: 85 },
  ],
  routes: [
    {
      path: '/workbench/provider-profile',
      name: 'workbench-provider-profile',
      component: () => import('./ProviderProfilePage.vue'),
      meta: { title: '服务商资料', roles: ROLES },
    },
  ],
}
