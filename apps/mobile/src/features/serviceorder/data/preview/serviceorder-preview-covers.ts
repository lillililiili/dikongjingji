import type { ServiceOrderType } from '../../domain/service-order'

// A service order has no picture of its own. The OrderService and PlanConfirm boards show the scene
// photo of the demand; preview builds use the same board pictures as the demand pages
// (demand-preview-covers.ts) and public builds keep the plain document icon. The pictures are not
// licensed, so they follow the preview build itself, whatever the SERVICE_ORDER switch says
// (feature-flags.ts PREVIEW_SAMPLES_EXPRESSION).
const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__
const DIRECTORY = '/static/images/preview'

const BY_TYPE: Record<ServiceOrderType, string> = {
  AERIAL: 'proservice-solar.webp',
  SURVEY: 'proservice-survey-kit.jpg',
  MAINTENANCE: 'proservice-drone-parts.jpg',
  TECH_SUPPORT: 'proservice-tech-robot.jpg',
}

export function resolveServiceOrderPreviewCover(
  type: ServiceOrderType,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return previewEnabled ? `${DIRECTORY}/${BY_TYPE[type]}` : null
}
