import { Aim } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/**
 * 服务商巡检工作台（契约 openapi-inspection-service-flow.yaml 2.3.0）：本机构的巡检预约、出方案、安排团队设备、
 * 排期、推进每次巡检、上传交付。只给机构操作员，只看本机构（后端按令牌里的机构过滤）。
 */
export const inspectionWorkbenchModule: AdminModule = {
  key: 'inspection-workbench',
  menu: [
    { path: '/workbench/inspection', title: '巡检预约', icon: Aim, group: 'WORKBENCH', roles: ROLES, order: 60 },
  ],
  routes: [
    {
      path: '/workbench/inspection',
      name: 'workbench-inspection',
      component: () => import('./InspectionReservationListPage.vue'),
      meta: { title: '巡检预约', roles: ROLES },
    },
    {
      path: '/workbench/inspection/:reservationId',
      name: 'workbench-inspection-detail',
      component: () => import('./InspectionReservationDetailPage.vue'),
      meta: { title: '巡检预约详情', roles: ROLES },
    },
  ],
}
