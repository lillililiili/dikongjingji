import { formatBeijingTime, padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type { ExperienceRecord } from '../domain/experience-record'

// What the trial pages (boards SoftwareTrial, CourseTrial) and a record row say about a software
// trial or a course preview. The status comes from the server; the countdown only runs on the page
// until the next read.

export type TrialStage = 'ACTIVE' | 'ENDED' | 'EXPIRED'

export function trialStage(record: Pick<ExperienceRecord, 'status'>): TrialStage {
  if (record.status === 'ENDED') return 'ENDED'
  return record.status === 'ACTIVE' ? 'ACTIVE' : 'EXPIRED'
}

export function trialRemainingMs(record: Pick<ExperienceRecord, 'expiresAt' | 'status'>, now: number): number {
  if (record.status !== 'ACTIVE' || !record.expiresAt) return 0
  return Math.max(0, new Date(record.expiresAt).getTime() - now)
}

/** 剩余 25:00 under an hour; 剩余 6 天 / 剩余 3 小时 for a course preview that runs for days. */
export function formatTrialRemaining(milliseconds: number): string {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000))
  if (seconds < 3600) return `剩余 ${padTwo(Math.floor(seconds / 60))}:${padTwo(seconds % 60)}`
  const hours = Math.floor(seconds / 3600)
  // Whole days rounded up: a seven-day preview opened just now has 7 days left, not 6.
  return hours >= 24 ? `剩余 ${Math.ceil(seconds / 86400)} 天` : `剩余 ${hours} 小时`
}

/** 11/03 14:02 in Beijing time. */
export function formatTrialMoment(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${formatBeijingTime(iso)}`
}

/** 7 分钟, 2 小时 5 分钟, 7 天 */
export function formatTrialLength(milliseconds: number): string {
  const minutes = Math.max(1, Math.round(milliseconds / 60000))
  if (minutes < 60) return `${minutes} 分钟`
  if (minutes < 24 * 60) {
    const hours = Math.floor(minutes / 60)
    const rest = minutes % 60
    return rest > 0 ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`
  }
  return `${Math.round(minutes / (24 * 60))} 天`
}

export interface TrialSummary {
  stage: TrialStage
  // 11/03 14:27 · 到期自动结束 / 11/03 14:09 · 你主动结束; null while it runs.
  endLine: string | null
  // How long it ran (or has run so far).
  lengthText: string
}

export function summariseTrial(record: ExperienceRecord, now: number): TrialSummary {
  const stage = trialStage(record)
  const started = new Date(record.startedAt).getTime()
  if (stage === 'ENDED' && record.endedAt) {
    return {
      stage,
      endLine: `${formatTrialMoment(record.endedAt)} · 你主动结束`,
      lengthText: formatTrialLength(new Date(record.endedAt).getTime() - started),
    }
  }
  if (stage === 'EXPIRED' && record.expiresAt) {
    return {
      stage,
      endLine: `${formatTrialMoment(record.expiresAt)} · 到期自动结束`,
      lengthText: formatTrialLength(new Date(record.expiresAt).getTime() - started),
    }
  }
  return { stage, endLine: null, lengthText: formatTrialLength(now - started) }
}

/** One line of a record row (board ExpRecords). */
export function trialRecordLine(record: ExperienceRecord, now: number): string {
  const course = record.experience.type === 'COURSE_TRIAL'
  const summary = summariseTrial(record, now)
  if (summary.stage === 'ACTIVE') {
    return `${course ? '试听中' : '试用中'} · ${formatTrialRemaining(trialRemainingMs(record, now))}`
  }
  if (summary.stage === 'ENDED') return `已结束 · ${course ? '试听' : '试用'} ${summary.lengthText}`
  return '已到期'
}
