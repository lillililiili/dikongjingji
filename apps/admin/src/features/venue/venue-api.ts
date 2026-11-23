import { newIdempotencyKey, type ApiClient } from '@/core/http/api-client'
import type { OpsChange } from '@/core/ops/ops-common'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-venue.yaml 1.0.0：场馆与设备。

export type VenueStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type VenueItemStatus = 'ACTIVE' | 'DISABLED'
export type VenueSessionStatus = 'ACTIVE' | 'STOPPED' | 'CANCELLED'
export type DeviceCategory = 'ROBOT' | 'DRONE' | 'PARTS'
export type TrialMode = 'ACCOMPANIED_TRIAL' | 'DISPLAY_ONLY'
export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
export type VenuePublishField = 'coverUrl' | 'openingHours' | 'projects' | 'sessions'

export const VENUE_STATUS_OPTIONS: ReadonlyArray<{ value: VenueStatus, label: string, tag: 'info' | 'success' | 'warning' }> = [
  { value: 'DRAFT', label: '草稿', tag: 'info' },
  { value: 'PUBLISHED', label: '已上线', tag: 'success' },
  { value: 'OFFLINE', label: '已下线', tag: 'warning' },
]

export const SESSION_STATUS_OPTIONS: ReadonlyArray<{ value: VenueSessionStatus, label: string, tag: 'success' | 'warning' | 'info' }> = [
  { value: 'ACTIVE', label: '在售', tag: 'success' },
  { value: 'STOPPED', label: '停售', tag: 'warning' },
  { value: 'CANCELLED', label: '已取消', tag: 'info' },
]

export const DEVICE_CATEGORY_OPTIONS: ReadonlyArray<{ value: DeviceCategory, label: string }> = [
  { value: 'ROBOT', label: '机器人' },
  { value: 'DRONE', label: '无人机' },
  { value: 'PARTS', label: '配件' },
]

export const TRIAL_MODE_OPTIONS: ReadonlyArray<{ value: TrialMode, label: string }> = [
  { value: 'ACCOMPANIED_TRIAL', label: '工作人员陪同试用' },
  { value: 'DISPLAY_ONLY', label: '只展示' },
]

/** 上线前必填项的中文（后端 details.missing 的键）。 */
export const PUBLISH_FIELD_LABELS: Readonly<Record<VenuePublishField, string>> = {
  coverUrl: '封面',
  openingHours: '营业时间',
  projects: '至少一个启用的项目',
  sessions: '至少一场没开始的在售场次',
}

export const SUBJECT_LABELS: Readonly<Record<string, string>> = {
  VENUE: '场馆',
  PROJECT: '项目',
  SESSION: '场次',
  DEVICE: '设备',
}

export const VENUE_ACTION_LABELS: Readonly<Record<string, string>> = {
  ENABLE: '启用',
  DISABLE: '停用',
  CANCEL: '取消',
}

export function venueStatus(status: VenueStatus) {
  return VENUE_STATUS_OPTIONS.find((option) => option.value === status) ?? VENUE_STATUS_OPTIONS[0]!
}

export function sessionStatus(status: VenueSessionStatus) {
  return SESSION_STATUS_OPTIONS.find((option) => option.value === status) ?? SESSION_STATUS_OPTIONS[0]!
}

export function deviceCategoryLabel(category: DeviceCategory): string {
  return DEVICE_CATEGORY_OPTIONS.find((option) => option.value === category)?.label ?? category
}

export function trialModeLabel(mode: TrialMode): string {
  return TRIAL_MODE_OPTIONS.find((option) => option.value === mode)?.label ?? mode
}

export interface OpsVenueSummary {
  venueId: string
  name: string
  cityCode: string
  operatorId: string
  operatorName: string
  status: VenueStatus
  sortOrder: number
  projectCount: number
  upcomingSessionCount: number
  updatedAt: string
  version: number
}

export interface OpsVenueInfoItem {
  label: string
  content: string
}

export interface OpsVenueProject {
  projectId: string
  name: string
  durationMinutes: number
  priceAmountMinor: number
  minAge: number | null
  capacity: number
  limitNote: string | null
  sortOrder: number
  status: VenueItemStatus
  upcomingSessionCount: number
  nextSessionAt: string | null
  version: number
}

export interface OpsVenueDevice {
  deviceId: string
  category: DeviceCategory
  name: string
  summary: string | null
  imageUrl: string | null
  trialMode: TrialMode
  productId: string | null
  sortOrder: number
  status: VenueItemStatus
  version: number
}

export interface OpsVenueDetail {
  venueId: string
  name: string
  operatorId: string
  operatorName: string
  cityCode: string
  cityName: string | null
  coverUrl: string | null
  address: string
  phone: string | null
  opensAt: string | null
  closesAt: string | null
  closedNote: string | null
  openingNote: string | null
  checkinOpensMinutes: number
  lateEntryMinutes: number | null
  sortOrder: number
  status: VenueStatus
  publishedAt: string | null
  infoItems: OpsVenueInfoItem[]
  projects: OpsVenueProject[]
  devices: OpsVenueDevice[]
  publishMissing: VenuePublishField[]
  updatedAt: string
  version: number
}

export interface OpsVenueSession {
  sessionId: string
  projectId: string
  startsAt: string
  endsAt: string
  capacity: number
  bookedCount: number
  activeBookingCount: number
  status: VenueSessionStatus
  version: number
}

export interface OpsVenueSessionsGenerated {
  createdCount: number
  skippedExistingCount: number
  skippedPastCount: number
}

export interface VenueDraft {
  operatorId: string
  name: string
  cityCode: string
  coverUrl: string
  address: string
  phone: string
  opensAt: string
  closesAt: string
  closedNote: string
  checkinOpensMinutes: number
  /** 空表示到场次结束都能入场。 */
  lateEntryMinutes: number | null
  sortOrder: number
  infoItems: OpsVenueInfoItem[]
}

export interface ProjectDraft {
  name: string
  durationMinutes: number
  priceAmountMinor: number
  minAge: number | null
  capacity: number
  limitNote: string
  sortOrder: number
}

export interface DeviceDraft {
  category: DeviceCategory
  name: string
  summary: string
  imageUrl: string
  trialMode: TrialMode
  productId: string
  sortOrder: number
}

export interface SessionTemplate {
  fromDate: string
  toDate: string
  weekdays: Weekday[]
  startTimes: string[]
  /** 空取项目每场人数。 */
  capacity: number | null
}

export interface VenueFilters {
  keyword: string
  cityCode: string
  status: VenueStatus | ''
}

const blank = (value: string): string | null => (value.trim() === '' ? null : value.trim())

/** 表单 → 请求体：空白当作没填（null），信息条目去掉空行。 */
export function venueBody(draft: VenueDraft, version: number | null) {
  return {
    operatorId: draft.operatorId,
    name: draft.name.trim(),
    cityCode: draft.cityCode,
    coverUrl: blank(draft.coverUrl),
    address: draft.address.trim(),
    phone: blank(draft.phone),
    opensAt: blank(draft.opensAt),
    closesAt: blank(draft.closesAt),
    closedNote: blank(draft.closedNote),
    checkinOpensMinutes: draft.checkinOpensMinutes,
    lateEntryMinutes: draft.lateEntryMinutes,
    sortOrder: draft.sortOrder,
    infoItems: draft.infoItems
      .map((item) => ({ label: item.label.trim(), content: item.content.trim() }))
      .filter((item) => item.label !== '' || item.content !== ''),
    version,
  }
}

export function projectBody(draft: ProjectDraft, version: number | null) {
  return {
    name: draft.name.trim(),
    durationMinutes: draft.durationMinutes,
    priceAmountMinor: draft.priceAmountMinor,
    minAge: draft.minAge,
    capacity: draft.capacity,
    limitNote: blank(draft.limitNote),
    sortOrder: draft.sortOrder,
    version,
  }
}

export function deviceBody(draft: DeviceDraft, version: number | null) {
  return {
    category: draft.category,
    name: draft.name.trim(),
    summary: blank(draft.summary),
    imageUrl: blank(draft.imageUrl),
    trialMode: draft.trialMode,
    productId: blank(draft.productId),
    sortOrder: draft.sortOrder,
    version,
  }
}

export function createVenueApi(http: ApiClient) {
  const base = '/api/v1/ops/venues'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    list(filters: VenueFilters, page: number, size: number): Promise<Page<OpsVenueSummary>> {
      return http.get<Page<OpsVenueSummary>>(base, {
        keyword: filters.keyword.trim(),
        cityCode: filters.cityCode,
        status: filters.status,
        page,
        size,
      })
    },
    get(venueId: string): Promise<OpsVenueDetail> {
      return http.get<OpsVenueDetail>(one(venueId))
    },
    create(draft: VenueDraft, idempotencyKey: string = newIdempotencyKey()): Promise<OpsVenueDetail> {
      return http.post<OpsVenueDetail>(base, venueBody(draft, null), { idempotencyKey })
    },
    update(venueId: string, draft: VenueDraft, version: number): Promise<OpsVenueDetail> {
      return http.put<OpsVenueDetail>(one(venueId), venueBody(draft, version))
    },
    publish(venueId: string, version: number, note: string): Promise<OpsVenueDetail> {
      return http.post<OpsVenueDetail>(`${one(venueId)}/publish`, { version, note: blank(note) })
    },
    offline(venueId: string, version: number, note: string): Promise<OpsVenueDetail> {
      return http.post<OpsVenueDetail>(`${one(venueId)}/offline`, { version, note: blank(note) })
    },
    changes(venueId: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${one(venueId)}/changes`)
    },
    createProject(venueId: string, draft: ProjectDraft, idempotencyKey: string = newIdempotencyKey()):
    Promise<OpsVenueDetail> {
      return http.post<OpsVenueDetail>(`${one(venueId)}/projects`, projectBody(draft, null), { idempotencyKey })
    },
    updateProject(venueId: string, projectId: string, draft: ProjectDraft, version: number): Promise<OpsVenueDetail> {
      return http.put<OpsVenueDetail>(`${one(venueId)}/projects/${encodeURIComponent(projectId)}`,
        projectBody(draft, version))
    },
    projectStatus(venueId: string, projectId: string, status: VenueItemStatus, version: number):
    Promise<OpsVenueDetail> {
      return http.put<OpsVenueDetail>(`${one(venueId)}/projects/${encodeURIComponent(projectId)}/status`,
        { status, version })
    },
    sessions(venueId: string, projectId: string, from: string, to: string): Promise<OpsVenueSession[]> {
      return http.get<OpsVenueSession[]>(`${one(venueId)}/projects/${encodeURIComponent(projectId)}/sessions`,
        { from, to })
    },
    generateSessions(venueId: string, projectId: string, template: SessionTemplate):
    Promise<OpsVenueSessionsGenerated> {
      return http.post<OpsVenueSessionsGenerated>(
        `${one(venueId)}/projects/${encodeURIComponent(projectId)}/sessions/generate`, template)
    },
    changeSession(venueId: string, sessionId: string, action: 'stop-sale' | 'resume-sale' | 'cancel', version: number):
    Promise<OpsVenueSession> {
      return http.post<OpsVenueSession>(`${one(venueId)}/sessions/${encodeURIComponent(sessionId)}/${action}`,
        { version })
    },
    createDevice(venueId: string, draft: DeviceDraft, idempotencyKey: string = newIdempotencyKey()):
    Promise<OpsVenueDetail> {
      return http.post<OpsVenueDetail>(`${one(venueId)}/devices`, deviceBody(draft, null), { idempotencyKey })
    },
    updateDevice(venueId: string, deviceId: string, draft: DeviceDraft, version: number): Promise<OpsVenueDetail> {
      return http.put<OpsVenueDetail>(`${one(venueId)}/devices/${encodeURIComponent(deviceId)}`,
        deviceBody(draft, version))
    },
    deviceStatus(venueId: string, deviceId: string, status: VenueItemStatus, version: number):
    Promise<OpsVenueDetail> {
      return http.put<OpsVenueDetail>(`${one(venueId)}/devices/${encodeURIComponent(deviceId)}/status`,
        { status, version })
    },
  }
}

export type VenueApi = ReturnType<typeof createVenueApi>
