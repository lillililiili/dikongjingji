import { ChatLineSquare } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_SUPPORT'] as const

/** 举报与反馈（契约 openapi-ops-feedback.yaml）：只给平台客服。 */
export const feedbackModule: AdminModule = {
  key: 'feedback',
  menu: [
    { path: '/feedback', title: '举报与反馈', icon: ChatLineSquare, group: 'SUPPORT', roles: ROLES, order: 10 },
  ],
  routes: [
    {
      path: '/feedback',
      name: 'feedback',
      component: () => import('./FeedbackListPage.vue'),
      meta: { title: '举报与反馈', roles: ROLES },
    },
  ],
}
