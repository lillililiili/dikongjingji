import type { ExperienceType } from '../domain/experience'

// Entries that are not built yet. They go through this typed gate instead of ad-hoc toasts.
// Venue booking left the gate in E10 (plan §21): 预约到馆 opens the confirm page.
export type ExperienceIntent =
  | { type: 'OPEN_EXPERIENCE'; experienceId: string; experienceType: ExperienceType }

export type ExperienceIntentResolution =
  | { action: 'SHOW_NOTICE'; message: string }
  | { action: 'HIDE' }

const EXPERIENCE_NOTICES: Record<ExperienceType, string> = {
  SIMULATION: '虚拟仿真即将开放',
  RECORDED_DEMO: '录播演示即将开放',
  SOFTWARE_TRIAL: '软件试用即将开放',
  COURSE_TRIAL: '课程试听即将开放',
}

// Preview builds explain that the entry is coming; public builds do not show it at all.
export function resolveExperienceIntent(
  intent: ExperienceIntent,
  previewEnabled: boolean,
): ExperienceIntentResolution {
  if (!previewEnabled) return { action: 'HIDE' }
  return {
    action: 'SHOW_NOTICE',
    message: EXPERIENCE_NOTICES[intent.experienceType],
  }
}
