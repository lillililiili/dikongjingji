import type { FormItemRule } from 'element-plus'

import { maxChars, matches, required } from '@/core/ui/form-rules'

import type { DeviceDraft, OpsVenueDetail, OpsVenueDevice, OpsVenueProject, ProjectDraft, VenueDraft, Weekday }
  from './venue-api'

/** 和后端 VenueOpsService 的限制一致。 */
export const VENUE_LIMITS = {
  name: 120,
  address: 240,
  phone: 32,
  closedNote: 40,
  /** S4-4（运营场馆契约 1.1.0）：首页场馆卡的项目摘要。 */
  projectSummary: 40,
  infoItems: 10,
  infoLabel: 40,
  infoContent: 200,
  checkinMinutes: 240,
  minDuration: 5,
  maxDuration: 480,
  maxPriceYuan: 100000,
  maxAge: 99,
  maxCapacity: 999,
  limitNote: 200,
  summary: 200,
  sortOrder: 9999,
  sessionsPerBatch: 200,
  rangeDays: 62,
  startTimes: 12,
} as const

export const HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/
const PHONE = /^[0-9+\- ]{5,32}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export const WEEKDAY_OPTIONS: ReadonlyArray<{ value: Weekday, label: string }> = [
  { value: 'MONDAY', label: '周一' },
  { value: 'TUESDAY', label: '周二' },
  { value: 'WEDNESDAY', label: '周三' },
  { value: 'THURSDAY', label: '周四' },
  { value: 'FRIDAY', label: '周五' },
  { value: 'SATURDAY', label: '周六' },
  { value: 'SUNDAY', label: '周日' },
]

export const venueRules: Record<string, FormItemRule[]> = {
  operatorId: [required('运营机构', 'change')],
  name: [required('场馆名称'), maxChars('场馆名称', VENUE_LIMITS.name)],
  cityCode: [required('城市', 'change')],
  address: [required('地址'), maxChars('地址', VENUE_LIMITS.address)],
  phone: [matches(PHONE, '电话只能有数字、空格、+ 和 -，5–32 位')],
  opensAt: [matches(HHMM, '开门时间写成 09:30 这样')],
  closesAt: [matches(HHMM, '关门时间写成 18:00 这样')],
  closedNote: [maxChars('休息日说明', VENUE_LIMITS.closedNote)],
  projectSummary: [maxChars('项目摘要', VENUE_LIMITS.projectSummary)],
}

export const projectRules: Record<string, FormItemRule[]> = {
  name: [required('项目名称'), maxChars('项目名称', VENUE_LIMITS.name)],
  priceYuan: [required('价格'), matches(/^\d{1,6}(\.\d{1,2})?$/, '价格写成 39 或 39.50 这样（元，最多两位小数）')],
  limitNote: [maxChars('限制说明', VENUE_LIMITS.limitNote)],
}

export const deviceRules: Record<string, FormItemRule[]> = {
  category: [required('类别', 'change')],
  name: [required('设备名称'), maxChars('设备名称', VENUE_LIMITS.name)],
  summary: [maxChars('说明', VENUE_LIMITS.summary)],
  trialMode: [required('体验方式', 'change')],
  productId: [matches(UUID, '商品 ID 是 36 位的 UUID（在商品与供给里复制）')],
}

/** 营业时间：两项一起填或一起空，开门早于关门；返回问题说明，没问题返回 null。 */
export function openingHoursProblem(opensAt: string, closesAt: string): string | null {
  const opens = opensAt.trim()
  const closes = closesAt.trim()
  if (opens === '' && closes === '') return null
  if (opens === '' || closes === '') return '开门和关门时间要一起填'
  if (!HHMM.test(opens) || !HHMM.test(closes)) return '时间写成 09:30 这样'
  if (opens >= closes) return '开门时间要早于关门时间'
  return null
}

/** App 上显示的那一行（后端保存时生成同样的文字）。 */
export function openingNotePreview(opensAt: string, closesAt: string, closedNote: string): string | null {
  if (openingHoursProblem(opensAt, closesAt) !== null || opensAt.trim() === '') return null
  const note = closedNote.trim()
  return `${opensAt.trim()}–${closesAt.trim()}${note ? ` · ${note}` : ''}`
}

export function entryRuleText(checkinOpensMinutes: number, lateEntryMinutes: number | null): string {
  const late = lateEntryMinutes === null ? '到场次结束都能入场' : `开场 ${lateEntryMinutes} 分钟后停止入场`
  return `开场前 ${checkinOpensMinutes} 分钟开始检票，${late}`
}

const WEEKDAY_INDEX: Readonly<Record<Weekday, number>> = {
  SUNDAY: 0, MONDAY: 1, TUESDAY: 2, WEDNESDAY: 3, THURSDAY: 4, FRIDAY: 5, SATURDAY: 6,
}

function parseDate(text: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!match) return null
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
  return date.toISOString().slice(0, 10) === text ? date : null
}

/**
 * 周模板展开（和后端一致，按北京时间的日期和时间，不跟电脑时区走）：fromDate–toDate（含两端，跨度不超过 62 天）里
 * weekdays 那几天 × startTimes，按日期、时间先后。结果是 “yyyy-MM-dd HH:mm” 文本；参数不合格返回问题说明。
 */
export function expandWeekTemplate(fromDate: string, toDate: string, weekdays: readonly Weekday[],
  startTimes: readonly string[]): { slots: string[] } | { problem: string } {
  const from = parseDate(fromDate)
  const to = parseDate(toDate)
  if (!from || !to) return { problem: '请选择开始和结束日期' }
  const days = Math.round((to.getTime() - from.getTime()) / 86_400_000)
  if (days < 0) return { problem: '结束日期不能早于开始日期' }
  if (days >= VENUE_LIMITS.rangeDays) return { problem: `日期跨度不能超过 ${VENUE_LIMITS.rangeDays} 天` }
  if (weekdays.length === 0) return { problem: '至少选一天' }
  const times = [...new Set(startTimes.map((time) => time.trim()))]
  if (times.length === 0) return { problem: '至少填一个开始时间' }
  if (times.length !== startTimes.length) return { problem: '开始时间重复了' }
  if (times.length > VENUE_LIMITS.startTimes) return { problem: `每天最多 ${VENUE_LIMITS.startTimes} 个开始时间` }
  if (times.some((time) => !HHMM.test(time))) return { problem: '开始时间写成 10:00 这样' }
  times.sort()
  const wanted = new Set(weekdays.map((day) => WEEKDAY_INDEX[day]))
  const slots: string[] = []
  for (let offset = 0; offset <= days; offset += 1) {
    const day = new Date(from.getTime() + offset * 86_400_000)
    if (!wanted.has(day.getUTCDay())) continue
    const date = day.toISOString().slice(0, 10)
    for (const time of times) slots.push(`${date} ${time}`)
  }
  return { slots }
}

export function emptyVenueDraft(): VenueDraft {
  return {
    operatorId: '',
    name: '',
    cityCode: '',
    coverUrl: '',
    address: '',
    phone: '',
    opensAt: '',
    closesAt: '',
    closedNote: '',
    projectSummary: '',
    checkinOpensMinutes: 30,
    lateEntryMinutes: null,
    sortOrder: 0,
    infoItems: [],
  }
}

export function venueDraftOf(detail: OpsVenueDetail): VenueDraft {
  return {
    operatorId: detail.operatorId,
    name: detail.name,
    cityCode: detail.cityCode,
    coverUrl: detail.coverUrl ?? '',
    address: detail.address,
    phone: detail.phone ?? '',
    opensAt: detail.opensAt ?? '',
    closesAt: detail.closesAt ?? '',
    closedNote: detail.closedNote ?? '',
    projectSummary: detail.projectSummary ?? '',
    checkinOpensMinutes: detail.checkinOpensMinutes,
    lateEntryMinutes: detail.lateEntryMinutes,
    sortOrder: detail.sortOrder,
    infoItems: detail.infoItems.map((item) => ({ ...item })),
  }
}

export function projectDraftOf(project: OpsVenueProject | null): ProjectDraft {
  return project
    ? {
        name: project.name,
        durationMinutes: project.durationMinutes,
        priceAmountMinor: project.priceAmountMinor,
        minAge: project.minAge,
        capacity: project.capacity,
        limitNote: project.limitNote ?? '',
        sortOrder: project.sortOrder,
      }
    : { name: '', durationMinutes: 30, priceAmountMinor: 0, minAge: null, capacity: 8, limitNote: '', sortOrder: 0 }
}

export function deviceDraftOf(device: OpsVenueDevice | null): DeviceDraft {
  return device
    ? {
        category: device.category,
        name: device.name,
        summary: device.summary ?? '',
        imageUrl: device.imageUrl ?? '',
        trialMode: device.trialMode,
        productId: device.productId ?? '',
        sortOrder: device.sortOrder,
      }
    : { category: 'ROBOT', name: '', summary: '', imageUrl: '', trialMode: 'ACCOMPANIED_TRIAL', productId: '', sortOrder: 0 }
}

/** 今天的北京时间日期（yyyy-MM-dd）。 */
export function beijingToday(now: Date = new Date()): string {
  return new Date(now.getTime() + 8 * 3_600_000).toISOString().slice(0, 10)
}

export function addDays(date: string, days: number): string {
  const parsed = parseDate(date)
  return parsed ? new Date(parsed.getTime() + days * 86_400_000).toISOString().slice(0, 10) : date
}

/** App 首页场馆卡第一行会显示什么：填了用填的，没填用启用的前三个项目名（和后端一样拼）。 */
export function projectSummaryPreview(projectSummary: string, projects: readonly Pick<OpsVenueProject, 'name' | 'status' | 'sortOrder'>[]): string | null {
  const written = projectSummary.trim()
  if (written) return written
  const names = projects
    .filter((project) => project.status === 'ACTIVE')
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .slice(0, 3)
    .map((project) => project.name)
  return names.length ? names.join(' · ') : null
}
