import type { ApiClient } from '@/core/http/api-client'
import type { OpsChange } from '@/core/ops/ops-common'
import { yuanToMinor, minorToYuanText } from '@/core/ops/ops-common'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-activity.yaml 1.1.0（1.1.0 加候补、团队报名两个开关）。

export type ActivityType = 'CONTEST' | 'TRAINING' | 'STUDY_TOUR' | 'OPEN_DAY'
export type ActivityPriceMode = 'PER_PERSON' | 'FREE' | 'INQUIRY'
export type OpsActivityStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type OpsSessionSaleStatus = 'ON_SALE' | 'STOPPED'
export type OpsActivityPublishField = 'coverUrl' | 'enrollmentNotice' | 'refundNote' | 'organizer' | 'sessions'

export const ACTIVITY_TYPE_OPTIONS: ReadonlyArray<{ value: ActivityType, label: string }> = [
  { value: 'CONTEST', label: '赛事' },
  { value: 'TRAINING', label: '培训' },
  { value: 'STUDY_TOUR', label: '研学' },
  { value: 'OPEN_DAY', label: '体验日' },
]

export const PRICE_MODE_OPTIONS: ReadonlyArray<{ value: ActivityPriceMode, label: string, hint: string }> = [
  { value: 'PER_PERSON', label: '按人收费', hint: '在线报名付款（主办机构要登记收款账户），钱进主办机构自己的商户号' },
  { value: 'FREE', label: '免费', hint: '在线报名，不用付款' },
  { value: 'INQUIRY', label: '询价', hint: 'App 上不能在线报名' },
]

export const ACTIVITY_STATUS_OPTIONS: ReadonlyArray<{ value: OpsActivityStatus, label: string, tag: 'info' | 'success' | 'warning' }> = [
  { value: 'DRAFT', label: '草稿', tag: 'info' },
  { value: 'PUBLISHED', label: '已上线', tag: 'success' },
  { value: 'OFFLINE', label: '已下线', tag: 'warning' },
]

/** 上线必填项的中文（422 的 details.missing 和详情的 publishMissing）。 */
export const PUBLISH_FIELD_LABELS: Readonly<Record<OpsActivityPublishField, string>> = {
  coverUrl: '封面',
  enrollmentNotice: '报名须知',
  refundNote: '退改说明（按人收费时）',
  organizer: '在用的主办机构',
  sessions: '至少一个在售、报名没截止的场次',
}

export const CHANGE_SUBJECT_LABELS: Readonly<Record<string, string>> = { ACTIVITY: '活动', SESSION: '场次' }

export const LIMITS = {
  title: 60,
  subtitle: 200,
  venueName: 120,
  venueAddress: 240,
  scheduleNote: 120,
  shortNote: 300,
  refundNote: 500,
  enrollmentNotice: 2000,
  gallery: 9,
  age: 120,
  label: 60,
  capacity: 10000,
  /** 单价上限 10 万元。 */
  priceMinor: 10_000_000,
} as const

export function labelOf<T extends string>(options: ReadonlyArray<{ value: T, label: string }>, value: T): string {
  return options.find((option) => option.value === value)?.label ?? value
}

export function statusTag(status: OpsActivityStatus): 'info' | 'success' | 'warning' {
  return ACTIVITY_STATUS_OPTIONS.find((option) => option.value === status)?.tag ?? 'info'
}

export interface OpsOrganizationRef {
  organizationId: string
  name: string | null
}

export interface OpsActivitySession {
  sessionId: string
  label: string | null
  startsAt: string
  endsAt: string | null
  checkInOpensAt: string | null
  enrollmentClosesAt: string
  capacity: number
  enrolledCount: number
  saleStatus: OpsSessionSaleStatus
  cancelled: boolean
  hasEnrollments: boolean
  started: boolean
  version: number
}

export interface OpsActivity {
  activityId: string
  type: ActivityType
  title: string
  subtitle: string | null
  coverUrl: string | null
  galleryUrls: string[]
  organizer: OpsOrganizationRef
  coOrganizer: OpsOrganizationRef | null
  cityCode: string
  cityName: string | null
  venueName: string
  venueAddress: string
  scheduleNote: string | null
  priceMode: ActivityPriceMode
  priceAmountMinor: number | null
  minAge: number | null
  maxAge: number | null
  eligibilityNote: string | null
  seatNote: string | null
  refundNote: string | null
  enrollmentNotice: string | null
  waitlistAllowed: boolean
  teamEnrollmentAllowed: boolean
  /** 还在排队或已转正待确认的候补条数：关掉候补后它们照常按顺序转正。 */
  activeWaitlistCount: number
  status: OpsActivityStatus
  firstPublishedAt: string | null
  hasEnrollments: boolean
  deletable: boolean
  publishMissing: OpsActivityPublishField[]
  sessions: OpsActivitySession[]
  createdAt: string
  updatedAt: string
  version: number
}

export interface OpsActivitySummary {
  activityId: string
  type: ActivityType
  title: string
  cityCode: string
  cityName: string | null
  organizer: OpsOrganizationRef
  status: OpsActivityStatus
  priceMode: ActivityPriceMode
  priceAmountMinor: number | null
  sessionCount: number
  nextStartsAt: string | null
  enrolledCount: number
  updatedAt: string
  version: number
}

export interface ActivityFilters {
  keyword: string
  cityCode: string
  type: ActivityType | ''
  status: OpsActivityStatus | ''
}

/** 页面表单（文字都是字符串，单价按“元”填）。 */
export interface ActivityForm {
  type: ActivityType
  title: string
  subtitle: string
  coverUrl: string
  galleryUrls: string[]
  organizerId: string
  coOrganizerId: string
  cityCode: string
  venueName: string
  venueAddress: string
  scheduleNote: string
  priceMode: ActivityPriceMode
  priceYuan: string
  minAge: number | null
  maxAge: number | null
  eligibilityNote: string
  seatNote: string
  refundNote: string
  enrollmentNotice: string
  waitlistAllowed: boolean
  teamEnrollmentAllowed: boolean
}

/** 场次表单：时间按北京时间“YYYY-MM-DD HH:mm”填。 */
export interface SessionForm {
  label: string
  startsAt: string
  endsAt: string
  checkInOpensAt: string
  enrollmentClosesAt: string
  capacity: number | null
}

export interface ActivityRequest {
  type: ActivityType
  title: string
  subtitle: string | null
  coverUrl: string | null
  galleryUrls: string[]
  organizerId: string
  coOrganizerId: string | null
  cityCode: string
  venueName: string
  venueAddress: string
  scheduleNote: string | null
  priceMode: ActivityPriceMode
  priceAmountMinor: number | null
  minAge: number | null
  maxAge: number | null
  eligibilityNote: string | null
  seatNote: string | null
  refundNote: string | null
  enrollmentNotice: string | null
  waitlistAllowed: boolean
  teamEnrollmentAllowed: boolean
  version?: number
}

export interface SessionRequest {
  label: string | null
  startsAt: string
  endsAt: string | null
  checkInOpensAt: string | null
  enrollmentClosesAt: string
  capacity: number
  version?: number
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

export function emptyActivityForm(): ActivityForm {
  return {
    type: 'CONTEST',
    title: '',
    subtitle: '',
    coverUrl: '',
    galleryUrls: [],
    organizerId: '',
    coOrganizerId: '',
    cityCode: '',
    venueName: '',
    venueAddress: '',
    scheduleNote: '',
    priceMode: 'FREE',
    priceYuan: '',
    minAge: null,
    maxAge: null,
    eligibilityNote: '',
    seatNote: '',
    refundNote: '',
    enrollmentNotice: '',
    waitlistAllowed: false,
    teamEnrollmentAllowed: false,
  }
}

export function activityFormOf(activity: OpsActivity): ActivityForm {
  return {
    type: activity.type,
    title: activity.title,
    subtitle: activity.subtitle ?? '',
    coverUrl: activity.coverUrl ?? '',
    galleryUrls: [...activity.galleryUrls],
    organizerId: activity.organizer.organizationId,
    coOrganizerId: activity.coOrganizer?.organizationId ?? '',
    cityCode: activity.cityCode,
    venueName: activity.venueName,
    venueAddress: activity.venueAddress,
    scheduleNote: activity.scheduleNote ?? '',
    priceMode: activity.priceMode,
    priceYuan: minorToYuanText(activity.priceAmountMinor),
    minAge: activity.minAge,
    maxAge: activity.maxAge,
    eligibilityNote: activity.eligibilityNote ?? '',
    seatNote: activity.seatNote ?? '',
    refundNote: activity.refundNote ?? '',
    enrollmentNotice: activity.enrollmentNotice ?? '',
    waitlistAllowed: activity.waitlistAllowed,
    teamEnrollmentAllowed: activity.teamEnrollmentAllowed,
  }
}

/** 候补、团队报名都要能在线报名（免费或按人收费）；询价活动只能关着。 */
export function enrollmentSwitchesAvailable(priceMode: ActivityPriceMode): boolean {
  return priceMode !== 'INQUIRY'
}

/**
 * 保存前要运营知道的开关变化（关掉候补、团队报名时已有的照常）；没关任何开关时为空数组。
 * 规则见 contracts/openapi-ops-activity.yaml 1.1.0“候补和团队报名开关”。
 */
export function switchClosingNotes(current: OpsActivity, form: ActivityForm): string[] {
  const available = enrollmentSwitchesAvailable(form.priceMode)
  const notes: string[] = []
  if (current.waitlistAllowed && !(available && form.waitlistAllowed)) {
    notes.push(current.activeWaitlistCount > 0
      ? `关闭候补：App 上满员的场次不能再排队；已在排队的 ${current.activeWaitlistCount} 条候补照常按顺序转正、确认或付款。`
      : '关闭候补：App 上满员的场次不能再排队。')
  }
  if (current.teamEnrollmentAllowed && !(available && form.teamEnrollmentAllowed)) {
    notes.push('关闭团队报名：App 上不能再团队报名；已有的团队报名照常（能按人取消、退款、改期、核销）。')
  }
  return notes
}

/** 表单 → 请求体：空白变 null，单价元 → 分（只有按人收费时带）。 */
export function activityRequestOf(form: ActivityForm, version?: number): ActivityRequest {
  return {
    type: form.type,
    title: form.title.trim(),
    subtitle: blankToNull(form.subtitle),
    coverUrl: blankToNull(form.coverUrl),
    galleryUrls: form.galleryUrls.map((url) => url.trim()).filter((url) => url !== ''),
    organizerId: form.organizerId,
    coOrganizerId: blankToNull(form.coOrganizerId),
    cityCode: form.cityCode,
    venueName: form.venueName.trim(),
    venueAddress: form.venueAddress.trim(),
    scheduleNote: blankToNull(form.scheduleNote),
    priceMode: form.priceMode,
    priceAmountMinor: form.priceMode === 'PER_PERSON' ? yuanToMinor(form.priceYuan) : null,
    minAge: form.minAge,
    maxAge: form.maxAge,
    eligibilityNote: blankToNull(form.eligibilityNote),
    seatNote: blankToNull(form.seatNote),
    refundNote: blankToNull(form.refundNote),
    enrollmentNotice: blankToNull(form.enrollmentNotice),
    // 询价活动不能在线报名，两个开关跟着关（后端对询价活动打开会回 400）。
    waitlistAllowed: enrollmentSwitchesAvailable(form.priceMode) && form.waitlistAllowed,
    teamEnrollmentAllowed: enrollmentSwitchesAvailable(form.priceMode) && form.teamEnrollmentAllowed,
    ...(version === undefined ? {} : { version }),
  }
}

// ---- 北京时间 ----

const BEIJING_PARTS = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** 接口时间 → 表单里的“YYYY-MM-DD HH:mm”（北京时间，不跟电脑时区走）。 */
export function beijingInputOf(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const parts = Object.fromEntries(BEIJING_PARTS.formatToParts(date).map((part) => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** 表单里的“YYYY-MM-DD HH:mm”（北京时间）→ 带 +08:00 的时间；格式不对返回 null。 */
export function isoOfBeijingInput(text: string): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})$/.exec(text.trim())
  if (!match) return null
  const iso = `${match[1]}T${match[2]}:00+08:00`
  return Number.isNaN(new Date(iso).getTime()) ? null : iso
}

export function emptySessionForm(): SessionForm {
  return { label: '', startsAt: '', endsAt: '', checkInOpensAt: '', enrollmentClosesAt: '', capacity: null }
}

export function sessionFormOf(session: OpsActivitySession): SessionForm {
  return {
    label: session.label ?? '',
    startsAt: beijingInputOf(session.startsAt),
    endsAt: beijingInputOf(session.endsAt),
    checkInOpensAt: beijingInputOf(session.checkInOpensAt),
    enrollmentClosesAt: beijingInputOf(session.enrollmentClosesAt),
    capacity: session.capacity,
  }
}

/** 场次表单 → 请求体；必填时间缺了或格式不对时抛错（表单校验先挡住）。 */
export function sessionRequestOf(form: SessionForm, version?: number): SessionRequest {
  const startsAt = isoOfBeijingInput(form.startsAt)
  const enrollmentClosesAt = isoOfBeijingInput(form.enrollmentClosesAt)
  if (!startsAt || !enrollmentClosesAt || form.capacity === null) throw new Error('场次的开始、报名截止和名额必填')
  return {
    label: blankToNull(form.label),
    startsAt,
    endsAt: isoOfBeijingInput(form.endsAt),
    checkInOpensAt: isoOfBeijingInput(form.checkInOpensAt),
    enrollmentClosesAt,
    capacity: form.capacity,
    ...(version === undefined ? {} : { version }),
  }
}

/** 场次在 App 上的样子（运营一眼看懂）。 */
export function sessionState(session: OpsActivitySession, now: Date = new Date()): { label: string, tag: 'success' | 'warning' | 'info' | 'danger' } {
  if (session.cancelled) return { label: '已取消', tag: 'info' }
  if (session.started) return { label: '已开始', tag: 'info' }
  if (session.saleStatus === 'STOPPED') return { label: '停售', tag: 'danger' }
  if (new Date(session.enrollmentClosesAt).getTime() <= now.getTime()) return { label: '报名截止', tag: 'warning' }
  if (session.enrolledCount >= session.capacity) return { label: '已满', tag: 'warning' }
  return { label: '在售', tag: 'success' }
}

export function createActivityApi(http: ApiClient) {
  const base = '/api/v1/ops/activities'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  const session = (id: string, sessionId: string) => `${one(id)}/sessions/${encodeURIComponent(sessionId)}`
  return {
    list(filters: ActivityFilters, page: number, size: number): Promise<Page<OpsActivitySummary>> {
      return http.get<Page<OpsActivitySummary>>(base, {
        keyword: filters.keyword.trim(),
        cityCode: filters.cityCode,
        type: filters.type,
        status: filters.status,
        page,
        size,
      })
    },
    detail(id: string): Promise<OpsActivity> {
      return http.get<OpsActivity>(one(id))
    },
    create(form: ActivityForm): Promise<OpsActivity> {
      return http.post<OpsActivity>(base, activityRequestOf(form))
    },
    update(id: string, form: ActivityForm, version: number): Promise<OpsActivity> {
      return http.put<OpsActivity>(one(id), activityRequestOf(form, version))
    },
    publish(id: string, version: number): Promise<OpsActivity> {
      return http.post<OpsActivity>(`${one(id)}/publish`, { version })
    },
    offline(id: string, version: number): Promise<OpsActivity> {
      return http.post<OpsActivity>(`${one(id)}/offline`, { version })
    },
    remove(id: string, version: number): Promise<{ activityId: string, deleted: boolean }> {
      return http.request('DELETE', one(id), { query: { version } })
    },
    changes(id: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${one(id)}/changes`)
    },
    addSession(id: string, form: SessionForm): Promise<OpsActivity> {
      return http.post<OpsActivity>(`${one(id)}/sessions`, sessionRequestOf(form))
    },
    updateSession(id: string, sessionId: string, form: SessionForm, version: number): Promise<OpsActivity> {
      return http.put<OpsActivity>(session(id, sessionId), sessionRequestOf(form, version))
    },
    removeSession(id: string, sessionId: string, version: number): Promise<OpsActivity> {
      return http.request('DELETE', session(id, sessionId), { query: { version } })
    },
    stopSale(id: string, sessionId: string, version: number): Promise<OpsActivity> {
      return http.post<OpsActivity>(`${session(id, sessionId)}/stop-sale`, { version })
    },
    resumeSale(id: string, sessionId: string, version: number): Promise<OpsActivity> {
      return http.post<OpsActivity>(`${session(id, sessionId)}/resume-sale`, { version })
    },
  }
}

export type ActivityApi = ReturnType<typeof createActivityApi>
