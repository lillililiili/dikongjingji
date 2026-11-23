import type {
  InspectionOrderPaymentNode,
  InspectionServiceOrder,
} from '../../data/service-flow/inspection-plan-api'
import { formatPlanPrice } from '../../domain/service-flow/plan'
import { formatMonthDay } from './service-flow-format'

// S4-1 (docs/redesign/08 §5 D9): what the plan, fulfillment, delivery, order and cashier pages say
// about an order's payment nodes. A 30% down payment once the plan is confirmed, then one fee per
// visit after that visit is accepted; the server decides what is payable and what is paid.
// Boards PlanConfirm, PlanExpired, FulfillmentLater, OrderService and the four Cashier* boards.

type PaymentOrder = Pick<InspectionServiceOrder, 'status' | 'paymentMode' | 'paymentNodes' | 'visits'>

const nodesOf = (order: PaymentOrder): InspectionOrderPaymentNode[] =>
  order.paymentMode === 'NODES' ? [...(order.paymentNodes ?? [])].sort((a, b) => a.nodeNo - b.nodeNo) : []

export const isDownPayment = (node: { nodeType: string }): boolean => node.nodeType === 'DOWN_PAYMENT'

/** 首付 30% / 按次结算 70% (several visits) / 验收后付款 70% (one visit). */
export function paymentNodeGroupName(kind: 'DOWN' | 'VISITS', visitCount: number): string {
  if (kind === 'DOWN') return '首付 30%'
  return visitCount > 1 ? '按次结算 70%' : '验收后付款 70%'
}

/** The fee of one visit: 首付 / 第 2/6 次服务费 / 验收款 (the fee after acceptance of a one-visit order). */
export function paymentNodeTitle(node: InspectionOrderPaymentNode, visitCount: number): string {
  if (isDownPayment(node)) return '首付'
  return visitCount > 1 ? `第 ${node.visitNo} 次服务费` : '验收款'
}

/** The first node that can be paid now, if any (the server's payable flag). */
export function findDueNode(order: PaymentOrder): InspectionOrderPaymentNode | null {
  return nodesOf(order).find((node) => node.payable) ?? null
}

/** Every node is paid (an order without nodes is settled). */
export function isSettled(order: PaymentOrder): boolean {
  return nodesOf(order).every((node) => Boolean(node.paidAt))
}

export function visitFeeNode(order: PaymentOrder, visitNo: number): InspectionOrderPaymentNode | null {
  return nodesOf(order).find((node) => !isDownPayment(node) && node.visitNo === visitNo) ?? null
}

/** 服务费已支付 / 服务费 ¥2,170 待支付 under an accepted visit (board FulfillmentLater); null otherwise. */
export function visitFeeLine(order: PaymentOrder, visitNo: number): { text: string; paid: boolean } | null {
  const node = visitFeeNode(order, visitNo)
  const visit = order.visits.find((item) => item.visitNo === visitNo)
  if (!node || visit?.status !== 'ACCEPTED') return null
  return node.paidAt
    ? { text: '服务费已支付', paid: true }
    : { text: `服务费 ${formatPlanPrice(node.amountMinor)} 待支付`, paid: false }
}

/**
 * The reminder card of the fulfillment page: the smallest unpaid fee (board FulfillmentLater) or the
 * down payment while the order waits for it.
 */
export function describeDuePayment(order: PaymentOrder): { nodeNo: number; title: string; note: string } | null {
  const node = findDueNode(order)
  if (!node) return null
  const visitCount = order.visits.length
  if (isDownPayment(node)) {
    return {
      nodeNo: node.nodeNo,
      title: `首付 ${formatPlanPrice(node.amountMinor)} 待支付`,
      note: '首付到账后服务商开始安排履约',
    }
  }
  return {
    nodeNo: node.nodeNo,
    title: `${paymentNodeTitle(node, visitCount)} ${formatPlanPrice(node.amountMinor)} 待支付`,
    note: '不影响后续巡检，全部结清后服务单才算完成',
  }
}

export interface PaymentGroupRow {
  key: 'DOWN_PAYMENT' | 'PER_VISIT'
  name: string
  tag: string
  amountText: string
  stateText: string
  isDue: boolean
  isPaid: boolean
  isMuted: boolean
}

function refundText(node: InspectionOrderPaymentNode): string {
  const refund = node.refund
  if (!refund) return ''
  const amount = formatPlanPrice(refund.amountMinor)
  if (refund.status === 'SUCCEEDED') return `订单已取消 · ${amount} 已原路退回`
  if (refund.status === 'ABNORMAL' || refund.status === 'CLOSED') return `订单已取消 · 退款异常，平台客服处理中`
  return `订单已取消 · ${amount} 原路退回中`
}

/**
 * The two rows of the 付款节点 card on the order (board OrderService): 首付 30% and 按次结算 70%
 * (or 验收后付款 70% for one visit). Per-visit fees are summed, with how many are paid.
 */
export function describeOrderPaymentGroups(order: PaymentOrder): PaymentGroupRow[] {
  const nodes = nodesOf(order)
  if (!nodes.length) return []
  const visitCount = order.visits.length
  const cancelled = order.status === 'CANCELLED'
  const down = nodes.find(isDownPayment)
  const visits = nodes.filter((node) => !isDownPayment(node))
  const rows: PaymentGroupRow[] = []
  if (down) {
    const refund = refundText(down)
    rows.push({
      key: 'DOWN_PAYMENT',
      name: paymentNodeGroupName('DOWN', visitCount),
      tag: '',
      amountText: formatPlanPrice(down.amountMinor),
      stateText: refund
        || (down.paidAt ? `已支付 · ${formatMonthDay(down.paidAt)}`
          : cancelled ? '订单已取消，无需支付'
            : '待支付'),
      isDue: down.payable,
      isPaid: Boolean(down.paidAt) && !refund,
      isMuted: cancelled,
    })
  }
  if (visits.length) {
    const total = visits.reduce((sum, node) => sum + node.amountMinor, 0)
    const paidCount = visits.filter((node) => node.paidAt).length
    const due = visits.find((node) => node.payable)
    const pending = order.visits.find((visit) => visit.status === 'DELIVERED')
    let stateText: string
    if (cancelled) {
      stateText = '订单已取消，无需支付'
    } else if (visitCount > 1) {
      const first = visits[0].amountMinor
      stateText = `每次验收后支付 ${formatPlanPrice(first)} · 已付 ${paidCount}/${visits.length}`
    } else {
      const only = visits[0]
      stateText = only.paidAt ? `已支付 · ${formatMonthDay(only.paidAt)}` : only.payable ? '待支付' : '验收后支付'
    }
    rows.push({
      key: 'PER_VISIT',
      name: paymentNodeGroupName('VISITS', visitCount),
      tag: due
        ? (visitCount > 1 ? `第 ${due.visitNo} 次待支付` : '')
        : pending && !cancelled ? `第 ${pending.visitNo} 次待验收` : '',
      amountText: formatPlanPrice(total),
      stateText,
      isDue: Boolean(due),
      isPaid: paidCount === visits.length,
      isMuted: cancelled,
    })
  }
  return rows
}

// ---- 收银台和支付结果（来源 INSPECTION） ----

export interface InspectionCashierView {
  /** The pill under the amount: 付款节点 1 · 首付 30%（方案 v2 已确认）. */
  dueChip: string
  /** 付款节点 row before payment. */
  nodeRow: string
  /** 付款主体 row: 东营港务物流（企业身份）/ 王女士（个人身份）. */
  payer: string | null
  paidTitle: string
  paidNote: string
  failedNote: string
  /** After payment: where the primary button leads, and an optional way back to the order. */
  next: { label: string; target: 'FULFILLMENT' | 'ORDER' }
  back: { label: string; target: 'FULFILLMENT' | 'ORDER' } | null
}

/**
 * What the cashier and the result page say for one inspection node (boards CashierFirst, CashierFinal,
 * CashierOneFirst, CashierOneFinal). `nodeNo` is the node the trade order pays.
 */
export function describeInspectionCashier(
  order: Pick<InspectionServiceOrder, 'status' | 'paymentMode' | 'paymentNodes' | 'visits' | 'customerParty'>,
  planVersion: number | null,
  nodeNo: number,
): InspectionCashierView | null {
  const nodes = nodesOf(order)
  const node = nodes.find((item) => item.nodeNo === nodeNo)
  if (!node) return null
  const visitCount = order.visits.length
  const visits = nodes.filter((item) => !isDownPayment(item))
  const party = order.customerParty
  const payer = party
    ? party.organizationName ? `${party.organizationName}（企业身份）` : `${party.operatorName}（个人身份）`
    : null
  const amount = formatPlanPrice(node.amountMinor)
  if (isDownPayment(node)) {
    const rest = visits.reduce((sum, item) => sum + item.amountMinor, 0)
    return {
      dueChip: `付款节点 ${node.nodeNo} · 首付 30%${planVersion ? `（方案 v${planVersion} 已确认）` : ''}`,
      nodeRow: visitCount > 1 ? `首付 30% ${amount} · 其余按次结算` : `首付 ${amount} · 验收后付 ${formatPlanPrice(rest)}`,
      payer,
      paidTitle: '首付已支付',
      paidNote: '服务商将按约定开始履约',
      failedNote: '首付仍为待支付',
      next: { label: '查看履约安排', target: 'FULFILLMENT' },
      back: null,
    }
  }
  if (visitCount > 1) {
    const unpaidAfter = visits.filter((item) => !item.paidAt && item.nodeNo !== node.nodeNo).length
    return {
      dueChip: `按次结算 · 第 ${node.visitNo}/${visitCount} 次服务费（第 ${node.visitNo} 次已验收）`,
      nodeRow: `首付已付 · 第 ${node.visitNo}/${visitCount} 次 ${amount}`,
      payer,
      paidTitle: `第 ${node.visitNo} 次服务费已支付`,
      paidNote: unpaidAfter > 0 ? `其余 ${unpaidAfter} 次在每次验收后支付` : '全部款项已结清，服务单已完成',
      failedNote: `第 ${node.visitNo} 次服务费仍为待支付`,
      next: { label: '查看后续巡检进度', target: 'FULFILLMENT' },
      back: { label: '返回服务订单', target: 'ORDER' },
    }
  }
  return {
    dueChip: `付款节点 ${node.nodeNo} · 验收后付款 70%（已验收）`,
    nodeRow: `首付已付 · 验收后付 ${amount}`,
    payer,
    paidTitle: '验收款已支付',
    paidNote: '全部款项已结清，服务单已完成',
    failedNote: '验收款仍为待支付',
    next: { label: '查看服务进度', target: 'FULFILLMENT' },
    back: null,
  }
}
