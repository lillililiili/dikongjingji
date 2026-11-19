import type { RentalOrder, RentalOrderStatus, RentalRecord, RentalRefund } from '../domain/rental'
import {
  RENTAL_HANDOVER_LABELS,
  RENTAL_RETURN_LABELS,
  beijingToday,
  daysBetween,
  describeRentalLessee,
  formatMonthDay,
  formatRentalAmount,
  formatRentalInstant,
  formatRentalPeriod,
  formatRentalPlace,
} from './rental-format'
import type { RentalRow } from './rental-quote-view'

export const RENTAL_ORDER_STATUS_LABELS: Record<RentalOrderStatus, string> = {
  PENDING_PAYMENT: '待付款',
  CLOSED: '已关闭',
  AWAITING_HANDOVER: '待交接',
  HANDOVER_RECORDED: '待确认交接',
  IN_USE: '使用中',
  RETURN_RECORDED: '待确认归还',
  ENDED: '已结束',
  CANCELLED: '已取消',
}

export type RentalOrderTone = 'ACTION' | 'DONE' | 'MUTED'

export function rentalOrderTone(status: RentalOrderStatus): RentalOrderTone {
  switch (status) {
    case 'ENDED': return 'DONE'
    case 'CLOSED':
    case 'CANCELLED': return 'MUTED'
    default: return 'ACTION'
  }
}

export interface RentalOrderHead {
  title: string
  text: string
  tone: RentalOrderTone
}

function dueText(order: RentalOrder, now: Date): string {
  const left = daysBetween(beijingToday(now), order.contract.endDate)
  if (left > 0) return `还有 ${left} 天到期`
  return left === 0 ? '今天到期' : '已过到期日'
}

// The status card at the top of OrderRental.
export function describeRentalOrderHead(order: RentalOrder, now: Date): RentalOrderHead {
  const contract = order.contract
  switch (order.status) {
    case 'PENDING_PAYMENT':
      return {
        title: `待付款 · ${formatRentalInstant(order.payment.payBefore)} 前付清`,
        text: '超时订单关闭、报价失效，要租需要重新询价',
        tone: 'ACTION',
      }
    case 'CLOSED':
      return { title: '已关闭 · 未付款', text: '没有扣款；报价已失效，要租需要重新询价', tone: 'MUTED' }
    case 'AWAITING_HANDOVER':
      return {
        title: '待交接 · 商家准备设备',
        text: `${RENTAL_HANDOVER_LABELS[contract.handoverMode]} · ${formatMonthDay(contract.startDate)} 起租，交接时商家录入记录，你核对后确认`,
        tone: 'ACTION',
      }
    case 'HANDOVER_RECORDED':
      return {
        title: '待交接 · 请核对交接记录',
        text: `商家 ${formatRentalInstant(order.handoverRecord?.occurredAt ?? '')} 已录入记录，确认后开始计租`,
        tone: 'ACTION',
      }
    case 'IN_USE':
      return {
        title: `使用中 · ${dueText(order, now)}`,
        text: `${formatMonthDay(contract.endDate)} 到期，按合同约定归还；超期按合同约定计费`,
        tone: 'ACTION',
      }
    case 'RETURN_RECORDED':
      return {
        title: '待归还 · 请核对归还记录',
        text: `商家 ${formatRentalInstant(order.returnRecord?.occurredAt ?? '')} 已录入记录，确认后订单结束`,
        tone: 'ACTION',
      }
    case 'ENDED':
      return {
        title: '已结束',
        text: order.endedAt ? `${formatRentalInstant(order.endedAt)} 确认归还，没有额外费用` : '已确认归还，没有额外费用',
        tone: 'DONE',
      }
    case 'CANCELLED':
      return {
        title: '已取消 · 租金原路退回',
        text: order.cancelReason ? `出租方在交接前取消：${order.cancelReason}` : '出租方在交接前取消了订单',
        tone: 'MUTED',
      }
  }
}

export const RENTAL_CHAIN_STEPS = ['待报价', '待确认', '待交接', '使用中', '待归还', '已结束'] as const

// The current step of the six-step chain; null when the order left the chain (closed, cancelled).
export function rentalChainIndex(status: RentalOrderStatus): number | null {
  switch (status) {
    case 'PENDING_PAYMENT': return 1
    case 'AWAITING_HANDOVER':
    case 'HANDOVER_RECORDED': return 2
    case 'IN_USE': return 3
    case 'RETURN_RECORDED': return 4
    case 'ENDED': return 5
    default: return null
  }
}

export interface RentalChainStep {
  label: string
  done: boolean
  current: boolean
  reached: boolean
}

export function rentalChain(status: RentalOrderStatus): RentalChainStep[] | null {
  const index = rentalChainIndex(status)
  if (index === null) return null
  const ended = status === 'ENDED'
  return RENTAL_CHAIN_STEPS.map((label, position) => ({
    label,
    done: position < index || ended,
    current: position === index && !ended,
    reached: position <= index,
  }))
}

export interface RentalFeeRow {
  key: string
  name: string
  state: string
  amount: string
  // A real sum (bold) or a word such as 含 / 线下约定.
  money: boolean
  paid: boolean
}

function rentState(order: RentalOrder): { text: string; paid: boolean } {
  const contract = order.contract
  const base = `${contract.days} 天 × ${formatRentalAmount(contract.dailyRentMinor)} × ${contract.quantity} 台`
  if (order.status === 'PENDING_PAYMENT') return { text: `${base} · 待付款`, paid: false }
  if (order.status === 'CLOSED') return { text: `${base} · 未付款`, paid: false }
  if (order.status === 'CANCELLED') return { text: `${base} · ${refundText(order.refund) ?? '已取消'}`, paid: false }
  const paidOn = order.payment.paidAt ? ` ${formatMonthDay(beijingToday(new Date(order.payment.paidAt)))}` : ''
  return { text: `${base} · 已支付${paidOn}`, paid: true }
}

/**
 * 费用约定: the rent and the merchant's fee lines are what was paid together; the deposit is not
 * collected online, and overtime and damage are settled by the contract offline (§24.1 rule 7), so
 * they say they have not happened.
 */
export function rentalFeeRows(order: RentalOrder): RentalFeeRow[] {
  const rent = rentState(order)
  return [
    { key: 'rent', name: '租金', state: rent.text, amount: formatRentalAmount(order.contract.rentMinor), money: true, paid: rent.paid },
    ...order.contract.fees.map((fee) => ({
      key: `fee-${fee.name}`,
      name: fee.name,
      state: fee.note ?? (fee.amountMinor > 0 ? '和租金一起支付' : '包含在租金里'),
      amount: fee.amountMinor > 0 ? formatRentalAmount(fee.amountMinor) : '含',
      money: fee.amountMinor > 0,
      paid: false,
    })),
    { key: 'deposit', name: '押金', state: '不在线收取，按合同约定', amount: '线下约定', money: false, paid: false },
    { key: 'extra', name: '超期和赔付', state: '按合同线下处理，没有发生', amount: '¥0', money: true, paid: false },
  ]
}

export function refundText(refund: RentalRefund | null): string | null {
  if (!refund) return null
  const amount = formatRentalAmount(refund.amountMinor)
  switch (refund.status) {
    case 'REQUESTED':
    case 'PROCESSING': return `退款中 ${amount}`
    case 'SUCCEEDED': return `已退款 ${amount}`
    case 'CLOSED': return `退款已关闭 ${amount}，请联系平台客服`
    case 'ABNORMAL': return `退款异常 ${amount}，请联系平台客服`
  }
}

export interface RentalRecordView {
  tag: string
  tone: RentalOrderTone
  rows: RentalRow[]
  // The grey line under the rows when there is no record yet.
  note: string | null
}

function recordRows(record: RentalRecord): RentalRow[] {
  return [
    { key: 'time', label: '时间', value: formatRentalInstant(record.occurredAt) },
    { key: 'note', label: '记录', value: record.note },
    { key: 'confirmed', label: '确认', value: record.confirmedAt ? `${formatRentalInstant(record.confirmedAt)} 你已确认` : '待你确认' },
  ]
}

function recordTag(record: RentalRecord | null): { tag: string; tone: RentalOrderTone } {
  if (!record) return { tag: '未开始', tone: 'MUTED' }
  return record.confirmedAt ? { tag: '双方已确认', tone: 'DONE' } : { tag: '待你确认', tone: 'ACTION' }
}

export function rentalHandoverView(order: RentalOrder): RentalRecordView {
  const record = order.handoverRecord
  if (record) return { ...recordTag(record), rows: recordRows(record), note: null }
  const contract = order.contract
  return {
    ...recordTag(null),
    rows: [
      { key: 'date', label: '起租', value: `${formatMonthDay(contract.startDate)} 起` },
      { key: 'mode', label: '方式', value: RENTAL_HANDOVER_LABELS[contract.handoverMode] },
      { key: 'place', label: '地点', value: formatRentalPlace(contract.place) },
    ],
    note: '交接时商家在这里录入记录，你核对后确认',
  }
}

export function rentalReturnView(order: RentalOrder): RentalRecordView {
  const record = order.returnRecord
  if (record) return { ...recordTag(record), rows: recordRows(record), note: null }
  const contract = order.contract
  return {
    ...recordTag(null),
    rows: [
      { key: 'due', label: '应还', value: `${formatMonthDay(contract.endDate)} 前` },
      { key: 'mode', label: '方式', value: RENTAL_RETURN_LABELS[contract.returnMode] },
      { key: 'check', label: '核对', value: '设备编号、配件数量、外观' },
    ],
    note: '归还后商家在这里录入记录，你核对后确认',
  }
}

// 合同摘要 on the order page.
export function rentalContractSummary(order: RentalOrder): RentalRow[] {
  const contract = order.contract
  return [
    // Half a card wide on the board: "11/01–11/05 · 5 天".
    {
      key: 'period',
      label: '租期',
      value: `${formatMonthDay(contract.startDate)}–${formatMonthDay(contract.endDate)} · ${contract.days} 天`,
    },
    { key: 'quantity', label: '数量', value: `${contract.quantity} 台` },
    { key: 'handover', label: '交接', value: RENTAL_HANDOVER_LABELS[contract.handoverMode] },
    { key: 'return', label: '归还', value: RENTAL_RETURN_LABELS[contract.returnMode] },
    { key: 'place', label: '地点', value: formatRentalPlace(contract.place) },
  ]
}

// 交易主体 on the order page.
export function rentalParties(order: RentalOrder): RentalRow[] {
  const lessor = order.contract.lessor.name
  return [
    { key: 'lessor', label: '出租方', value: lessor },
    { key: 'payee', label: '收款方', value: `${lessor} · 经平台支付渠道` },
    { key: 'lessee', label: '承租方', value: describeRentalLessee(order.contract.lessee).text },
  ]
}

export type RentalOrderAction =
  | { kind: 'PAY'; label: '去付款' }
  | { kind: 'CONFIRM_HANDOVER'; label: '确认交接' }
  | { kind: 'CONFIRM_RETURN'; label: '确认归还' }
  | { kind: 'RENT_AGAIN'; label: '再次租赁' }

// The orange button of the bottom bar; the server's flags decide the confirmations.
export function rentalOrderAction(order: RentalOrder): RentalOrderAction | null {
  if (order.status === 'PENDING_PAYMENT') return { kind: 'PAY', label: '去付款' }
  if (order.handoverConfirmable) return { kind: 'CONFIRM_HANDOVER', label: '确认交接' }
  if (order.returnConfirmable) return { kind: 'CONFIRM_RETURN', label: '确认归还' }
  if (order.status === 'ENDED' || order.status === 'CLOSED' || order.status === 'CANCELLED') {
    return { kind: 'RENT_AGAIN', label: '再次租赁' }
  }
  return null
}

export interface RentalConfirmSheetView {
  title: string
  note: string
  go: string
  rows: RentalRow[]
}

export function rentalConfirmSheet(order: RentalOrder, kind: 'CONFIRM_HANDOVER' | 'CONFIRM_RETURN'): RentalConfirmSheetView {
  const record = kind === 'CONFIRM_HANDOVER' ? order.handoverRecord : order.returnRecord
  const rows = record ? recordRows(record).filter((row) => row.key !== 'confirmed') : []
  return kind === 'CONFIRM_HANDOVER'
    ? { title: '确认交接', note: '确认后开始计租，记录不能再改。有问题先联系商家。', go: '确认交接', rows }
    : { title: '确认归还记录', note: '确认后订单结束。对记录有疑问先联系商家。', go: '确认归还', rows }
}

export interface RentalOrderCardView {
  statusText: string
  tone: RentalOrderTone
  title: string
  period: string
  amount: string
  line: string
  // Card buttons besides opening the order.
  pay: boolean
}

// One card of 我的 → 租赁订单.
export function describeRentalOrderCard(order: RentalOrder, now: Date): RentalOrderCardView {
  const contract = order.contract
  const head = describeRentalOrderHead(order, now)
  return {
    statusText: RENTAL_ORDER_STATUS_LABELS[order.status],
    tone: rentalOrderTone(order.status),
    title: `${contract.productName} ×${contract.quantity}`,
    period: formatRentalPeriod(contract.startDate, contract.endDate, contract.days),
    amount: formatRentalAmount(contract.totalMinor),
    line: order.status === 'CANCELLED' ? (refundText(order.refund) ?? head.title) : head.title,
    pay: order.status === 'PENDING_PAYMENT',
  }
}

// The tag of the contract page.
export function rentalContractTag(status: RentalOrderStatus): { label: string; tone: RentalOrderTone } {
  switch (status) {
    case 'PENDING_PAYMENT': return { label: '待付款', tone: 'ACTION' }
    case 'CLOSED': return { label: '未生效 · 未付款', tone: 'MUTED' }
    case 'CANCELLED': return { label: '已取消', tone: 'MUTED' }
    case 'ENDED': return { label: '已履行完毕', tone: 'DONE' }
    default: return { label: '生效中', tone: 'DONE' }
  }
}

export function rentalContractParties(order: RentalOrder): RentalRow[] {
  const contract = order.contract
  return [
    { key: 'party-a', label: '甲方', value: describeRentalLessee(contract.lessee).text },
    { key: 'party-b', label: '乙方', value: `${contract.lessor.name}（出租方）` },
    { key: 'payee', label: '收款方', value: `${contract.lessor.name} · 经平台支付渠道` },
    { key: 'signed', label: '确认', value: `${formatRentalInstant(contract.confirmedAt)} 在租赁报价单确认` },
    { key: 'term', label: '租期', value: `${formatMonthDay(contract.startDate)} 起 · 至 ${formatMonthDay(contract.endDate)}` },
  ]
}

export function rentalContractScope(order: RentalOrder): RentalRow[] {
  const contract = order.contract
  return [
    {
      key: 'device',
      label: '设备',
      value: `${contract.productName}${contract.skuName ? ` ${contract.skuName}` : ''} ×${contract.quantity} · 编号交接时确定`,
    },
    { key: 'period', label: '租期', value: formatRentalPeriod(contract.startDate, contract.endDate, contract.days) },
    {
      key: 'handover',
      label: '交接',
      value: `${RENTAL_HANDOVER_LABELS[contract.handoverMode]} · ${formatRentalPlace(contract.place)}`,
    },
    {
      key: 'return',
      label: '归还',
      value: `${RENTAL_RETURN_LABELS[contract.returnMode]} · ${formatMonthDay(contract.endDate)} 前`,
    },
    { key: 'contact', label: '联系人', value: contract.place.recipientName },
  ]
}
