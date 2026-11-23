import type {
  Aftersale,
  AftersaleAction,
  AftersaleImageSlot,
  AftersaleKind,
  AftersaleReason,
  AftersaleStatus,
  AftersaleTab,
  AftersaleUnavailableReason,
} from '../domain/aftersale'
import { AFTERSALE_IMAGE_SLOTS } from '../domain/aftersale'
import { readTradeFailure } from './start-payment'
import { formatTradeAmount, formatTradeTime } from './order-view'

/**
 * 售后单在页面上怎么说（画板 AftersaleDetail、AftersaleFlow、AftersaleList、AftersaleApply）。
 * 每句话只由服务端的状态、时间和金额拼出来；时限只在服务端给了 dueAt 时才写具体时间。
 */

export const AFTERSALE_KIND_LABELS: Record<AftersaleKind, string> = {
  RETURN_REFUND: '退货退款',
  REFUND_ONLY: '仅退款',
}

export const AFTERSALE_REASON_LABELS: Record<AftersaleReason, string> = {
  NO_REASON_7D: '不想要了（未拆封，7 天无理由）',
  NOT_AS_DESCRIBED: '商品与描述不符',
  DAMAGED: '商品有损坏',
  NOT_WANTED: '不想要了',
}

export const AFTERSALE_SLOT_LABELS: Record<AftersaleImageSlot, string> = {
  PRODUCT: '商品照片',
  PACKAGE: '包装照片',
  OTHER: '其他凭证',
}

// 不能选的原因（画板 AftersaleApply 的“订单已发货，不可选”）。
export const AFTERSALE_UNAVAILABLE_LABELS: Record<AftersaleUnavailableReason, string> = {
  ORDER_NOT_ELIGIBLE: '这笔订单不支持在线售后',
  ORDER_NOT_PAID: '订单未付款或已关闭，不可选',
  ORDER_REFUNDED: '订单已在退款中或已退款，不可选',
  ALREADY_SHIPPED: '订单已发货，不可选',
  NOT_SHIPPED: '订单还没发货，不可选（可以仅退款）',
  NO_REASON_NOT_SUPPORTED: '订单里有商品不支持七天无理由',
  WINDOW_PASSED: '已过申请时限',
  OPEN_AFTERSALE: '已有一张售后单在处理中',
  LIMIT_REACHED: '这笔订单已申请过 3 次售后',
  NOTHING_TO_REFUND: '没有可退的金额',
}

export const AFTERSALE_TAB_LABELS: Record<AftersaleTab, string> = {
  DOING: '售后中',
  DONE: '已完成',
  CLOSED: '已关闭',
}

// 列表卡片右上角的状态字（画板 AftersaleList 的 statusColor）。
export type AftersaleTone = 'ORANGE' | 'BLUE' | 'GREEN' | 'RED' | 'GREY'

export const AFTERSALE_STATUS_LABELS: Record<AftersaleStatus, { text: string; tone: AftersaleTone }> = {
  PENDING_REVIEW: { text: '等待商家处理', tone: 'ORANGE' },
  AWAITING_RETURN: { text: '待寄回', tone: 'ORANGE' },
  RETURN_SHIPPED: { text: '待商家收货', tone: 'ORANGE' },
  REFUNDING: { text: '退款中', tone: 'BLUE' },
  REFUNDED: { text: '已退款', tone: 'GREEN' },
  REFUND_FAILED: { text: '退款异常', tone: 'RED' },
  REJECTED: { text: '商家未同意', tone: 'RED' },
  RECEIPT_REJECTED: { text: '验货未通过', tone: 'RED' },
  REVOKED: { text: '已撤销', tone: 'GREY' },
  CLOSED: { text: '已关闭', tone: 'GREY' },
}

// 状态头的颜色（画板 AftersaleFlow：orange / green / red / gray）。
export const AFTERSALE_HEAD_COLORS = {
  ORANGE: '#ba470b',
  GREEN: '#11804a',
  RED: '#d64f67',
  GREY: '#536178',
} as const

export const AFTERSALE_REFUND_ROUTE = '经平台支付渠道原路退回'
const CHANNEL = '经平台支付渠道'

export function aftersaleAmount(aftersale: Aftersale): number {
  return aftersale.refundMinor ?? aftersale.requestedMinor
}

// 处理方：售后责任方，没记时就是商家自己。
export function aftersaleHandler(merchant: { name: string; aftersaleParty: string | null }): string {
  return merchant.aftersaleParty ?? merchant.name
}

function ended(status: AftersaleStatus): boolean {
  return status === 'REJECTED' || status === 'RECEIPT_REJECTED' || status === 'REVOKED' || status === 'CLOSED'
}

export interface AftersaleHead {
  title: string
  sub: string
  color: string
}

export function describeAftersaleHead(aftersale: Aftersale): AftersaleHead {
  const amount = formatTradeAmount(aftersaleAmount(aftersale))
  const due = aftersale.dueAt ? formatTradeTime(aftersale.dueAt) : null
  switch (aftersale.status) {
    case 'PENDING_REVIEW':
      return {
        title: '等待商家处理',
        sub: due ? `商家需在 ${due} 前处理，逾期自动同意` : '处理时限以平台交易协议为准',
        color: AFTERSALE_HEAD_COLORS.ORANGE,
      }
    case 'AWAITING_RETURN':
      return {
        title: '商家已同意，请寄回商品',
        sub: due ? `请在 ${due} 前寄回并填写运单` : '请按约定时间寄回并填写运单',
        color: AFTERSALE_HEAD_COLORS.ORANGE,
      }
    case 'RETURN_SHIPPED':
      return { title: '等待商家收货', sub: '运单已提交，商家收到并验货后发起退款', color: AFTERSALE_HEAD_COLORS.ORANGE }
    case 'REFUNDING':
      return {
        title: '退款中',
        sub: aftersale.kind === 'REFUND_ONLY' ? `${amount} 正在原路退回` : `商家已确认收货，${amount} 正在原路退回`,
        color: AFTERSALE_HEAD_COLORS.ORANGE,
      }
    case 'REFUNDED':
      return { title: '已退款', sub: `${amount} 已原路退回，本次售后已完成`, color: AFTERSALE_HEAD_COLORS.GREEN }
    case 'REFUND_FAILED':
      return { title: '退款异常', sub: '退款没有成功，平台正在人工处理', color: AFTERSALE_HEAD_COLORS.RED }
    case 'REJECTED':
      return { title: '商家未同意退货', sub: '这次申请没有通过，不会发起退款', color: AFTERSALE_HEAD_COLORS.RED }
    case 'RECEIPT_REJECTED':
      return { title: '验货未通过', sub: '商家收到商品后未同意退款', color: AFTERSALE_HEAD_COLORS.RED }
    case 'REVOKED':
      return { title: '已撤销', sub: '本次售后申请已撤销', color: AFTERSALE_HEAD_COLORS.GREY }
    case 'CLOSED':
      return { title: '已关闭', sub: '逾期未寄回，本次售后已关闭', color: AFTERSALE_HEAD_COLORS.GREY }
  }
}

export interface AftersaleInfoRow {
  key: string
  label: string
  value: string
  // 退款金额一行是 15px 橙色粗体（画板 AftersaleFlow 的 info）。
  emphasis: boolean
}

// 状态头下的灰底块：退款金额、去向、到账时间、交易号。撤销、拒绝、关闭之后不显示。
export function describeAftersaleInfo(aftersale: Aftersale): AftersaleInfoRow[] {
  if (ended(aftersale.status)) return []
  const rows: AftersaleInfoRow[] = [
    { key: 'amount', label: '退款金额', value: formatTradeAmount(aftersaleAmount(aftersale)), emphasis: true },
    { key: 'route', label: '退款去向', value: AFTERSALE_REFUND_ROUTE, emphasis: false },
  ]
  if (aftersale.status === 'REFUNDING') {
    rows.push({ key: 'arrival', label: '到账时间', value: '以微信支付处理为准', emphasis: false })
  }
  if (aftersale.status === 'REFUNDED') {
    const arrived = aftersale.refund?.succeededAt ?? aftersale.refundedAt
    if (arrived) rows.push({ key: 'arrival', label: '到账时间', value: formatTradeTime(arrived), emphasis: false })
  }
  const transaction = aftersale.refund?.channelTransactionId
  if (transaction && (aftersale.status === 'REFUNDING' || aftersale.status === 'REFUNDED' || aftersale.status === 'REFUND_FAILED')) {
    rows.push({ key: 'transaction', label: '交易号', value: transaction, emphasis: false })
  }
  return rows
}

// 进度：done 已完成 / cur 进行中 / todo 未开始 / end 已结束（撤销、拒绝、关闭，后面不再发生）。
export type AftersaleStepKind = 'done' | 'cur' | 'todo' | 'end'

export interface AftersaleStep {
  key: string
  title: string
  sub: string
  kind: AftersaleStepKind
  // 通往下一个节点的连线颜色；最后一个是 transparent。
  line: string
}

const STEP_LINE = { reached: '#ff6a00', ended: '#c9d3e1', ahead: '#e5ebf3', none: 'transparent' } as const

function lastEvent(aftersale: Aftersale, actions: readonly AftersaleAction[]): string | null {
  const found = [...aftersale.timeline].reverse().find((event) => actions.includes(event.action))
  return found?.occurredAt ?? null
}

function at(time: string | null, words: string): string {
  return time ? `${formatTradeTime(time)} · ${words}` : words
}

type RawStep = Omit<AftersaleStep, 'line'>

function returnRefundSteps(aftersale: Aftersale): RawStep[] {
  const status = aftersale.status
  const submitted: RawStep = { key: 'submit', title: '提交申请', sub: at(aftersale.submittedAt, '已完成'), kind: 'done' }
  const agreedAt = lastEvent(aftersale, ['AGREE', 'AUTO_AGREE']) ?? aftersale.reviewedAt
  const autoAgreed = lastEvent(aftersale, ['AUTO_AGREE']) !== null
  const agreed: RawStep = {
    key: 'review',
    title: '商家审核',
    sub: at(agreedAt, autoAgreed ? '逾期自动同意' : '已同意退货'),
    kind: 'done',
  }
  const shippedAt = lastEvent(aftersale, ['SHIP_RETURN', 'EDIT_RETURN']) ?? aftersale.returnShippedAt
  const shipped: RawStep = {
    key: 'ship',
    title: '寄回商品',
    // 商家在买家填运单之前就收到了货，也会直接确认收货。
    sub: shippedAt ? at(shippedAt, '运单已提交') : '商家已收到商品',
    kind: 'done',
  }
  const receivedAt = lastEvent(aftersale, ['CONFIRM_RECEIPT', 'AUTO_REFUND']) ?? aftersale.receivedAt
  const autoRefund = lastEvent(aftersale, ['AUTO_REFUND']) !== null
  const received: RawStep = {
    key: 'receipt',
    title: '商家确认收货',
    sub: at(receivedAt, autoRefund ? '商家逾期未处理，自动退款' : '验货通过'),
    kind: 'done',
  }
  const refundTodo: RawStep = { key: 'refund', title: '退款原路退回', sub: CHANNEL, kind: 'todo' }

  switch (status) {
    case 'PENDING_REVIEW':
      return [
        submitted,
        { key: 'review', title: '商家审核', sub: '进行中', kind: 'cur' },
        { key: 'ship', title: '寄回商品', sub: '', kind: 'todo' },
        { key: 'receipt', title: '商家确认收货', sub: '', kind: 'todo' },
        refundTodo,
      ]
    case 'AWAITING_RETURN':
      return [
        submitted,
        agreed,
        { key: 'ship', title: '寄回商品', sub: '等你寄回并填写运单', kind: 'cur' },
        { key: 'receipt', title: '商家确认收货', sub: '', kind: 'todo' },
        refundTodo,
      ]
    case 'RETURN_SHIPPED':
      return [submitted, agreed, shipped, { key: 'receipt', title: '商家确认收货', sub: '进行中', kind: 'cur' }, refundTodo]
    case 'REFUNDING':
      return [submitted, agreed, shipped, received, { key: 'refund', title: '退款原路退回', sub: `退款中 · ${CHANNEL}`, kind: 'cur' }]
    case 'REFUNDED':
      return [submitted, agreed, shipped, received, refundDone(aftersale)]
    case 'REFUND_FAILED':
      return [submitted, agreed, shipped, received, { key: 'refund', title: '退款原路退回', sub: '退款异常，平台正在人工处理', kind: 'cur' }]
    case 'REJECTED':
      return [submitted, { key: 'review', title: '商家审核', sub: at(lastEvent(aftersale, ['REJECT']) ?? aftersale.reviewedAt, '未同意'), kind: 'end' }]
    case 'RECEIPT_REJECTED':
      return [
        submitted,
        agreed,
        shipped,
        { key: 'receipt', title: '商家确认收货', sub: at(lastEvent(aftersale, ['REJECT_RECEIPT']) ?? aftersale.closedAt, '验货未通过'), kind: 'end' },
      ]
    case 'REVOKED': {
      const revoked: RawStep = { key: 'revoke', title: '撤销申请', sub: at(lastEvent(aftersale, ['REVOKE']) ?? aftersale.closedAt, '已撤销'), kind: 'end' }
      return agreedAt ? [submitted, agreed, revoked] : [submitted, revoked]
    }
    case 'CLOSED':
      return [
        submitted,
        agreed,
        { key: 'ship', title: '寄回商品', sub: at(lastEvent(aftersale, ['CLOSE_OVERDUE']) ?? aftersale.closedAt, '逾期未寄回，已关闭'), kind: 'end' },
      ]
  }
}

function refundDone(aftersale: Aftersale): RawStep {
  const arrived = lastEvent(aftersale, ['REFUND_SUCCEEDED']) ?? aftersale.refund?.succeededAt ?? aftersale.refundedAt
  return { key: 'refund', title: '退款原路退回', sub: at(arrived, `已到账 · ${CHANNEL}`), kind: 'done' }
}

// 仅退款（未发货）：申请即原路退回，只有两步。
function refundOnlySteps(aftersale: Aftersale): RawStep[] {
  const submitted: RawStep = { key: 'submit', title: '提交申请', sub: at(aftersale.submittedAt, '已完成'), kind: 'done' }
  switch (aftersale.status) {
    case 'REFUNDED':
      return [submitted, refundDone(aftersale)]
    case 'REFUND_FAILED':
      return [submitted, { key: 'refund', title: '退款原路退回', sub: '退款异常，平台正在人工处理', kind: 'cur' }]
    case 'REVOKED':
    case 'CLOSED':
    case 'REJECTED':
    case 'RECEIPT_REJECTED':
      return [submitted, { key: 'end', title: '售后结束', sub: AFTERSALE_STATUS_LABELS[aftersale.status].text, kind: 'end' }]
    default:
      return [submitted, { key: 'refund', title: '退款原路退回', sub: `退款中 · ${CHANNEL}`, kind: 'cur' }]
  }
}

export function describeAftersaleSteps(aftersale: Aftersale): AftersaleStep[] {
  const raw = aftersale.kind === 'REFUND_ONLY' ? refundOnlySteps(aftersale) : returnRefundSteps(aftersale)
  return raw.map((step, index) => {
    const next = raw[index + 1]
    let line: string = STEP_LINE.none
    // 通往已完成 / 进行中节点为橙色，通往结束节点为灰色，未到达为浅灰，末项不画（画板 AftersaleFlow）。
    if (next) line = next.kind === 'done' || next.kind === 'cur' ? STEP_LINE.reached : next.kind === 'end' ? STEP_LINE.ended : STEP_LINE.ahead
    return { ...step, line }
  })
}

// 节点的颜色（画板的 look 表）。
export const AFTERSALE_STEP_LOOK: Record<AftersaleStepKind, { bg: string; bd: string; tc: string; tw: number; sc: string }> = {
  done: { bg: '#ff6a00', bd: '#ff6a00', tc: '#202c42', tw: 500, sc: '#738096' },
  cur: { bg: '#ffffff', bd: '#ff6a00', tc: '#ba470b', tw: 700, sc: '#ba470b' },
  todo: { bg: '#ffffff', bd: '#c9d3e1', tc: '#738096', tw: 400, sc: '#91a0b4' },
  end: { bg: '#738096', bd: '#738096', tc: '#536178', tw: 700, sc: '#738096' },
}

// 寄回地址块的三条提示。
export function returnTips(aftersale: Aftersale): string[] {
  const due = aftersale.dueAt ? formatTradeTime(aftersale.dueAt) : null
  return [
    due ? `请在 ${due} 前寄回，逾期申请自动关闭` : '请按约定时间寄回，逾期申请自动关闭',
    '商品、配件和包装保持完整，包裹内附订单号',
    '寄回运费按销售方规则承担',
  ]
}

export function returnAddressText(aftersale: Aftersale): string | null {
  const address = aftersale.returnAddress
  if (!address) return null
  return [address.contactName, address.phone, address.address].filter(Boolean).join(' ')
}

// 退货物流块下面的一行小字。
export function waybillNote(aftersale: Aftersale): string {
  const waybill = aftersale.returnWaybill
  if (aftersale.receivedAt) return `${formatTradeTime(aftersale.receivedAt)} 商家已签收`
  const time = waybill?.shippedAt ?? aftersale.returnShippedAt
  if (aftersale.status === 'RETURN_SHIPPED') return time ? `${formatTradeTime(time)} 已提交 · 商家确认收货前可修改` : '商家确认收货前可修改'
  return time ? `${formatTradeTime(time)} 已提交` : '运单已提交'
}

// 商家说明块（商家未同意、验货未通过）。
export function refuseRows(aftersale: Aftersale): Array<{ label: string; value: string }> {
  if (aftersale.status !== 'REJECTED' && aftersale.status !== 'RECEIPT_REJECTED') return []
  const rows: Array<{ label: string; value: string }> = []
  if (aftersale.rejectReason) rows.push({ label: '拒绝原因', value: aftersale.rejectReason })
  if (aftersale.merchantNote) rows.push({ label: '商家说明', value: aftersale.merchantNote })
  return rows
}

export interface AftersaleEvidenceSlot {
  slot: AftersaleImageSlot
  label: string
  count: number
  // 已上传是绿字；没传的商品、包装照片是橙字（要补的就是它们），其他凭证灰字（画板 evidence）。
  noteColor: string
  note: string
}

export function evidenceSlots(aftersale: Aftersale): AftersaleEvidenceSlot[] {
  return AFTERSALE_IMAGE_SLOTS.map((slot) => {
    const count = aftersale.images.filter((image) => image.slot === slot).length
    return {
      slot,
      label: AFTERSALE_SLOT_LABELS[slot],
      count,
      note: count > 0 ? `已上传 ${count} 张` : '未上传',
      noteColor: count > 0 ? '#11804a' : slot === 'OTHER' ? '#738096' : '#ba470b',
    }
  })
}

// 进度标题右边的“刷新进度”：等对方动作的几步才有（画板：待寄回要自己动手，不给）。
export function canRefreshAftersale(aftersale: Aftersale): boolean {
  return ['PENDING_REVIEW', 'RETURN_SHIPPED', 'REFUNDING', 'REFUND_FAILED'].includes(aftersale.status)
}

export type AftersaleBarButton =
  | 'CONTACT_MERCHANT'
  | 'REVOKE'
  | 'SUBMIT_RETURN'
  | 'EDIT_RETURN'
  | 'CONTACT_PLATFORM'
  | 'REAPPLY'
  | 'VIEW_ORDER'

/**
 * 底栏按钮（画板 AftersaleFlow）：联系商家（撤销后不再给）、撤销申请、填写运单、修改运单、
 * 商家拒绝后的联系平台客服、重新申请；没有填写运单、重新申请这样的主按钮时给“查看订单”。
 * 能不能撤销、填运单、重新申请只看服务端给的 actions。
 */
export function aftersaleBarButtons(aftersale: Aftersale, supportEnabled: boolean): AftersaleBarButton[] {
  const buttons: AftersaleBarButton[] = []
  if (supportEnabled && aftersale.status !== 'REVOKED') buttons.push('CONTACT_MERCHANT')
  if (aftersale.actions.canRevoke) buttons.push('REVOKE')
  if (aftersale.actions.canSubmitReturn) buttons.push('SUBMIT_RETURN')
  if (aftersale.actions.canEditReturn) buttons.push('EDIT_RETURN')
  if (supportEnabled && (aftersale.status === 'REJECTED' || aftersale.status === 'RECEIPT_REJECTED')) buttons.push('CONTACT_PLATFORM')
  if (aftersale.actions.canReapply) buttons.push('REAPPLY')
  if (!aftersale.actions.canSubmitReturn && !aftersale.actions.canReapply) buttons.push('VIEW_ORDER')
  return buttons
}

export const AFTERSALE_BAR_LABELS: Record<AftersaleBarButton, string> = {
  CONTACT_MERCHANT: '联系商家',
  REVOKE: '撤销申请',
  SUBMIT_RETURN: '填写运单',
  EDIT_RETURN: '修改运单',
  CONTACT_PLATFORM: '联系平台客服',
  REAPPLY: '重新申请',
  VIEW_ORDER: '查看订单',
}

export const AFTERSALE_PRIMARY_BUTTONS: readonly AftersaleBarButton[] = ['SUBMIT_RETURN', 'REAPPLY', 'VIEW_ORDER']

// 列表卡片标题下的那行说明。
export function aftersaleCardLine(aftersale: Aftersale): { text: string; urgent: boolean } {
  const due = aftersale.dueAt ? formatTradeTime(aftersale.dueAt) : null
  const amount = formatTradeAmount(aftersaleAmount(aftersale))
  switch (aftersale.status) {
    case 'PENDING_REVIEW':
      return { text: due ? `商家处理时限 ${due}` : '等待商家处理', urgent: false }
    case 'AWAITING_RETURN':
      return { text: due ? `商家已同意，请在 ${due} 前寄回` : '商家已同意，请寄回商品并填写运单', urgent: true }
    case 'RETURN_SHIPPED':
      return { text: '运单已提交，等待商家收货验货', urgent: false }
    case 'REFUNDING':
      return { text: aftersale.kind === 'REFUND_ONLY' ? `${amount} 正在原路退回` : `商家已确认收货，${amount} 正在原路退回`, urgent: false }
    case 'REFUNDED': {
      const arrived = aftersale.refund?.succeededAt ?? aftersale.refundedAt
      return { text: arrived ? `${formatTradeTime(arrived)} 已原路退回` : '已原路退回', urgent: false }
    }
    case 'REFUND_FAILED':
      return { text: '退款异常，平台正在人工处理', urgent: false }
    case 'REJECTED':
    case 'RECEIPT_REJECTED': {
      const reason = aftersale.rejectReason ?? (aftersale.status === 'REJECTED' ? '商家未同意这次申请' : '商家验货未通过')
      return { text: aftersale.actions.canReapply ? `${reason}，可补充后重新申请` : reason, urgent: false }
    }
    case 'REVOKED':
      return { text: '你已撤销这次申请', urgent: false }
    case 'CLOSED':
      return { text: '逾期未寄回，已关闭', urgent: false }
  }
}

// 第一件商品代表这张售后单；几件时写“等 N 种商品”。
export function aftersaleGoods(lines: Aftersale['lines']): { name: string; spec: string } {
  const first = lines[0]
  if (!first) return { name: '', spec: '' }
  if (lines.length === 1) return { name: first.productName, spec: `${first.skuName} × ${first.quantity}` }
  const pieces = lines.reduce((sum, line) => sum + line.quantity, 0)
  return { name: `${first.productName} 等 ${lines.length} 种商品`, spec: `共 ${pieces} 件` }
}

/** 售后接口的错误翻成中文。 */
export type AftersaleFailure =
  | { kind: 'UNAUTHORIZED' }
  // 售后单或订单在页面读过之后变了：再读一次。
  | { kind: 'CHANGED'; message: string }
  | { kind: 'FAILED'; message: string }

const AFTERSALE_ERROR_TEXTS: Record<string, string> = {
  TRADE_AFTERSALE_ALREADY_OPEN: '这笔订单已有一张售后单在处理中',
  TRADE_AFTERSALE_AMOUNT_EXCEEDS: '申请金额超过了还能退的金额',
  TRADE_AFTERSALE_STATE_INVALID: '售后单状态已变化，请查看最新进度',
  TRADE_AFTERSALE_VERSION_CONFLICT: '售后单已更新，请查看最新进度',
  TRADE_IDEMPOTENCY_KEY_REUSED: '申请内容有变化，请重新提交',
  TRADE_AFTERSALE_IMAGE_INVALID: '凭证图片无效或已用过，请重新上传',
  TRADE_AFTERSALE_UPLOAD_LIMITED: '上传太频繁，请稍后再试',
  TRADE_AFTERSALE_STORAGE_UNAVAILABLE: '图片服务暂不可用，请稍后再试',
  TRADE_CHANNEL_UNAVAILABLE: '微信支付暂不可用，请稍后再试',
}

export function classifyAftersaleFailure(error: unknown): AftersaleFailure {
  const failure = readTradeFailure(error)
  if (failure && (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED')) return { kind: 'UNAUTHORIZED' }
  if (failure?.code === 'TRADE_AFTERSALE_NOT_ELIGIBLE') {
    const details = (error as { details?: Record<string, unknown> | null }).details
    const reason = typeof details?.reason === 'string' ? details.reason as AftersaleUnavailableReason : null
    const text = reason && AFTERSALE_UNAVAILABLE_LABELS[reason]
    return { kind: 'CHANGED', message: text ? `现在不能这样申请：${text}` : '这笔订单现在不能这样申请售后' }
  }
  const known = failure?.code ? AFTERSALE_ERROR_TEXTS[failure.code] : undefined
  if (failure?.status === 412 || failure?.code === 'TRADE_AFTERSALE_VERSION_CONFLICT' || failure?.code === 'TRADE_AFTERSALE_STATE_INVALID') {
    return { kind: 'CHANGED', message: known ?? '售后单已更新，请查看最新进度' }
  }
  if (known) return { kind: failure?.code === 'TRADE_AFTERSALE_ALREADY_OPEN' ? 'CHANGED' : 'FAILED', message: known }
  if (failure?.status === 404) return { kind: 'FAILED', message: '没有找到这张售后单' }
  if (failure?.status === 400) return { kind: 'FAILED', message: '填写的内容不符合要求，请检查后重试' }
  return { kind: 'FAILED', message: failure && failure.status !== 0 ? '操作失败，请重试' : '网络连接失败，请稍后重试' }
}
