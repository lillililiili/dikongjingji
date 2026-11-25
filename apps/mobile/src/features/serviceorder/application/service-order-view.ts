import type {
  ServiceOrder,
  ServiceOrderDelivery,
  ServiceOrderRefund,
  ServiceOrderStatus,
  ServiceOrderSummary,
} from '../domain/service-order'
import {
  describeServiceOrderCustomer,
  describeServiceOrderLine,
  formatServiceAmount,
  formatServiceDay,
  formatServiceInstant,
  type ServiceOrderLineView,
} from './service-order-format'

// What the three service-order pages say (boards OrderService, PlanConfirm, DeliveryOne, made one
// visit and one payment: §28.2). Every state comes from the server's order; nothing here moves it.

export const SERVICE_ORDER_STATUS_LABELS: Record<ServiceOrderStatus, string> = {
  AWAITING_PLAN: '待出方案',
  PLAN_PENDING: '待确认方案',
  AWAITING_PAYMENT: '待付款',
  AWAITING_START: '待开工',
  IN_SERVICE: '服务中',
  DELIVERED: '待验收',
  REWORK: '整改中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  TERMINATED: '已终止',
}

export type ServiceOrderTone = 'ACTION' | 'DONE' | 'MUTED'

export function serviceOrderTone(status: ServiceOrderStatus): ServiceOrderTone {
  switch (status) {
    case 'COMPLETED': return 'DONE'
    case 'CANCELLED':
    case 'TERMINATED': return 'MUTED'
    default: return 'ACTION'
  }
}

export interface ServiceOrderRow {
  key: string
  label: string
  value: string
}

export function latestDelivery(order: Pick<ServiceOrder, 'deliveries'>): ServiceOrderDelivery | null {
  return order.deliveries.length ? order.deliveries[order.deliveries.length - 1] : null
}

function paidOrNothing(order: ServiceOrder): string {
  return order.payment.paidAt ? '已支付的款项整笔原路退回' : '没有付款，不涉及费用'
}

export interface ServiceOrderHead {
  title: string
  text: string
  tone: ServiceOrderTone
}

// The status card under the head card (OrderService board).
export function describeServiceOrderHead(order: ServiceOrder): ServiceOrderHead {
  const amount = formatServiceAmount(order.amountMinor)
  const delivery = latestDelivery(order)
  switch (order.status) {
    case 'AWAITING_PLAN':
      return { title: '待出方案 · 服务商正在出方案', text: `出方案后由你确认，确认后一次付清 ${amount}`, tone: 'ACTION' }
    case 'PLAN_PENDING':
      return {
        title: order.plan ? `待确认 · 方案 v${order.plan.version} 已出` : '待确认 · 方案已出',
        text: order.plan?.issuedAt
          ? `服务商 ${formatServiceInstant(order.plan.issuedAt)} 出具方案，请核对后确认`
          : '服务商已出方案，请核对后确认',
        tone: 'ACTION',
      }
    case 'AWAITING_PAYMENT':
      if (!order.payment.collectable) {
        return { title: `待付款 · 一次付清 ${amount}`, text: '服务商暂未开通在线收款，请先联系服务商', tone: 'ACTION' }
      }
      return {
        title: `待付款 · 一次付清 ${amount}`,
        text: order.payment.pendingPayBefore
          ? `请在 ${formatServiceInstant(order.payment.pendingPayBefore)} 前付款，超时可重新发起`
          : '方案已确认，付款后服务商安排开工',
        tone: 'ACTION',
      }
    case 'AWAITING_START':
      return {
        title: '待开工 · 已付款',
        text: '等待服务商开工；开工前可以取消，已付款项整笔原路退回',
        tone: 'ACTION',
      }
    case 'IN_SERVICE':
      return {
        title: '服务中',
        text: order.startedAt
          ? `服务商 ${formatServiceInstant(order.startedAt)} 开工，完成后提交交付`
          : '服务商正在作业，完成后提交交付',
        tone: 'ACTION',
      }
    case 'DELIVERED':
      return {
        title: delivery ? `待验收 · 第 ${delivery.round} 次交付` : '待验收 · 成果已交付',
        text: delivery?.deliveredAt
          ? `第 ${delivery.round} 次交付 ${formatServiceInstant(delivery.deliveredAt)} 已提交，请核对后验收`
          : '服务商已提交交付，请核对后验收',
        tone: 'ACTION',
      }
    case 'REWORK':
      return {
        title: delivery ? `整改中 · 第 ${delivery.round} 次交付已退回` : '整改中 · 交付已退回',
        text: '服务商整改后重新交付，再由你验收',
        tone: 'ACTION',
      }
    case 'COMPLETED':
      return {
        title: '已完成',
        text: order.completedAt ? `${formatServiceInstant(order.completedAt)} 验收通过` : '已验收通过',
        tone: 'DONE',
      }
    case 'CANCELLED': {
      const who = order.closedBy === 'PROVIDER' ? '服务商在开工前取消了订单' : '你在开工前取消了订单'
      return {
        title: '已取消',
        text: `${who}${order.closeReason ? `：${order.closeReason}` : ''}；${paidOrNothing(order)}`,
        tone: 'MUTED',
      }
    }
    case 'TERMINATED':
      return {
        title: '已终止 · 协商终止',
        text: order.closeReason ? `服务商与你协商终止：${order.closeReason}` : '服务商与你协商终止了订单',
        tone: 'MUTED',
      }
  }
}

export const SERVICE_ORDER_CHAIN_STEPS = ['待出方案', '待确认', '待付款', '待开工', '服务中', '待验收', '已完成'] as const

// The current step; null when the order left the chain (cancelled, terminated).
export function serviceOrderChainIndex(status: ServiceOrderStatus): number | null {
  switch (status) {
    case 'AWAITING_PLAN': return 0
    case 'PLAN_PENDING': return 1
    case 'AWAITING_PAYMENT': return 2
    case 'AWAITING_START': return 3
    case 'IN_SERVICE':
    case 'REWORK': return 4
    case 'DELIVERED': return 5
    case 'COMPLETED': return 6
    default: return null
  }
}

export interface ServiceOrderChainStep {
  label: string
  done: boolean
  current: boolean
  reached: boolean
}

// 整改中 takes the place of 服务中 while the provider reworks the delivery.
export function serviceOrderChain(status: ServiceOrderStatus): ServiceOrderChainStep[] | null {
  const index = serviceOrderChainIndex(status)
  if (index === null) return null
  const completed = status === 'COMPLETED'
  return SERVICE_ORDER_CHAIN_STEPS.map((step, position) => ({
    label: status === 'REWORK' && position === 4 ? '整改中' : step,
    done: position < index || completed,
    current: position === index && !completed,
    reached: position <= index,
  }))
}

export interface ServicePlanCardView {
  title: string
  tag: { label: string; tone: ServiceOrderTone }
  time: string
  rows: ServiceOrderRow[]
  // A grey line when there is no plan yet.
  note: string | null
}

function isClosed(status: ServiceOrderStatus): boolean {
  return status === 'CANCELLED' || status === 'TERMINATED'
}

export function servicePlanTag(order: ServiceOrder): { label: string; tone: ServiceOrderTone } {
  if (!order.plan) return { label: isClosed(order.status) ? '未出方案' : '待出方案', tone: isClosed(order.status) ? 'MUTED' : 'ACTION' }
  if (order.plan.confirmedAt) return { label: '已确认', tone: 'DONE' }
  return isClosed(order.status) ? { label: '已取消', tone: 'MUTED' } : { label: '待确认', tone: 'ACTION' }
}

// 服务方案 on the order page: 方式, 安排 (the plan's schedule, or the quote's before a plan) and 交付约定.
export function describeServicePlanCard(order: ServiceOrder): ServicePlanCardView {
  const plan = order.plan
  const tag = servicePlanTag(order)
  if (!plan) {
    return {
      title: '服务方案',
      tag,
      time: '',
      rows: [
        { key: 'method', label: '方式', value: order.quote.method },
        { key: 'quote', label: '报价', value: `方案 v${order.quote.quoteVersion} · ${formatServiceAmount(order.amountMinor)}` },
      ].filter((row) => row.value),
      note: isClosed(order.status) ? null : '服务商出方案后在这里核对并确认',
    }
  }
  return {
    title: `服务方案 v${plan.version}`,
    tag,
    time: formatServiceInstant(plan.confirmedAt ?? plan.issuedAt),
    rows: [
      { key: 'method', label: '方式', value: order.quote.method },
      { key: 'schedule', label: '安排', value: plan.schedule },
      { key: 'terms', label: '交付约定', value: plan.terms.join('、') },
    ].filter((row) => row.value),
    note: null,
  }
}

export function serviceRefundText(refund: ServiceOrderRefund): string {
  const amount = formatServiceAmount(refund.amountMinor)
  switch (refund.status) {
    case 'REQUESTED':
    case 'PROCESSING': return `退款中 ${amount}`
    case 'SUCCEEDED': return `已退款 ${amount}`
    case 'CLOSED': return `退款已关闭 ${amount}，请联系平台客服`
    case 'ABNORMAL': return `退款异常 ${amount}，请联系平台客服`
  }
}

export interface ServicePaymentRowView {
  key: string
  name: string
  tag: string | null
  state: string
  stateTone: 'MUTED' | 'ACTION' | 'DONE' | 'ALERT'
  amount: string
  amountTone: 'PLAIN' | 'ACCENT' | 'MUTED' | 'DONE'
}

// The single 一次付清 line: what it is waiting for, or when it was paid.
export function servicePaymentRow(order: ServiceOrder): ServicePaymentRowView {
  const base = { key: 'once', name: '一次付清', tag: null, amount: formatServiceAmount(order.payment.amountMinor) }
  if (order.payment.paidAt) {
    return { ...base, state: `已支付 · ${formatServiceDay(order.payment.paidAt)}`, stateTone: 'DONE', amountTone: 'PLAIN' }
  }
  if (isClosed(order.status)) {
    return { ...base, state: '订单已关闭，无需支付', stateTone: 'MUTED', amountTone: 'MUTED' }
  }
  if (order.status === 'AWAITING_PAYMENT') {
    return order.payment.collectable
      ? { ...base, tag: '待支付', state: '方案已确认，一次付清', stateTone: 'ACTION', amountTone: 'ACCENT' }
      : { ...base, state: '服务商暂未开通在线收款', stateTone: 'ALERT', amountTone: 'PLAIN' }
  }
  return { ...base, state: '确认方案后支付', stateTone: 'MUTED', amountTone: 'PLAIN' }
}

const REFUND_KIND_LABELS = { CANCEL: '取消退款', TERMINATE: '协商退款' } as const

// 付款 on the order page: the one payment and every refund the trade module reports.
export function servicePaymentRows(order: ServiceOrder): ServicePaymentRowView[] {
  return [
    servicePaymentRow(order),
    ...order.payment.refunds.map((refund): ServicePaymentRowView => ({
      key: `refund-${refund.refundId}`,
      name: '退款',
      tag: REFUND_KIND_LABELS[refund.kind],
      state: [serviceRefundText(refund), refund.reason].filter(Boolean).join(' · '),
      stateTone: refund.status === 'SUCCEEDED' ? 'DONE' : refund.status === 'CLOSED' || refund.status === 'ABNORMAL' ? 'ALERT' : 'ACTION',
      amount: formatServiceAmount(refund.amountMinor),
      amountTone: refund.status === 'SUCCEEDED' ? 'DONE' : 'PLAIN',
    })),
  ]
}

export interface ServiceFulfilmentView {
  state: string
  time: string
  done: boolean
}

// 服务履约 on the order page, from the first status after payment; null before.
export function describeServiceFulfilment(order: ServiceOrder): ServiceFulfilmentView | null {
  const delivery = latestDelivery(order)
  switch (order.status) {
    case 'AWAITING_START':
      return { state: '待服务商开工', time: order.payment.paidAt ? `${formatServiceDay(order.payment.paidAt)} 已付款` : '', done: false }
    case 'IN_SERVICE':
      return { state: '服务中', time: order.startedAt ? `${formatServiceDay(order.startedAt)} 开工` : '', done: false }
    case 'DELIVERED':
      return { state: `第 ${delivery?.round ?? 1} 次交付待验收`, time: delivery ? `${formatServiceDay(delivery.deliveredAt)} 交付` : '', done: false }
    case 'REWORK':
      return { state: '退回整改中', time: delivery?.reviewedAt ? `${formatServiceDay(delivery.reviewedAt)} 退回` : '', done: false }
    case 'COMPLETED':
      return { state: '已验收', time: order.completedAt ? `${formatServiceDay(order.completedAt)} 完成` : '', done: true }
    case 'TERMINATED':
      return order.startedAt ? { state: '协商终止', time: order.closedAt ? `${formatServiceDay(order.closedAt)} 终止` : '', done: false } : null
    default:
      return null
  }
}

export interface ServiceDeliverySummary {
  round: number
  time: string
  note: string
  files: string
}

// 交付物 on the order page: the latest delivery.
export function describeServiceDeliverySummary(order: ServiceOrder): ServiceDeliverySummary | null {
  const delivery = latestDelivery(order)
  if (!delivery) return null
  const names = delivery.files.map((file) => file.name)
  return {
    round: delivery.round,
    time: `第 ${delivery.round} 次 · ${formatServiceInstant(delivery.deliveredAt)} 交付`,
    note: delivery.note,
    files: names.length ? `${names.length} 个文件：${names.join('、')}` : '没有附带文件',
  }
}

export type ServiceOrderPrimaryAction =
  | { kind: 'CONFIRM_PLAN'; label: '去确认方案' }
  | { kind: 'PAY'; label: '去付款' }
  | { kind: 'ACCEPT'; label: '去验收' }

// The orange button of the order page's bar; the server's flags decide it.
export function serviceOrderPrimaryAction(order: ServiceOrder): ServiceOrderPrimaryAction | null {
  if (order.actions.confirmPlan) return { kind: 'CONFIRM_PLAN', label: '去确认方案' }
  if (order.actions.pay) return { kind: 'PAY', label: '去付款' }
  if (order.actions.accept || order.actions.rework) return { kind: 'ACCEPT', label: '去验收' }
  return null
}

export interface ServiceOrderSheetCopy {
  title: string
  text: string
  keep: string
  go: string
}

// 取消订单 (before the work starts): unpaid costs nothing, paid is refunded whole (§28.2 rule 7).
export function serviceOrderCancelCopy(order: ServiceOrder): ServiceOrderSheetCopy {
  return order.payment.paidAt
    ? {
        title: '确认取消订单？',
        text: `已支付的 ${formatServiceAmount(order.payment.amountMinor)} 将整笔原路退回，到账时间以微信支付为准。取消后服务商停止安排本次服务。`,
        keep: '暂不取消',
        go: '确认取消',
      }
    : {
        title: '确认取消订单？',
        text: '还没有付款，取消不涉及费用。取消后服务商停止安排本次服务。',
        keep: '暂不取消',
        go: '确认取消',
      }
}

// 交易主体 on the order page.
export function serviceOrderParties(order: ServiceOrder): Array<{ label: string; value: string }> {
  return [
    { label: '服务商', value: order.provider.name },
    { label: '收款方', value: `${order.provider.name} · 经平台支付渠道` },
    { label: '下单方', value: describeServiceOrderCustomer(order.customer).text },
    { label: '订单号', value: order.orderNo },
  ].filter((row) => row.value)
}

// ---------------------------------------------------------------- plan page (PlanConfirm, one visit)

export interface ServicePlanPageView {
  version: number | null
  chip: { label: string; tone: ServiceOrderTone }
  updated: string
  lines: ServiceOrderLineView[]
  total: string
  schedule: ServiceOrderRow[]
  terms: string[]
  payment: ServicePaymentRowView
}

export function describeServicePlanPage(order: ServiceOrder): ServicePlanPageView {
  const plan = order.plan
  return {
    version: plan?.version ?? null,
    chip: servicePlanTag(order),
    updated: plan ? `${formatServiceDay(plan.issuedAt)} ${plan.version > 1 ? '更新' : '出具'}` : '',
    lines: order.lines.map(describeServiceOrderLine),
    total: formatServiceAmount(plan?.amountMinor ?? order.amountMinor),
    schedule: [
      { key: 'scope', label: '范围', value: order.scope },
      { key: 'method', label: '方式', value: order.quote.method },
      { key: 'schedule', label: '安排', value: plan?.schedule ?? order.quote.schedule },
      { key: 'place', label: '地点', value: [order.cityName, order.address].filter(Boolean).join(' ') },
      { key: 'note', label: '说明', value: plan?.note ?? '' },
    ].filter((row) => row.value),
    terms: plan?.terms ?? [],
    payment: servicePaymentRow(order),
  }
}

export type ServicePlanBar =
  | { kind: 'WAITING' }
  | { kind: 'CONFIRM'; version: number }
  | { kind: 'PAY'; version: number; amount: string; collectable: boolean; by: string; at: string }
  | { kind: 'CONFIRMED'; version: number; by: string; at: string }
  | { kind: 'CLOSED' }

// The bottom of the plan page: wait, tick and confirm, pay, or what happened.
export function servicePlanBar(order: ServiceOrder): ServicePlanBar {
  const plan = order.plan
  if (isClosed(order.status)) return { kind: 'CLOSED' }
  if (!plan) return { kind: 'WAITING' }
  if (order.actions.confirmPlan && !plan.confirmedAt) return { kind: 'CONFIRM', version: plan.version }
  // "王女士 · 11/01 09:30": who confirmed and when.
  const by = plan.confirmedBy ?? ''
  const at = formatServiceInstant(plan.confirmedAt)
  if (order.status === 'AWAITING_PAYMENT') {
    return {
      kind: 'PAY',
      version: plan.version,
      amount: formatServiceAmount(order.payment.amountMinor),
      collectable: order.actions.pay && order.payment.collectable,
      by,
      at,
    }
  }
  if (plan.confirmedAt) return { kind: 'CONFIRMED', version: plan.version, by, at }
  return { kind: 'WAITING' }
}

// ---------------------------------------------------------------- delivery page (DeliveryOne)

export const SERVICE_DELIVERY_STATUS_TAGS = {
  SUBMITTED: { label: '待验收', tone: 'ACTION' },
  ACCEPTED: { label: '已验收', tone: 'DONE' },
  RETURNED: { label: '已退回', tone: 'MUTED' },
} as const satisfies Record<ServiceOrderDelivery['status'], { label: string; tone: ServiceOrderTone }>

export interface ServiceDeliveryHeadView {
  subtitle: string
  title: string
  tag: { label: string; tone: ServiceOrderTone }
  line: string
}

export function describeServiceDeliveryHead(order: ServiceOrder, delivery: ServiceOrderDelivery): ServiceDeliveryHeadView {
  return {
    subtitle: `${order.title} · ${order.provider.name}`,
    title: `第 ${delivery.round} 次交付 · ${formatServiceDay(delivery.deliveredAt)}`,
    tag: SERVICE_DELIVERY_STATUS_TAGS[delivery.status],
    line: `${formatServiceInstant(delivery.deliveredAt)} 已交付`,
  }
}

// The green banner of an accepted delivery: "11/07 15:30 · 王女士 · 验收记录已留存".
export function serviceAcceptedLine(delivery: ServiceOrderDelivery): string {
  return [formatServiceInstant(delivery.reviewedAt), delivery.reviewedBy, '验收记录已留存'].filter(Boolean).join(' · ')
}

export interface ServiceDeliveryHistoryRow {
  round: number
  title: string
  tag: { label: string; tone: ServiceOrderTone }
  reason: string | null
  files: string
}

// 历次交付, newest first; a returned one says why it was returned.
export function serviceDeliveryHistory(order: ServiceOrder): ServiceDeliveryHistoryRow[] {
  return [...order.deliveries].reverse().map((delivery) => ({
    round: delivery.round,
    title: `第 ${delivery.round} 次 · ${formatServiceInstant(delivery.deliveredAt)} 交付`,
    tag: SERVICE_DELIVERY_STATUS_TAGS[delivery.status],
    reason: delivery.status === 'RETURNED' && delivery.reviewNote ? `退回原因：${delivery.reviewNote}` : null,
    files: delivery.files.length ? `${delivery.files.length} 个文件` : '没有附带文件',
  }))
}

// "影像存档按交付约定保存 90 天": only when the chosen quote said so.
export function serviceArchiveNote(order: ServiceOrder): string | null {
  const days = order.quote.archiveDays
  return days && days > 0 ? `影像存档按交付约定保存 ${days} 天` : null
}

// Only the latest delivery waiting for acceptance can be accepted or sent back.
export function serviceDeliveryReviewable(order: ServiceOrder, delivery: ServiceOrderDelivery): boolean {
  return delivery.status === 'SUBMITTED'
    && latestDelivery(order)?.round === delivery.round
    && (order.actions.accept || order.actions.rework)
}

export function serviceAcceptRows(order: ServiceOrder, delivery: ServiceOrderDelivery): ServiceOrderRow[] {
  const customer = describeServiceOrderCustomer(order.customer)
  return [
    {
      key: 'content',
      label: '验收内容',
      value: delivery.files.length ? delivery.files.map((file) => file.name).join('、') : delivery.note,
    },
    { key: 'provider', label: '服务商', value: order.provider.name },
    { key: 'customer', label: '验收方', value: `${customer.text}（${customer.tag}）` },
    {
      key: 'amount',
      label: '合同金额',
      value: `${formatServiceAmount(order.amountMinor)}${order.payment.paidAt ? ' · 已一次付清' : ''}`,
    },
  ].filter((row) => row.value)
}

export const SERVICE_REWORK_REASON_MAX = 200

// ---------------------------------------------------------------- command failures

export interface ServiceOrderFailureInput {
  status: number
  code: string | null
  message: string
}

export interface ServiceOrderFailure {
  message: string
  // Read the order again before anything else.
  reload: boolean
}

export function describeServiceOrderFailure(failure: ServiceOrderFailureInput | null): ServiceOrderFailure {
  if (!failure) return { message: '没有成功，请稍后重试', reload: false }
  switch (failure.code) {
    case 'SERVICE_ORDER_VERSION_CONFLICT':
      return { message: '订单有更新，已刷新，请核对后再操作', reload: true }
    case 'SERVICE_ORDER_PLAN_CHANGED':
      return { message: '服务商更新了方案，已刷新，请核对最新方案后再确认', reload: true }
    case 'SERVICE_ORDER_DELIVERY_CHANGED':
      return { message: '交付有更新，已刷新，请核对最新一次交付', reload: true }
    case 'SERVICE_ORDER_STATE_INVALID':
      return { message: '订单状态已变化，已刷新', reload: true }
    case 'SERVICE_ORDER_PAYMENT_UNAVAILABLE':
      return { message: '服务商暂未开通在线收款，请先联系服务商', reload: true }
    case 'SERVICE_ORDER_TERMS_NOT_AGREED':
      return { message: '请先勾选“我已核对方案的服务范围、金额与交付约定”', reload: false }
    case 'SERVICE_ORDER_REFUND_NOT_ALLOWED':
      return { message: '暂时不能退款，请联系平台客服', reload: true }
    case 'SERVICE_ORDER_FILE_STORE_UNAVAILABLE':
      return { message: '文件暂时无法下载，请稍后重试', reload: false }
    default:
      break
  }
  if (failure.status === 412) return { message: '订单有更新，已刷新，请核对后再操作', reload: true }
  if (failure.status === 404) return { message: '订单或文件已不存在', reload: true }
  if (failure.status === 503) return { message: '文件暂时无法下载，请稍后重试', reload: false }
  if (failure.status === 0) return { message: '网络连接失败，请稍后重试', reload: false }
  return { message: failure.message || '没有成功，请稍后重试', reload: false }
}

// ---------------------------------------------------------------- 我的订单 card

export interface ServiceOrderCardLine {
  key: string
  text: string
  tone: 'MUTED' | 'PLAIN' | 'ORANGE' | 'GREEN'
}

export type ServiceOrderCardButton =
  | { kind: 'VIEW'; label: '查看订单' }
  | { kind: 'CONFIRM_PLAN'; label: '去确认方案' }
  | { kind: 'PAY'; label: '去付款' }
  | { kind: 'ACCEPT'; label: '去验收' }

export interface ServiceOrderCardView {
  statusText: string
  tone: ServiceOrderTone
  lines: ServiceOrderCardLine[]
  // 查看订单 first, then the one thing the customer has to do, if any.
  buttons: ServiceOrderCardButton[]
}

function summaryStateLine(order: ServiceOrderSummary): ServiceOrderCardLine {
  const plan = order.planVersion ? `方案 v${order.planVersion}` : ''
  switch (order.status) {
    case 'AWAITING_PLAN': return { key: 'state', text: '等待服务商出方案', tone: 'PLAIN' }
    case 'PLAN_PENDING': return { key: 'state', text: `${plan || '方案'} 待你确认`, tone: 'ORANGE' }
    case 'AWAITING_PAYMENT': return { key: 'state', text: `${plan || '方案'} 已确认 · 待一次付清`, tone: 'ORANGE' }
    case 'AWAITING_START':
      return { key: 'state', text: `${order.paidAt ? `${formatServiceDay(order.paidAt)} 已支付` : '已支付'} · 待开工`, tone: 'PLAIN' }
    case 'IN_SERVICE': return { key: 'state', text: '服务商作业中', tone: 'PLAIN' }
    case 'DELIVERED':
      return {
        key: 'state',
        text: `${order.latestDeliveryAt ? `${formatServiceInstant(order.latestDeliveryAt)} ` : ''}已交付 · 待验收`,
        tone: 'ORANGE',
      }
    case 'REWORK': return { key: 'state', text: '已退回整改 · 等待重新交付', tone: 'PLAIN' }
    case 'COMPLETED': return { key: 'state', text: '已验收', tone: 'GREEN' }
    case 'CANCELLED':
      return { key: 'state', text: order.paidAt ? '已取消 · 已付款项整笔原路退回' : '已取消 · 没有付款', tone: 'MUTED' }
    case 'TERMINATED': return { key: 'state', text: '协商终止', tone: 'MUTED' }
  }
}

export function describeServiceOrderCard(order: ServiceOrderSummary): ServiceOrderCardView {
  const buttons: ServiceOrderCardButton[] = [{ kind: 'VIEW', label: '查看订单' }]
  if (order.status === 'PLAN_PENDING') buttons.push({ kind: 'CONFIRM_PLAN', label: '去确认方案' })
  if (order.status === 'AWAITING_PAYMENT') buttons.push({ kind: 'PAY', label: '去付款' })
  if (order.status === 'DELIVERED') buttons.push({ kind: 'ACCEPT', label: '去验收' })
  return {
    statusText: SERVICE_ORDER_STATUS_LABELS[order.status],
    tone: serviceOrderTone(order.status),
    lines: [
      { key: 'customer', text: describeServiceOrderCustomer(order.customer).badge, tone: 'MUTED' },
      summaryStateLine(order),
    ],
    buttons,
  }
}

