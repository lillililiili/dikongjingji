import type { InspectionPortalContent } from '../../domain/inspection-portal-content'

export type PortalRuntimeEnvironment = 'development' | 'production'

export const EMPTY_PORTAL_CONTENT: InspectionPortalContent = {
  hero: null,
  categories: [],
  guarantees: [],
  hotServices: [],
  processSteps: [],
}

export function resolvePortalContent(
  environment: PortalRuntimeEnvironment,
  developmentContent: InspectionPortalContent,
): InspectionPortalContent {
  return environment === 'development'
    ? developmentContent
    : EMPTY_PORTAL_CONTENT
}
