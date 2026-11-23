import { Download } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/**
 * 对账下载（契约 openapi-trade.yaml 的 /api/v1/platform/trade/reconciliation…）：只给平台运营。
 * 商家没有对账接口，工作台里不出“对账单”。
 */
export const reconciliationModule: AdminModule = {
  key: 'reconciliation',
  menu: [
    { path: '/reconciliation', title: '对账下载', icon: Download, group: 'PLATFORM', roles: ROLES, order: 36 },
  ],
  routes: [
    {
      path: '/reconciliation',
      name: 'reconciliation',
      component: () => import('./ReconciliationPage.vue'),
      meta: { title: '对账下载', roles: ROLES },
    },
  ],
}
