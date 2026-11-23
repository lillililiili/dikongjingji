import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'
import { charCount } from '@/core/ui/format'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-activity.yaml 1.4.0 的机构工作台“活动结果”（S5-3，ActivityResultWorkbenchController）：
// 本机构主办或承办的活动最近 180 天已经开始的场次、一场的名单、给已签到的人录入或修改结果。
// 名单里的姓名只给姓、凭证号只给后 4 位；机构只从令牌里取。第一次录入时后端给报名的账号发站内信“结果已发布”。

registerErrorMessages({
  ACT_RESULT_NOT_ALLOWED: '这位还没签到，不能录结果',
  ACT_RESULT_VERSION_CONFLICT: '结果刚被别人改过，请刷新名单后再改',
})

export type ResultCompletion = 'COMPLETED' | 'INCOMPLETE'

export interface ResultSession {
  sessionId: string
  activityId: string
  activityTitle: string
  label: string | null
  startsAt: string
  endsAt: string | null
  enrolledPeople: number
  checkedInPeople: number
  recordedPeople: number
}

export interface TicketResult {
  completion: ResultCompletion
  scoreText: string | null
  certificateNote: string | null
  recordedAt: string
}

export interface ResultRosterEntry {
  ticketId: string
  /** 只给姓，如“王**”。 */
  holderName: string | null
  age: number | null
  /** 凭证号后 4 位。 */
  codeTail: string | null
  checkedIn: boolean
  checkedInAt: string | null
  teamName: string | null
  result: TicketResult | null
  /** 修改时作为 expectedVersion 带回去；还没录时为 null。 */
  resultVersion: number | null
}

export interface ResultRoster {
  session: ResultSession
  /** 场次开始了才能录结果。 */
  started: boolean
  entries: ResultRosterEntry[]
}

export interface RecordResultRequest {
  completion: ResultCompletion
  scoreText: string | null
  certificateNote: string | null
  expectedVersion: number | null
}

export const COMPLETION_LABELS: Record<ResultCompletion, string> = {
  COMPLETED: '完成',
  INCOMPLETE: '未完成',
}

export const RESULT_LIMITS = { score: 40, certificate: 100 } as const

export function sessionLabel(session: Pick<ResultSession, 'label'>): string {
  return session.label ?? '—'
}

export interface ResultForm {
  completion: ResultCompletion
  scoreText: string
  certificateNote: string
}

/** 修改时用现在的结果预填；第一次录入默认“完成”。 */
export function initialResultForm(entry: Pick<ResultRosterEntry, 'result'>): ResultForm {
  return {
    completion: entry.result?.completion ?? 'COMPLETED',
    scoreText: entry.result?.scoreText ?? '',
    certificateNote: entry.result?.certificateNote ?? '',
  }
}

export type BuildResult<T> = { ok: true, request: T } | { ok: false, message: string }

const CONTROL = /[\u0000-\u001f\u007f]/

function optional(label: string, value: string, max: number): string | null {
  const text = value.trim()
  if (charCount(text) > max) return `${label}最多 ${max} 个字`
  if (CONTROL.test(text)) return `${label}里不能有换行或特殊字符`
  return null
}

/** 提交前按契约检查；空的成绩、证书说明交 null。修改带读到的 resultVersion。 */
export function buildResultRequest(
  form: ResultForm,
  entry: Pick<ResultRosterEntry, 'resultVersion'>,
): BuildResult<RecordResultRequest> {
  if (form.completion !== 'COMPLETED' && form.completion !== 'INCOMPLETE') {
    return { ok: false, message: '请选择完成情况' }
  }
  const problem = optional('成绩', form.scoreText, RESULT_LIMITS.score)
    ?? optional('证书说明', form.certificateNote, RESULT_LIMITS.certificate)
  if (problem) return { ok: false, message: problem }
  const score = form.scoreText.trim()
  const certificate = form.certificateNote.trim()
  return {
    ok: true,
    request: {
      completion: form.completion,
      scoreText: score || null,
      certificateNote: certificate || null,
      expectedVersion: entry.resultVersion,
    },
  }
}

export function createActivityResultsApi(http: ApiClient) {
  return {
    sessions(page: number, size: number): Promise<Page<ResultSession>> {
      return http.get<Page<ResultSession>>('/api/v1/provider/activity-sessions', { page, size })
    },
    roster(sessionId: string): Promise<ResultRoster> {
      return http.get<ResultRoster>(`/api/v1/provider/activity-sessions/${encodeURIComponent(sessionId)}/roster`)
    },
    record(ticketId: string, request: RecordResultRequest): Promise<ResultRosterEntry> {
      return http.put<ResultRosterEntry>(`/api/v1/provider/activity-tickets/${encodeURIComponent(ticketId)}/result`,
        request)
    },
  }
}

export type ActivityResultsApi = ReturnType<typeof createActivityResultsApi>
