import type { ChangeDetail, ChangeSummary, EnrollmentSessionRef } from '../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime } from '../presentation/shared/activity-format'
import { changeReasonLabel } from './reschedule-view'

// 退改详情 (board EnrollRefundDetail, S5-3). Unlike the board, nothing waits for the organiser: a
// cancellation releases the seats and (paid) asks the trade module for the refund at once, and a
// reschedule takes effect at once. The refund's progress comes only from the trade module's state.

export const CHANGE_GREEN = '#11804a'
export const CHANGE_ORANGE = '#ba470b'
export const CHANGE_GREY = '#536178'
export const CHANGE_RED = '#d64f67'

// "11/15 上午 09:00", or "11/15 09:00" without a label.
export function formatSessionRef(session: EnrollmentSessionRef): string {
  const [day, time] = formatActivityShortDateTime(session.startsAt).split(' ')
  return session.label ? `${day} ${session.label} ${time}` : `${day} ${time}`
}

export type ChangeStepKind = 'DONE' | 'CURRENT' | 'TODO' | 'END'

export interface ChangeStep {
  title: string
  sub: string
  kind: ChangeStepKind
}

export interface ChangeRow {
  label: string
  value: string
  strong?: 'AMOUNT' | 'BOLD'
}

export interface ChangeView {
  statusTitle: string
  statusSub: string
  statusColor: string
  info: ChangeRow[]
  steps: ChangeStep[]
  apply: ChangeRow[]
  // The refund may still move; the page offers 刷新进度.
  refreshable: boolean
}

type RefundPhase = 'NONE' | 'PENDING' | 'DONE' | 'FAILED'

export function refundPhase(change: ChangeSummary): RefundPhase {
  if (change.refundAmountMinor === null) return 'NONE'
  switch (change.refundStatus) {
    case 'SUCCEEDED': return 'DONE'
    case 'CLOSED':
    case 'ABNORMAL': return 'FAILED'
    default: return 'PENDING'
  }
}

export function describeChange(detail: ChangeDetail): ChangeView {
  const change = detail.change
  const submittedAt = formatActivityShortDateTime(change.createdAt)
  const names = detail.tickets.filter((ticket) => !ticket.issued).map((ticket) => ticket.participantName).join('、')
  const reason = changeReasonLabel(change.reason)
  const handler = `${detail.organizerName} · 平台协助沟通`

  if (change.kind === 'RESCHEDULE' && change.toSession) {
    const to = formatSessionRef(change.toSession)
    return {
      statusTitle: '已改期',
      statusSub: `名额已转入 ${to}，原凭证已作废`,
      statusColor: CHANGE_GREEN,
      info: [
        { label: '原场次', value: formatSessionRef(change.fromSession) },
        { label: '新场次', value: to, strong: 'BOLD' },
        { label: '费用变化', value: '无需补差价' },
      ],
      steps: [
        { title: '提交申请', sub: `${submittedAt} · 已完成`, kind: 'DONE' },
        { title: '改期生效', sub: `${submittedAt} · ${change.people} 个名额转入新场次`, kind: 'DONE' },
        { title: '发放新凭证', sub: `${submittedAt} · 一人一码`, kind: 'DONE' },
      ],
      apply: [
        { label: '申请类型', value: '改到其他场次 · 无需补差价' },
        { label: '申请原因', value: reason },
        { label: '提交时间', value: submittedAt },
      ],
      refreshable: false,
    }
  }

  const phase = refundPhase(change)
  const amount = change.refundAmountMinor === null ? null : formatActivityAmount(change.refundAmountMinor)
  const released = { title: '释放名额', sub: `${submittedAt} · ${change.people} 个名额`, kind: 'DONE' as const }
  const submitted = { title: '提交申请', sub: `${submittedAt} · 已完成`, kind: 'DONE' as const }
  const apply: ChangeRow[] = [
    { label: '申请类型', value: amount ? '取消报名并退款' : '取消报名' },
    { label: '参与人', value: names || `${change.people} 人` },
    { label: '申请原因', value: reason },
    { label: '处理方', value: handler },
    { label: '提交时间', value: submittedAt },
  ]

  if (phase === 'NONE' || !amount) {
    return {
      statusTitle: '已取消',
      statusSub: `${change.people} 个名额已释放，免费报名不涉及退款`,
      statusColor: CHANGE_GREY,
      info: [
        { label: '取消人数', value: `${change.people} 人` },
        { label: '费用', value: '免费报名，不涉及退款' },
      ],
      steps: [submitted, released],
      apply,
      refreshable: false,
    }
  }

  const arrived = change.refundSucceededAt ? formatActivityShortDateTime(change.refundSucceededAt) : null
  const info: ChangeRow[] = [
    { label: '退款金额', value: amount, strong: 'AMOUNT' },
    { label: '退款去向', value: '微信支付 原路退回' },
    { label: '到账时间', value: arrived ?? '以微信支付为准' },
  ]
  if (phase === 'DONE') {
    return {
      statusTitle: '已退款',
      statusSub: `${amount} 已原路退回，凭证已作废`,
      statusColor: CHANGE_GREEN,
      info,
      steps: [submitted, released, { title: '退款原路退回', sub: `${arrived ?? submittedAt} · 已到账 微信支付`, kind: 'DONE' }],
      apply,
      refreshable: false,
    }
  }
  if (phase === 'FAILED') {
    return {
      statusTitle: '退款异常',
      statusSub: '退款没有成功，平台正在人工处理，可联系客服',
      statusColor: CHANGE_RED,
      info,
      steps: [submitted, released, { title: '退款原路退回', sub: '退款异常 · 平台人工处理', kind: 'END' }],
      apply,
      refreshable: true,
    }
  }
  return {
    statusTitle: '退款中',
    statusSub: `${change.people} 个名额已释放，原凭证已作废`,
    statusColor: CHANGE_ORANGE,
    info,
    steps: [submitted, released, { title: '退款原路退回', sub: '退款中 · 微信支付', kind: 'CURRENT' }],
    apply,
    refreshable: true,
  }
}

// One line for a change in a list ("退款中 ¥240" / "已改期 11/22 上午 09:00").
export function describeChangeLine(change: ChangeSummary): { title: string; sub: string } {
  if (change.kind === 'RESCHEDULE' && change.toSession) {
    return { title: '已改期', sub: `${formatSessionRef(change.fromSession)} → ${formatSessionRef(change.toSession)}` }
  }
  const amount = change.refundAmountMinor === null ? null : formatActivityAmount(change.refundAmountMinor)
  const phase = refundPhase(change)
  if (phase === 'NONE' || !amount) return { title: '已取消', sub: `${change.people} 人 · 免费报名，不涉及退款` }
  const label = phase === 'DONE' ? '已退款' : phase === 'FAILED' ? '退款异常' : '退款中'
  return { title: label, sub: `${change.people} 人 · ${amount} 原路退回` }
}
