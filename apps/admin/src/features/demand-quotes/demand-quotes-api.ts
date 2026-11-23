import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount, formatMoney, minorToYuanText, yuanToMinor } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

import {
  buildLines,
  LINE_LIMITS,
  lineFormsFrom,
  linesTotalMinor,
  type LineForm,
  type QuoteExtras,
  type QuoteLine,
} from './quote-lines'

// 契约 contracts/openapi-demand.yaml 1.3.0 的服务商接口（DemandProviderController）：本机构能看到的需求
// （平台匹配到本机构服务城市、邀请了本机构、或本机构报过价的），报价、改价、撤回。需求里没有发布人的账号和联系方式。
// 报价、撤回不带 Idempotency-Key：内容和现在的有效报价一样时没有变化，撤回过的再撤回原样返回。
// 1.3.0 起报价可以带明细（最多 10 行，合计等于总价）和对比项（服务团队、夜间作业、报告时效、影像存档、首次进场）；
// 四类服务（巡检除外）被选定后生成服务订单，在“服务订单”菜单里出方案、开工、交付。

registerErrorMessages({
  DEMAND_QUOTING_CLOSED: '这份需求的报价已截止，或已经选定、关闭',
  DEMAND_QUOTE_LOCKED: '本机构的报价已被选中、未被选中或已随需求关闭，不能再改或撤回',
  DEMAND_STATE_INVALID: '这份需求的状态已经变了，请刷新后再看',
  DEMAND_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请刷新后重新填写',
  DEMAND_VERSION_CONFLICT: '数据刚被修改过，请刷新后重试',
})

export type DemandType = 'INSPECTION' | 'AERIAL' | 'SURVEY' | 'MAINTENANCE' | 'TECH_SUPPORT'
/** 服务商看不到草稿。 */
export type DemandStatus = 'OPEN' | 'QUOTED' | 'CHOSEN' | 'CLOSED'
export type QuoteStatus = 'ACTIVE' | 'EXPIRED' | 'WITHDRAWN' | 'CHOSEN' | 'NOT_CHOSEN' | 'CLOSED'
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface ProviderQuote extends QuoteExtras {
  quoteId: string
  version: number
  status: QuoteStatus
  amountMinor: number
  method: string
  schedule: string
  durationDays: number
  deliverables: string
  validUntil: string
  submittedAt: string
  revisedAt: string
  /** 报价明细（1.3.0）；旧报价或没填明细时为空数组。 */
  lines: QuoteLine[]
}

export interface ProviderDemand {
  demandId: string
  status: DemandStatus
  type: DemandType
  title: string
  cityCode: string
  cityName: string
  address: string
  scope: string
  dueDate: string | null
  budgetMinMinor: number | null
  budgetMaxMinor: number | null
  deliverables: Array<{ code: string, label: string }>
  note: string | null
  /** true：发布人邀请了本机构；false：平台匹配看到的。 */
  invited: boolean
  submittedAt: string
  quoteDeadline: string
  /** 现在还可以报价、改价。 */
  quotingOpen: boolean
  myQuote: ProviderQuote | null
}

export interface QuoteRequest extends QuoteExtras {
  amountMinor: number
  method: string
  schedule: string
  durationDays: number
  deliverables: string
  validUntil: string
  /** 空数组表示没有明细；有明细时各行金额之和等于 amountMinor。 */
  lines: QuoteLine[]
}

export interface DemandFilters {
  status: DemandStatus | ''
}

export const DEMAND_TYPE_LABELS: Record<DemandType, string> = {
  INSPECTION: '巡检',
  AERIAL: '航拍',
  SURVEY: '测绘',
  MAINTENANCE: '设备维护',
  TECH_SUPPORT: '技术支持',
}

export const DEMAND_STATUS: Record<DemandStatus, { label: string, tag: TagType }> = {
  OPEN: { label: '待报价', tag: 'warning' },
  QUOTED: { label: '报价中', tag: 'primary' },
  CHOSEN: { label: '已选定', tag: 'success' },
  CLOSED: { label: '已关闭', tag: 'info' },
}

export const DEMAND_STATUS_OPTIONS = (Object.keys(DEMAND_STATUS) as DemandStatus[])
  .map((value) => ({ value, label: DEMAND_STATUS[value].label }))

export const QUOTE_STATUS: Record<QuoteStatus, { label: string, tag: TagType }> = {
  ACTIVE: { label: '有效', tag: 'primary' },
  EXPIRED: { label: '已过有效期', tag: 'info' },
  WITHDRAWN: { label: '已撤回', tag: 'info' },
  CHOSEN: { label: '已被选中', tag: 'success' },
  NOT_CHOSEN: { label: '未被选中', tag: 'info' },
  CLOSED: { label: '需求已关闭', tag: 'info' },
}

export const QUOTE_LIMITS = {
  maxAmountMinor: 10_000_000_000,
  method: 40,
  schedule: 60,
  deliverables: 120,
  maxDurationDays: 3650,
  validDays: 30,
} as const

const LOCKED: readonly QuoteStatus[] = ['CHOSEN', 'NOT_CHOSEN', 'CLOSED']

/** 能报价、改价：报价期还开着，本机构的报价没被选中、没落选、没随需求关闭。 */
export function canQuote(demand: Pick<ProviderDemand, 'quotingOpen' | 'myQuote'>): boolean {
  return demand.quotingOpen && !(demand.myQuote && LOCKED.includes(demand.myQuote.status))
}

/** 能撤回：有一份有效（或已过期）的报价，还没被选定。 */
export function canWithdraw(demand: Pick<ProviderDemand, 'myQuote'>): boolean {
  return demand.myQuote?.status === 'ACTIVE' || demand.myQuote?.status === 'EXPIRED'
}

export function budgetLabel(demand: Pick<ProviderDemand, 'budgetMinMinor' | 'budgetMaxMinor'>): string {
  const yuan = formatMoney
  const { budgetMinMinor: min, budgetMaxMinor: max } = demand
  if (min === null && max === null) return '未填预算'
  if (min !== null && max !== null) return min === max ? yuan(min) : `${yuan(min)}–${yuan(max)}`
  return min !== null ? `${yuan(min)} 起` : `${yuan(max as number)} 以内`
}

export interface QuoteForm {
  /** 没有明细时填的总价；有明细时不用（总价按明细合计）。 */
  amountYuan: string
  method: string
  schedule: string
  durationDays: number
  deliverables: string
  validUntil: Date | null
  lines: LineForm[]
  team: string
  nightWork: string
  reportHours: number | null
  archiveDays: number | null
  /** yyyy-MM-dd */
  firstVisitDate: string | null
}

const DAY_MS = 86_400_000

/** 改价用现在这份报价预填；第一次报价有效期默认 7 天。 */
export function initialQuoteForm(demand: ProviderDemand, now: Date = new Date()): QuoteForm {
  const quote = demand.myQuote && demand.myQuote.status !== 'WITHDRAWN' ? demand.myQuote : null
  return {
    amountYuan: quote ? minorToYuanText(quote.amountMinor) : '',
    method: quote?.method ?? '',
    schedule: quote?.schedule ?? '',
    durationDays: quote?.durationDays ?? 1,
    deliverables: quote?.deliverables ?? demand.deliverables.map((item) => item.label).join('、'),
    validUntil: new Date(now.getTime() + 7 * DAY_MS),
    lines: lineFormsFrom(quote?.lines ?? []),
    team: quote?.team ?? '',
    nightWork: quote?.nightWork ?? '',
    reportHours: quote?.reportHours ?? null,
    archiveDays: quote?.archiveDays ?? null,
    firstVisitDate: quote?.firstVisitDate ?? null,
  }
}

/**
 * 总价：有明细时按明细自动合计（页面上总价框只读），没有明细时用填的总价。算不出来时为 null。
 * 选“按明细自动算”而不是“不一致时拦住”：服务商不用把同一个数填两遍，也就不会出现对不上的情况。
 */
export function quoteAmountMinor(form: Pick<QuoteForm, 'amountYuan' | 'lines'>): number | null {
  return form.lines.length > 0 ? linesTotalMinor(form.lines) : yuanToMinor(form.amountYuan)
}

function optionalText(label: string, value: string, max: number): { value: string | null } | { problem: string } {
  const text = value.trim()
  if (!text) return { value: null }
  if (charCount(text) > max) return { problem: `${label}最多 ${max} 个字` }
  if (CONTROL.test(text)) return { problem: `${label}里不能有换行或特殊字符` }
  return { value: text }
}

function optionalInteger(label: string, value: number | null, min: number, max: number):
{ value: number | null } | { problem: string } {
  if (value === null || value === undefined) return { value: null }
  if (!Number.isInteger(value) || value < min || value > max) return { problem: `${label}要在 ${min}–${max} 之间` }
  return { value }
}

export type BuildResult<T> = { ok: true, request: T } | { ok: false, message: string }

// 一行的说明：不含换行等控制字符（后端拒绝）。
const CONTROL = /[\u0000-\u001f\u007f]/

function line(label: string, value: string, max: number): string | null {
  const text = value.trim()
  if (!text) return `请填写${label}`
  if (charCount(text) > max) return `${label}最多 ${max} 个字`
  if (CONTROL.test(text)) return `${label}里不能有换行或特殊字符`
  return null
}

/** 提交前按契约检查一遍，返回请求体或第一条错误（服务端还会再校验）。 */
export function buildQuoteRequest(form: QuoteForm, now: Date = new Date()): BuildResult<QuoteRequest> {
  const built = buildLines(form.lines)
  if (!built.ok) return built
  const amount = built.lines.length > 0
    ? built.lines.reduce((sum, item) => sum + item.amountMinor, 0)
    : yuanToMinor(form.amountYuan)
  if (amount === null || amount < 1) return { ok: false, message: '报价金额要填大于 0 的金额，最多两位小数' }
  if (amount > QUOTE_LIMITS.maxAmountMinor) {
    return { ok: false, message: built.lines.length > 0 ? '明细合计超过报价上限' : '报价金额超过上限' }
  }
  const problem = line('做法', form.method, QUOTE_LIMITS.method)
    ?? line('排期', form.schedule, QUOTE_LIMITS.schedule)
    ?? line('交付物说明', form.deliverables, QUOTE_LIMITS.deliverables)
  if (problem) return { ok: false, message: problem }
  if (!Number.isInteger(form.durationDays) || form.durationDays < 1 || form.durationDays > QUOTE_LIMITS.maxDurationDays) {
    return { ok: false, message: `工期要在 1–${QUOTE_LIMITS.maxDurationDays} 天` }
  }
  if (!form.validUntil || Number.isNaN(form.validUntil.getTime())) return { ok: false, message: '请选报价有效期' }
  if (form.validUntil.getTime() <= now.getTime()) return { ok: false, message: '报价有效期要晚于现在' }
  if (form.validUntil.getTime() > now.getTime() + QUOTE_LIMITS.validDays * DAY_MS) {
    return { ok: false, message: `报价有效期最长 ${QUOTE_LIMITS.validDays} 天` }
  }
  const team = optionalText('服务团队', form.team, LINE_LIMITS.team)
  if ('problem' in team) return { ok: false, message: team.problem }
  const nightWork = optionalText('夜间作业', form.nightWork, LINE_LIMITS.nightWork)
  if ('problem' in nightWork) return { ok: false, message: nightWork.problem }
  const reportHours = optionalInteger('报告时效（小时）', form.reportHours, 1, LINE_LIMITS.maxReportHours)
  if ('problem' in reportHours) return { ok: false, message: reportHours.problem }
  const archiveDays = optionalInteger('影像存档（天）', form.archiveDays, 0, LINE_LIMITS.maxArchiveDays)
  if ('problem' in archiveDays) return { ok: false, message: archiveDays.problem }
  const firstVisitDate = form.firstVisitDate || null
  if (firstVisitDate !== null && !/^\d{4}-\d{2}-\d{2}$/.test(firstVisitDate)) {
    return { ok: false, message: '首次进场日期格式不对' }
  }
  return {
    ok: true,
    request: {
      amountMinor: amount,
      method: form.method.trim(),
      schedule: form.schedule.trim(),
      durationDays: form.durationDays,
      deliverables: form.deliverables.trim(),
      validUntil: form.validUntil.toISOString(),
      lines: built.lines,
      team: team.value,
      nightWork: nightWork.value,
      reportHours: reportHours.value,
      archiveDays: archiveDays.value,
      firstVisitDate,
    },
  }
}

export function createDemandQuotesApi(http: ApiClient) {
  const one = (id: string) => `/api/v1/provider/demands/${encodeURIComponent(id)}`
  return {
    list(filters: DemandFilters, page: number, size: number): Promise<Page<ProviderDemand>> {
      return http.get<Page<ProviderDemand>>('/api/v1/provider/demands', { status: filters.status, page, size })
    },
    detail(id: string): Promise<ProviderDemand> {
      return http.get<ProviderDemand>(one(id))
    },
    quote(id: string, request: QuoteRequest): Promise<ProviderDemand> {
      return http.put<ProviderDemand>(`${one(id)}/quote`, request)
    },
    withdraw(id: string): Promise<ProviderDemand> {
      return http.delete<ProviderDemand>(`${one(id)}/quote`)
    },
  }
}

export type DemandQuotesApi = ReturnType<typeof createDemandQuotesApi>
