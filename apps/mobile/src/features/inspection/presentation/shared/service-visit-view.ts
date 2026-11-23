import type {
  InspectionServiceOrderStatus,
  InspectionServiceVisit,
} from '../../data/service-flow/inspection-plan-api'
import { formatMonthDay } from './service-flow-format'

export const SERVICE_ORDER_STATUS_LABELS: Record<InspectionServiceOrderStatus, string> = {
  // S4-1: confirmed, the down payment has not arrived yet; cancelled before the first visit.
  AWAITING_PAYMENT: '待付首付',
  CANCELLED: '已取消',
  ARRANGING: '待履约',
  IN_PROGRESS: '履约中',
  DELIVERED: '待验收',
  ACCEPTED: '已完成',
}

export function formatVisitDate(
  visit: Pick<InspectionServiceVisit, 'plannedDate' | 'plannedStartAt'>,
): string {
  return formatMonthDay(visit.plannedStartAt ?? visit.plannedDate)
}
