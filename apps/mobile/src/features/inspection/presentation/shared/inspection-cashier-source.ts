import type {
  SourceOrderView,
  TradeOrderDestination,
} from '../../../trade/application/order-source'
import type { TradeOrder } from '../../../trade/domain/trade-order'

import type { InspectionServiceOrder } from '../../data/service-flow/inspection-plan-api'
import { describeInspectionCashier } from './payment-view'

// S4-1: the trade cashier's and result page's view of an inspection payment node, from the service
// order (boards CashierFirst, CashierFinal, CashierOneFirst, CashierOneFinal).

/** The node a trade order pays: the payment record or node that carries this trade order. */
export function nodeNoOfTradeOrder(order: InspectionServiceOrder, tradeOrderId: string): number | null {
  return order.paymentRecords?.find((record) => record.tradeOrderId === tradeOrderId)?.nodeNo
    ?? order.paymentNodes?.find((node) => node.tradeOrderId === tradeOrderId)?.nodeNo
    ?? null
}

/** 方案 v2 from the order line "服务名 · 方案 v2". */
export function planVersionOfLine(productName: string | undefined): number | null {
  const match = /方案 v(\d+)$/.exec(productName ?? '')
  return match ? Number(match[1]) : null
}

export function toSourceView(order: TradeOrder, serviceOrder: InspectionServiceOrder): SourceOrderView | null {
  const nodeNo = nodeNoOfTradeOrder(serviceOrder, order.orderId)
  if (nodeNo === null) return null
  const view = describeInspectionCashier(serviceOrder, planVersionOfLine(order.lines[0]?.productName), nodeNo)
  if (!view) return null
  const destination = (target: 'FULFILLMENT' | 'ORDER'): TradeOrderDestination => target === 'FULFILLMENT'
    ? { kind: 'INSPECTION_FULFILLMENT', serviceOrderId: serviceOrder.id }
    : { kind: 'INSPECTION_ORDER', serviceOrderId: serviceOrder.id }
  return {
    dueChip: view.dueChip,
    rows: [
      ...(view.payer ? [{ key: 'inspection-payer', label: '付款主体', value: view.payer }] : []),
      { key: 'inspection-node', label: '付款节点', value: view.nodeRow },
    ],
    paidTitle: view.paidTitle,
    paidNote: view.paidNote,
    failedNote: view.failedNote,
    next: { label: view.next.label, destination: destination(view.next.target) },
    back: view.back ? { label: view.back.label, destination: destination(view.back.target) } : null,
  }
}
