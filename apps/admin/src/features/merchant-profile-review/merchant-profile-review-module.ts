import { Stamp } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 商家资料审核（契约 openapi-ops-merchant-profile.yaml 1.0.0）：只给平台运营。 */
export const merchantProfileReviewModule: AdminModule = {
  key: 'merchant-profile-review',
  menu: [
    { path: '/merchant-profiles', title: '商家资料审核', icon: Stamp, group: 'PLATFORM', roles: ROLES, order: 33 },
  ],
  routes: [
    {
      path: '/merchant-profiles',
      name: 'merchant-profile-reviews',
      component: () => import('./MerchantProfileReviewListPage.vue'),
      meta: { title: '商家资料审核', roles: ROLES },
    },
    {
      path: '/merchant-profiles/:submissionId',
      name: 'merchant-profile-review-detail',
      component: () => import('./MerchantProfileReviewDetailPage.vue'),
      meta: { title: '商家资料详情', roles: ROLES },
    },
  ],
}
