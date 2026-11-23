import type {
  InspectionCustomerParty,
  InspectionPlan,
  InspectionPlanItem,
  InspectionPlanVersionSummary,
  InspectionServiceOrder,
} from '../../data/service-flow/inspection-plan-api'
import { formatPlanPrice } from '../../domain/service-flow/plan'
import { describeOrderPaymentGroups } from '../shared/payment-view'
import {
  formatDateRange,
  formatDistance,
  formatMonthDay,
  formatMonthDayTime,
} from '../shared/service-flow-format'

export type PlanStatusTone = 'pending' | 'confirmed' | 'muted'

export interface PlanStatusChip {
  label: string
  tone: PlanStatusTone
}

export interface PlanChange {
  text: string
  amountText: string
  isIncrease: boolean
}

export interface PlanPreviousVersion {
  title: string
  totalText: string
  itemsText: string
}

export interface PlanQuoteRow {
  code: string
  name: string
  quantityText: string
  amountText: string
  newLabel: string
}

export interface PlanArrangementView {
  rows: Array<{ label: string; value: string }>
  visits: Array<{ visitNo: number; date: string; label: string }>
}

export interface PlanPaymentRow {
  key: string
  name: string
  amountText: string
  stateText: string
  isDue: boolean
  // (S4-1) Grey amount: the order was cancelled (boards PlanConfirm, PlanExpired).
  isMuted?: boolean
}

/** Where the plan stands for its payment nodes (S4-1, boards PlanConfirm and PlanExpired). */
export interface PlanPaymentState {
  isExpired?: boolean
  isCancelled?: boolean
  // The confirmed order: its nodes say what is paid and what is due.
  order?: Pick<InspectionServiceOrder, 'status' | 'paymentMode' | 'paymentNodes' | 'visits'> | null
}

export function describePlanStatus(state: {
  isConfirmed: boolean
  isExpired: boolean
  isCancelled: boolean
}): PlanStatusChip {
  if (state.isConfirmed) return { label: '已确认', tone: 'confirmed' }
  if (state.isCancelled) return { label: '已取消', tone: 'muted' }
  if (state.isExpired) return { label: '已过期', tone: 'muted' }
  return { label: '待确认', tone: 'pending' }
}

export function findPreviousPlanVersion(
  plan: InspectionPlan,
  versions: InspectionPlanVersionSummary[],
): InspectionPlanVersionSummary | null {
  return versions
    .filter((version) => version.planVersion < plan.planVersion)
    .sort((left, right) => right.planVersion - left.planVersion)[0] ?? null
}

export function describePlanChange(
  plan: InspectionPlan,
  previous: InspectionPlanVersionSummary | null,
): PlanChange | null {
  if (!previous) return null
  const difference = plan.totalAmountMinor - previous.totalAmountMinor
  const amountText = difference === 0
    ? ''
    : `${difference > 0 ? '+' : '-'}${formatPlanPrice(Math.abs(difference))}`
  const note = plan.changeNote?.trim()
    || (amountText ? '' : '报价不变')
  const separator = note && amountText ? '，' : ''
  return {
    text: `相比 v${previous.planVersion}：${note}${separator}`,
    amountText,
    isIncrease: difference > 0,
  }
}

// An item priced as one lump sum carries quantity 1 and unit 项, and shows no quantity.
function isLumpSum(item: InspectionPlanItem): boolean {
  return item.quantity === 1 && item.unit === '项'
}

export function describePreviousVersion(
  previous: InspectionPlanVersionSummary,
): PlanPreviousVersion {
  return {
    title: `方案 v${previous.planVersion}`,
    totalText: formatPlanPrice(previous.totalAmountMinor),
    itemsText: previous.planItems
      .map((item) => {
        const quantity = isLumpSum(item) ? '' : ` ${item.quantity} ${item.unit}`
        return `${item.name}${quantity} ${formatPlanPrice(item.lineAmountMinor)}`
      })
      .join(' + '),
  }
}

export function toQuoteRows(
  plan: InspectionPlan,
  previous: InspectionPlanVersionSummary | null,
): PlanQuoteRow[] {
  const previousCodes = new Set(previous?.planItems.map((item) => item.code))
  return plan.planItems.map((item) => ({
    code: item.code,
    name: item.name,
    quantityText: isLumpSum(item)
      ? ''
      : `${item.quantity} ${item.unit} × ${formatPlanPrice(item.unitPriceMinor)}`,
    amountText: formatPlanPrice(item.lineAmountMinor),
    newLabel: previous && !previousCodes.has(item.code)
      ? `v${plan.planVersion} 新增`
      : '',
  }))
}

export function describeServiceArrangement(
  plan: InspectionPlan,
  lineLengthMeters: number | null,
): PlanArrangementView {
  const methods = plan.serviceMethods?.map((method) => method.label).join(' + ')
  const scope = plan.scopeSummary
    || (lineLengthMeters == null ? '' : `线路约 ${formatDistance(lineLengthMeters)}`)
  const method = methods || plan.summary || ''
  const dates = plan.visits.map((visit) => visit.plannedDate)
  return {
    rows: [
      ...(scope ? [{ label: '范围', value: scope }] : []),
      ...(method ? [{ label: '方式', value: method }] : []),
      { label: '周期', value: `${formatDateRange(dates)} · 共 ${dates.length} 次` },
    ],
    visits: plan.visits.map((visit) => ({
      visitNo: visit.visitNo,
      date: formatMonthDay(visit.plannedDate),
      label: `第 ${visit.visitNo} 次 ${formatMonthDay(visit.plannedDate)}`,
    })),
  }
}

export function describeDeliveryTerms(plan: InspectionPlan): string[] {
  const deliverables = plan.deliverables.map((item) => item.label).join('、')
  const reportDueHours = plan.deliveryTerms?.reportDueHours
  const mediaRetentionDays = plan.deliveryTerms?.mediaRetentionDays
  return [
    reportDueHours
      ? `每次巡检后 ${reportDueHours} 小时内交付${deliverables}`
      : `交付内容：${deliverables}`,
    ...(mediaRetentionDays ? [`影像存档保存 ${mediaRetentionDays} 天`] : []),
    '交付文件通过受控链接下载',
  ]
}

function percentOf(amountMinor: number, totalMinor: number): string {
  return totalMinor > 0 ? ` ${Math.round((amountMinor / totalMinor) * 100)}%` : ''
}

function describePerVisitAmounts(amounts: number[]): string {
  const first = amounts[0]
  const last = amounts[amounts.length - 1]
  const lastVisit = last === first ? '' : `，最后一次 ${formatPlanPrice(last)}`
  return `每次验收后支付 ${formatPlanPrice(first)}${lastVisit}，共 ${amounts.length} 次`
}

export function describePaymentNodes(
  plan: InspectionPlan,
  isConfirmed: boolean,
  state: PlanPaymentState = {},
): PlanPaymentRow[] {
  const nodes = plan.paymentNodes ?? []
  const downPayment = nodes.find((node) => node.nodeType === 'DOWN_PAYMENT')
  const perVisit = nodes.filter((node) => node.nodeType === 'PER_VISIT')
  const perVisitTotal = perVisit.reduce((sum, node) => sum + node.amountMinor, 0)
  if (!nodes.length) return []
  // A confirmed order knows what is paid: the same rows as the order page.
  if (isConfirmed && state.order && state.order.paymentMode === 'NODES') {
    return describeOrderPaymentGroups(state.order).map((row) => ({
      key: row.key,
      name: row.name,
      amountText: row.amountText,
      stateText: row.stateText,
      isDue: row.isDue,
      isMuted: row.isMuted,
    }))
  }
  const single = perVisit.length === 1
  const fixedState = state.isCancelled
    ? '订单已取消，无需支付'
    : state.isExpired && !isConfirmed ? '方案过期后不可支付' : ''
  const rows: PlanPaymentRow[] = [
    ...(downPayment
      ? [{
          key: 'DOWN_PAYMENT',
          name: `首付${percentOf(downPayment.amountMinor, plan.totalAmountMinor)}`,
          amountText: formatPlanPrice(downPayment.amountMinor),
          stateText: fixedState || (isConfirmed ? '待支付' : '确认方案后支付'),
          isDue: !fixedState && isConfirmed,
        }]
      : []),
    ...(perVisit.length
      ? [{
          key: 'PER_VISIT',
          name: `${single ? '验收后付款' : '按次结算'}${percentOf(perVisitTotal, plan.totalAmountMinor)}`,
          amountText: formatPlanPrice(perVisitTotal),
          stateText: fixedState || (single ? '验收后支付' : describePerVisitAmounts(perVisit.map((node) => node.amountMinor))),
          isDue: false,
        }]
      : []),
  ]
  return state.isCancelled ? rows.map((row) => ({ ...row, isMuted: true })) : rows
}

export function describeCustomerParty(
  party: InspectionCustomerParty | null | undefined,
): { isOrganization: boolean; text: string } | null {
  if (!party) return null
  return party.organizationName
    ? {
        isOrganization: true,
        text: `${party.organizationName}（经办人 ${party.operatorName}）`,
      }
    : { isOrganization: false, text: party.operatorName }
}

export function describeConfirmation(plan: InspectionPlan): {
  title: string
  byText: string
  note: string
} {
  const person = plan.confirmedBy?.displayName
  const role = plan.customerParty?.organizationName ? '（经办人）' : ''
  const who = person ? `${person}${role}` : ''
  const when = plan.confirmedAt ? formatMonthDayTime(plan.confirmedAt) : ''
  // A full-width bracket already leaves room before the dot.
  const separator = who.endsWith('）') ? '· ' : ' · '
  return {
    title: `已确认方案 v${plan.planVersion}`,
    byText: who && when ? `${who}${separator}${when}` : who || when,
    note: `你确认的是 v${plan.planVersion}；服务商如更新方案，需要重新确认`,
  }
}
