import { ApiRequestError } from '@/core/http/api-error'
import { charCount, formatMoney, yuanToMinor } from '@/core/ui/format'

import type {
  DeliveryRequest,
  PlanRequest,
  ServiceOrder,
  ServiceOrderCustomer,
  ServiceOrderDeliveryStatus,
  ServiceOrderEvent,
  ServiceOrderEventType,
  ServiceOrderRefundKind,
  ServiceOrderRefundStatus,
  ServiceOrderStatus,
  ServiceOrderType,
  TerminateRequest,
} from './service-order-api'

// 服务订单工作台的显示和规则（契约 openapi-service-order.yaml 1.0.0，规则 docs/redesign/03-App实现任务计划.md §28）。
// 按钮以服务端给的 actions 为准，这里再按状态核一遍（两边都允许才显示）；服务端仍然会再判断。

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export const STATUS: Record<ServiceOrderStatus, { label: string, tag: TagType }> = {
  AWAITING_PLAN: { label: '待出方案', tag: 'warning' },
  PLAN_PENDING: { label: '待客户确认方案', tag: 'primary' },
  AWAITING_PAYMENT: { label: '待客户付款', tag: 'primary' },
  AWAITING_START: { label: '待开工', tag: 'warning' },
  IN_SERVICE: { label: '服务中', tag: 'warning' },
  DELIVERED: { label: '待客户验收', tag: 'primary' },
  REWORK: { label: '整改中', tag: 'danger' },
  COMPLETED: { label: '已完成', tag: 'success' },
  CANCELLED: { label: '已取消', tag: 'info' },
  TERMINATED: { label: '已终止', tag: 'info' },
}

/** 列表上方的状态页签（最后一个是全部）。 */
export const STATUS_TABS: ReadonlyArray<{ value: ServiceOrderStatus | '', label: string }> = [
  { value: 'AWAITING_PLAN', label: '待出方案' },
  { value: 'PLAN_PENDING', label: '待确认' },
  { value: 'AWAITING_PAYMENT', label: '待付款' },
  { value: 'AWAITING_START', label: '待开工' },
  { value: 'IN_SERVICE', label: '服务中' },
  { value: 'DELIVERED', label: '待验收' },
  { value: 'REWORK', label: '整改中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'TERMINATED', label: '已终止' },
  { value: '', label: '全部' },
]

export const TYPE_LABELS: Record<ServiceOrderType, string> = {
  AERIAL: '航拍',
  SURVEY: '测绘',
  MAINTENANCE: '设备维护',
  TECH_SUPPORT: '技术支持',
}

export const DELIVERY_STATUS: Record<ServiceOrderDeliveryStatus, { label: string, tag: TagType }> = {
  SUBMITTED: { label: '待验收', tag: 'primary' },
  ACCEPTED: { label: '已验收', tag: 'success' },
  RETURNED: { label: '已退回整改', tag: 'danger' },
}

export const REFUND_KIND_LABELS: Record<ServiceOrderRefundKind, string> = {
  CANCEL: '开工前取消，整笔退款',
  TERMINATE: '协商终止退款',
}

export const REFUND_STATUS_LABELS: Record<ServiceOrderRefundStatus, string> = {
  REQUESTED: '已提交，等渠道受理',
  PROCESSING: '退款中',
  SUCCEEDED: '已原路退回',
  CLOSED: '退款已关闭',
  ABNORMAL: '退款异常，请联系平台',
}

const EVENT_LABELS: Record<ServiceOrderEventType, string> = {
  CREATED: '客户选定报价，生成订单',
  PLAN_ISSUED: '出方案',
  PLAN_REVISED: '改方案',
  PLAN_CONFIRMED: '客户确认方案',
  PAID: '客户付款到账',
  STARTED: '标记开工',
  DELIVERED: '提交交付',
  ACCEPTED: '客户确认验收',
  RETURNED: '客户退回整改',
  CANCELLED: '订单取消',
  TERMINATED: '协商终止',
}

/** 订单记录的一行：“改方案（方案 v2）”“提交交付（第 2 次）”。 */
export function eventLabel(event: ServiceOrderEvent): string {
  const label = EVENT_LABELS[event.type] ?? event.type
  if (event.round !== null) return `${label}（第 ${event.round} 次交付）`
  if (event.planVersion !== null) return `${label}（方案 v${event.planVersion}）`
  return label
}

export function customerLabel(customer: ServiceOrderCustomer): string {
  return customer.kind === 'ORGANIZATION' && customer.organizationName
    ? `企业 · ${customer.organizationName}（经办人 ${customer.contactName}）`
    : `个人 · ${customer.contactName}`
}

export function closedByLabel(order: Pick<ServiceOrder, 'closedBy'>): string {
  if (order.closedBy === 'CUSTOMER') return '客户'
  if (order.closedBy === 'PROVIDER') return '本机构'
  return ''
}

// ---- 什么时候能做什么 ----

export type OrderButton = 'issuePlan' | 'revisePlan' | 'start' | 'deliver' | 'cancel' | 'terminate'

/** 每个操作在哪些状态下可以做（和契约一致）。 */
const ALLOWED: Record<'issuePlan' | 'start' | 'deliver' | 'cancel' | 'terminate', readonly ServiceOrderStatus[]> = {
  issuePlan: ['AWAITING_PLAN', 'PLAN_PENDING'],
  start: ['AWAITING_START'],
  deliver: ['IN_SERVICE', 'REWORK'],
  cancel: ['AWAITING_PLAN', 'PLAN_PENDING', 'AWAITING_PAYMENT', 'AWAITING_START'],
  terminate: ['IN_SERVICE', 'DELIVERED', 'REWORK'],
}

function allowed(order: Pick<ServiceOrder, 'status' | 'actions'>, action: keyof typeof ALLOWED): boolean {
  return order.actions[action] && ALLOWED[action].includes(order.status)
}

/** 详情页上要显示的操作按钮（按显示顺序）。出过方案的“出方案”叫“改方案”。 */
export function orderButtons(order: Pick<ServiceOrder, 'status' | 'actions' | 'plan'>): OrderButton[] {
  const buttons: OrderButton[] = []
  if (allowed(order, 'issuePlan')) buttons.push(order.plan ? 'revisePlan' : 'issuePlan')
  if (allowed(order, 'start')) buttons.push('start')
  if (allowed(order, 'deliver')) buttons.push('deliver')
  if (allowed(order, 'cancel')) buttons.push('cancel')
  if (allowed(order, 'terminate')) buttons.push('terminate')
  return buttons
}

/** 上传、删除还没交付的文件：和提交交付同一个时候（服务中、整改中）。 */
export function canEditFiles(order: Pick<ServiceOrder, 'status' | 'actions'>): boolean {
  return allowed(order, 'deliver')
}

/** 412：订单在此期间变了，提示刷新。 */
export function isStale(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 412 || error.code === 'SERVICE_ORDER_VERSION_CONFLICT')
}

// ---- 金额 ----

/** 实付（分）：付了款才有，否则 0。 */
export function paidMinor(order: Pick<ServiceOrder, 'payment'>): number {
  return order.payment.paidAt ? order.payment.amountMinor : 0
}

/** 协商终止最多退多少（分）：实付减去已经发起的退款。 */
export function terminateMaxMinor(order: Pick<ServiceOrder, 'payment'>): number {
  return Math.max(0, paidMinor(order) - order.payment.refundedMinor)
}

// ---- 表单 ----

export const LIMITS = {
  schedule: 60,
  terms: 5,
  term: 60,
  note: 500,
  deliveryNote: 1000,
  deliveryFiles: 10,
  stagedFiles: 10,
  cancelReason: 80,
  terminateReason: 200,
  fileBytes: 20 * 1024 * 1024,
} as const

export type BuildResult<T> = { ok: true, request: T } | { ok: false, message: string }

const CONTROL = /[\u0000-\u001f\u007f]/

function fail<T>(message: string): BuildResult<T> {
  return { ok: false, message }
}

export interface PlanForm {
  schedule: string
  terms: Array<{ label: string }>
  note: string
}

/** 改方案用最新一版预填；第一次出方案服务安排用报价里的排期。 */
export function initialPlanForm(order: Pick<ServiceOrder, 'plan' | 'quote'>): PlanForm {
  if (order.plan) {
    return {
      schedule: order.plan.schedule,
      terms: order.plan.terms.map((label) => ({ label })),
      note: order.plan.note ?? '',
    }
  }
  const schedule = charCount(order.quote.schedule) <= LIMITS.schedule ? order.quote.schedule : ''
  return { schedule, terms: [{ label: '' }], note: '' }
}

export function buildPlanRequest(form: PlanForm): BuildResult<PlanRequest> {
  const schedule = form.schedule.trim()
  if (!schedule) return fail('请填写服务安排')
  if (charCount(schedule) > LIMITS.schedule) return fail(`服务安排最多 ${LIMITS.schedule} 个字`)
  if (CONTROL.test(schedule)) return fail('服务安排里不能有换行或特殊字符')
  const terms = form.terms.map((term) => term.label.trim())
  if (terms.length === 0) return fail('至少写一条交付约定')
  if (terms.length > LIMITS.terms) return fail(`交付约定最多 ${LIMITS.terms} 条`)
  for (const [index, term] of terms.entries()) {
    if (!term) return fail(`交付约定第 ${index + 1} 条是空的`)
    if (charCount(term) > LIMITS.term) return fail(`交付约定每条最多 ${LIMITS.term} 个字`)
    if (CONTROL.test(term)) return fail('交付约定里不能有换行或特殊字符')
  }
  const note = form.note.trim()
  if (charCount(note) > LIMITS.note) return fail(`方案说明最多 ${LIMITS.note} 个字`)
  return { ok: true, request: { schedule, terms, note: note || null } }
}

export function buildDeliveryRequest(note: string, fileIds: readonly string[]): BuildResult<DeliveryRequest> {
  const text = note.trim()
  if (!text) return fail('请写交付说明（做了什么、交付了哪些成果）')
  if (charCount(text) > LIMITS.deliveryNote) return fail(`交付说明最多 ${LIMITS.deliveryNote} 个字`)
  if (fileIds.length > LIMITS.deliveryFiles) return fail(`一次交付最多 ${LIMITS.deliveryFiles} 个文件`)
  return { ok: true, request: { note: text, fileIds: [...fileIds] } }
}

export function cancelReasonError(reason: string): string | null {
  const text = reason.trim()
  if (!text) return '请填写取消原因（客户能看到）'
  if (charCount(text) > LIMITS.cancelReason) return `取消原因最多 ${LIMITS.cancelReason} 个字`
  return null
}

/** 协商终止：退款按元填、按分提交，0 到实付（减去已退）；原因 1–200 字。 */
export function buildTerminateRequest(refundYuan: string, reason: string, maxMinor: number):
BuildResult<TerminateRequest> {
  const amount = yuanToMinor(refundYuan)
  if (amount === null) return fail('请填写退款金额（元，最多两位小数；不退款填 0）')
  if (amount > maxMinor) return fail(`退款金额不能超过实付 ${formatMoney(maxMinor)}`)
  const text = reason.trim()
  if (!text) return fail('请填写终止原因（和客户协商的结果）')
  if (charCount(text) > LIMITS.terminateReason) return fail(`终止原因最多 ${LIMITS.terminateReason} 个字`)
  return { ok: true, request: { refundAmountMinor: amount, reason: text } }
}

// ---- 交付文件 ----

/** 能传的扩展名（后端按文件头再判断）：PDF、JPEG、PNG、ZIP 和基于 ZIP 的 Office 文档、MP4。 */
export const FILE_EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png', 'zip', 'docx', 'xlsx', 'pptx', 'mp4'] as const
export const FILE_ACCEPT = FILE_EXTENSIONS.map((extension) => `.${extension}`).join(',')

/** 上传前在页面上先挡一次；返回错误说明，没问题返回 null。 */
export function fileProblem(file: { name: string, size: number }, stagedCount: number): string | null {
  if (stagedCount >= LIMITS.stagedFiles) return `还没交付的文件最多 ${LIMITS.stagedFiles} 个，先交付或删掉一些`
  if (file.size <= 0) return '文件是空的'
  if (file.size > LIMITS.fileBytes) return '单个文件不能超过 20 MB'
  const extension = file.name.toLowerCase().split('.').pop() ?? ''
  if (!file.name.includes('.') || !(FILE_EXTENSIONS as readonly string[]).includes(extension)) {
    return '只能上传 PDF、JPEG、PNG、ZIP、Office 文档（docx、xlsx、pptx）或 MP4'
  }
  return null
}
