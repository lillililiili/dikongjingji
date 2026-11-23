import type { ExperienceRecord } from '../domain/experience-record'
import {
  formatSimulationDuration,
  simulationScore,
  type SimulationTemplate,
} from '../domain/simulation'
import type { LocalSimulationResult } from '../data/local-simulation-results'

// What the result page (board ExpResult) and a record row (board ExpRecords) say about one run.

export interface SimulationOutcome {
  experienceId: string
  title: string
  template: SimulationTemplate
  productId: string | null
  levelsCompleted: number
  levelsTotal: number
  mistakes: number
  durationSeconds: number
  score: number
  endedAt: string
  // Only kept on this device (not signed in, or not sent yet).
  guest: boolean
}

export function outcomeOfRecord(record: ExperienceRecord): SimulationOutcome | null {
  if (!record.result || !record.experience.simulationTemplate) return null
  return {
    experienceId: record.experience.experienceId,
    title: record.experience.title,
    template: record.experience.simulationTemplate,
    productId: record.experience.productId,
    levelsCompleted: record.result.levelsCompleted,
    levelsTotal: record.result.levelsTotal,
    mistakes: record.result.mistakes,
    durationSeconds: record.result.durationSeconds,
    score: record.result.score,
    endedAt: record.endedAt ?? record.startedAt,
    guest: false,
  }
}

export function outcomeOfLocal(result: LocalSimulationResult): SimulationOutcome {
  return {
    experienceId: result.experienceId,
    title: result.title,
    template: result.template,
    productId: result.productId,
    levelsCompleted: result.levelsCompleted,
    levelsTotal: result.levelsTotal,
    mistakes: result.mistakes,
    durationSeconds: result.durationSeconds,
    // The server scores signed-in runs; the same rule here for a guest.
    score: simulationScore(result.levelsCompleted, result.levelsTotal, result.mistakes),
    endedAt: result.endedAt,
    guest: true,
  }
}

export type StatTone = 'PLAIN' | 'GOOD' | 'WARN'

export interface ResultStat {
  value: string
  label: string
  tone: StatTone
}

export interface SimulationResultView {
  completed: boolean
  heroTitle: string
  heroSub: string
  stats: ResultStat[]
}

/** Board ExpResult: 挑战完成 / 还差一点, and 用时 · 关卡 · 碰撞 (or 失误) in one row. */
export function describeSimulationOutcome(outcome: SimulationOutcome): SimulationResultView {
  const completed = outcome.levelsCompleted >= outcome.levelsTotal
  const robot = outcome.template === 'ROBOT_OBSTACLE'
  const heroSub = outcome.guest
    ? '记录只保存在本机，登录后可归档到账户'
    : completed
      ? `得分 ${outcome.score} 分`
      : `得分 ${outcome.score} 分 · 停在第 ${outcome.levelsCompleted + 1} 关，可以再试一次`
  return {
    completed,
    heroTitle: completed ? '挑战完成' : '还差一点',
    heroSub,
    stats: [
      { value: formatSimulationDuration(outcome.durationSeconds), label: '用时', tone: 'PLAIN' },
      {
        value: `${outcome.levelsCompleted}/${outcome.levelsTotal}`,
        label: robot ? '绕过障碍' : '完成关卡',
        tone: completed ? 'GOOD' : 'WARN',
      },
      {
        value: `${outcome.mistakes} 次`,
        label: robot ? '轻微碰撞' : '操作失误',
        tone: outcome.mistakes > 0 ? 'WARN' : 'GOOD',
      },
    ],
  }
}

/** One line of a record row (board ExpRecords): 挑战完成 · 用时 2′36″ · 碰撞 1 次 / 未完成 · 停在第 2 关. */
export function summariseSimulationOutcome(outcome: SimulationOutcome): string {
  const robot = outcome.template === 'ROBOT_OBSTACLE'
  if (outcome.levelsCompleted < outcome.levelsTotal) return `未完成 · 停在第 ${outcome.levelsCompleted + 1} 关`
  return [
    '挑战完成',
    `用时 ${formatSimulationDuration(outcome.durationSeconds)}`,
    `${robot ? '碰撞' : '失误'} ${outcome.mistakes} 次`,
  ].join(' · ')
}
