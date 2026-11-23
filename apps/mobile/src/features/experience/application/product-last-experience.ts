import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type { ExperienceRecordApi } from '../data/experience-record-api'
import type { LocalSimulationResults } from '../data/local-simulation-results'
import { formatSimulationDuration } from '../domain/simulation'

import { outcomeOfLocal, outcomeOfRecord, type SimulationOutcome } from './simulation-result-view'

// Board PdFull, under the 先体验 pills: 你在 11/02 体验过“机器人避障模拟”，用时 2′36″ · 查看记录.
// The signed-in account's latest run of the product's simulation, or a guest's latest on this device.

export function describeLastSimulation(outcome: SimulationOutcome): string {
  const parts = toBeijingParts(new Date(outcome.endedAt))
  const how = outcome.levelsCompleted >= outcome.levelsTotal
    ? `用时 ${formatSimulationDuration(outcome.durationSeconds)}`
    : `停在第 ${outcome.levelsCompleted + 1} 关`
  return `你在 ${padTwo(parts.month)}/${padTwo(parts.day)} 体验过“${outcome.title}”，${how}`
}

/** Null when there is no run (or it cannot be read): the line is an extra. */
export async function loadLastSimulationNote(
  api: Pick<ExperienceRecordApi, 'list'>,
  store: Pick<LocalSimulationResults, 'all'>,
  accessToken: string | null,
  experienceId: string,
): Promise<string | null> {
  try {
    if (accessToken) {
      const record = (await api.list(accessToken, { experienceId, page: 1, size: 1 })).items[0]
      const outcome = record ? outcomeOfRecord(record) : null
      if (outcome) return describeLastSimulation(outcome)
    }
    const local = store.all().find((result) => result.experienceId === experienceId && (!accessToken || !result.recordId))
    return local ? describeLastSimulation(outcomeOfLocal(local)) : null
  } catch {
    return null
  }
}
