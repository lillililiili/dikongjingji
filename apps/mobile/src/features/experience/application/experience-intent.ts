import type { ExperienceType } from '../domain/experience'
import {
  buildCourseTrialUrl,
  buildSimulationUrl,
  buildSoftwareTrialUrl,
} from '../navigation/experience-routes'

export type ExperienceIntent =
  | { type: 'OPEN_EXPERIENCE'; experienceId: string; experienceType: ExperienceType }

export type ExperienceIntentResolution =
  | { action: 'NAVIGATE'; url: string }
  | { action: 'HIDE' }

const PAGES: Record<ExperienceType, (experienceId: string) => string> = {
  SIMULATION: buildSimulationUrl,
  RECORDED_DEMO: id => '/pages/experience/recorded?experienceId=' + encodeURIComponent(id),
  SOFTWARE_TRIAL: (experienceId) => buildSoftwareTrialUrl(experienceId),
  COURSE_TRIAL: (experienceId) => buildCourseTrialUrl(experienceId),
}

export function resolveExperienceIntent(
  intent: ExperienceIntent,
  previewEnabled: boolean,
): ExperienceIntentResolution {
  if (!previewEnabled) return { action: 'HIDE' }
  const page = PAGES[intent.experienceType]
  return { action: 'NAVIGATE', url: page(intent.experienceId) }
}
