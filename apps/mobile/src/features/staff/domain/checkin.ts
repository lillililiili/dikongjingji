// 核销工作台（S3-3，契约 contracts/openapi-checkin-workbench.yaml 1.0.0）。App 内“工作模式”只做扫码 / 输码核销和
// 本人核销记录（默认决定 D8）；名单和权限在 Web 运营端。

export const CHECKIN_KINDS = ['ACTIVITY', 'VENUE'] as const
export type CheckinKind = (typeof CHECKIN_KINDS)[number]

export const CHECKIN_PHASES = ['UPCOMING', 'OPEN', 'ENDED'] as const
export type CheckinPhase = (typeof CHECKIN_PHASES)[number]

export const CHECKIN_OUTCOMES = [
  'CHECKED_IN',
  'REPEATED',
  'EXPIRED',
  'OTHER_ORGANIZATION',
  'SESSION_MISMATCH',
  'CANCELLED',
  'UNPAID',
] as const
export type CheckinOutcome = (typeof CHECKIN_OUTCOMES)[number]

export const CHECKIN_METHODS = ['SCAN', 'MANUAL'] as const
export type CheckinMethod = (typeof CHECKIN_METHODS)[number]

export const CHECKIN_ACTIONS = ['RELEASE', 'REJECT', 'BLOCK'] as const
export type CheckinAction = (typeof CHECKIN_ACTIONS)[number]

/** 本机构这一天的一个场次。 */
export interface CheckinSession {
  kind: CheckinKind
  sessionId: string
  title: string
  placeName: string | null
  label: string | null
  startsAt: string
  endsAt: string | null
  checkInOpensAt: string
  checkInClosesAt: string
  phase: CheckinPhase
  expectedPeople: number
  checkedInPeople: number
}

export interface CheckinWorkbench {
  /** YYYY-MM-DD，北京时间。 */
  date: string
  /** 这个机构能核销的种类；为空时没有“工作模式”。 */
  kinds: CheckinKind[]
  sessions: CheckinSession[]
}

export interface CheckinSessionRef {
  kind: CheckinKind
  sessionId: string
  title: string
  label: string | null
  startsAt: string
}

export interface CheckinResolution {
  action: CheckinAction
  identityChecked: boolean
  note: string | null
  resolvedAt: string
  admittedSession: CheckinSessionRef | null
}

/** 操作员本人的一条核销记录：核销成功的，或被拦下的。 */
export interface CheckinRecord {
  kind: CheckinKind
  recordId: string
  outcome: CheckinOutcome
  method: CheckinMethod
  occurredAt: string
  codeTail: string
  /** 姓加两个星号；非本机构的为 null。 */
  holderName: string | null
  people: number
  /** 分；免费为 0，非本机构的为 null。 */
  amountMinor: number | null
  ticketSession: CheckinSessionRef | null
  currentSession: CheckinSessionRef | null
  firstCheckedInAt: string | null
  allowedActions: CheckinAction[]
  resolution: CheckinResolution | null
}

export interface CheckinRecordList {
  date: string
  items: CheckinRecord[]
}

export interface VerifyInput {
  code: string
  method: CheckinMethod
  session: { kind: CheckinKind; sessionId: string } | null
}

/**
 * 核销的结果：成功；被拦下（服务端已记录，带那条记录）；或者没有记录的拒绝（没有这张凭证、还没到时间、格式不对）。
 * 网络和登录问题照常抛出。
 */
export type VerifyResult =
  | { kind: 'checked-in'; record: CheckinRecord }
  | { kind: 'rejected'; code: string; record: CheckinRecord }
  | { kind: 'refused'; code: string }

export interface ResolveInput {
  action: CheckinAction
  identityChecked: boolean
  note: string
}
