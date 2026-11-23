import type { InspectionPaymentLaunch } from '../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../data/service-flow/uni-booking-http-client'
import { paymentDestinationUrl } from './payment-destination'

// S4-1: pay one node of an inspection order. The server opens (or returns) the trade order; the App
// pays it on the trade module's cashier, or shows its result when it is already paid.

export { paymentDestinationUrl }

export async function openNodePayment(
  serviceOrderId: string,
  nodeNo: number,
  pay: (serviceOrderId: string, nodeNo: number) => Promise<InspectionPaymentLaunch> = inspectionPlanApi.payNode,
): Promise<void> {
  try {
    const launch = await pay(serviceOrderId, nodeNo)
    uni.navigateTo({ url: paymentDestinationUrl(launch) })
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '暂时无法发起支付，请稍后重试'
    uni.showToast({ title: message, icon: 'none' })
  }
}
