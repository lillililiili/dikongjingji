import { DAY_MS, padTwo, toBeijingDate, toBeijingParts } from '../../../shared/time/beijing-time'

import {
  DEMAND_TYPE_INFO,
  MAX_INVITED_PROVIDERS,
  type Demand,
  type DemandDeliverableCode,
  type DemandDraftInput,
  type DemandFormField,
  type DemandProvider,
  type DemandQuoteScope,
  type DemandType,
} from '../domain/demand'

export interface DemandFormCity {
  code: string
  name: string
}

// What the publish page edits. Everything but the type may be empty in a draft.
export interface DemandForm {
  type: DemandType
  city: DemandFormCity | null
  address: string
  scope: string
  dueDate: string | null
  budgetMinMinor: number | null
  budgetMaxMinor: number | null
  deliverables: DemandDeliverableCode[]
  note: string
  quoteScope: DemandQuoteScope
  invited: DemandProvider[]
}

export const DEMAND_ADDRESS_MAX = 80
export const DEMAND_SCOPE_MAX = 60
export const DEMAND_NOTE_MAX = 500

export const DEMAND_NOTICE_TEXT =
  '提交后服务商在 72 小时内报价，报价会通过“消息”通知你，并在“我的 → 需求与报价”中比选。提交需求不产生费用。'

export function createDemandForm(type: DemandType, city: DemandFormCity | null): DemandForm {
  return {
    type,
    city,
    address: '',
    scope: '',
    dueDate: null,
    budgetMinMinor: null,
    budgetMaxMinor: null,
    deliverables: [...DEMAND_TYPE_INFO[type].defaultDeliverables],
    note: '',
    quoteScope: 'MATCH',
    invited: [],
  }
}

// A draft read back from the server, or an earlier demand to publish again (its deliverables are
// kept only when they still belong to its type, which they always should).
export function formFromDemand(demand: Demand): DemandForm {
  const allowed = new Set(DEMAND_TYPE_INFO[demand.type].deliverables)
  return {
    type: demand.type,
    city: demand.cityCode && demand.cityName ? { code: demand.cityCode, name: demand.cityName } : null,
    address: demand.address ?? '',
    scope: demand.scope ?? '',
    dueDate: demand.dueDate,
    budgetMinMinor: demand.budgetMinMinor,
    budgetMaxMinor: demand.budgetMaxMinor,
    deliverables: demand.deliverables.map((item) => item.code).filter((code) => allowed.has(code)),
    note: demand.note ?? '',
    quoteScope: demand.quoteScope,
    invited: demand.quoteScope === 'INVITE' ? [...demand.invitedProviders] : [],
  }
}

// Each type has its own deliverables, so switching goes back to the new type's defaults and the
// scope (whose meaning changes with the type) is filled in again (Demand board).
export function switchDemandType(form: DemandForm, type: DemandType): DemandForm {
  if (type === form.type) return form
  return {
    ...form,
    type,
    scope: '',
    deliverables: [...DEMAND_TYPE_INFO[type].defaultDeliverables],
  }
}

// Ticks keep the page order of the type, whatever order they were tapped in.
export function toggleDemandDeliverable(form: DemandForm, code: DemandDeliverableCode): DemandForm {
  const picked = form.deliverables.includes(code)
    ? form.deliverables.filter((item) => item !== code)
    : [...form.deliverables, code]
  const order = DEMAND_TYPE_INFO[form.type].deliverables
  return { ...form, deliverables: order.filter((item) => picked.includes(item)) }
}

// Providers serve cities, so a new city drops the ones picked for the old one.
export function changeDemandCity(form: DemandForm, city: DemandFormCity): DemandForm {
  if (form.city?.code === city.code) return { ...form, city }
  return { ...form, city, invited: [] }
}

export function toggleInvitedProvider(form: DemandForm, provider: DemandProvider): DemandForm {
  const invited = form.invited.some((item) => item.providerId === provider.providerId)
    ? form.invited.filter((item) => item.providerId !== provider.providerId)
    : form.invited.length >= MAX_INVITED_PROVIDERS
      ? form.invited
      : [...form.invited, provider]
  return { ...form, invited }
}

// 发布需求 from a service or provider page: 仅邀请这家, but only when the provider is on the
// inviteable list of the form's city and type; otherwise the form stays on 平台匹配.
export function preselectInvitedProvider(
  form: DemandForm,
  providers: readonly DemandProvider[],
  providerId: string,
): { form: DemandForm; found: boolean } {
  const provider = providers.find((item) => item.providerId === providerId)
  if (!provider) return { form, found: false }
  return { form: { ...form, quoteScope: 'INVITE', invited: [provider] }, found: true }
}

export function toDemandDraftInput(form: DemandForm): DemandDraftInput {
  return {
    type: form.type,
    cityCode: form.city?.code ?? null,
    address: form.address,
    scope: form.scope,
    dueDate: form.dueDate,
    budgetMinMinor: form.budgetMinMinor,
    budgetMaxMinor: form.budgetMaxMinor,
    deliverables: [...form.deliverables],
    note: form.note,
    quoteScope: form.quoteScope,
    invitedProviderIds: form.quoteScope === 'INVITE' ? form.invited.map((item) => item.providerId) : [],
  }
}

export function demandFieldMessage(field: DemandFormField, type: DemandType): string {
  switch (field) {
    case 'cityCode': return '请选择服务城市'
    case 'address': return '请填写服务地点'
    case 'scope': return `请填写${DEMAND_TYPE_INFO[type].scopeLabel}`
    case 'dueDate': return '期望完成日期已过，请重新选择'
    case 'deliverables': return '请至少选择一项交付物'
    case 'invitedProviderIds': return '请邀请 1–3 家服务这个城市的服务商'
  }
}

export interface DemandFieldError {
  field: DemandFormField
  message: string
}

/**
 * The first required field that is missing, top to bottom like the server checks them. The due
 * date is left to the server: only it knows today in Beijing for sure, and a past date in an old
 * draft comes back as a 422 on that field.
 */
export function findDemandFormError(form: DemandForm): DemandFieldError | null {
  const missing: DemandFormField | null = !form.city
    ? 'cityCode'
    : !form.address.trim()
      ? 'address'
      : !form.scope.trim()
        ? 'scope'
        : form.deliverables.length === 0
          ? 'deliverables'
          : form.quoteScope === 'INVITE' && form.invited.length === 0
            ? 'invitedProviderIds'
            : null
  return missing ? { field: missing, message: demandFieldMessage(missing, form.type) } : null
}

const DEMAND_FIELDS: readonly DemandFormField[] = ['cityCode', 'address', 'scope', 'dueDate', 'deliverables', 'invitedProviderIds']

export function isDemandFormField(value: unknown): value is DemandFormField {
  return typeof value === 'string' && (DEMAND_FIELDS as readonly string[]).includes(value)
}

export interface DemandSaveFailureInput {
  status: number
  code: string | null
  message: string
  details: Record<string, unknown> | null
}

export interface DemandSaveFailure {
  // The field to mark, for a 422 that names one.
  field: DemandFormField | null
  message: string
  // The demand changed under the page (already submitted elsewhere): read it again.
  reload: boolean
  // The idempotency key was used for other content: the next try needs a new key.
  newKey: boolean
}

export function describeDemandSaveFailure(failure: DemandSaveFailureInput | null, type: DemandType): DemandSaveFailure {
  const base = { field: null, reload: false, newKey: false }
  if (!failure) return { ...base, message: '保存失败，请稍后重试' }
  if (failure.code === 'DEMAND_INCOMPLETE') {
    const field = failure.details?.field
    return isDemandFormField(field)
      ? { ...base, field, message: demandFieldMessage(field, type) }
      : { ...base, message: failure.message || '还有必填项没有填' }
  }
  if (failure.code === 'DEMAND_STATE_INVALID') {
    return { ...base, reload: true, message: '这份需求已经提交，不能再改' }
  }
  if (failure.code === 'DEMAND_IDEMPOTENCY_KEY_REUSED') {
    return { ...base, newKey: true, message: '内容有变化，请再提交一次' }
  }
  if (failure.status === 404) return { ...base, reload: true, message: '这份需求已不存在' }
  if (failure.status === 0) return { ...base, message: '网络连接失败，请稍后重试' }
  return { ...base, message: failure.message || '保存失败，请稍后重试' }
}

// --- budget ---

export interface DemandBudgetOption {
  label: string
  minMinor: number | null
  maxMinor: number | null
}

const WAN = 1_000_000 // ¥10,000 in fen

// The board's ranges; both ends empty means 面议.
export const DEMAND_BUDGET_OPTIONS: readonly DemandBudgetOption[] = [
  { label: '1 万元以下', minMinor: null, maxMinor: WAN },
  { label: '1 万–2 万元', minMinor: WAN, maxMinor: 2 * WAN },
  { label: '2 万–5 万元', minMinor: 2 * WAN, maxMinor: 5 * WAN },
  { label: '5 万元以上', minMinor: 5 * WAN, maxMinor: null },
  { label: '不限', minMinor: null, maxMinor: null },
]

// "1 万" for whole thousands from ten thousand up, otherwise "8,000".
function budgetPart(minor: number): string {
  const yuan = minor / 100
  if (yuan >= 10_000 && yuan % 1_000 === 0) return `${yuan / 10_000} 万`
  return Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function formatDemandBudget(minMinor: number | null, maxMinor: number | null): string {
  if (minMinor === null && maxMinor === null) return '不限'
  if (minMinor === null || minMinor === 0) return `${budgetPart(maxMinor as number)}元以下`
  if (maxMinor === null) return `${budgetPart(minMinor)}元以上`
  if (minMinor === maxMinor) return `${budgetPart(minMinor)}元`
  return `${budgetPart(minMinor)}–${budgetPart(maxMinor)}元`
}

// --- due date ---

export interface DemandDueOption {
  label: string
  // YYYY-MM-DD, or null for 时间可协商.
  date: string | null
}

// "11/26 前"; another year is written out.
export function formatDemandDueDate(date: string | null, now: Date): string {
  if (!date) return '时间可协商'
  const [year, month, day] = date.split('-').map(Number)
  const thisYear = toBeijingParts(now).year
  return year === thisYear ? `${padTwo(month)}/${padTwo(day)} 前` : `${year}/${padTwo(month)}/${padTwo(day)} 前`
}

// A week, two weeks and a month from today (Beijing), then 时间可协商; any other day comes from
// the date picker under them.
export function buildDemandDueOptions(now: Date): DemandDueOption[] {
  const days = [7, 14, 30].map((offset) => toBeijingDate(new Date(now.getTime() + offset * DAY_MS)))
  return [
    ...days.map((date) => ({ label: formatDemandDueDate(date, now), date })),
    { label: '时间可协商', date: null },
  ]
}

// --- idempotency key ---

// A random UUID (version 4 layout) for the Idempotency-Key header.
export function createDemandKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

/**
 * One idempotency key per distinct new demand. Tapping again after a timeout sends the same
 * content with the same key, so the server answers with the demand it already made instead of a
 * second one; changed content (or saving a draft instead of submitting) gets a new key.
 */
export function createDemandKeyHolder(createKey: () => string = createDemandKey) {
  let signature = ''
  let key = ''
  return {
    keyFor(input: DemandDraftInput, submit: boolean): string {
      const next = JSON.stringify({ input, submit })
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
    reset(): void {
      signature = ''
      key = ''
    },
  }
}
