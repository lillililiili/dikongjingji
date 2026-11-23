import { providerCredentialLabel } from '../../../shared/ui/qualification'
import { formatBeijingTime, padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import {
  DEMAND_TYPE_INFO,
  type Demand,
  type DemandPublisher,
  type DemandQuote,
  type DemandQuoteLine,
  type DemandQuoteSort,
  type DemandStatus,
} from '../domain/demand'
import type { DemandListFilter } from '../navigation/demand-routes'
import { formatDemandBudget } from './demand-form'

export const DEMAND_FILTER_LABELS: Record<DemandListFilter, string> = {
  ALL: '全部',
  OPEN: '待报价',
  QUOTED: '报价中',
  CHOSEN: '已选定',
  CLOSED: '已关闭',
  DRAFT: '草稿',
}

export const DEMAND_STATUS_LABELS: Record<DemandStatus, string> = {
  DRAFT: '未提交',
  OPEN: '待报价',
  QUOTED: '报价中',
  CHOSEN: '已选定',
  CLOSED: '已关闭',
}

export const DEMAND_SORT_LABELS: Record<DemandQuoteSort, string> = {
  ALL: '综合',
  PRICE: '总价',
  DURATION: '交付周期',
}

// The tone decides the colour (DemandList board): OPEN orange, QUOTED blue, CHOSEN green, the rest grey.
export type DemandTone = 'PENDING' | 'QUOTING' | 'CHOSEN' | 'OFF' | 'DRAFT'

export function demandTone(status: DemandStatus): DemandTone {
  switch (status) {
    case 'OPEN': return 'PENDING'
    case 'QUOTED': return 'QUOTING'
    case 'CHOSEN': return 'CHOSEN'
    case 'CLOSED': return 'OFF'
    case 'DRAFT': return 'DRAFT'
  }
}

export function formatDemandAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

// "11/22 18:00" in Beijing time.
export function formatDemandInstant(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

// "11/22"
function formatDemandDay(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

export function describeDemandPublisher(publisher: DemandPublisher): string {
  return publisher.kind === 'ORGANIZATION' && publisher.organizationName
    ? `企业 · ${publisher.organizationName}`
    : '个人'
}

// "东营市 · 东营经济技术开发区 · 约 1.2 平方公里"; a draft says what is still missing instead.
export function describeDemandMeta(demand: Demand): string {
  if (demand.status === 'DRAFT') {
    const missing = describeDraftMissing(demand)
    const label = DEMAND_TYPE_INFO[demand.type].label
    return missing.length ? `${label} · 还差${missing.join('、')}` : `${label} · 已填好，待提交`
  }
  return [demand.address, demand.scope].filter((part): part is string => Boolean(part?.trim())).join(' · ')
}

// The required parts a draft does not have yet, in page order.
export function describeDraftMissing(demand: Demand): string[] {
  const info = DEMAND_TYPE_INFO[demand.type]
  return [
    !demand.cityCode || !demand.address?.trim() ? '地点' : null,
    !demand.scope?.trim() ? info.scopeLabel : null,
    demand.deliverables.length === 0 ? '交付物' : null,
    demand.quoteScope === 'INVITE' && demand.invitedProviders.length === 0 ? '邀请的服务商' : null,
  ].filter((part): part is string => part !== null)
}

function deadlinePart(demand: Demand): string {
  if (!demand.quoteDeadline) return ''
  return demand.quotingOpen ? `报价截止 ${formatDemandInstant(demand.quoteDeadline)}` : '报价已截止'
}

// The coloured line under the meta (DemandList board).
export function describeDemandLine(demand: Demand): string {
  switch (demand.status) {
    case 'DRAFT':
      return `${formatDemandDay(demand.updatedAt)} 保存`
    case 'OPEN': {
      const reach = demand.quoteScope === 'INVITE'
        ? `已邀请 ${demand.invitedProviders.length} 家服务商`
        : '平台匹配中'
      return [reach, deadlinePart(demand)].filter(Boolean).join(' · ')
    }
    case 'QUOTED':
      return [`已收到 ${demand.quoteCount} 份报价`, deadlinePart(demand)].filter(Boolean).join(' · ')
    case 'CHOSEN':
      return demand.chosen
        ? `已选定 ${demand.chosen.providerName} · 方案 v${demand.chosen.quoteVersion} · ${formatDemandAmount(demand.chosen.amountMinor)}`
        : '已选定'
    case 'CLOSED':
      return demand.closeReason === 'NO_QUOTES' ? '报价期内没有服务商报价' : '你已取消这份需求'
  }
}

export type DemandCardAction = 'EDIT' | 'DELETE' | 'CANCEL' | 'VIEW' | 'QUOTES' | 'REPUBLISH' | 'ORDER'

export interface DemandCardButton {
  action: DemandCardAction
  label: string
  primary: boolean
}

export interface DemandCardView {
  kind: '需求单' | '草稿'
  publisher: string
  statusText: string
  tone: DemandTone
  title: string
  meta: string
  line: string
  // Where tapping the card goes.
  open: DemandCardAction
  buttons: DemandCardButton[]
}

/**
 * One card of 我的 → 需求与报价. What may be done comes from the server's flags (cancellable,
 * deletable, editable); the page never decides that from the status alone. A chosen demand with a
 * service order (S4-3) also offers 查看订单 where service orders are open.
 */
export function describeDemandCard(demand: Demand, serviceOrdersOpen = false): DemandCardView {
  const buttons: DemandCardButton[] = []
  let open: DemandCardAction = 'VIEW'
  if (demand.status === 'DRAFT') {
    open = demand.editable ? 'EDIT' : 'VIEW'
    if (demand.deletable) buttons.push({ action: 'DELETE', label: '删除', primary: false })
    buttons.push({ action: open, label: demand.editable ? '继续填写' : '查看需求', primary: true })
  } else if (demand.status === 'OPEN') {
    if (demand.cancellable) buttons.push({ action: 'CANCEL', label: '取消需求', primary: false })
    buttons.push({ action: 'VIEW', label: '查看需求', primary: false })
  } else if (demand.status === 'QUOTED') {
    open = 'QUOTES'
    if (demand.cancellable) buttons.push({ action: 'CANCEL', label: '取消需求', primary: false })
    buttons.push({ action: 'QUOTES', label: '比选报价', primary: true })
  } else if (demand.status === 'CHOSEN') {
    open = 'QUOTES'
    if (serviceOrdersOpen && demand.serviceOrderId) buttons.push({ action: 'ORDER', label: '查看订单', primary: false })
    buttons.push({ action: 'QUOTES', label: '查看报价', primary: false })
  } else {
    buttons.push({ action: 'VIEW', label: '查看需求', primary: false })
    buttons.push({ action: 'REPUBLISH', label: '重新发布', primary: false })
  }
  return {
    kind: demand.status === 'DRAFT' ? '草稿' : '需求单',
    publisher: describeDemandPublisher(demand.publisher),
    statusText: DEMAND_STATUS_LABELS[demand.status],
    tone: demandTone(demand.status),
    title: demand.title,
    meta: describeDemandMeta(demand),
    line: describeDemandLine(demand),
    open,
    buttons,
  }
}

// The summary card on the quotes and compare pages: "● 3 家已报价 · 报价截止 11/22 18:00".
export function describeDemandQuoteSummary(demand: Demand, quoteCount: number): { count: string; deadline: string } {
  const count = quoteCount > 0 ? `${quoteCount} 家已报价` : '还没有报价'
  if (demand.status === 'CHOSEN') return { count, deadline: '已选定' }
  if (demand.status === 'CLOSED') return { count, deadline: '已关闭' }
  return { count, deadline: deadlinePart(demand) }
}

// The read-only rows of a submitted demand (the publish page in view mode).
export function describeDemandFacts(demand: Demand, dueText: string): Array<{ label: string; value: string }> {
  const info = DEMAND_TYPE_INFO[demand.type]
  return [
    { label: '服务类型', value: info.label },
    { label: '服务地点', value: [demand.cityName, demand.address].filter(Boolean).join(' · ') || '未填写' },
    { label: info.scopeLabel, value: demand.scope || '未填写' },
    { label: '期望完成', value: dueText },
    { label: '预算区间', value: formatDemandBudget(demand.budgetMinMinor, demand.budgetMaxMinor) },
    {
      label: '交付物',
      value: demand.deliverables.length ? demand.deliverables.map((item) => item.label).join('、') : '未选择',
    },
    {
      label: '报价范围',
      value: demand.quoteScope === 'INVITE'
        ? `仅邀请 ${demand.invitedProviders.map((item) => item.name).join('、')}`
        : '平台匹配',
    },
  ]
}

export const DEMAND_QUOTE_STATUS_TAGS: Partial<Record<DemandQuote['status'], string>> = {
  CHOSEN: '已选中',
  NOT_CHOSEN: '未选中',
  EXPIRED: '已过有效期',
  CLOSED: '已关闭',
}

export interface DemandQuoteView {
  price: string
  // Beside the provider's name: 资质已审核 when the platform reviewed it (R6-4), else its own label.
  credential: string | null
  statusTag: string | null
  rows: Array<{ label: string; value: string }>
}

// Total in the title row, then 方式、工期、周期、交付物、有效期 (Quote board).
export function describeDemandQuote(quote: DemandQuote): DemandQuoteView {
  return {
    price: formatDemandAmount(quote.amountMinor),
    credential: providerCredentialLabel(quote.provider.qualificationReviewed, quote.provider.certificationLabel),
    statusTag: DEMAND_QUOTE_STATUS_TAGS[quote.status] ?? null,
    rows: [
      { label: '方式', value: quote.method },
      { label: '工期', value: quote.schedule },
      { label: '周期', value: `${quote.durationDays} 天` },
      { label: '交付物', value: quote.deliverables },
      { label: '有效期', value: quote.validUntil ? `至 ${formatDemandInstant(quote.validUntil)}` : '' },
    ],
  }
}

export interface DemandCompareRow {
  label: string
  // One cell per quote, in the quotes' order.
  cells: string[]
  price?: boolean
  // Cells that say the item is not included (影像存档 0 天 → 不含), drawn grey like the board.
  muted?: boolean[]
}

// "48 小时内"
export function formatReportHours(hours: number): string {
  return `${hours} 小时内`
}

// "90 天"; 0 days means the footage is not kept.
export function formatArchiveDays(days: number): string {
  return days > 0 ? `${days} 天` : '不含'
}

// "11/25" from YYYY-MM-DD.
export function formatQuoteDate(date: string): string {
  const [, month, day] = date.split('-')
  return month && day ? `${month}/${day}` : date
}

export interface DemandQuoteLineView {
  key: string
  name: string
  // "6 次 × ¥1,800"; '' for a line not counted by the unit.
  quantity: string
  amount: string
}

// 报价明细 as PlanConfirm and PlanCompare draw it.
export function describeDemandQuoteLines(lines: DemandQuoteLine[]): DemandQuoteLineView[] {
  return lines.map((line, index) => ({
    key: `${index}-${line.name}`,
    name: line.name,
    quantity: line.quantity !== null && line.unitAmountMinor !== null
      ? `${line.quantity}${line.unit ? ` ${line.unit}` : ''} × ${formatDemandAmount(line.unitAmountMinor)}`
      : '',
    amount: formatDemandAmount(line.amountMinor),
  }))
}

type QuoteItemKey = 'team' | 'firstVisit' | 'nightWork' | 'report' | 'archive'

// The comparison items of 1.3.0 a quote says something about; null when the provider left it out.
function quoteItem(quote: DemandQuote, key: QuoteItemKey): string | null {
  switch (key) {
    case 'team': return quote.team
    case 'firstVisit': return quote.firstVisitDate ? formatQuoteDate(quote.firstVisitDate) : null
    case 'nightWork': return quote.nightWork
    case 'report': return quote.reportHours !== null ? formatReportHours(quote.reportHours) : null
    case 'archive': return quote.archiveDays !== null ? formatArchiveDays(quote.archiveDays) : null
  }
}

const QUOTE_ITEM_LABELS: Record<QuoteItemKey, string> = {
  team: '服务团队',
  firstVisit: '首次进场',
  nightWork: '夜间作业',
  report: '报告时效',
  archive: '影像存档',
}

// A comparison row only when at least one quote fills it; the others show a dash, never a guess.
function itemRow(quotes: DemandQuote[], key: QuoteItemKey): DemandCompareRow | null {
  const values = quotes.map((quote) => quoteItem(quote, key))
  if (values.every((value) => value === null)) return null
  return {
    label: QUOTE_ITEM_LABELS[key],
    cells: values.map((value) => value ?? '—'),
    muted: values.map((value) => value === null || value === '不含'),
  }
}

// The 1.3.0 items of one quote, as label and value rows (Quote card, a provider tab of PlanCompare).
export function describeDemandQuoteItems(quote: DemandQuote): Array<{ label: string; value: string }> {
  return (['team', 'firstVisit', 'nightWork', 'report', 'archive'] as const).flatMap((key) => {
    const value = quoteItem(quote, key)
    return value === null ? [] : [{ label: QUOTE_ITEM_LABELS[key], value }]
  })
}

// Only what the quotes say; nothing is filled in for a provider that did not say it.
export function buildDemandCompareRows(quotes: DemandQuote[]): DemandCompareRow[] {
  const rows: Array<DemandCompareRow | null> = [
    { label: '总价', cells: quotes.map((quote) => formatDemandAmount(quote.amountMinor)), price: true },
    { label: '方案版本', cells: quotes.map((quote) => `v${quote.version}`) },
    // PlanCompare board: 已审核 when the platform reviewed the provider (R6-4), else its own label.
    { label: '资质', cells: quotes.map((quote) => (quote.provider.qualificationReviewed ? '已审核' : providerCredentialLabel(false, quote.provider.certificationLabel) ?? '—')) },
    itemRow(quotes, 'team'),
    { label: '方式', cells: quotes.map((quote) => quote.method) },
    { label: '工期', cells: quotes.map((quote) => quote.schedule) },
    { label: '周期', cells: quotes.map((quote) => `${quote.durationDays} 天`) },
    itemRow(quotes, 'firstVisit'),
    itemRow(quotes, 'nightWork'),
    itemRow(quotes, 'report'),
    { label: '交付物', cells: quotes.map((quote) => quote.deliverables) },
    itemRow(quotes, 'archive'),
    { label: '有效期', cells: quotes.map((quote) => (quote.validUntil ? formatDemandInstant(quote.validUntil) : '—')) },
    { label: '状态', cells: quotes.map((quote) => DEMAND_QUOTE_STATUS_TAGS[quote.status] ?? (quote.choosable ? '可选择' : '—')) },
  ]
  return rows.filter((row): row is DemandCompareRow => row !== null)
}

export interface DemandChoiceFailureInput {
  status: number
  code: string | null
  message: string
}

export interface DemandChoiceFailure {
  message: string
  // Read the demand and its quotes again before anything else.
  reload: boolean
}

export function describeDemandChoiceFailure(failure: DemandChoiceFailureInput | null): DemandChoiceFailure {
  if (!failure) return { message: '没有选成，请稍后重试', reload: false }
  switch (failure.code) {
    case 'DEMAND_VERSION_CONFLICT':
      return { message: '需求有更新，已刷新，请看过最新报价后再选', reload: true }
    case 'DEMAND_QUOTE_CHANGED':
      return { message: '这份报价已更新或撤回，已刷新，请看过最新报价后再选', reload: true }
    case 'DEMAND_QUOTE_EXPIRED':
      return { message: '这份报价已过有效期，不能再选', reload: true }
    case 'DEMAND_STATE_INVALID':
      return { message: '需求已选定或已关闭，不能再选', reload: true }
    default:
      break
  }
  if (failure.status === 412) return { message: '需求有更新，已刷新，请看过最新报价后再选', reload: true }
  if (failure.status === 404) return { message: '这份报价已不存在', reload: true }
  if (failure.status === 0) return { message: '网络连接失败，请稍后重试', reload: false }
  return { message: failure.message || '没有选成，请稍后重试', reload: false }
}

/**
 * What comes after a quote is chosen. From 1.3.0 an aerial, survey, maintenance or tech-support
 * demand makes a service order when chosen (§28): its plan, payment and work are on the order. An
 * inspection demand still stops at the choice (inspection has its own booking flow), and so does
 * a build where service orders are not open: the gate stays closed and the page says so.
 */
export type DemandFollowUp =
  | { kind: 'ORDER'; serviceOrderId: string }
  | { kind: 'UNAVAILABLE'; title: string; text: string }

export function resolveDemandFollowUp(demand?: Pick<Demand, 'serviceOrderId'> | null, serviceOrdersOpen = false): DemandFollowUp {
  if (serviceOrdersOpen && demand?.serviceOrderId) return { kind: 'ORDER', serviceOrderId: demand.serviceOrderId }
  return {
    kind: 'UNAVAILABLE',
    title: '方案确认和付款还没有开通',
    text: '选定报价不产生费用。方案确认、合同和付款开通后，从这里继续。',
  }
}

// Whether choosing a quote of this demand makes a service order (§28.2 rule 1).
export function demandMakesServiceOrder(demand: Pick<Demand, 'type'>, serviceOrdersOpen: boolean): boolean {
  return serviceOrdersOpen && demand.type !== 'INSPECTION'
}

// The text of the 选择此报价 sheet.
export function demandChoiceText(demand: Pick<Demand, 'type'>, quote: DemandQuote, serviceOrdersOpen: boolean): string {
  const head = `总价 ${formatDemandAmount(quote.amountMinor)}（方案 v${quote.version}）。选定后不能再选别家，其余报价会标为未选中。`
  return demandMakesServiceOrder(demand, serviceOrdersOpen)
    ? `${head}选定后生成服务订单，服务商出方案后由你确认并一次付清。`
    : `${head}选定不产生费用，方案确认和付款还没有开通。`
}
