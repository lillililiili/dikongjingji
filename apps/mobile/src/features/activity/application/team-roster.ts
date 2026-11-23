import type { ActivityAgeRange } from '../domain/activity'
import {
  MAX_TEAM_ROSTER_ROWS,
  MAX_TEAM_SIZE,
  MIN_TEAM_SIZE,
  type Enrollment,
  type EnrollmentTicket,
  type TeamMemberCheck,
  type TeamMemberInput,
  type TeamMemberReason,
} from '../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime, formatActivityTime } from '../presentation/shared/activity-format'
import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'
import { formatPayCountdown } from './enrollment-view'

// The team sign-up (board TeamSignup) and the team order (board TeamOrder), S5-3. The roster lives
// on the page until it is submitted; who may come is always the server's answer (checkTeam).

/** One row of the roster on the page: an existing participant, or a name and birth month typed in. */
export interface RosterEntry {
  key: string
  participantId: string | null
  name: string
  birthMonth: string | null
}

export const TEAM_NAME_MAX = 20
export const LEADER_NAME_MAX = 20
const PHONE = /^1[0-9]{10}$/
const NAME_MAX = 20

let rosterKeySeed = 0
function nextKey(): string {
  rosterKeySeed += 1
  return `roster-${rosterKeySeed}`
}

export function rosterEntryFromParticipant(participantId: string, name: string, birthMonth: string | null): RosterEntry {
  return { key: nextKey(), participantId, name, birthMonth }
}

export function rosterEntryFromInput(name: string, birthMonth: string): RosterEntry {
  return { key: nextKey(), participantId: null, name: name.trim(), birthMonth }
}

export function toTeamMemberInput(entry: RosterEntry): TeamMemberInput {
  return entry.participantId
    ? { participantId: entry.participantId }
    : { name: entry.name, birthMonth: entry.birthMonth ?? undefined }
}

export function isValidLeaderPhone(phone: string): boolean {
  return PHONE.test(phone.trim())
}

export function isValidMemberName(name: string): boolean {
  const trimmed = name.trim()
  return trimmed.length > 0 && [...trimmed].length <= NAME_MAX && !/[\u0000-\u001f]/.test(trimmed)
}

// What the paste box of 批量导入 starts with (the board's "下载模板" becomes 复制模板 in the App).
export const ROSTER_TEMPLATE = '姓名 出生年月\n李明轩 2014-05\n张雨桐 2016-03\n'

/**
 * Lines of "姓名 出生年月" pasted from a spreadsheet or a chat: separated by spaces, tabs or commas;
 * the month as 2014-05, 2014/5, 2014.05, 201405 or 2014年5月. A header line and blank lines are
 * skipped; lines that cannot be read come back as they were so the sheet can show them.
 */
export function parseRosterText(text: string, today: Date): { entries: RosterEntry[]; invalidLines: string[] } {
  const entries: RosterEntry[] = []
  const invalidLines: string[] = []
  const parts = toBeijingParts(today)
  const thisMonth = `${parts.year}-${padTwo(parts.month)}`
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || /^姓名/.test(line)) continue
    const parts = line.split(/[\s,，、;；]+/).filter(Boolean)
    const month = parts.length >= 2 ? parseBirthMonth(parts.slice(1).join('')) : null
    const name = parts[0] ?? ''
    if (!month || month > thisMonth || !isValidMemberName(name)) {
      invalidLines.push(line)
      continue
    }
    entries.push(rosterEntryFromInput(name, month))
  }
  return { entries, invalidLines }
}

export function parseBirthMonth(text: string): string | null {
  const value = text.trim()
  const match = /^(\d{4})(?:[-/.年]?)(\d{1,2})月?$/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  if (year < 1900 || month < 1 || month > 12) return null
  return `${year}-${String(month).padStart(2, '0')}`
}

/** How a row reads: `session` like "11/15 上午场"; `contest` says 参赛 instead of 参加 (board TeamSignup). */
export interface RosterWording {
  session?: string
  contest?: boolean
}

export function describeMemberReason(
  reason: TeamMemberReason,
  ageRange: ActivityAgeRange,
  wording: RosterWording = {},
): string {
  const age = wording.contest ? '不符合参赛年龄' : '不符合参加年龄'
  switch (reason) {
    case 'ELIGIBLE':
      return '符合报名条件'
    case 'UNDER_MIN_AGE':
      return ageRange.minAge !== null ? `未满 ${ageRange.minAge} 岁，${age}` : age
    case 'OVER_MAX_AGE':
      return ageRange.maxAge !== null ? `超过 ${ageRange.maxAge} 岁，${age}` : age
    case 'ALREADY_ENROLLED':
      return wording.session ? `已报名 ${wording.session}，不能重复报名` : '已报名这一场，不能重复报名'
    case 'DUPLICATED':
      return '名单里重复了'
  }
}

export interface RosterRow {
  entry: RosterEntry
  check: TeamMemberCheck | null
  eligible: boolean
  ageText: string
  stateText: string
}

/** The roster as the page draws it: each row with the server's verdict (still checking: none yet). */
export function describeRoster(
  entries: RosterEntry[],
  checks: TeamMemberCheck[] | null,
  ageRange: ActivityAgeRange,
  wording: RosterWording = {},
): { rows: RosterRow[]; eligibleCount: number; issueCount: number } {
  const rows = entries.map((entry, index) => {
    const check = checks && checks.length === entries.length ? checks[index] : null
    const age = check?.ageAtSession ?? null
    return {
      entry,
      check,
      eligible: check?.eligible ?? false,
      ageText: age === null ? '' : `${age} 岁`,
      stateText: check ? describeMemberReason(check.reason, ageRange, wording) : '核对中…',
    }
  })
  const checked = rows.filter((row) => row.check)
  return {
    rows,
    eligibleCount: checked.filter((row) => row.eligible).length,
    issueCount: checked.filter((row) => !row.eligible).length,
  }
}

export function rosterLine(total: number, eligible: number, issues: number): string {
  return issues > 0 ? `共 ${total} 人 · 可报名 ${eligible} 人` : `共 ${total} 人`
}

export function rosterIssueLine(issues: number): string {
  return `${issues} 人不符合条件，不计入人数和费用`
}

export interface TeamFormInput {
  sessionOpen: boolean
  teamName: string
  leaderName: string
  leaderPhone: string
  eligibleCount: number
  rosterRows: number
  checking: boolean
  agreed: boolean
  seatsLeft: number | null
}

/** What still stops the form, in the order the page reads (null: ready to send). */
export function teamMissingStep(form: TeamFormInput): string | null {
  if (!form.sessionOpen) return '请先选择一个可报名的场次'
  if (!form.teamName.trim()) return '请输入团队名称'
  if ([...form.teamName.trim()].length > TEAM_NAME_MAX) return `团队名称最多 ${TEAM_NAME_MAX} 个字`
  if (!form.leaderName.trim()) return '请输入领队姓名'
  if (!isValidLeaderPhone(form.leaderPhone)) return '请输入 11 位领队手机号'
  if (form.rosterRows > MAX_TEAM_ROSTER_ROWS) return `名单最多 ${MAX_TEAM_ROSTER_ROWS} 行`
  if (form.checking) return '正在核对名单，请稍候'
  if (form.eligibleCount < MIN_TEAM_SIZE) return `团队报名至少 ${MIN_TEAM_SIZE} 人`
  if (form.eligibleCount > MAX_TEAM_SIZE) return `团队报名最多 ${MAX_TEAM_SIZE} 人`
  if (form.seatsLeft !== null && form.eligibleCount > form.seatsLeft) return `本场只剩 ${form.seatsLeft} 个名额`
  if (!form.agreed) return '请先勾选同意'
  return null
}

// ---- team order (board TeamOrder) ----

export type TeamOrderPhase = 'PENDING_PAYMENT' | 'UPCOMING' | 'ON_DAY' | 'FINISHED' | 'CANCELLED'

/** On the day: from the check-in time (or the start) until the end. */
export function teamOrderPhase(enrollment: Enrollment, now: number): TeamOrderPhase {
  if (enrollment.status === 'CANCELLED') return 'CANCELLED'
  if (enrollment.payment.status === 'PENDING_PAYMENT') return 'PENDING_PAYMENT'
  if (enrollment.status === 'FINISHED') return 'FINISHED'
  const opens = new Date(enrollment.session.checkInOpensAt ?? enrollment.session.startsAt).getTime()
  return now >= opens ? 'ON_DAY' : 'UPCOMING'
}

/** The people still on the team (cancelled ones are listed but not counted). */
export function teamMembers(enrollment: Enrollment): EnrollmentTicket[] {
  return enrollment.tickets.filter((ticket) => ticket.status !== 'CANCELLED')
}

export function signedCount(enrollment: Enrollment): number {
  return enrollment.tickets.filter((ticket) => ticket.status === 'CHECKED_IN').length
}

export function describeTeamOrderHead(enrollment: Enrollment, now: number): { title: string; sub: string; tone: 'ORANGE' | 'GRAY'; icon: 'check' | 'clock' | 'close' | 'info' } {
  const phase = teamOrderPhase(enrollment, now)
  const count = teamMembers(enrollment).length
  const signed = signedCount(enrollment)
  const startTime = formatActivityTime(enrollment.session.startsAt)
  switch (phase) {
    case 'PENDING_PAYMENT': {
      const left = enrollment.payment.payBefore ? new Date(enrollment.payment.payBefore).getTime() - now : 0
      return { title: '团队报名 · 待付款', sub: `请在 ${formatPayCountdown(left)} 内付款，超时名额自动释放`, tone: 'ORANGE', icon: 'clock' }
    }
    case 'UPCOMING':
      return { title: '报名成功 · 待参加', sub: '到场后领队逐个出示队员的码，协助签到', tone: 'ORANGE', icon: 'check' }
    case 'ON_DAY': {
      const opens = enrollment.session.checkInOpensAt ? `${formatActivityShortDateTime(enrollment.session.checkInOpensAt)} 起签到，` : ''
      return { title: `签到中 · ${signed}/${count} 已签到`, sub: `${opens}${startTime} 开始 · 领队逐个出示队员的码`, tone: 'ORANGE', icon: 'clock' }
    }
    case 'FINISHED':
      return { title: '活动已结束', sub: `${signed}/${count} 人已签到`, tone: 'GRAY', icon: 'info' }
    case 'CANCELLED':
      return { title: '团队报名已取消', sub: enrollment.payment.status === 'CLOSED' ? '没有付款，名额已释放' : '全部队员都已取消', tone: 'GRAY', icon: 'close' }
  }
}

export function teamSignSummary(enrollment: Enrollment, now: number): string {
  const phase = teamOrderPhase(enrollment, now)
  const count = teamMembers(enrollment).length
  if (phase === 'ON_DAY' || phase === 'FINISHED') return `${signedCount(enrollment)}/${count} 已签到`
  return `${count} 人 · 一人一码`
}

export function teamMemberState(ticket: EnrollmentTicket): { text: string; tone: 'DONE' | 'PENDING' | 'MUTED' } {
  switch (ticket.status) {
    case 'CHECKED_IN':
      return { text: `已签到 ${ticket.checkedInAt ? formatActivityTime(ticket.checkedInAt) : ''}`.trim(), tone: 'DONE' }
    case 'ACTIVE':
      return { text: '待签到', tone: 'PENDING' }
    case 'PENDING_PAYMENT':
      return { text: '待付款', tone: 'PENDING' }
    case 'EXPIRED':
      return { text: '未签到', tone: 'MUTED' }
    case 'CANCELLED':
      return { text: ticket.refund ? '已退款' : '已取消', tone: 'MUTED' }
  }
}

/** The rule strip: a cancellable order says until when; afterwards nothing can be changed online. */
export function teamRuleLine(enrollment: Enrollment): { text: string; open: boolean } {
  if (enrollment.cancellable) {
    return { text: `${formatActivityShortDateTime(enrollment.cancellableUntil)} 前可在线取消（整单或按人）`, open: true }
  }
  return { text: '活动已开始或已结束，不再支持在线退改', open: false }
}

export function teamRules(enrollment: Enrollment): string[] {
  const unit = enrollment.payment.unitAmountMinor
  return unit && enrollment.payment.status !== 'FREE'
    ? [
        `按人退改只退该队员的报名费 ${formatActivityAmount(unit)}，其余队员的码不受影响`,
        '退款提交即退，按原路退回领队的付款账户',
        '活动开始后不能在线退改，有问题请联系主办方',
      ]
    : [
        '按人取消只释放该队员的名额，其余队员的码不受影响',
        '活动开始后不能在线取消，有问题请联系主办方',
      ]
}
