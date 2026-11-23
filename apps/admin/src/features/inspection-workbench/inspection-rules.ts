import { charCount, yuanToMinor } from '@/core/ui/format'

import type {
  DeliveryFileType,
  ReservationStage,
  ReservationStatus,
  ServiceOrder,
  ServiceVisit,
} from './inspection-workbench-api'

// 服务商巡检工作台的页面规则。都和后端服务层的校验一致（PlanApplicationService、OrderApplicationService、
// DeliveryApplicationService）；后端仍会再校验一次，这里只决定按钮能不能点、提交前提示哪里不对。

export type VisitAction = 'SCHEDULE' | 'SERVICE_STARTED' | 'SERVICE_PROGRESS' | 'SERVICE_COMPLETED' | 'DELIVER'

export const VISIT_ACTION_LABELS: Record<VisitAction, string> = {
  SCHEDULE: '排期',
  SERVICE_STARTED: '开始巡检',
  SERVICE_PROGRESS: '记一条进度',
  SERVICE_COMPLETED: '现场完成',
  DELIVER: '上传交付并发布',
}

export function visitServiceCompleted(visit: Pick<ServiceVisit, 'status' | 'progress'>): boolean {
  return visit.status === 'DELIVERED' || visit.status === 'ACCEPTED'
    || visit.progress.some((event) => event.eventCode === 'SERVICE_COMPLETED')
}

export function hasTeamAndDevice(order: Pick<ServiceOrder, 'assignments'>): boolean {
  return order.assignments.some((item) => item.type === 'TEAM_MEMBER')
    && order.assignments.some((item) => item.type === 'DEVICE')
}

export interface VisitActionState {
  action: VisitAction
  enabled: boolean
  /** 不能点时说明原因。 */
  reason: string | null
}

/**
 * 某一次巡检现在能做的服务商动作（照 infra/scripts/dev-advance-reservation.cjs 的 pendingActions，
 * 加上后端的前置条件）：
 * - 待排期：排期；已排期：可以改排期，安排好人员和设备、上一次现场完成后能开始；
 * - 巡检中、现场没完成：记进度、现场完成；现场完成后：上传交付并发布；
 * - 已交付、已验收：没有动作（等客户验收 / 已结束）。
 */
export function visitActions(order: Pick<ServiceOrder, 'assignments' | 'visits'>, visit: ServiceVisit):
VisitActionState[] {
  const ok = (action: VisitAction): VisitActionState => ({ action, enabled: true, reason: null })
  const blocked = (action: VisitAction, reason: string): VisitActionState => ({ action, enabled: false, reason })
  switch (visit.status) {
    case 'PENDING_SCHEDULE':
      return [ok('SCHEDULE'), blocked('SERVICE_STARTED', '先排期')]
    case 'SCHEDULED': {
      const previous = order.visits.find((other) => other.visitNo === visit.visitNo - 1)
      const start = !hasTeamAndDevice(order)
        ? blocked('SERVICE_STARTED', '先安排人员和设备（各至少一项）')
        : previous && !visitServiceCompleted(previous)
          ? blocked('SERVICE_STARTED', `第 ${previous.visitNo} 次巡检现场还没完成`)
          : ok('SERVICE_STARTED')
      return [ok('SCHEDULE'), start]
    }
    case 'IN_PROGRESS':
      return visitServiceCompleted(visit)
        ? [ok('DELIVER')]
        : [ok('SERVICE_PROGRESS'), ok('SERVICE_COMPLETED'), blocked('DELIVER', '现场完成后才能发布交付')]
    default:
      return []
  }
}

export interface AssignmentDraft {
  type: 'TEAM_MEMBER' | 'DEVICE'
  displayName: string
  roleLabel: string
  contactMobile: string
}

/** 团队和设备安排的检查（后端 validAssignments）：1–30 项，至少一名人员和一台设备，手机号选填。 */
export function arrangementProblem(list: readonly AssignmentDraft[]): string | null {
  if (list.length === 0 || list.length > 30) return '要有 1–30 项'
  if (!list.some((row) => row.type === 'TEAM_MEMBER')) return '至少安排一名人员或一个班组'
  if (!list.some((row) => row.type === 'DEVICE')) return '至少安排一台设备'
  for (const [index, row] of list.entries()) {
    if (!row.displayName.trim() || charCount(row.displayName.trim()) > 120) return `第 ${index + 1} 项要填名称（≤120 字）`
    if (charCount(row.roleLabel.trim()) > 80) return `第 ${index + 1} 项的角色最多 80 个字`
    if (row.contactMobile.trim() && !/^1[3-9]\d{9}$/.test(row.contactMobile.trim())) {
      return `第 ${index + 1} 项的手机号格式不对`
    }
  }
  return null
}

/** 服务单还没全部验收时都能改团队和设备安排。 */
export function canArrange(order: Pick<ServiceOrder, 'status'>): boolean {
  return order.status !== 'ACCEPTED'
}

/** 能不能出方案：预约没取消，客户还没确认（待出方案，或已出方案、客户没确认时再发一版）。 */
export function canPublishPlan(stage: ReservationStage | null, status: ReservationStatus): boolean {
  if (status === 'CANCELLED') return false
  return stage === null ? status === 'SUBMITTED' : stage === 'AWAITING_PLAN' || stage === 'AWAITING_CONFIRMATION'
}

export interface PlanItemDraft {
  code: string
  name: string
  quantity: number
  unit: string
  /** 单价（元，输入框里的文字）。 */
  unitPrice: string
}

/** 方案合计（分）：每行数量 × 单价相加；有一行单价填得不对返回 null。 */
export function planTotalMinor(items: readonly PlanItemDraft[]): number | null {
  let total = 0
  for (const item of items) {
    const unit = yuanToMinor(item.unitPrice)
    if (unit === null || !Number.isInteger(item.quantity) || item.quantity < 1) return null
    total += unit * item.quantity
  }
  return total
}

/**
 * 巡检日期：1–52 个，严格升序，不早于今天（today 是 yyyy-MM-dd）。返回第一条问题，没有问题返回 null。
 */
export function visitDatesError(dates: readonly string[], today: string): string | null {
  if (dates.length === 0) return '至少安排一次巡检'
  if (dates.length > 52) return '最多安排 52 次巡检'
  let previous = ''
  for (const [index, date] of dates.entries()) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return `第 ${index + 1} 次巡检没选日期`
    if (date < today) return `第 ${index + 1} 次巡检的日期早于今天`
    if (previous && date <= previous) return `第 ${index + 1} 次巡检要晚于第 ${index} 次（日期严格往后排）`
    previous = date
  }
  return null
}

/** 北京时间的今天（yyyy-MM-dd）。 */
export function beijingToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(now)
}

/** 交付文件名的扩展名要和类型对上（后端按扩展名和 Content-Type 核对）。 */
export function fileMatchesType(fileName: string, extension: string): boolean {
  return fileName.toLowerCase().endsWith(`.${extension}`)
}

/** 发布交付的指标里，指标、塔数、巡检长度至少有一项（后端 validatePublish）。 */
export function deliveryMeasuresError(input: {
  towerCount: number | null
  inspectedLengthMeters: number | null
  metricCount: number
}): string | null {
  if (input.metricCount === 0 && input.towerCount === null && input.inspectedLengthMeters === null) {
    return '巡检长度、塔数和结果指标至少填一项'
  }
  return null
}

/** 默认的交付物（四类文件各一份，和开发脚本一致）。 */
export const DEFAULT_DELIVERABLES: ReadonlyArray<{ code: string, name: string, fileType: DeliveryFileType }> = [
  { code: 'HD_MEDIA', name: '高清影像', fileType: 'IMAGE_ARCHIVE' },
  { code: 'DEFECT_LIST', name: '缺陷清单', fileType: 'DEFECT_LIST' },
  { code: 'POINT_MARKS', name: '点位标注', fileType: 'POINT_KML' },
  { code: 'REPORT', name: '巡检报告', fileType: 'INSPECTION_REPORT' },
]

export const SERVICE_METHOD_OPTIONS: ReadonlyArray<{ code: string, label: string }> = [
  { code: 'DRONE', label: '无人机巡检' },
  { code: 'ROBOT_DOG', label: '机器狗巡检' },
  { code: 'THERMAL', label: '红外热成像' },
  { code: 'MANUAL_REVIEW', label: '人工复核' },
]
