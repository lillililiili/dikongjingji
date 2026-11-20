import { DEMAND_PREVIEW_ENABLED } from '../../application/demand-preview'
import type { DemandType } from '../../domain/demand'

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
  previewEnabled: boolean = DEMAND_PREVIEW_ENABLED,
): string | null {
  return previewEnabled ? `${DIRECTORY}/${BY_TYPE[type]}` : null
}
