import type { DemandType } from '../../domain/demand'

const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// A demand has no picture of its own (attachments are not built). The DemandList, Quote and
// PlanCompare boards show a scene photo per demand type; preview builds use the same board pictures
// (shared with the professional-service pages) and public builds keep the plain document icon.
const DIRECTORY = '/static/images/preview'

const BY_TYPE: Record<DemandType, string> = {
  INSPECTION: 'proservice-park-aerial.webp',
  AERIAL: 'proservice-solar.webp',
  SURVEY: 'proservice-survey-kit.jpg',
  MAINTENANCE: 'proservice-drone-parts.jpg',
  TECH_SUPPORT: 'proservice-tech-robot.jpg',
}

export function resolveDemandPreviewCover(
  type: DemandType,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return previewEnabled ? `${DIRECTORY}/${BY_TYPE[type]}` : null
}
