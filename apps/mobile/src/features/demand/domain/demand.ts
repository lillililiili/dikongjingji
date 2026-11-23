// Mirrors contracts/openapi-demand.yaml 1.3.0 (1.0.0 was step F1, docs/redesign/03-App实现任务计划.md §22;
// 1.1.0 adds qualificationReviewed to providers, R6-4; 1.3.0 adds the quote lines and comparison
// items, the service order a choice makes and the list counts, S4-3 and S4-4, §28).
// The server is the source of truth: status, deadlines, what may be cancelled or chosen all come
// from it; the page only draws them.

export type DemandType = 'INSPECTION' | 'AERIAL' | 'SURVEY' | 'MAINTENANCE' | 'TECH_SUPPORT'

export type DemandDeliverableCode =
  | 'INSPECTION_REPORT'
  | 'ORTHOPHOTO'
  | 'HAZARD_LIST'
  | 'MODEL_3D'
  | 'VIDEO_FOOTAGE'
  | 'PHOTOS'
  | 'PANORAMA'
  | 'EDITED_FILM'
  | 'POINT_CLOUD'
  | 'TOPOGRAPHIC_MAP'
  | 'SURVEY_REPORT'
  | 'REPAIR_RECORD'
  | 'TEST_REPORT'
  | 'PARTS_LIST'
  | 'MAINTENANCE_ADVICE'
  | 'SERVICE_RECORD'
  | 'ISSUE_LIST'
  | 'OPERATION_TRAINING'
  | 'SOLUTION_ADVICE'

export type DemandStatus = 'DRAFT' | 'OPEN' | 'QUOTED' | 'CHOSEN' | 'CLOSED'
export type DemandCloseReason = 'NO_QUOTES' | 'CANCELLED'
export type DemandQuoteScope = 'MATCH' | 'INVITE'
export type DemandPublisherKind = 'ORGANIZATION' | 'PERSONAL'
export type DemandQuoteStatus = 'ACTIVE' | 'EXPIRED' | 'WITHDRAWN' | 'CHOSEN' | 'NOT_CHOSEN' | 'CLOSED'
export type DemandQuoteSort = 'ALL' | 'PRICE' | 'DURATION'

export const DEMAND_TYPES: readonly DemandType[] = ['INSPECTION', 'AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT']
export const DEMAND_QUOTE_SORTS: readonly DemandQuoteSort[] = ['ALL', 'PRICE', 'DURATION']
export const MAX_INVITED_PROVIDERS = 3
export const DEMAND_PAGE_SIZE = 20

export const DEMAND_DELIVERABLE_LABELS: Record<DemandDeliverableCode, string> = {
  INSPECTION_REPORT: '巡检报告',
  ORTHOPHOTO: '正射影像',
  HAZARD_LIST: '隐患清单',
  MODEL_3D: '三维模型',
  VIDEO_FOOTAGE: '视频素材',
  PHOTOS: '照片',
  PANORAMA: '全景图',
  EDITED_FILM: '成片剪辑',
  POINT_CLOUD: '点云数据',
  TOPOGRAPHIC_MAP: '地形图',
  SURVEY_REPORT: '测绘报告',
  REPAIR_RECORD: '维修记录',
  TEST_REPORT: '检测报告',
  PARTS_LIST: '更换件清单',
  MAINTENANCE_ADVICE: '保养建议',
  SERVICE_RECORD: '服务记录',
  ISSUE_LIST: '问题清单',
  OPERATION_TRAINING: '操作培训',
  SOLUTION_ADVICE: '方案建议',
}

export interface DemandTypeInfo {
  type: DemandType
  label: string
  // The line under the name in the type sheet.
  description: string
  // The 作业范围 row: its label and placeholder change with the type (Demand board).
  scopeLabel: string
  scopePlaceholder: string
  // In the order the page shows them (contract DemandType).
  deliverables: readonly DemandDeliverableCode[]
  // What is ticked when the type is picked.
  defaultDeliverables: readonly DemandDeliverableCode[]
}

export const DEMAND_TYPE_INFO: Record<DemandType, DemandTypeInfo> = {
  INSPECTION: {
    type: 'INSPECTION',
    label: '巡检',
    description: '园区综合、电力、光伏、油气等场景',
    scopeLabel: '作业范围',
    scopePlaceholder: '面积或线路长度',
    deliverables: ['INSPECTION_REPORT', 'ORTHOPHOTO', 'HAZARD_LIST', 'MODEL_3D', 'VIDEO_FOOTAGE'],
    defaultDeliverables: ['INSPECTION_REPORT', 'ORTHOPHOTO', 'HAZARD_LIST'],
  },
  AERIAL: {
    type: 'AERIAL',
    label: '航拍',
    description: '工程记录、宣传素材、活动拍摄',
    scopeLabel: '作业范围',
    scopePlaceholder: '拍摄区域或点位',
    deliverables: ['VIDEO_FOOTAGE', 'PHOTOS', 'PANORAMA', 'EDITED_FILM'],
    defaultDeliverables: ['VIDEO_FOOTAGE', 'PHOTOS'],
  },
  SURVEY: {
    type: 'SURVEY',
    label: '测绘',
    description: '正射影像、三维模型等成果',
    scopeLabel: '作业范围',
    scopePlaceholder: '面积或线路长度',
    deliverables: ['ORTHOPHOTO', 'MODEL_3D', 'POINT_CLOUD', 'TOPOGRAPHIC_MAP', 'SURVEY_REPORT'],
    defaultDeliverables: ['ORTHOPHOTO', 'MODEL_3D'],
  },
  MAINTENANCE: {
    type: 'MAINTENANCE',
    label: '设备维护',
    description: '无人机、机器人等设备的检修保养',
    scopeLabel: '设备情况',
    scopePlaceholder: '设备型号与数量',
    deliverables: ['REPAIR_RECORD', 'TEST_REPORT', 'PARTS_LIST', 'MAINTENANCE_ADVICE'],
    defaultDeliverables: ['REPAIR_RECORD', 'TEST_REPORT'],
  },
  TECH_SUPPORT: {
    type: 'TECH_SUPPORT',
    label: '技术支持',
    description: '远程或驻场的技术服务',
    scopeLabel: '支持内容',
    scopePlaceholder: '需要支持的事项',
    deliverables: ['SERVICE_RECORD', 'ISSUE_LIST', 'OPERATION_TRAINING', 'SOLUTION_ADVICE'],
    defaultDeliverables: ['SERVICE_RECORD'],
  },
}

export function isDemandType(value: unknown): value is DemandType {
  return typeof value === 'string' && (DEMAND_TYPES as readonly string[]).includes(value)
}

export interface DemandPublisher {
  kind: DemandPublisherKind
  organizationName: string | null
}

export interface DemandDeliverable {
  code: DemandDeliverableCode
  label: string
}

export interface DemandProvider {
  providerId: string
  name: string
  certificationLabel: string | null
  description: string | null
  // The platform reviewed the provider's qualification: shown as 资质已审核 in place of the label.
  qualificationReviewed: boolean
}

export interface DemandChosen {
  quoteId: string
  quoteVersion: number
  providerName: string
  amountMinor: number
  chosenAt: string
}

export interface Demand {
  demandId: string
  status: DemandStatus
  closeReason: DemandCloseReason | null
  type: DemandType
  title: string
  publisher: DemandPublisher
  cityCode: string | null
  cityName: string | null
  address: string | null
  scope: string | null
  // YYYY-MM-DD (Beijing); null means 时间可协商.
  dueDate: string | null
  budgetMinMinor: number | null
  budgetMaxMinor: number | null
  deliverables: DemandDeliverable[]
  note: string | null
  quoteScope: DemandQuoteScope
  invitedProviders: DemandProvider[]
  submittedAt: string | null
  quoteDeadline: string | null
  quotingOpen: boolean
  quoteCount: number
  chosen: DemandChosen | null
  // The service order made when the quote was chosen (aerial, survey, maintenance, tech support);
  // null before a choice, for inspection demands and for choices made before 1.3.0.
  serviceOrderId: string | null
  editable: boolean
  cancellable: boolean
  deletable: boolean
  createdAt: string
  updatedAt: string
  // Same as the ETag; sent back as If-Match when choosing.
  version: number
}

export interface DemandPage {
  total: number
  hasMore: boolean
  items: Demand[]
}

// One line of a quote: "无人机巡检 · 6 次 × ¥1,800 · ¥10,800", or "报告与隐患跟踪 · ¥1,200" when it is
// not counted by the unit (quantity, unit and unit price are null).
export interface DemandQuoteLine {
  name: string
  quantity: number | null
  unit: string | null
  unitAmountMinor: number | null
  amountMinor: number
}

export interface DemandQuote {
  quoteId: string
  provider: { providerId: string; name: string; certificationLabel: string | null; qualificationReviewed: boolean }
  // 方案 v2; sent back as quoteVersion when choosing.
  version: number
  status: DemandQuoteStatus
  amountMinor: number
  method: string
  schedule: string
  durationDays: number
  deliverables: string
  validUntil: string
  submittedAt: string
  revisedAt: string
  choosable: boolean
  // 1.3.0, all optional for the provider (older quotes have none): an empty list or null leaves the
  // line out of the page.
  lines: DemandQuoteLine[]
  team: string | null
  nightWork: string | null
  // Hours after the work until the report.
  reportHours: number | null
  // Days the footage is kept; 0 means not included.
  archiveDays: number | null
  // YYYY-MM-DD (Beijing).
  firstVisitDate: string | null
}

// How many demands of each status the account has (the list's tab numbers); total includes drafts.
export interface DemandCounts {
  total: number
  draft: number
  open: number
  quoted: number
  chosen: number
  closed: number
}

export interface DemandQuoteList {
  demandId: string
  demandStatus: DemandStatus
  // Can be sent as If-Match directly.
  demandVersion: number
  sort: DemandQuoteSort
  items: DemandQuote[]
}

// What the page sends for a new demand or a draft (the contract's Create/UpdateDemandRequest).
export interface DemandDraftInput {
  type: DemandType
  cityCode: string | null
  address: string | null
  scope: string | null
  dueDate: string | null
  budgetMinMinor: number | null
  budgetMaxMinor: number | null
  deliverables: DemandDeliverableCode[]
  note: string | null
  quoteScope: DemandQuoteScope
  invitedProviderIds: string[]
}

// The fields a 422 DEMAND_INCOMPLETE can name (details.field), top to bottom on the page.
export type DemandFormField = 'cityCode' | 'address' | 'scope' | 'dueDate' | 'deliverables' | 'invitedProviderIds'
