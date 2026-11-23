import type { ExperienceType } from '../domain/experience'
import {
  buildCourseTrialUrl,
  buildSimulationUrl,
  buildSoftwareTrialUrl,
} from '../navigation/experience-routes'

// Opening an online experience item. Simulations, software trials and course previews have their
// pages since S5-4; recorded demos are not built yet and go through this typed gate instead of
// ad-hoc toasts. Venue booking left the gate in E10 (plan §21).
export type ExperienceIntent =
  | { type: 'OPEN_EXPERIENCE'; experienceId: string; experienceType: ExperienceType }

export type ExperienceIntentResolution =
  | { action: 'NAVIGATE'; url: string }
  | { action: 'SHOW_NOTICE'; message: string }
  | { action: 'HIDE' }

const PAGES: Partial<Record<ExperienceType, (experienceId: string) => string>> = {
  SIMULATION: buildSimulationUrl,
  SOFTWARE_TRIAL: (experienceId) => buildSoftwareTrialUrl(experienceId),
  COURSE_TRIAL: (experienceId) => buildCourseTrialUrl(experienceId),
}

const NOTICES: Record<ExperienceType, string> = {
  SIMULATION: '虚拟仿真即将开放',
  RECORDED_DEMO: '录播演示即将开放',
  SOFTWARE_TRIAL: '软件试用即将开放',
  COURSE_TRIAL: '课程试听即将开放',
}

// Preview builds open the page (or explain that it is coming); public builds do not show the
// entries at all.
export function resolveExperienceIntent(
  intent: ExperienceIntent,
  previewEnabled: boolean,
): ExperienceIntentResolution {
  if (!previewEnabled) return { action: 'HIDE' }
  const page = PAGES[intent.experienceType]
  return page
    ? { action: 'NAVIGATE', url: page(intent.experienceId) }
    : { action: 'SHOW_NOTICE', message: NOTICES[intent.experienceType] }
}
