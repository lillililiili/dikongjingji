import { ChatDotRound } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 本机构（商家、服务商）的客服会话回复（契约 openapi-support.yaml 的 /api/v1/provider/conversations）。 */
export const supportDeskModule: AdminModule = {
  key: 'support-desk',
  menu: [
    {
      path: '/workbench/conversations',
      title: '客服会话',
      icon: ChatDotRound,
      group: 'WORKBENCH',
      roles: ROLES,
      order: 70,
    },
  ],
  routes: [
    {
      path: '/workbench/conversations',
      name: 'workbench-conversations',
      component: () => import('./ConversationListPage.vue'),
      meta: { title: '客服会话', roles: ROLES },
    },
    {
      path: '/workbench/conversations/:conversationId',
      name: 'workbench-conversation',
      component: () => import('./ConversationPage.vue'),
      meta: { title: '回复客户', roles: ROLES },
    },
  ],
}
