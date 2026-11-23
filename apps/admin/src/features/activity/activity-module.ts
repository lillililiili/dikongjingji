import { Trophy } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 活动与场次（契约 openapi-ops-activity.yaml）：只给平台运营。 */
export const activityModule: AdminModule = {
  key: 'activity',
  menu: [
    { path: '/activities', title: '活动与场次', icon: Trophy, group: 'PLATFORM', roles: ROLES, order: 40 },
  ],
  routes: [
    {
      path: '/activities',
      name: 'activities',
      component: () => import('./ActivityListPage.vue'),
      meta: { title: '活动与场次', roles: ROLES },
    },
    {
      path: '/activities/new',
      name: 'activity-new',
      component: () => import('./ActivityEditPage.vue'),
      meta: { title: '新建活动', roles: ROLES },
    },
    {
      path: '/activities/:activityId',
      name: 'activity-detail',
      component: () => import('./ActivityEditPage.vue'),
      meta: { title: '活动详情', roles: ROLES },
    },
  ],
}
