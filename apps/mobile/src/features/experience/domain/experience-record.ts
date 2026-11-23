import type { ExperienceImage, ExperienceType } from './experience'
import type { SimulationTemplate } from './simulation'

// Mirrors contracts/openapi-experience.yaml 1.4.0 (S5-4). Statuses are worked out by the server:
// a trial past its minutes reads EXPIRED without anything being written.

export interface ExperienceItemDetail {
  experienceId: string
  type: ExperienceType
  title: string
  summary: string | null
  coverImage: ExperienceImage | null
  loginRequired: boolean
  productId: string | null
  // Only a simulation has one; a simulation without one cannot be played.
  simulationTemplate: SimulationTemplate | null
  // Only software trials and course previews: how long one trial runs.
  trialMinutes: number | null
}

export type ExperienceRecordStatus = 'COMPLETED' | 'UNFINISHED' | 'ACTIVE' | 'ENDED' | 'EXPIRED'

export interface ExperienceRecordItem {
  experienceId: string
  type: ExperienceType
  title: string
  coverImage: ExperienceImage | null
  productId: string | null
  simulationTemplate: SimulationTemplate | null
}

export interface SimulationScore {
  score: number
  durationSeconds: number
  levelsCompleted: number
  levelsTotal: number
  mistakes: number
}

export interface ExperienceRecord {
  recordId: string
  experience: ExperienceRecordItem
  status: ExperienceRecordStatus
  startedAt: string
  endedAt: string | null
  expiresAt: string | null
  result: SimulationScore | null
}

export interface ExperienceRecordPage {
  total: number
  hasMore: boolean
  items: ExperienceRecord[]
}

export const EXPERIENCE_RECORD_PAGE_SIZE = 20
