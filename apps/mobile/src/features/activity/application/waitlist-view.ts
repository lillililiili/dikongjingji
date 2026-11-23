import type { Waitlist, WaitlistStatus } from '../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime } from '../presentation/shared/activity-format'
import { formatPayCountdown } from './enrollment-view'

// What the waitlist page (board EnrollWaitlist, S5-3) says about one waitlist. Everything here is
// read from the server's facts (status, position, holdUntil, amounts); nothing moves the state.

export type WaitlistTone = 'ORANGE' | 'GRAY'
export type WaitlistHeroIcon = 'clock' | 'check' | 'info' | 'close'
export type WaitlistStepKind = 'done' | 'cur' | 'todo' | 'end'
export type WaitlistBar = 'WAITING' | 'OFFERED' | 'ENROLLED' | 'ENDED'

export interface WaitlistStep {
  label: string
  kind: WaitlistStepKind
}

export interface WaitlistPageView {
  tone: WaitlistTone
  heroIcon: WaitlistHeroIcon
  heroTitle: string
  heroSub: string
  noteIcon: 'clock' | 'info' | 'check'
  note: string
  steps: WaitlistStep[]
  // 费用 card: “报名费 2 人 × ¥120” (null when free) and the second line.
  feeLine: string | null
  payLabel: string
  payValue: string
  // ORANGE: an amount still to pay; GREEN: nothing charged; DARK: paid.
  payTone: 'ORANGE' | 'GREEN' | 'DARK'
  bar: WaitlistBar
  rules: string[]
}

export const WAITLIST_STATUS_LABELS: Record<WaitlistStatus, string> = {
  WAITING: '候补中',
  OFFERED: '候补成功',
  ENROLLED: '已报名',
  EXPIRED: '候补已失效',
  ENDED: '候补未成功',
  ABANDONED: '已放弃',
}

export function isPaidWaitlist(waitlist: Waitlist): boolean {
  return waitlist.unitAmountMinor !== null && waitlist.unitAmountMinor > 0
}

// "下午场" from the session label "下午"; "本场" without one.
export function waitlistSessionName(waitlist: Waitlist): string {
  const label = waitlist.session.label?.trim()
  if (!label) return '本场'
  return label.endsWith('场') ? label : `${label}场`
}

// "11/15 下午场 14:00", or "11/15 14:00" without a label.
export function formatWaitlistSession(waitlist: Waitlist): string {
  const [day, time] = formatActivityShortDateTime(waitlist.session.startsAt).split(' ')
  return waitlist.session.label ? `${day} ${waitlistSessionName(waitlist)} ${time}` : `${day} ${time}`
}

export function waitlistHoldRemainingMs(waitlist: Waitlist, now: number): number {
  if (waitlist.status !== 'OFFERED' || !waitlist.holdUntil) return 0
  return Math.max(0, new Date(waitlist.holdUntil).getTime() - now)
}

// Board rules; the second one only when several people wait together.
export function waitlistRules(waitlist: Waitlist): string[] {
  const paid = isPaidWaitlist(waitlist)
  const rules = ['名额释放后按加入先后转正，并通过“消息”通知你']
  if (waitlist.people > 1) {
    rules.push(`同单 ${waitlist.people} 人一起转正，余位不足 ${waitlist.people} 个时继续排队`)
  }
  rules.push(paid
    ? '转正后保留 15 分钟，超时未付款释放给下一位'
    : '转正后保留 15 分钟，超时未确认释放给下一位')
  rules.push(paid
    ? '候补期间不收费，可随时放弃；活动开始前仍未转正，候补自动结束'
    : '候补和报名都不收费，可随时放弃；活动开始前仍未转正，候补自动结束')
  return rules
}

const STEP_NAMES_PAID = ['加入候补', '等待名额', '付款', '报名成功']
const STEP_NAMES_FREE = ['加入候补', '等待名额', '确认报名', '报名成功']

function steps(waitlist: Waitlist, kinds: WaitlistStepKind[], endLabel = ''): WaitlistStep[] {
  const names = isPaidWaitlist(waitlist) ? STEP_NAMES_PAID : STEP_NAMES_FREE
  return kinds.map((kind, index) => ({ label: kind === 'end' ? endLabel : names[index], kind }))
}

export function describeWaitlist(waitlist: Waitlist, now: number): WaitlistPageView {
  const paid = isPaidWaitlist(waitlist)
  const amount = waitlist.amountMinor !== null ? formatActivityAmount(waitlist.amountMinor) : '免费'
  const feeLine = paid && waitlist.unitAmountMinor
    ? `${waitlist.people} 人 × ${formatActivityAmount(waitlist.unitAmountMinor)}`
    : null
  const nothing = { payLabel: '本次费用', payValue: '未产生费用', payTone: 'GREEN' as const }
  const rules = waitlistRules(waitlist)
  const base = { feeLine, rules }

  switch (waitlist.status) {
    case 'WAITING': {
      const ahead = waitlist.ahead ?? 0
      return {
        ...base,
        tone: 'ORANGE',
        heroIcon: 'clock',
        heroTitle: `候补中 · 第 ${waitlist.position ?? ahead + 1} 位`,
        heroSub: paid
          ? '有名额释放时会通过“消息”通知你，候补成功后再付款'
          : '有名额释放时会通过“消息”通知你，候补成功后再确认报名',
        noteIcon: 'clock',
        note: ahead > 0
          ? `${waitlistSessionName(waitlist)}已满 · 前面还有 ${ahead} 位在候补`
          : `${waitlistSessionName(waitlist)}已满 · 你排在第一位`,
        steps: steps(waitlist, ['done', 'cur', 'todo', 'todo']),
        ...(paid
          ? { payLabel: '候补成功后应付', payValue: amount, payTone: 'ORANGE' as const }
          : { payLabel: '本次费用', payValue: '免费', payTone: 'GREEN' as const }),
        bar: 'WAITING',
      }
    }
    case 'OFFERED':
      return {
        ...base,
        tone: 'ORANGE',
        heroIcon: 'check',
        heroTitle: paid ? '候补成功 · 待付款' : '候补成功 · 待确认',
        heroSub: paid
          ? '名额已为你保留 15 分钟，超时释放给下一位'
          : '名额已为你保留 15 分钟，确认后报名成功，超时释放给下一位',
        noteIcon: 'clock',
        note: `名额保留 ${formatPayCountdown(waitlistHoldRemainingMs(waitlist, now))} · 超时自动释放`,
        steps: steps(waitlist, ['done', 'done', 'cur', 'todo']),
        ...(paid
          ? { payLabel: '应付', payValue: amount, payTone: 'ORANGE' as const }
          : { payLabel: '本次费用', payValue: '免费', payTone: 'GREEN' as const }),
        bar: 'OFFERED',
      }
    case 'ENROLLED':
      return {
        ...base,
        tone: 'ORANGE',
        heroIcon: 'check',
        heroTitle: '候补成功 · 已报名',
        heroSub: '名额已转为正式报名，每人一张凭证',
        noteIcon: 'check',
        note: paid ? '已付款 · 凭证已生成' : '报名成功 · 凭证已生成',
        steps: steps(waitlist, ['done', 'done', 'done', 'done']),
        ...(paid
          ? { payLabel: '实付', payValue: amount, payTone: 'DARK' as const }
          : { payLabel: '本次费用', payValue: '免费', payTone: 'GREEN' as const }),
        bar: 'ENROLLED',
      }
    case 'EXPIRED':
      return {
        ...base,
        tone: 'GRAY',
        heroIcon: 'clock',
        heroTitle: '候补已失效',
        heroSub: paid ? '15 分钟内没有付款，名额已释放给下一位' : '15 分钟内没有确认，名额已释放给下一位',
        noteIcon: 'info',
        note: paid ? '付款时限已过，未收取任何费用' : '确认时限已过，未产生任何费用',
        steps: steps(waitlist, ['done', 'done', 'end', 'todo'], '已超时'),
        ...nothing,
        bar: 'ENDED',
      }
    case 'ENDED':
      return {
        ...base,
        tone: 'GRAY',
        heroIcon: 'info',
        heroTitle: '候补未成功',
        heroSub: '活动开始前没有释放足够名额，本次候补已结束',
        noteIcon: 'info',
        note: '候补已自动结束，未收取任何费用',
        steps: steps(waitlist, ['done', 'end', 'todo', 'todo'], '未转正'),
        ...nothing,
        bar: 'ENDED',
      }
    case 'ABANDONED':
      return {
        ...base,
        tone: 'GRAY',
        heroIcon: 'close',
        heroTitle: '已放弃候补',
        heroSub: '排队位置不再保留，需要时可以重新加入',
        noteIcon: 'info',
        note: '再次候补要重新排队，未收取任何费用',
        steps: steps(waitlist, ['done', 'end', 'todo', 'todo'], '已放弃'),
        ...nothing,
        bar: 'ENDED',
      }
  }
}

// The abandon sheet's text (board): an offer gives its seats to the next one at once.
export function abandonSheetText(waitlist: Waitlist): string {
  return waitlist.status === 'OFFERED'
    ? '为你保留的名额会马上释放给下一位，放弃后不能恢复。'
    : '放弃后排队位置不再保留，再次候补要重新排队。'
}

// "11 岁"; "本人" has no age.
export function waitlistMemberAge(age: number | null): string {
  return age === null ? '' : `${age} 岁`
}
