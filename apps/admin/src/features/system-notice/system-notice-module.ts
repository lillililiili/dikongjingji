import { Bell } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 系统通知（S5-9，契约 openapi-notification.yaml 1.5.0 的运营部分）：只给平台运营。 */
export const systemNoticeModule: AdminModule = {
  key: 'system-notice',
  menu: [
    { path: '/system-notices', title: '系统通知', icon: Bell, group: 'PLATFORM', roles: ROLES, order: 98 },
  ],
  routes: [
    {
      path: '/system-notices',
      name: 'system-notices',
      component: () => import('./SystemNoticeListPage.vue'),
      meta: { title: '系统通知', roles: ROLES },
    },
  ],
}
