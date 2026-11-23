import { Camera } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 专业服务目录（契约 openapi-ops-proservice.yaml）：航拍、测绘、设备维护、技术支持，只给平台运营。 */
export const proServiceModule: AdminModule = {
  key: 'proservice',
  menu: [
    { path: '/pro-services', title: '专业服务目录', icon: Camera, group: 'PLATFORM', roles: ROLES, order: 75 },
  ],
  routes: [
    {
      path: '/pro-services',
      name: 'pro-services',
      component: () => import('./ProServiceListPage.vue'),
      meta: { title: '专业服务目录', roles: ROLES },
    },
    {
      path: '/pro-services/new',
      name: 'pro-service-new',
      component: () => import('./ProServiceEditPage.vue'),
      meta: { title: '新建专业服务', roles: ROLES },
    },
    {
      path: '/pro-services/:serviceId',
      name: 'pro-service',
      component: () => import('./ProServiceEditPage.vue'),
      meta: { title: '专业服务', roles: ROLES },
    },
  ],
}
