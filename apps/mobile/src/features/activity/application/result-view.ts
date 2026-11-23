import type { Enrollment, EnrollmentTicket, ResultCompletion } from '../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityShortDateTime,
  formatActivityTime,
} from '../presentation/shared/activity-format'
import { formatSessionRef } from './change-view'
import { formatEnrollmentCheckIn, formatEnrollmentSession } from './enrollment-order-view'

// 订单详情 after the day (board EnrollResult, S5-3): sign-in records while it runs, the organiser's
// result record once it is over, or 未到场. Everything is the server's: ticket statuses, check-in times
// and the results the organiser entered in the web workbench. The board's 活动延期 / 活动取消 frames are
// started by the organiser; that flow does not exist yet, so they are not drawn here.

export type ResultPhase = 'CANCELLED' | 'UPCOMING' | 'IN_PROGRESS' | 'COMPLETED' | 'ABSENT'

export type StepKind = 'DONE' | 'CURRENT' | 'TODO' | 'END'

export interface ResultStep {
  title: string
  kind: StepKind
}

export interface ResultFact {
  label: string
  value: string
  strong: boolean
}

export interface ResultPerson {
  ticketId: string
  name: string
  age: string
  state: string
  tone: 'DONE' | 'PENDING' | 'MUTED'
}

export interface ResultRecord {
  ticketId: string
  name: string
  age: string
  badge: string
  completed: boolean
  stats: Array<{ label: string; value: string }>
}

export interface ResultView {
  phase: ResultPhase
  grey: boolean
  heroIcon: 'check' | 'clock' | 'info' | 'close'
  heroTitle: string
  heroSub: string
  move: { from: string; to: string } | null
  facts: ResultFact[]
  steps: ResultStep[]
  ticketTitle: string
  ticketSub: string
  people: ResultPerson[]
  peopleTitle: string
  peopleNote: string
  showPeople: boolean
  // The result card: shown once the activity is over and someone signed in.
  showResults: boolean
  records: ResultRecord[]
  publishedLine: string | null
  certificateNotes: string[]
  noticeTitle: string | null
  notices: string[]
  showNext: boolean
  parties: Array<{ label: string; value: string }>
  feeLine: string
  paidText: string
  paid: boolean
  // NOTE: under way, no more changes; LINKS: over (contact / more activities); UPCOMING: 申请退改 / 查看凭证.
  bar: 'NOTE' | 'LINKS' | 'UPCOMING'
}

const COMPLETION_LABELS: Record<ResultCompletion, string> = {
  COMPLETED: '完成',
  INCOMPLETE: '未完成',
}

export function completionLabel(completion: ResultCompletion): string {
  return COMPLETION_LABELS[completion]
}

function sessionEnd(enrollment: Enrollment): number {
  return Date.parse(enrollment.session.endsAt ?? enrollment.session.startsAt)
}

// The tickets that count: everyone who was not cancelled.
function liveTickets(enrollment: Enrollment): EnrollmentTicket[] {
  return enrollment.tickets.filter((ticket) => ticket.status !== 'CANCELLED' && ticket.status !== 'PENDING_PAYMENT')
}

function ageText(ticket: EnrollmentTicket): string {
  if (ticket.age !== null && ticket.relation !== 'SELF') return `${ticket.age} 岁`
  return ticket.relation === 'SELF' ? '本人' : ''
}

export function resultPhase(enrollment: Enrollment, now: number): ResultPhase {
  if (enrollment.status === 'CANCELLED' || liveTickets(enrollment).length === 0) return 'CANCELLED'
  const signed = liveTickets(enrollment).some((ticket) => ticket.status === 'CHECKED_IN')
  // Signing in opens before the start; someone signed in means the day is under way.
  const started = signed || now >= Date.parse(enrollment.session.startsAt)
  if (!started) return 'UPCOMING'
  if (now < sessionEnd(enrollment)) return 'IN_PROGRESS'
  return signed ? 'COMPLETED' : 'ABSENT'
}

function steps(phase: ResultPhase, paid: boolean): ResultStep[] {
  const names = [paid ? '已支付' : '提交报名', '报名成功', '签到', '完成']
  const kinds: Record<ResultPhase, StepKind[]> = {
    UPCOMING: ['DONE', 'DONE', 'TODO', 'TODO'],
    IN_PROGRESS: ['DONE', 'DONE', 'DONE', 'CURRENT'],
    COMPLETED: ['DONE', 'DONE', 'DONE', 'DONE'],
    ABSENT: ['DONE', 'DONE', 'END', 'TODO'],
    CANCELLED: ['DONE', 'END', 'TODO', 'TODO'],
  }
  return kinds[phase].map((kind, index) => ({
    title: kind === 'END' ? (phase === 'ABSENT' ? '未签到' : '已取消') : names[index],
    kind,
  }))
}

export function describeResult(enrollment: Enrollment, now: number): ResultView {
  const phase = resultPhase(enrollment, now)
  const paid = enrollment.payment.status === 'PAID'
  const tickets = liveTickets(enrollment)
  const signed = tickets.filter((ticket) => ticket.status === 'CHECKED_IN')
  const total = tickets.length
  const endText = formatActivityShortDateTime(enrollment.session.endsAt ?? enrollment.session.startsAt)
  const withResult = signed.filter((ticket) => ticket.result)

  let heroTitle: string
  let heroSub: string
  let heroIcon: ResultView['heroIcon'] = 'check'
  switch (phase) {
    case 'UPCOMING':
      heroTitle = '报名成功 · 待参加'
      heroSub = '活动开始后，这里显示签到和结果记录'
      break
    case 'IN_PROGRESS':
      if (signed.length > 0) {
        heroTitle = '已签到 · 活动进行中'
        heroSub = signed.length === total
          ? `${total} 人都已签到，活动结束后这里显示结果记录`
          : `${signed.length}/${total} 人已签到，活动结束后这里显示结果记录`
      } else {
        heroIcon = 'clock'
        heroTitle = '活动进行中'
        heroSub = '到场出示凭证签到，活动结束后这里显示结果记录'
      }
      break
    case 'COMPLETED':
      heroTitle = '活动已完成'
      heroSub = withResult.length > 0
        ? `${endText} 结束 · 主办方已发布结果记录`
        : `${endText} 结束 · 主办方发布结果后通过“消息”通知你`
      break
    case 'ABSENT':
      heroIcon = 'info'
      heroTitle = '未到场'
      heroSub = `活动已在 ${endText} 结束，没有你的签到记录`
      break
    case 'CANCELLED':
      heroIcon = 'close'
      heroTitle = '报名已取消'
      heroSub = '这笔报名没有需要签到的参与人'
      break
  }

  const facts: ResultFact[] = [
    { label: '场次', value: formatEnrollmentSession(enrollment), strong: true },
    { label: '场馆', value: enrollment.location.name, strong: false },
  ]
  if (phase === 'IN_PROGRESS' && enrollment.session.endsAt) {
    facts.push({
      label: '活动时间',
      value: `${formatActivityTime(enrollment.session.startsAt)}–${formatActivityTime(enrollment.session.endsAt)}`,
      strong: false,
    })
  } else if (phase === 'COMPLETED' || phase === 'ABSENT') {
    facts.push({ label: '签到', value: signed.length > 0 ? `${signed.length} 人已签到` : '无签到记录', strong: false })
  } else if (phase === 'UPCOMING') {
    const checkIn = formatEnrollmentCheckIn(enrollment)
    if (checkIn) facts.push({ label: '签到时间', value: checkIn, strong: false })
  }

  const ticketSub: Record<ResultPhase, string> = {
    UPCOMING: '到场出示凭证签到 · 一人一码',
    IN_PROGRESS: signed.length > 0 ? '已核销 · 一人一码' : '到场出示凭证签到 · 一人一码',
    COMPLETED: '已使用 · 可回看签到记录',
    ABSENT: '已失效 · 活动已结束',
    CANCELLED: '凭证已作废',
  }

  const people: ResultPerson[] = tickets.map((ticket) => {
    if (ticket.status === 'CHECKED_IN' && ticket.checkedInAt) {
      return { ticketId: ticket.ticketId, name: ticket.participantName, age: ageText(ticket), state: `${formatActivityTime(ticket.checkedInAt)} 已签到`, tone: 'DONE' }
    }
    return {
      ticketId: ticket.ticketId,
      name: ticket.participantName,
      age: ageText(ticket),
      state: phase === 'IN_PROGRESS' || phase === 'UPCOMING' ? '待签到' : '未签到',
      tone: phase === 'IN_PROGRESS' || phase === 'UPCOMING' ? 'PENDING' : 'MUTED',
    }
  })

  const records: ResultRecord[] = withResult.map((ticket) => {
    const result = ticket.result!
    return {
      ticketId: ticket.ticketId,
      name: ticket.participantName,
      age: ageText(ticket),
      badge: COMPLETION_LABELS[result.completion],
      completed: result.completion === 'COMPLETED',
      stats: [
        { label: '完成情况', value: COMPLETION_LABELS[result.completion] },
        { label: '成绩', value: result.scoreText ?? '—' },
        { label: '签到', value: ticket.checkedInAt ? formatActivityTime(ticket.checkedInAt) : '—' },
      ],
    }
  })
  const recordedTimes = withResult.map((ticket) => ticket.result!.recordedAt).sort()
  const latest = recordedTimes.length > 0 ? recordedTimes[recordedTimes.length - 1] : null
  const certificateNotes = [...new Set(withResult
    .map((ticket) => ticket.result!.certificateNote)
    .filter((note): note is string => Boolean(note)))]

  let noticeTitle: string | null = null
  let notices: string[] = []
  if (phase === 'IN_PROGRESS') {
    noticeTitle = '现场须知'
    notices = [
      '活动开始后不再支持退改',
      '中途离场请告诉现场工作人员，由监护人接走',
      '结果记录由主办方在活动结束后发布，发布后通过“消息”通知你',
    ]
  } else if (phase === 'ABSENT') {
    noticeTitle = '费用说明'
    notices = [
      paid ? '活动开始后不再支持退改，报名费不退' : '免费报名，不涉及费用',
      '到场了却没有记录，可以联系主办方核实',
    ]
  }

  const parties = [{ label: '主办', value: enrollment.activity.organizerName }]
  if (paid || enrollment.payment.status === 'PENDING_PAYMENT') {
    parties.push({ label: '收款方', value: `${enrollment.activity.organizerName} · 经平台支付渠道` })
  }
  parties.push({ label: '退改处理', value: enrollment.activity.organizerName })

  const unit = enrollment.payment.unitAmountMinor
  return {
    phase,
    grey: phase === 'ABSENT' || phase === 'CANCELLED',
    heroIcon,
    heroTitle,
    heroSub,
    move: enrollment.reschedule
      ? { from: formatSessionRef(enrollment.reschedule.previousSession), to: formatEnrollmentSession(enrollment) }
      : null,
    facts,
    steps: steps(phase, paid),
    ticketTitle: '查看凭证',
    ticketSub: ticketSub[phase],
    people,
    peopleTitle: phase === 'UPCOMING' ? '参与人' : '签到记录',
    peopleNote: phase === 'UPCOMING' ? `${total} 人` : `${signed.length} / ${total} 人`,
    showPeople: phase !== 'COMPLETED' && phase !== 'CANCELLED',
    showResults: phase === 'COMPLETED',
    records,
    publishedLine: latest ? `主办方 ${formatActivityShortDateTime(latest)} 发布` : null,
    certificateNotes,
    noticeTitle,
    notices,
    showNext: phase === 'COMPLETED' || phase === 'ABSENT',
    parties,
    feeLine: paid && unit ? `${tickets.length} 人 × ${formatActivityAmount(unit)}` : '免费',
    paidText: enrollment.payment.amountMinor ? formatActivityAmount(enrollment.payment.amountMinor) : '免费',
    paid,
    bar: phase === 'IN_PROGRESS' ? 'NOTE' : phase === 'UPCOMING' ? 'UPCOMING' : 'LINKS',
  }
}

// The ticket page offers 查看结果记录 once the activity is over, or as soon as a result is in.
export function hasResultPage(enrollment: Enrollment, now: number): boolean {
  if (enrollment.tickets.some((ticket) => ticket.result)) return true
  const phase = resultPhase(enrollment, now)
  return phase === 'IN_PROGRESS' || phase === 'COMPLETED' || phase === 'ABSENT'
}
