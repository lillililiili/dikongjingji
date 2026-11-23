import { Upload } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 批量导入首批资源（契约 openapi-ops-import.yaml）：只给平台运营。 */
export const importModule: AdminModule = {
  key: 'import',
  menu: [
    { path: '/imports', title: '批量导入', icon: Upload, group: 'PLATFORM', roles: ROLES, order: 40 },
  ],
  routes: [
    {
      path: '/imports',
      name: 'imports',
      component: () => import('./ImportPage.vue'),
      meta: { title: '批量导入', roles: ROLES },
    },
    {
      path: '/imports/:batchId',
      name: 'import-batch',
      component: () => import('./ImportBatchPage.vue'),
      meta: { title: '导入批次', roles: ROLES },
    },
  ],
}
