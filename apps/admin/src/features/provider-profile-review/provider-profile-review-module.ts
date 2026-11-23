import { Stamp } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 服务商资料审核（契约 openapi-ops-proservice.yaml 1.1.0 的 /api/v1/ops/provider-profiles），只给平台运营。 */
export const providerProfileReviewModule: AdminModule = {
  key: 'provider-profile-review',
  menu: [
    { path: '/provider-profiles', title: '服务商资料审核', icon: Stamp, group: 'PLATFORM', roles: ROLES, order: 76 },
  ],
  routes: [
    {
      path: '/provider-profiles',
      name: 'provider-profile-reviews',
      component: () => import('./ProviderProfileReviewListPage.vue'),
      meta: { title: '服务商资料审核', roles: ROLES },
    },
    {
      path: '/provider-profiles/:providerId',
      name: 'provider-profile-review',
      component: () => import('./ProviderProfileReviewDetailPage.vue'),
      meta: { title: '服务商资料', roles: ROLES },
    },
  ],
}
