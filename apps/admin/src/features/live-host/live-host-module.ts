import { VideoCamera } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['LIVE_HOST'] as const

/**
 * 直播主办工作台（契约 openapi-live.yaml 1.1.0）：本机构的直播间、建直播间、改预告、讲解清单、回看链接、
 * 隐藏弹幕、推流地址。只给直播主办角色（平台开通直播的机构），只看本机构的直播间。
 */
export const liveHostModule: AdminModule = {
  key: 'live-host',
  menu: [
    { path: '/workbench/live-rooms', title: '直播间', icon: VideoCamera, group: 'WORKBENCH', roles: ROLES, order: 80 },
  ],
  routes: [
    {
      path: '/workbench/live-rooms',
      name: 'workbench-live-rooms',
      component: () => import('./LiveRoomListPage.vue'),
      meta: { title: '直播间', roles: ROLES },
    },
    {
      path: '/workbench/live-rooms/:roomId',
      name: 'workbench-live-room',
      component: () => import('./LiveRoomDetailPage.vue'),
      meta: { title: '直播间详情', roles: ROLES },
    },
  ],
}
