import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import type { Page } from '@/core/ui/use-paged-list'

// 服务商巡检工作台（契约 contracts/openapi-inspection-service-flow.yaml 2.3.0）。
// 只用现有接口：本机构预约列表（2.3.0 新加的 listProviderInspectionReservations）、预约详情和服务单（服务商能读已分配的）、
// 出方案、安排团队设备、排期、履约进度、交付文件上传（三步）和发布交付。方案详情和交付详情只有客户和平台能读，这里不读。

export type ReservationStage =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'
  | 'CANCELLED'
export type ReservationFilter = 'ALL' | Exclude<ReservationStage, 'CANCELLED'>
export type ReservationStatus = 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
// 2.5.0（S4-1）：AWAITING_PAYMENT 客户还没付首付，CANCELLED 第 1 次巡检开始前客户取消了；REWORK 客户退回整改。
export type ServiceOrderStatus = 'AWAITING_PAYMENT' | 'ARRANGING' | 'IN_PROGRESS' | 'DELIVERED' | 'ACCEPTED' | 'CANCELLED'
export type ServiceVisitStatus = 'PENDING_SCHEDULE' | 'SCHEDULED' | 'IN_PROGRESS' | 'REWORK' | 'DELIVERED' | 'ACCEPTED'
export type AssignmentType = 'TEAM_MEMBER' | 'DEVICE'
export type ProgressEventCode = 'SERVICE_STARTED' | 'SERVICE_PROGRESS' | 'SERVICE_COMPLETED'
export type DeliveryFileType = 'IMAGE_ARCHIVE' | 'DEFECT_LIST' | 'POINT_KML' | 'INSPECTION_REPORT'
export type IssueGroupBy = 'SEVERITY' | 'CATEGORY'
export type PaymentNodeType = 'DOWN_PAYMENT' | 'PER_VISIT'
export type TimeSlotCode = 'MORNING' | 'AFTERNOON' | 'EVENING'

export const STAGE_TABS: ReadonlyArray<{ value: ReservationFilter, label: string }> = [
  { value: 'AWAITING_PLAN', label: '待出方案' },
  { value: 'AWAITING_CONFIRMATION', label: '待客户确认' },
  { value: 'IN_PROGRESS', label: '履约中' },
  { value: 'AWAITING_ACCEPTANCE', label: '待验收' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'ALL', label: '全部' },
]

export const STAGE_LABELS: Record<ReservationStage, string> = {
  AWAITING_PLAN: '待出方案',
  AWAITING_CONFIRMATION: '待客户确认',
  IN_PROGRESS: '履约中',
  AWAITING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

export const ORDER_STATUS_LABELS: Record<ServiceOrderStatus, string> = {
  AWAITING_PAYMENT: '待客户付首付',
  CANCELLED: '客户已取消',
  ARRANGING: '安排中',
  IN_PROGRESS: '巡检中',
  DELIVERED: '已全部交付，待验收',
  ACCEPTED: '已全部验收',
}

export const VISIT_STATUS_LABELS: Record<ServiceVisitStatus, string> = {
  PENDING_SCHEDULE: '待排期',
  SCHEDULED: '已排期',
  IN_PROGRESS: '巡检中',
  REWORK: '客户退回整改，待重新交付',
  DELIVERED: '已交付，待客户验收',
  ACCEPTED: '客户已验收',
}

export const TIME_SLOT_LABELS: Record<TimeSlotCode, string> = {
  MORNING: '上午',
  AFTERNOON: '下午',
  EVENING: '晚上',
}

export const PROGRESS_LABELS: Record<string, string> = {
  ARRANGEMENT_READY: '团队与设备已安排',
  SERVICE_STARTED: '已开始巡检',
  SERVICE_PROGRESS: '巡检进度',
  SERVICE_COMPLETED: '现场巡检完成',
}

export const ASSIGNMENT_TYPE_LABELS: Record<AssignmentType, string> = {
  TEAM_MEMBER: '人员 / 班组',
  DEVICE: '设备',
}

export const PAYMENT_NODE_LABELS: Record<PaymentNodeType, string> = {
  DOWN_PAYMENT: '首付款',
  PER_VISIT: '按次付款',
}

/** 交付文件的四种类型：扩展名和 Content-Type 必须和后端 DeliveryFileType 完全一致，否则上传意图会被拒。 */
export const DELIVERY_FILE_TYPES: ReadonlyArray<{
  type: DeliveryFileType
  label: string
  extension: string
  contentType: string
}> = [
  { type: 'IMAGE_ARCHIVE', label: '巡检影像（.zip）', extension: 'zip', contentType: 'application/zip' },
  {
    type: 'DEFECT_LIST',
    label: '缺陷清单（.xlsx）',
    extension: 'xlsx',
    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  },
  { type: 'POINT_KML', label: '点位标注（.kml）', extension: 'kml', contentType: 'application/vnd.google-earth.kml+xml' },
  { type: 'INSPECTION_REPORT', label: '巡检报告（.pdf）', extension: 'pdf', contentType: 'application/pdf' },
]

export function deliveryFileTypeLabel(type: DeliveryFileType): string {
  return DELIVERY_FILE_TYPES.find((item) => item.type === type)?.label ?? type
}

export interface CodeLabel {
  code: string
  label: string
}

export interface ReservationSummary {
  reservationId: string
  reservationNo: string
  serviceId: string
  serviceTitle: string
  serviceCoverUrl?: string
  cityCode: string
  locationSummary: string
  preferredServiceDate: string
  submittedAt: string
  stage: ReservationStage
  reservationStatus: ReservationStatus
  currentPlanId?: string
  serviceOrderId?: string
  serviceOrderStatus?: ServiceOrderStatus
  quoteAmountMinor?: number
  currency: 'CNY'
  visitCount?: number
  acceptedVisitCount?: number
  nextVisitDate?: string
  pendingAcceptanceVisitId?: string
  pendingAcceptanceVisitNo?: number
  // 2.5.0（S4-1）：现在该客户付的第一个付款节点（来自服务单的付款节点）；没有要付的时不出现。
  paymentDueNodeNo?: number
  paymentDueNodeType?: PaymentNodeType
  paymentDueVisitNo?: number
  paymentDueAmountMinor?: number
}

export interface ReservationPage extends Page<ReservationSummary> {
  stageCounts: Record<Exclude<ReservationStage, 'CANCELLED'>, number>
}

export interface FileObject {
  id: string
  originalName: string
  contentType: string
  sizeBytes: number
  status: string
  scanFailureReason?: string | null
}

export interface Reservation {
  id: string
  reservationNo: string
  customerId: string
  providerId: string | null
  serviceId: string
  serviceTitle?: string
  serviceCoverUrl?: string | null
  cityCode: string
  status: ReservationStatus
  location: { address: string, longitude?: number | null, latitude?: number | null }
  lineLengthMeters?: number | null
  preferredServiceDate: string
  preferredTimeSlotCode: TimeSlotCode
  inspectionItems: CodeLabel[]
  note?: string | null
  contact: { name: string, mobile: string }
  attachments: FileObject[]
  submittedAt: string
  currentPlanId?: string | null
  version: number
}

export interface ProgressEvent {
  id: string
  eventCode: string
  occurredAt: string
  message: string
}

export interface OrderAssignment {
  type: AssignmentType
  resourceId: string
  displayName: string
  roleLabel: string | null
  contactMobileMasked: string | null
  /** 契约 2.6.0：服务团队成员是否持证飞手（App 履约页“持证飞手 N 名”）；老服务端不返回，按否。 */
  certifiedPilot?: boolean
}

export interface ServiceVisit {
  id: string
  serviceOrderId: string
  visitNo: number
  plannedDate: string
  plannedStartAt?: string | null
  status: ServiceVisitStatus
  progress: ProgressEvent[]
  startedAt?: string | null
  deliveredAt?: string | null
  acceptedAt?: string | null
  version: number
}

export interface OrderPaymentNode {
  nodeNo: number
  nodeType: PaymentNodeType
  visitNo: number | null
  amountMinor: number
  payable: boolean
  paidAt?: string | null
}

export interface ServiceOrder {
  id: string
  serviceOrderNo: string
  reservationId: string
  planId: string
  customerId: string
  providerId: string
  status: ServiceOrderStatus
  paymentMode: 'NONE' | 'NODES'
  paymentNodes?: OrderPaymentNode[]
  provider?: { name?: string } | null
  customerParty?: { organizationName?: string | null, operatorName: string } | null
  serviceSnapshot: {
    serviceId: string
    serviceTitle: string
    cityCode: string
    location: { address: string }
    lineLengthMeters?: number | null
    serviceDate: string
    inspectionItems: CodeLabel[]
  }
  assignments: OrderAssignment[]
  progress: ProgressEvent[]
  visits: ServiceVisit[]
  createdAt: string
  deliveredAt?: string | null
  acceptedAt?: string | null
  version: number
}

export interface PlanItemInput {
  code: string
  name: string
  quantity: number
  unit: string
  unitPriceMinor: number
}

export interface DeliverableInput {
  code: string
  name: string
  fileType: DeliveryFileType | null
}

export interface PublishPlanRequest {
  providerId: string
  validUntil: string
  visits: Array<{ plannedDate: string }>
  summary: string | null
  scopeSummary: string | null
  serviceMethods: CodeLabel[]
  deliveryTerms: { reportDueHours: number | null, mediaRetentionDays: number | null } | null
  changeNote: string | null
  planItems: PlanItemInput[]
  deliverables: DeliverableInput[]
  currency: 'CNY'
}

export interface PublishedPlan {
  id: string
  planNo: string
  planVersion: number
  totalAmountMinor: number
}

export interface AssignmentInput {
  type: AssignmentType
  resourceId: string
  displayName: string
  roleLabel: string | null
  contactMobile: string | null
  /** 契约 2.6.0：只有人员能勾，设备一律 false（后端设备为 true 回 400）。 */
  certifiedPilot: boolean
}

export interface DeliveryMetricInput {
  code: string
  label: string
  value: number
  unit: string
}

export interface IssueSummaryInput {
  groupCode: string
  groupName: string
  count: number
}

export interface DeliveryFileInput {
  fileId: string
  type: DeliveryFileType
  displayName: string
  pointCount: number | null
  itemCount: number | null
}

export interface PublishDeliveryRequest {
  towerCount: number | null
  inspectedLengthMeters: number | null
  metrics: DeliveryMetricInput[]
  issueCount: number
  issueGroupBy: IssueGroupBy
  issueSummary: IssueSummaryInput[]
  files: DeliveryFileInput[]
}

export interface UploadIntent {
  fileId: string
  method: 'PUT'
  uploadUrl: string
  requiredHeaders: Record<string, string>
  expiresAt: string
}

/** 要上传的一份交付文件：idempotencyKey 在选文件时生成，重试同一份文件沿用，后端只建一个文件对象。 */
export interface PendingDeliveryFile {
  type: DeliveryFileType
  file: Blob
  originalName: string
  idempotencyKey: string
  completeKey: string
}

registerErrorMessages({
  INS_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
  INS_VERSION_CONFLICT: '这一单刚被更新过（客户确认了方案或别的同事操作过），请刷新后再试',
  INS_RESERVATION_STATE_INVALID: '预约已取消或状态不对，不能出方案',
  INS_PLAN_ALREADY_CONFIRMED: '客户已经确认了方案，不能再改',
  INS_PLAN_NOT_PUBLISHED: '方案还没有发布',
  INS_PLAN_EXPIRED: '方案已过有效期',
  INS_ORDER_STATE_INVALID: '服务单已全部验收，不能再改安排',
  INS_VISIT_STATE_INVALID: '这一次巡检现在不能做这个操作（先排期、安排团队设备，或已经交付），请刷新后再看',
  INS_VISIT_OUT_OF_ORDER: '上一次巡检还没完成，不能开始这一次',
  INS_DELIVERY_STATE_INVALID: '这一次巡检还没完成现场作业，或已经发布过交付',
  INS_DELIVERY_FILES_INVALID: '交付文件不全或不符合方案约定（每种约定的类型各一份，文件名不能重复），请检查后重试',
  INS_DELIVERY_FILE_NOT_READY: '有交付文件还没通过校验，请重新上传',
  INS_DELIVERY_ALREADY_ACCEPTED: '客户已经验收',
  INS_FILE_TYPE_UNSUPPORTED: '文件类型和所选的交付类型不符（扩展名要对应）',
  INS_FILE_TOO_LARGE: '文件太大（单个不超过 1 GB）',
  INS_FILE_COUNT_EXCEEDED: '这一次巡检的交付文件已到上限（20 份）',
  INS_FILE_SCAN_FAILED: '文件没通过校验，请换一份重新上传',
  INS_STORAGE_UNAVAILABLE: '文件存储暂不可用，请稍后再试',
})

const ROOT = '/api/v1'

function visitPath(serviceOrderId: string, visitId: string): string {
  return `${ROOT}/provider/inspection/service-orders/${encodeURIComponent(serviceOrderId)}/visits/${encodeURIComponent(visitId)}`
}

/** 浏览器里算 SHA-256（十六进制小写），上传意图和完成上传都要带。 */
export async function sha256Hex(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export interface InspectionWorkbenchDeps {
  /** 直传对象存储用的 fetch（不带登录令牌，只带上传意图要求的请求头）。 */
  fetchImpl?: typeof fetch
  hash?: (file: Blob) => Promise<string>
}

export function createInspectionWorkbenchApi(http: ApiClient, deps: InspectionWorkbenchDeps = {}) {
  const fetchImpl = deps.fetchImpl ?? ((input, init) => fetch(input, init))
  const hash = deps.hash ?? sha256Hex

  return {
    list(stage: ReservationFilter, page: number, size: number): Promise<ReservationPage> {
      return http.get<ReservationPage>(`${ROOT}/provider/inspection/reservations`, { stage, page, size })
    },
    /**
     * 本机构的一张预约的列表摘要（阶段、报价合计、服务单 ID）：GET /provider/inspection/reservations/{id}
     * （契约 2.3.0 getProviderInspectionReservationSummary）。不是本机构的回 404。
     */
    summary(reservationId: string): Promise<ReservationSummary> {
      return http.get<ReservationSummary>(
        `${ROOT}/provider/inspection/reservations/${encodeURIComponent(reservationId)}`)
    },
    reservation(reservationId: string): Promise<Reservation> {
      return http.get<Reservation>(`${ROOT}/inspection/reservations/${encodeURIComponent(reservationId)}`)
    },
    serviceOrder(serviceOrderId: string): Promise<ServiceOrder> {
      return http.get<ServiceOrder>(`${ROOT}/inspection/service-orders/${encodeURIComponent(serviceOrderId)}`)
    },
    publishPlan(reservation: Pick<Reservation, 'id' | 'version'>, body: PublishPlanRequest, key: string):
    Promise<PublishedPlan> {
      return http.post<PublishedPlan>(`${ROOT}/provider/inspection/reservations/${encodeURIComponent(reservation.id)}/plans`,
        body, { idempotencyKey: key, ifMatch: reservation.version })
    },
    arrange(order: Pick<ServiceOrder, 'id' | 'version'>, assignments: AssignmentInput[], key: string):
    Promise<ServiceOrder> {
      return http.put<ServiceOrder>(`${ROOT}/provider/inspection/service-orders/${encodeURIComponent(order.id)}/arrangement`,
        { assignments }, { idempotencyKey: key, ifMatch: order.version })
    },
    schedule(visit: Pick<ServiceVisit, 'id' | 'serviceOrderId' | 'version'>, plannedStartAt: string, key: string):
    Promise<ServiceVisit> {
      return http.put<ServiceVisit>(`${visitPath(visit.serviceOrderId, visit.id)}/schedule`, { plannedStartAt },
        { idempotencyKey: key, ifMatch: visit.version })
    },
    progress(visit: Pick<ServiceVisit, 'id' | 'serviceOrderId' | 'version'>, eventCode: ProgressEventCode,
      message: string, occurredAt: string, key: string): Promise<ServiceVisit> {
      return http.post<ServiceVisit>(`${visitPath(visit.serviceOrderId, visit.id)}/progress-events`,
        { eventCode, occurredAt, message: message.trim() }, { idempotencyKey: key, ifMatch: visit.version })
    },
    /**
     * 上传一份交付文件：① 建上传意图 → ② 按意图直接 PUT 到对象存储 → ③ 告诉后端传好了（后端去对象存储核对大小和摘要）。
     * 返回文件 ID。后端核对不过（REJECTED）时抛错。只有“巡检中”的那一次能传。
     */
    async uploadDeliveryFile(visitId: string, pending: PendingDeliveryFile): Promise<string> {
      const type = DELIVERY_FILE_TYPES.find((item) => item.type === pending.type)
      if (!type) throw new Error('不认识的交付文件类型')
      const sha256 = await hash(pending.file)
      const intent = await http.post<UploadIntent>(`${ROOT}/inspection/file-objects/upload-intents`, {
        purpose: 'DELIVERY_ARTIFACT',
        ownerId: visitId,
        deliveryFileType: pending.type,
        originalName: pending.originalName,
        contentType: type.contentType,
        sizeBytes: pending.file.size,
        sha256,
      }, { idempotencyKey: pending.idempotencyKey })
      let uploaded: Response
      try {
        uploaded = await fetchImpl(intent.uploadUrl, {
          method: intent.method,
          headers: intent.requiredHeaders,
          body: pending.file,
        })
      } catch {
        throw new Error(`${pending.originalName} 上传失败：连不上文件存储（检查网络，或存储没有放行运营端的跨域上传）`)
      }
      if (!uploaded.ok) throw new Error(`${pending.originalName} 上传失败（文件存储返回 ${uploaded.status}）`)
      // 跨域时浏览器读得到 ETag 要存储在 Access-Control-Expose-Headers 里放出来；读不到时用文件 ID 占位，
      // 后端不拿 etag 比对内容（内容按大小和 SHA-256 在对象存储上核对）。
      const etag = uploaded.headers.get('ETag')?.trim() || `admin-upload-${intent.fileId}`
      const file = await http.post<FileObject>(
        `${ROOT}/inspection/file-objects/${encodeURIComponent(intent.fileId)}/complete`,
        { etag, sizeBytes: pending.file.size, sha256 },
        { idempotencyKey: pending.completeKey },
      )
      if (file.status !== 'READY') {
        throw new Error(`${pending.originalName} 没通过校验${file.scanFailureReason ? `：${file.scanFailureReason}` : ''}，请换一份重新上传`)
      }
      return intent.fileId
    },
    publishDelivery(visit: Pick<ServiceVisit, 'id' | 'serviceOrderId' | 'version'>, body: PublishDeliveryRequest,
      key: string): Promise<unknown> {
      return http.post<unknown>(`${visitPath(visit.serviceOrderId, visit.id)}/delivery`, body,
        { idempotencyKey: key, ifMatch: visit.version })
    },
  }
}

export type InspectionWorkbenchApi = ReturnType<typeof createInspectionWorkbenchApi>
