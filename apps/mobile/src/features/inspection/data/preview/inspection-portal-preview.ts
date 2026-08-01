import { composePortalPreviewContent } from '../../application/portal/resolve-portal-content'
import type { InspectionPortalContent } from '../../domain/inspection-portal-content'
import { DEVELOPMENT_PORTAL_CONTENT } from '../fixtures/inspection-portal.fixture'

export const composeInspectionPortalContentForRuntime = (
  liveContent: InspectionPortalContent,
): InspectionPortalContent =>
  composePortalPreviewContent(
    true,
    liveContent,
    DEVELOPMENT_PORTAL_CONTENT,
  )
