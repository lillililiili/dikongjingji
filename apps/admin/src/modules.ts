import type { AdminModule } from '@/core/navigation/module'
import { catalogModule } from '@/features/catalog/catalog-module'
import { cityModule } from '@/features/city/city-module'
import { demandQuotesModule } from '@/features/demand-quotes/demand-quotes-module'
import { feedbackModule } from '@/features/feedback/feedback-module'
import { importModule } from '@/features/import/import-module'
import { inspectionWorkbenchModule } from '@/features/inspection-workbench/inspection-workbench-module'
import { liveHostModule } from '@/features/live-host/live-host-module'
import { mallInquiryModule } from '@/features/mall-inquiry/mall-inquiry-module'
import { merchantAccountModule } from '@/features/merchant-account/merchant-account-module'
import { merchantOrdersModule } from '@/features/merchant-orders/merchant-orders-module'
import { opsMerchantAccountModule } from '@/features/ops-merchant-account/merchant-account-module'
import { organizationModule } from '@/features/organization/organization-module'
import { rentalWorkbenchModule } from '@/features/rental-workbench/rental-workbench-module'
import { supportDeskModule } from '@/features/support-desk/support-desk-module'

/**
 * 运营端全部功能块。新增一块（比如活动与场馆、发现内容、直播间开通，或机构工作台的发货核销）：
 * 1. 在 src/features/<块>/ 下写 api、页面和 <块>-module.ts（菜单项 + 路由，roles 写清楚给哪些角色）；
 * 2. 在这里登记。布局、菜单、路由守卫都不用改。
 *
 * 机构工作台（S3-2，分组 WORKBENCH）：商家和服务商都是机构操作员（PROVIDER_OPERATOR），菜单相同，数据都只按令牌里的
 * 机构过滤（没有卖货的服务商看到的商城订单是空的）；直播主办（LIVE_HOST）另有“直播间”。
 */
export const ADMIN_MODULES: readonly AdminModule[] = [
  cityModule,
  organizationModule,
  catalogModule,
  opsMerchantAccountModule,
  importModule,
  feedbackModule,
  merchantOrdersModule,
  mallInquiryModule,
  rentalWorkbenchModule,
  demandQuotesModule,
  inspectionWorkbenchModule,
  supportDeskModule,
  liveHostModule,
  merchantAccountModule,
]
