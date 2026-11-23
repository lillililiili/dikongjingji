import {
  CHECKIN_ACTIONS,
  CHECKIN_KINDS,
  CHECKIN_METHODS,
  CHECKIN_OUTCOMES,
  CHECKIN_PHASES,
  type CheckinAction,
  type CheckinKind,
  type CheckinRecord,
  type CheckinRecordList,
  type CheckinResolution,
  type CheckinSession,
  type CheckinSessionRef,
  type CheckinWorkbench,
  type ResolveInput,
  type VerifyInput,
  type VerifyResult,
} from '../domain/checkin'

type CheckinRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

/** 请求错误里工作台要看的部分（ApiRequestError 有这几项）。 */
interface RequestErrorShape {
  status: number
  code: string | null
  details: Record<string, unknown> | null
}

// The DTOs as they arrive. Fields are read defensively: a row the page cannot draw is dropped.
interface SessionDto {
  kind?: string
  sessionId?: string
  title?: string
  placeName?: string | null
  label?: string | null
  startsAt?: string
  endsAt?: string | null
  checkInOpensAt?: string
  checkInClosesAt?: string
  phase?: string
  expectedPeople?: number
  checkedInPeople?: number
}
interface WorkbenchDto { date?: string; kinds?: string[]; sessions?: SessionDto[] }
interface SessionRefDto { kind?: string; sessionId?: string; title?: string; label?: string | null; startsAt?: string }
interface ResolutionDto {
  action?: string
  identityChecked?: boolean
  note?: string | null
  resolvedAt?: string
  admittedSession?: SessionRefDto | null
}
export interface RecordDto {
  kind?: string
  recordId?: string
  outcome?: string
  method?: string
  occurredAt?: string
  codeTail?: string
  holderName?: string | null
  people?: number
  amountMinor?: number | null
  ticketSession?: SessionRefDto | null
  currentSession?: SessionRefDto | null
  firstCheckedInAt?: string | null
  allowedActions?: string[]
  resolution?: ResolutionDto | null
}
interface RecordListDto { date?: string; items?: RecordDto[] }

const oneOf = <T extends string>(values: readonly T[], value: unknown): T | null =>
  typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : null

function toSession(dto: SessionDto): CheckinSession | null {
  const kind = oneOf(CHECKIN_KINDS, dto.kind)
  const phase = oneOf(CHECKIN_PHASES, dto.phase)
  if (!kind || !phase || !dto.sessionId || !dto.startsAt || !dto.checkInOpensAt || !dto.checkInClosesAt) return null
  return {
    kind,
    sessionId: dto.sessionId,
    title: dto.title?.trim() || '场次',
    placeName: dto.placeName?.trim() || null,
    label: dto.label?.trim() || null,
    startsAt: dto.startsAt,
    endsAt: dto.endsAt ?? null,
    checkInOpensAt: dto.checkInOpensAt,
    checkInClosesAt: dto.checkInClosesAt,
    phase,
    expectedPeople: Math.max(0, dto.expectedPeople ?? 0),
    checkedInPeople: Math.max(0, dto.checkedInPeople ?? 0),
  }
}

function toRef(dto: SessionRefDto | null | undefined): CheckinSessionRef | null {
  const kind = oneOf(CHECKIN_KINDS, dto?.kind)
  if (!dto || !kind || !dto.sessionId || !dto.startsAt) return null
  return { kind, sessionId: dto.sessionId, title: dto.title?.trim() || '场次', label: dto.label?.trim() || null, startsAt: dto.startsAt }
}

function toResolution(dto: ResolutionDto | null | undefined): CheckinResolution | null {
  const action = oneOf(CHECKIN_ACTIONS, dto?.action)
  if (!dto || !action || !dto.resolvedAt) return null
  return {
    action,
    identityChecked: dto.identityChecked === true,
    note: dto.note?.trim() || null,
    resolvedAt: dto.resolvedAt,
    admittedSession: toRef(dto.admittedSession),
  }
}

export function toCheckinRecord(dto: RecordDto | null | undefined): CheckinRecord | null {
  if (!dto) return null
  const kind = oneOf(CHECKIN_KINDS, dto.kind)
  const outcome = oneOf(CHECKIN_OUTCOMES, dto.outcome)
  const method = oneOf(CHECKIN_METHODS, dto.method)
  if (!kind || !outcome || !method || !dto.recordId || !dto.occurredAt) return null
  return {
    kind,
    recordId: dto.recordId,
    outcome,
    method,
    occurredAt: dto.occurredAt,
    codeTail: dto.codeTail ?? '',
    holderName: dto.holderName?.trim() || null,
    people: Math.max(1, dto.people ?? 1),
    amountMinor: typeof dto.amountMinor === 'number' ? dto.amountMinor : null,
    ticketSession: toRef(dto.ticketSession),
    currentSession: toRef(dto.currentSession),
    firstCheckedInAt: dto.firstCheckedInAt ?? null,
    allowedActions: (dto.allowedActions ?? []).flatMap((value) => {
      const action = oneOf(CHECKIN_ACTIONS, value)
      return action ? [action] : []
    }),
    resolution: toResolution(dto.resolution),
  }
}

function isRequestError(error: unknown): error is RequestErrorShape {
  return typeof error === 'object' && error !== null && 'status' in error && 'code' in error
}

export interface CheckinApi {
  workbench(accessToken: string, date?: string | null): Promise<CheckinWorkbench>
  records(accessToken: string, date?: string | null): Promise<CheckinRecordList>
  record(accessToken: string, kind: CheckinKind, recordId: string): Promise<CheckinRecord>
  verify(accessToken: string, input: VerifyInput): Promise<VerifyResult>
  resolve(accessToken: string, kind: CheckinKind, recordId: string, input: ResolveInput): Promise<CheckinRecord>
}

// Every call carries the access token it was given; the caller decides what a 401 or 403 means.
export function createCheckinApi(request: CheckinRequest): CheckinApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const dateQuery = (date?: string | null) => (date ? `?date=${encodeURIComponent(date)}` : '')
  const recordPath = (kind: CheckinKind, recordId: string) =>
    `/api/v1/checkin-workbench/records/${kind}/${encodeURIComponent(recordId)}`
  const required = (dto: RecordDto): CheckinRecord => {
    const record = toCheckinRecord(dto)
    if (!record) throw new Error('核销记录无效')
    return record
  }
  return {
    async workbench(accessToken, date) {
      const dto = await request<WorkbenchDto>({
        url: `/api/v1/checkin-workbench${dateQuery(date)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        date: dto.date ?? date ?? '',
        kinds: (dto.kinds ?? []).flatMap((value) => {
          const kind = oneOf(CHECKIN_KINDS, value)
          return kind ? [kind] : []
        }),
        sessions: (dto.sessions ?? []).flatMap((item) => {
          const session = toSession(item)
          return session ? [session] : []
        }),
      }
    },
    async records(accessToken, date) {
      const dto = await request<RecordListDto>({
        url: `/api/v1/checkin-workbench/records${dateQuery(date)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        date: dto.date ?? date ?? '',
        items: (dto.items ?? []).flatMap((item) => {
          const record = toCheckinRecord(item)
          return record ? [record] : []
        }),
      }
    },
    async record(accessToken, kind, recordId) {
      return required(await request<RecordDto>({ url: recordPath(kind, recordId), method: 'GET', headers: auth(accessToken) }))
    },
    async verify(accessToken, input) {
      try {
        const dto = await request<RecordDto>({
          url: '/api/v1/checkin-workbench/verifications',
          method: 'POST',
          headers: auth(accessToken),
          data: {
            code: input.code,
            method: input.method,
            ...(input.session ? { sessionKind: input.session.kind, sessionId: input.session.sessionId } : {}),
          },
        })
        return { kind: 'checked-in', record: required(dto) }
      } catch (error) {
        if (!isRequestError(error) || !error.code || (error.status !== 404 && error.status !== 409 && error.status !== 400)) {
          throw error
        }
        const record = toCheckinRecord(error.details?.record as RecordDto | undefined)
        if (error.status === 409 && error.code.startsWith('CHECKIN_') && record) {
          return { kind: 'rejected', code: error.code, record }
        }
        return { kind: 'refused', code: error.code }
      }
    },
    async resolve(accessToken, kind, recordId, input) {
      const note = input.note.trim()
      return required(await request<RecordDto>({
        url: `${recordPath(kind, recordId)}/resolution`,
        method: 'POST',
        headers: auth(accessToken),
        data: { action: input.action, identityChecked: input.identityChecked, ...(note ? { note } : {}) },
      }))
    },
  }
}

export type { CheckinAction }
