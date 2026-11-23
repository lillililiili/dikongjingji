import { Trophy } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/**
 * 机构工作台的“活动结果”（S5-3，契约 openapi-activity.yaml 1.4.0 的 /api/v1/provider/activity-*）：
 * 本机构主办或承办的活动，给已签到的参与人录入完成情况、成绩和证书说明。
 */
export const activityResultsModule: AdminModule = {
  key: 'activity-results',
  menu: [
    { path: '/workbench/activity-results', title: '活动结果', icon: Trophy, group: 'WORKBENCH', roles: ROLES, order: 65 },
  ],
  routes: [
    {
      path: '/workbench/activity-results',
      name: 'workbench-activity-results',
      component: () => import('./ActivityResultSessionsPage.vue'),
      meta: { title: '活动结果', roles: ROLES },
    },
    {
      path: '/workbench/activity-results/:sessionId',
      name: 'workbench-activity-roster',
      component: () => import('./ActivityResultRosterPage.vue'),
      meta: { title: '名单与结果', roles: ROLES },
    },
  ],
}
