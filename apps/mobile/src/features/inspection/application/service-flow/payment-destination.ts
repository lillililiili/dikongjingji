import { buildCashierUrl, buildPayResultUrl } from '../../../trade/navigation/trade-routes'

import type { InspectionPaymentLaunch } from '../../data/service-flow/inspection-plan-api'

// S4-1: an open trade order is paid on the cashier; one already paid shows its result.
export function paymentDestinationUrl(launch: InspectionPaymentLaunch): string {
  return launch.paid ? buildPayResultUrl(launch.tradeOrderId) : buildCashierUrl(launch.tradeOrderId)
}
