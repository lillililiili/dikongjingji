import { Finished } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/**
 * 服务商的通用服务订单（契约 openapi-service-order.yaml 1.0.0 的 /api/v1/provider/service-orders）：航拍、测绘、设备维护、
 * 技术支持需求选定报价后生成。出方案 / 改方案、标记开工、上传交付文件并提交交付、开工前取消、开工后协商终止。
 */
export const serviceOrderWorkbenchModule: AdminModule = {
  key: 'service-order-workbench',
  menu: [
    { path: '/workbench/service-orders', title: '服务订单', icon: Finished, group: 'WORKBENCH', roles: ROLES, order: 55 },
  ],
  routes: [
    {
      path: '/workbench/service-orders',
      name: 'workbench-service-orders',
      component: () => import('./ServiceOrderListPage.vue'),
      meta: { title: '服务订单', roles: ROLES },
    },
    {
      path: '/workbench/service-orders/:orderId',
      name: 'workbench-service-order',
      component: () => import('./ServiceOrderDetailPage.vue'),
      meta: { title: '服务订单详情', roles: ROLES },
    },
  ],
}
