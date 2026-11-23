// Words and groupings of the three 工作模式 boards (StaffVerify, StaffRecords, StaffException),
// worked out from what the server returned. Times are Beijing time; nothing here reads the phone's zone.
import { padTwo, toBeijingDate, toBeijingParts } from '../../../shared/time/beijing-time'

import type {
  CheckinAction,
  CheckinKind,
  CheckinOutcome,
  CheckinPhase,
  CheckinRecord,
  CheckinSession,
  CheckinSessionRef,
} from '../domain/checkin'

export const sessionKey = (session: { kind: CheckinKind; sessionId: string }): string =>
  `${session.kind}:${session.sessionId}`

// ---- 时间 ----

/** 14:05 */
export function clock(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

/** 11/03 */
export function monthDay(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

/** "11/03 周二" for a YYYY-MM-DD in Beijing time. */
export function dayTitle(date: string): string {
  const [year, month, day] = date.split('-').map(Number)
  if (!year || !month || !day) return date
  // Noon in Beijing, so the weekday is the same in every zone.
  const parts = toBeijingParts(new Date(Date.UTC(year, month - 1, day, 4)))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${parts.weekday}`
}

/** 14:00, or 11/02 14:00 when the time is not on `date`. */
export function timeOnDay(iso: string, date: string): string {
  return toBeijingDate(new Date(iso)) === date ? clock(iso) : `${monthDay(iso)} ${clock(iso)}`
}

// ---- 金额 ----

/** "已支付 ¥39" / "免费"; nothing when the amount is not known (another organization's code). */
export function paidText(amountMinor: number | null): string {
  if (amountMinor === null) return ''
  if (amountMinor <= 0) return '免费'
  const yuan = amountMinor / 100
  return `已支付 ¥${Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2)}`
}

// ---- 场次 ----

export interface PhaseTone {
  status: string
  tagBg: string
  tagColor: string
  numberColor: string
  barColor: string
}

// The board's `tone` table.
export const PHASE_TONES: Record<CheckinPhase, PhaseTone> = {
  ENDED: { status: '已结束', tagBg: '#eef2f8', tagColor: '#738096', numberColor: '#11804a', barColor: '#159a70' },
  OPEN: { status: '进行中', tagBg: '#fff0e3', tagColor: '#ba470b', numberColor: '#ff6a00', barColor: '#ff6a00' },
  UPCOMING: { status: '未开始', tagBg: '#e6f0ff', tagColor: '#1d63c4', numberColor: '#536178', barColor: '#2f80ed' },
}

/** The session the workbench starts on: the one checking in now, else the next, else the last. */
export function pickDefaultSession(sessions: CheckinSession[], previousKey: string | null): string | null {
  if (previousKey && sessions.some((session) => sessionKey(session) === previousKey)) return previousKey
  const chosen = sessions.find((session) => session.phase === 'OPEN')
    ?? sessions.find((session) => session.phase === 'UPCOMING')
    ?? sessions[sessions.length - 1]
  return chosen ? sessionKey(chosen) : null
}

export interface SessionRow {
  key: string
  time: string
  name: string
  sub: string
  done: number
  total: number
  unit: string
  percent: string
  tone: PhaseTone
}

export function toSessionRow(session: CheckinSession): SessionRow {
  const tone = PHASE_TONES[session.phase]
  const time = clock(session.startsAt)
  const percent = session.expectedPeople > 0
    ? Math.min(100, Math.round((session.checkedInPeople / session.expectedPeople) * 1000) / 10)
    : 0
  return {
    key: sessionKey(session),
    time,
    name: session.title,
    sub: `${time} 场 · ${tone.status}`,
    done: session.checkedInPeople,
    total: session.expectedPeople,
    unit: session.phase === 'ENDED' ? '到场 / 应到' : '已核销 / 应到',
    percent: `${percent}%`,
    tone,
  }
}

/** The line under the badge (board: 仅能核销本场馆场次，服务端校验场馆与场次归属). */
export function scopeHint(kinds: CheckinKind[]): string {
  if (kinds.includes('ACTIVITY') && kinds.includes('VENUE')) return '仅能核销本机构的活动和场馆场次，服务端校验归属'
  if (kinds.includes('ACTIVITY')) return '仅能核销本机构主办或承办的活动场次，服务端校验活动与场次归属'
  return '仅能核销本场馆场次，服务端校验场馆与场次归属'
}

// ---- 记录 ----

const OUTCOME_LABELS: Record<Exclude<CheckinOutcome, 'CHECKED_IN'>, string> = {
  SESSION_MISMATCH: '场次不符',
  REPEATED: '重复核销',
  EXPIRED: '凭证已过期',
  OTHER_ORGANIZATION: '非本机构',
  CANCELLED: '凭证已取消',
  UNPAID: '凭证未付款',
}

export const isException = (record: CheckinRecord): boolean => record.outcome !== 'CHECKED_IN'

/** Checked in: either at the door, or let in by hand from an exception. */
export const isAdmitted = (record: CheckinRecord): boolean =>
  record.outcome === 'CHECKED_IN' || record.resolution?.action === 'RELEASE'

export function outcomeLabel(outcome: CheckinOutcome): string {
  return outcome === 'CHECKED_IN' ? '核销成功' : OUTCOME_LABELS[outcome]
}

export const methodLabel = (record: CheckinRecord): string => (record.method === 'SCAN' ? '扫码' : '手动输入凭证号')

/** "李** · 1 人"; another organization's code has no holder. */
export function holderLine(record: CheckinRecord): string {
  return `${record.holderName ?? '持有人未知'} · ${record.people} 人`
}

/** The session the record belongs to on the board: where it was let in, else the one being checked. */
export function recordSession(record: CheckinRecord): CheckinSessionRef | null {
  return record.resolution?.admittedSession ?? record.currentSession ?? record.ticketSession
}

/** "机器人避障闯关 14:00 场" */
export function sessionName(ref: CheckinSessionRef | null, date: string): string {
  return ref ? `${ref.title} ${timeOnDay(ref.startsAt, date)} 场` : '未选场次'
}

/** The line of a failed check-in (board: 场次不符：凭证为 15:30 机器人闯关场). */
export function failureLine(record: CheckinRecord, date: string): string {
  const own = record.ticketSession
  switch (record.outcome) {
    case 'SESSION_MISMATCH':
      return own ? `场次不符：凭证为 ${timeOnDay(own.startsAt, date)} ${own.title}场` : '场次不符'
    case 'EXPIRED':
      return own ? `凭证已过期：凭证为 ${monthDay(own.startsAt)} ${clock(own.startsAt)} ${own.title}场` : '凭证已过期'
    case 'REPEATED':
      return record.firstCheckedInAt
        ? `重复核销：该凭证已于 ${timeOnDay(record.firstCheckedInAt, date)} 核销`
        : '重复核销：该凭证已核销过'
    case 'OTHER_ORGANIZATION':
      return '非本机构：这张凭证不属于本机构的活动或场馆'
    case 'CANCELLED':
      return '凭证已取消：不能核销'
    case 'UNPAID':
      return '凭证未付款：报名还没付款'
    case 'CHECKED_IN':
      return '核销成功'
  }
}

/** A refusal the server did not record. */
export function refusalLine(code: string): string {
  if (code === 'COMMON_NOT_FOUND') return '没有这张凭证，请核对凭证号'
  if (code === 'CHECKIN_NOT_OPEN') return '还没到这一场的核销时间'
  if (code === 'COMMON_INVALID_ARGUMENT') return '凭证号格式不对，请核对后重新输入'
  return '核销没有成功，请稍后重试'
}

export interface RecordRow {
  recordId: string
  kind: CheckinKind
  title: string
  sub: string
  bad: boolean
  tag: string
  tagBg: string
  tagColor: string
  dotBg: string
  dotPath: string
}

const OK_DOT = 'M5 12l5 5 9-10'
const BAD_DOT = 'M12 6v8M12 18h.01'

export function toRecordRow(record: CheckinRecord): RecordRow {
  const bad = isException(record)
  const admitted = isAdmitted(record)
  return {
    recordId: record.recordId,
    kind: record.kind,
    title: holderLine(record),
    sub: `${clock(record.occurredAt)} · ${bad ? outcomeLabel(record.outcome) : methodLabel(record)}`,
    bad,
    tag: admitted ? (bad ? '已放行' : '已核销') : '未核销',
    tagBg: admitted ? '#e2f6ed' : '#fff0e3',
    tagColor: admitted ? '#11804a' : '#ba470b',
    dotBg: admitted ? '#159a70' : '#ff6a00',
    dotPath: admitted ? OK_DOT : BAD_DOT,
  }
}

/** 最近核销 on the workbench: the latest three that were let in. */
export function recentAdmissions(records: CheckinRecord[], limit = 3): CheckinRecord[] {
  return records.filter(isAdmitted).slice(0, limit)
}

// ---- 记录页的分组与筛选 ----

export type RecordTab = 'all' | 'ok' | 'bad'

export interface RecordFilter {
  tab: RecordTab
  /** sessionKey, or '' for every session. */
  session: string
  keyword: string
}

export interface RecordGroup {
  key: string
  title: string
  tone: PhaseTone | null
  done: number | null
  total: number | null
  rows: RecordRow[]
  /** No record yet and nothing is being filtered: the board writes 还没有核销记录. */
  empty: boolean
}

export function matchesFilter(record: CheckinRecord, filter: RecordFilter): boolean {
  if (filter.tab === 'ok' && isException(record)) return false
  if (filter.tab === 'bad' && !isException(record)) return false
  if (filter.session) {
    const ref = recordSession(record)
    if (!ref || sessionKey(ref) !== filter.session) return false
  }
  const keyword = filter.keyword.trim()
  if (!keyword) return true
  return (record.holderName ?? '').includes(keyword) || record.codeTail.toUpperCase().includes(keyword.toUpperCase())
}

/** The board's groups: one card per session of the day, then any other session a record names. */
export function groupRecords(
  records: CheckinRecord[],
  sessions: CheckinSession[],
  filter: RecordFilter,
  date: string,
): RecordGroup[] {
  const filtering = filter.tab !== 'all' || Boolean(filter.keyword.trim())
  const groups = new Map<string, RecordGroup>()
  for (const session of sessions) {
    const key = sessionKey(session)
    if (filter.session && filter.session !== key) continue
    const tone = PHASE_TONES[session.phase]
    groups.set(key, {
      key,
      title: `${clock(session.startsAt)} ${session.title}`,
      tone,
      done: session.checkedInPeople,
      total: session.expectedPeople,
      rows: [],
      empty: false,
    })
  }
  const loose: RecordGroup = { key: '', title: '未选场次', tone: null, done: null, total: null, rows: [], empty: false }
  for (const record of records) {
    if (!matchesFilter(record, filter)) continue
    const ref = recordSession(record)
    const key = ref ? sessionKey(ref) : ''
    let group = key ? groups.get(key) : loose
    if (!group && ref) {
      group = { key, title: `${timeOnDay(ref.startsAt, date)} ${ref.title}`, tone: null, done: null, total: null, rows: [], empty: false }
      groups.set(key, group)
    }
    ;(group ?? loose).rows.push(toRecordRow(record))
  }
  const result = [...groups.values()]
  if (loose.rows.length) result.push(loose)
  return result
    .map((group) => ({ ...group, empty: group.rows.length === 0 && !filtering }))
    .filter((group) => group.rows.length > 0 || group.empty)
}

export function tabCounts(records: CheckinRecord[]): Record<RecordTab, number> {
  const bad = records.filter(isException).length
  return { all: records.length, ok: records.length - bad, bad }
}

/** 今日已核销 10 / 16 人: the organization's people on the day's sessions. */
export function daySummary(sessions: CheckinSession[]): { done: number; total: number } {
  return sessions.reduce(
    (sum, session) => ({ done: sum.done + session.checkedInPeople, total: sum.total + session.expectedPeople }),
    { done: 0, total: 0 },
  )
}

/** The chips under the search box: 全部场次, then each session ("14:00 场", with the name when two share a time). */
export function sessionChips(sessions: CheckinSession[]): { key: string; label: string }[] {
  const times = sessions.map((session) => clock(session.startsAt))
  return [
    { key: '', label: '全部场次' },
    ...sessions.map((session, index) => {
      const time = times[index]
      const shared = times.filter((other) => other === time).length > 1
      return { key: sessionKey(session), label: shared ? `${time} ${session.title}` : `${time} 场` }
    }),
  ]
}

// ---- 异常处理 ----

export interface ExceptionOption {
  action: CheckinAction
  title: string
  sub: string
  /** 放行前要先勾选已人工核验身份。 */
  needsIdentity: boolean
}

export interface ExceptionCopy {
  title: string
  sub: string
  fact: string
  blocked: boolean
  notice: string
}

export function exceptionCopy(record: CheckinRecord, date: string): ExceptionCopy {
  const own = record.ticketSession
  const current = record.currentSession
  const ownTime = own ? timeOnDay(own.startsAt, date) : ''
  const blocked = record.outcome === 'REPEATED' || record.outcome === 'OTHER_ORGANIZATION'
    || record.outcome === 'CANCELLED' || record.outcome === 'UNPAID'
  switch (record.outcome) {
    case 'SESSION_MISMATCH':
      return {
        title: '场次不符，需人工核验',
        sub: '凭证有效，但不属于当前核销场次',
        fact: own && current
          ? `凭证为 ${ownTime} 场，当前核销 ${timeOnDay(current.startsAt, date)} 场`
          : '凭证不属于当前核销场次',
        blocked,
        notice: '',
      }
    case 'EXPIRED':
      return {
        title: '凭证已过期，需人工核验',
        sub: '凭证场次已过，不能直接核销',
        fact: own ? `凭证为 ${monthDay(own.startsAt)} 场，已过期` : '凭证场次已过',
        blocked,
        notice: '',
      }
    case 'REPEATED':
      return {
        title: '重复核销，已拦截',
        sub: '同一凭证只能核销一次',
        fact: record.firstCheckedInAt ? `该凭证已于 ${timeOnDay(record.firstCheckedInAt, date)} 核销` : '该凭证已核销过',
        blocked,
        notice: '重复核销一律拦截，不允许放行',
      }
    case 'OTHER_ORGANIZATION':
      return {
        title: '非本机构凭证，已拦截',
        sub: '只能核销本机构的活动和场馆凭证',
        fact: '这张凭证不属于本机构',
        blocked,
        notice: '非本机构凭证一律拦截，不允许放行',
      }
    case 'CANCELLED':
      return {
        title: '凭证已取消，已拦截',
        sub: '已取消的凭证不能核销',
        fact: '这张凭证已取消',
        blocked,
        notice: '已取消的凭证一律拦截，不允许放行',
      }
    case 'UNPAID':
      return {
        title: '凭证未付款，已拦截',
        sub: '还没付款的报名不能核销',
        fact: '这张凭证的报名还没付款',
        blocked,
        notice: '未付款的凭证一律拦截，不允许放行',
      }
    case 'CHECKED_IN':
      return { title: '核销成功', sub: '', fact: '', blocked: false, notice: '' }
  }
}

/** The ways to settle a 场次不符 or 过期 that the server still allows (board `options`). */
export function exceptionOptions(record: CheckinRecord, date: string): ExceptionOption[] {
  const ownTime = record.ticketSession ? timeOnDay(record.ticketSession.startsAt, date) : ''
  return record.allowedActions.flatMap((action): ExceptionOption[] => {
    if (action === 'BLOCK') return []
    if (record.outcome === 'SESSION_MISMATCH') {
      return action === 'RELEASE'
        ? [{ action, title: '本场有余位，改签并核销', sub: `释放 ${ownTime} 名额，记录改签原因`, needsIdentity: true }]
        : [{ action, title: '不予核销', sub: `提示按原场次 ${ownTime} 参加`, needsIdentity: false }]
    }
    return action === 'RELEASE'
      ? [{ action, title: '补发本场凭证并核销', sub: '核验身份后补发，记录补发原因', needsIdentity: true }]
      : [{ action, title: '不予核销', sub: '提示凭证已过期，按退改规则处理', needsIdentity: false }]
  })
}

/** 处理 / 原因 of a settled exception, written the way the board records them. */
export function resolutionLines(record: CheckinRecord, date: string): { act: string; reason: string } | null {
  const resolution = record.resolution
  if (!resolution) return null
  const own = record.ticketSession
  if (resolution.action === 'BLOCK') {
    const reason = record.outcome === 'REPEATED'
      ? `重复核销（该凭证已于 ${record.firstCheckedInAt ? timeOnDay(record.firstCheckedInAt, date) : '之前'} 核销）`
      : record.outcome === 'OTHER_ORGANIZATION' ? '非本机构凭证'
        : record.outcome === 'CANCELLED' ? '凭证已取消' : '报名还没付款'
    return { act: '拦截，不予入场', reason }
  }
  if (record.outcome === 'SESSION_MISMATCH') {
    return resolution.action === 'RELEASE'
      ? { act: '改签并核销（登记放行）', reason: '本场有余位，现场改签' }
      : { act: '不予核销（拒绝入场）', reason: `凭证属于 ${own ? timeOnDay(own.startsAt, date) : '其他'} 场，按原场次参加` }
  }
  return resolution.action === 'RELEASE'
    ? { act: '补发凭证并核销（登记放行）', reason: '凭证已过期，现场补发本场凭证' }
    : { act: '不予核销（拒绝入场）', reason: `凭证已过期（${own ? monthDay(own.startsAt) : '原'} 场）` }
}

/** The chips of the exception page: what still waits for a decision, plus the one being looked at. */
export function exceptionChips(records: CheckinRecord[], selectedId: string | null): { record: CheckinRecord; label: string }[] {
  const shown = records.filter((record) =>
    isException(record) && (record.allowedActions.length > 0 || record.recordId === selectedId))
  return shown.map((record) => {
    const label = outcomeLabel(record.outcome)
    const shared = shown.filter((other) => other.outcome === record.outcome).length > 1
    return { record, label: shared ? `${label} ${record.codeTail}` : label }
  })
}

export const NOTE_LIMIT = 200
