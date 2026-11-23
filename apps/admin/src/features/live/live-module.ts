import { VideoCamera } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 直播间（契约 openapi-ops-live.yaml）：只给平台运营。 */
export const liveModule: AdminModule = {
  key: 'live',
  menu: [
    { path: '/live', title: '直播间', icon: VideoCamera, group: 'PLATFORM', roles: ROLES, order: 65 },
  ],
  routes: [
    {
      path: '/live',
      name: 'live-rooms',
      component: () => import('./LiveRoomsPage.vue'),
      meta: { title: '直播间', roles: ROLES },
    },
  ],
}
