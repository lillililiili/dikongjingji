import { Tickets, Van } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PROVIDER_OPERATOR'] as const

/** 商家的设备租赁（契约 openapi-rental.yaml 出租方接口）：询价报价、拒绝，交接、归还录入，交接前取消。 */
export const rentalWorkbenchModule: AdminModule = {
  key: 'rental-workbench',
  menu: [
    { path: '/workbench/rental-inquiries', title: '租赁询价', icon: Tickets, group: 'WORKBENCH', roles: ROLES, order: 30 },
    { path: '/workbench/rental-orders', title: '租赁订单', icon: Van, group: 'WORKBENCH', roles: ROLES, order: 40 },
  ],
  routes: [
    {
      path: '/workbench/rental-inquiries',
      name: 'workbench-rental-inquiries',
      component: () => import('./RentalInquiryListPage.vue'),
      meta: { title: '租赁询价', roles: ROLES },
    },
    {
      path: '/workbench/rental-inquiries/:inquiryId',
      name: 'workbench-rental-inquiry',
      component: () => import('./RentalInquiryDetailPage.vue'),
      meta: { title: '租赁询价详情', roles: ROLES },
    },
    {
      path: '/workbench/rental-orders',
      name: 'workbench-rental-orders',
      component: () => import('./RentalOrderListPage.vue'),
      meta: { title: '租赁订单', roles: ROLES },
    },
    {
      path: '/workbench/rental-orders/:orderId',
      name: 'workbench-rental-order',
      component: () => import('./RentalOrderDetailPage.vue'),
      meta: { title: '租赁订单详情', roles: ROLES },
    },
  ],
}
