import type {
  InspectionOrderPage,
  InspectionOrderSummary,
} from '../../domain/service-flow/order-center'
import {
  buildInspectionBookingSubmittedUrl,
  buildInspectionDeliveryUrl,
  buildInspectionFulfillmentUrl,
  buildInspectionPlanConfirmationUrl,
} from '../../navigation/inspection-routes'

export interface LatestRequestCoordinator {
  begin(): number
  isLatest(requestId: number): boolean
}

export type LatestRequestResult<T> =
  | { status: 'success'; value: T }
  | { status: 'error'; error: unknown }
  | { status: 'discarded' }

export function createLatestRequestCoordinator(): LatestRequestCoordinator {
  let latestRequestId = 0
  return {
    begin: () => ++latestRequestId,
    isLatest: (requestId) => requestId === latestRequestId,
  }
}

export async function runLatestRequest<T>(
  coordinator: LatestRequestCoordinator,
  request: () => Promise<T>,
): Promise<LatestRequestResult<T>> {
  const requestId = coordinator.begin()
  try {
    const value = await request()
    return coordinator.isLatest(requestId)
      ? { status: 'success', value }
      : { status: 'discarded' }
  } catch (error) {
    return coordinator.isLatest(requestId)
      ? { status: 'error', error }
      : { status: 'discarded' }
  }
}

export type LatestInspectionOrdersResult =
  | LatestRequestResult<InspectionOrderPage>
  | { status: 'unauthenticated' }

export function requestLatestInspectionOrders(
  coordinator: LatestRequestCoordinator,
  getAccessToken: () => string | null,
  listOrders: (input: {
    stage: 'ALL'
    page: number
    size: number
  }) => Promise<InspectionOrderPage>,
): Promise<LatestInspectionOrdersResult> {
  if (!getAccessToken()) {
    coordinator.begin()
    return Promise.resolve({ status: 'unauthenticated' })
  }
  return runLatestRequest(coordinator, () => listOrders({
    stage: 'ALL',
    page: 1,
    size: 3,
  }))
}

export function shouldNavigateBackToInspectionOrderCenter(
  previousRoute: string | undefined,
): boolean {
  return previousRoute === 'pages/mine/inspection-orders'
}

export function getPreviousInspectionPageRoute(
  pages: ArrayLike<{ route?: string }>,
): string | undefined {
  return pages[pages.length - 2]?.route
}

export type InspectionDeliveryBackAction = 'navigateBack' | 'reLaunchOrderCenter'

export function resolveInspectionDeliveryBackAction(
  previousRoute: string | undefined,
  hasOrderCenterHistory = shouldNavigateBackToInspectionOrderCenter(previousRoute),
): InspectionDeliveryBackAction {
  return previousRoute === 'pages/service/inspection/fulfillment'
    || hasOrderCenterHistory
    ? 'navigateBack'
    : 'reLaunchOrderCenter'
}

export function resolveInspectionOrderDestination(
  order: InspectionOrderSummary,
): string | null {
  switch (order.stage) {
    case 'AWAITING_PLAN':
    case 'CANCELLED':
      return order.reservationId
        ? buildInspectionBookingSubmittedUrl(order.reservationId)
        : null
    case 'AWAITING_CONFIRMATION':
      return order.currentPlanId
        ? buildInspectionPlanConfirmationUrl(order.currentPlanId)
        : null
    case 'IN_PROGRESS':
      return order.serviceOrderId
        ? buildInspectionFulfillmentUrl(order.serviceOrderId)
        : null
    case 'AWAITING_ACCEPTANCE':
    case 'COMPLETED':
      return order.serviceOrderId
        ? buildInspectionDeliveryUrl(order.serviceOrderId)
        : null
  }
}
