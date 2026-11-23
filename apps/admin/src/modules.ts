import type { AdminModule } from '@/core/navigation/module'
import { cityModule } from '@/features/city/city-module'
import { contentModule } from '@/features/content/content-module'
import { feedbackModule } from '@/features/feedback/feedback-module'
import { organizationModule } from '@/features/organization/organization-module'

/**
 * 运营端全部功能块。新增一块（比如商品与供给、活动与场馆、发现内容、直播间开通，或机构工作台的发货核销）：
 * 1. 在 src/features/<块>/ 下写 api、页面和 <块>-module.ts（菜单项 + 路由，roles 写清楚给哪些角色）；
 * 2. 在这里登记。布局、菜单、路由守卫都不用改。
 */
export const ADMIN_MODULES: readonly AdminModule[] = [
  cityModule,
  organizationModule,
  contentModule,
  feedbackModule,
]
