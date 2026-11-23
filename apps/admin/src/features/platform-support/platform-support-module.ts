import { Service } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_SUPPORT', 'PLATFORM_OPERATOR'] as const

/**
 * 平台客服会话（契约 openapi-support.yaml 1.1.0 的 /api/v1/platform/support/conversations）：客户在 App 里找“平台客服”
 * 发来的消息，平台客服和平台运营在这里看、回复、标已读。商家、服务商的会话在机构工作台的“客服会话”，不在这里。
 */
export const platformSupportModule: AdminModule = {
  key: 'platform-support',
  menu: [
    {
      path: '/support/conversations',
      title: '客服会话',
      icon: Service,
      group: 'SUPPORT',
      roles: ROLES,
      order: 5,
    },
  ],
  routes: [
    {
      path: '/support/conversations',
      name: 'support-conversations',
      component: () => import('./PlatformConversationListPage.vue'),
      meta: { title: '客服会话', roles: ROLES },
    },
    {
      path: '/support/conversations/:conversationId',
      name: 'support-conversation',
      component: () => import('./PlatformConversationPage.vue'),
      meta: { title: '回复客户', roles: ROLES },
    },
  ],
}
