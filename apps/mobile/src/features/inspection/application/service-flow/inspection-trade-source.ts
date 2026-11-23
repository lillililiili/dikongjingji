import type { DescribeSourceOrder } from '@/features/trade/application/order-source'

import { inspectionPlanApi } from '../../data/service-flow/uni-booking-http-client'
import { toSourceView } from '../../presentation/shared/inspection-cashier-source'

// S4-1: what the trade cashier and result page say for an inspection payment node. The page hosts
// pass this to the trade pages, so the trade module never reads inspection data itself.

export const describeInspectionTradeOrder: DescribeSourceOrder = async (order) => {
  const serviceOrderId = order.source === 'INSPECTION' ? order.lines[0]?.productId : null
  if (!serviceOrderId) return null
  return toSourceView(order, await inspectionPlanApi.getServiceOrder(serviceOrderId))
}
