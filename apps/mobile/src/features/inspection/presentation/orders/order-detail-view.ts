import type {
  InspectionDelivery,
  InspectionPlan,
  InspectionServiceOrder,
} from '../../data/service-flow/inspection-plan-api'
import { formatPlanPrice } from '../../domain/service-flow/plan'
import { findNextVisit, findPendingAcceptanceVisit, isVisitFinished } from '../../domain/service-flow/service-visit'
import {
  describeDuePayment,
  describeOrderPaymentGroups,
  findDueNode,
  isSettled,
  paymentNodeTitle,
  type PaymentGroupRow,
} from '../shared/payment-view'
import { formatDateRange, formatMonthDay, formatMonthDayTime } from '../shared/service-flow-format'
import { formatVisitDate } from '../shared/service-visit-view'

// Board OrderService: one inspection service order. The page only reads; it leads to the plan,
// fulfillment or delivery page by the server's state, and to the cashier for a due node (S4-1).

export const ORDER_CHAIN_STEPS = ['待报价', '待确认', '待履约', '履约中', '待验收', '已完成'] as const

export interface OrderChainStep {
  label: string
  done: boolean
  current: boolean
}

export interface OrderHeadline {
  title: string
  text: string
  isCancelled: boolean
}

type DetailOrder = Pick<
  InspectionServiceOrder,
  'status' | 'paymentMode' | 'paymentNodes' | 'visits' | 'cancellation' | 'paymentRecords'
>

/** Where the order is on the chain; null when it was cancelled (the chain is not drawn). */
export function orderChainIndex(order: DetailOrder): number | null {
  switch (order.status) {
    case 'CANCELLED': return null
    case 'AWAITING_PAYMENT':
    case 'ARRANGING': return 2
    case 'IN_PROGRESS': return 3
    case 'DELIVERED': return 4
    case 'ACCEPTED': return isSettled(order) ? 5 : 4
  }
}

export function describeOrderChain(order: DetailOrder): OrderChainStep[] {
  const current = orderChainIndex(order)
  return ORDER_CHAIN_STEPS.map((label, index) => ({
    label,
    done: current !== null && (index < current || (index === current && current === 5)),
    current: current !== null && index === current && current !== 5,
  }))
}

export function describeOrderHeadline(order: DetailOrder): OrderHeadline {
  const pending = findPendingAcceptanceVisit(order.visits)
  const next = findNextVisit(order.visits)
  const due = findDueNode(order)
  switch (order.status) {
    case 'CANCELLED': {
      const down = order.paymentNodes?.find((node) => node.nodeType === 'DOWN_PAYMENT')
      const refund = down?.refund
      const when = order.cancellation ? `${formatMonthDayTime(order.cancellation.cancelledAt)} 取消` : '已取消'
      return {
        title: '已取消',
        text: refund
          ? `${when} · 首付 ${formatPlanPrice(refund.amountMinor)} ${refund.status === 'SUCCEEDED' ? '已原路退回' : '原路退回中'}`
          : `${when} · 没有收费`,
        isCancelled: true,
      }
    }
    case 'AWAITING_PAYMENT':
      return {
        title: '待付首付',
        text: due ? `支付首付 ${formatPlanPrice(due.amountMinor)} 后，服务商开始安排履约` : '首付到账后，服务商开始安排履约',
        isCancelled: false,
      }
    case 'ARRANGING':
      return {
        title: '待履约',
        text: next ? `第 ${next.visitNo} 次巡检 ${formatVisitDate(next)}，服务商正在安排` : '服务商正在安排团队与设备',
        isCancelled: false,
      }
    case 'IN_PROGRESS':
    case 'DELIVERED':
      if (pending) {
        return {
          title: `${order.status === 'DELIVERED' ? '待验收' : '履约中'} · 第 ${pending.visitNo} 次待验收`,
          text: pending.deliveredAt
            ? `第 ${pending.visitNo} 次报告 ${formatMonthDayTime(pending.deliveredAt)} 已交付，请核对后验收`
            : `第 ${pending.visitNo} 次报告已交付，请核对后验收`,
          isCancelled: false,
        }
      }
      return {
        title: '履约中',
        text: next ? `下次巡检 ${formatVisitDate(next)}` : '服务商按实际作业更新进度',
        isCancelled: false,
      }
    case 'ACCEPTED':
      return due
        ? {
            title: `待付款 · ${paymentNodeTitle(due, order.visits.length)}待支付`,
            text: '全部巡检已验收，付清后服务单才算完成',
            isCancelled: false,
          }
        : { title: '已完成', text: '全部巡检已验收，款项已结清', isCancelled: false }
  }
}

export interface OrderPaymentCard {
  totalText: string
  rows: PaymentGroupRow[]
  records: Array<{ key: string; text: string; timeText: string }>
}

/** The 付款节点 card, with the paid records under it (付款记录). Null without payment nodes. */
export function describeOrderPaymentCard(
  order: DetailOrder,
  totalAmountMinor: number,
): OrderPaymentCard | null {
  const rows = describeOrderPaymentGroups(order)
  if (!rows.length) return null
  const visits = order.visits.length
  const nodes = order.paymentNodes ?? []
  const records = (order.paymentRecords ?? [])
    .filter((record) => record.status === 'PAID' && record.paidAt)
    .map((record) => {
      const node = nodes.find((item) => item.nodeNo === record.nodeNo)
      const title = node ? paymentNodeTitle(node, visits) : '付款'
      return {
        key: record.attemptId,
        text: `${title} ${formatPlanPrice(record.amountMinor)}`,
        timeText: `${formatMonthDayTime(record.paidAt ?? '')} 已支付`,
      }
    })
  return { totalText: formatPlanPrice(totalAmountMinor), rows, records }
}

export function describePlanLine(plan: InspectionPlan): { title: string; whenText: string; rows: string[] } {
  const methods = plan.serviceMethods?.map((method) => method.label).join(' + ')
  const period = formatDateRange(plan.visits.map((visit) => visit.plannedDate))
  const deliverables = plan.deliverables.map((item) => item.label).join('、')
  return {
    title: `服务方案 v${plan.planVersion}`,
    whenText: plan.confirmedAt ? formatMonthDayTime(plan.confirmedAt) : '',
    rows: [
      ...(methods ? [`方式：${methods}`] : []),
      `周期：${period} · 共 ${plan.visits.length} 次`,
      ...(deliverables ? [`交付物：${deliverables}`] : []),
    ],
  }
}

export function describeFulfillmentLine(order: DetailOrder): {
  summaryText: string
  latest: { title: string; text: string; doneText: string } | null
} {
  const visits = [...order.visits].sort((a, b) => a.visitNo - b.visitNo)
  const finished = visits.filter(isVisitFinished)
  const next = findNextVisit(visits)
  const last = finished[finished.length - 1] ?? null
  return {
    summaryText: `${visits.length} 次中已完成 ${finished.length} 次${next ? ` · 下次 ${formatVisitDate(next)}` : ''}`,
    latest: last
      ? {
          title: `第 ${last.visitNo} 次巡检`,
          text: last.status === 'ACCEPTED' ? '已验收' : '报告已交付',
          doneText: `${formatMonthDay(last.startedAt ?? last.deliveredAt ?? last.plannedDate)} 完成`,
        }
      : null,
  }
}

/** The delivery card: the latest delivered visit. */
export function describeDeliveryLine(
  visitNo: number,
  delivery: InspectionDelivery,
): { whenText: string; summary: string; files: string } {
  const metric = delivery.metrics[0]
  const covered = metric ? `${metric.label}约 ${formatMetric(metric.value)}${metric.unit}` : ''
  const issues = `发现隐患 ${delivery.issueCount} 项`
  return {
    whenText: `第 ${visitNo} 次 · ${formatMonthDayTime(delivery.publishedAt)} 交付`,
    summary: [covered, issues].filter(Boolean).join(' · '),
    files: delivery.files.map((file) => file.displayName).join('、'),
  }
}

function formatMetric(value: number): string {
  return value >= 10_000 && Number.isInteger(value / 10_000)
    ? `${value / 10_000} 万`
    : value.toLocaleString('zh-CN')
}

export type OrderPrimaryAction =
  | { kind: 'PAY'; nodeNo: number; label: string }
  | { kind: 'ACCEPT'; visitId: string; label: string }
  | { kind: 'FULFILLMENT'; label: string }
  | null

/** The bottom button: pay what is due (where payment is open), else accept, else see the fulfillment. */
export function describePrimaryAction(order: DetailOrder, paymentOpen: boolean): OrderPrimaryAction {
  const due = paymentOpen ? describeDuePayment(order) : null
  const dueNode = paymentOpen ? findDueNode(order) : null
  if (due && dueNode) return { kind: 'PAY', nodeNo: due.nodeNo, label: `去支付 ${formatPlanPrice(dueNode.amountMinor)}` }
  const pending = findPendingAcceptanceVisit(order.visits)
  if (pending) return { kind: 'ACCEPT', visitId: pending.id, label: '去验收' }
  if (order.status === 'CANCELLED' || order.status === 'AWAITING_PAYMENT') return null
  return { kind: 'FULFILLMENT', label: '查看履约进度' }
}
