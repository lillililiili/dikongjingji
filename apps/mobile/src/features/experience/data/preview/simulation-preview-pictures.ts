import type { SimulationTemplate } from '../../domain/simulation'

const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Pictures of the boards ExpSim, ExpResult, ExpRecords and SoftwareTrial for preview builds. They are
// the prototype's own pictures (already in the preview folder under other names); the server's
// cover, when an item has one, always wins on the cards.
const DIRECTORY = '/static/images/preview'

// The scene behind the task (ExpSim `img`) and the result's hero (ExpResult `experienceImg`).
const SCENES: Record<SimulationTemplate, string> = {
  ROBOT_OBSTACLE: 'proservice-software',
  DRONE_HOVER: 'content-ref-flight-guide',
}

// The 72px picture of a record row (ExpRecords `recs`).
const RECORD_PICTURES: Record<SimulationTemplate, string> = {
  ROBOT_OBSTACLE: 'proservice-tech-robot',
  DRONE_HOVER: 'content-ref-flight-guide',
}

// SoftwareTrial: the 64px card picture and the 软件画面 frame; the course preview uses the drone picture.
const TRIAL_PICTURES = {
  softwareCard: 'proservice-robot-care',
  softwareScreen: 'proservice-software',
  course: 'content-ref-flight-guide',
} as const

const url = (name: string | undefined, previewEnabled: boolean) =>
  previewEnabled && name ? `${DIRECTORY}/${name}.jpg` : null

export function simulationScenePicture(
  template: SimulationTemplate | null,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return url(template ? SCENES[template] : undefined, previewEnabled)
}

export function simulationRecordPicture(
  template: SimulationTemplate | null,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return url(template ? RECORD_PICTURES[template] : undefined, previewEnabled)
}

export function trialPicture(
  which: keyof typeof TRIAL_PICTURES,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return url(TRIAL_PICTURES[which], previewEnabled)
}
