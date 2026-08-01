import type { InspectionPortalContent } from '../../domain/inspection-portal-content'

export type PortalRuntimeEnvironment = 'development' | 'production'

export const EMPTY_PORTAL_CONTENT: InspectionPortalContent = {
  hero: null,
  categories: [],
  guarantees: [],
  hotServices: [],
  processSteps: [],
}

export type PortalContentLoadResult =
  | { kind: 'loaded'; content: InspectionPortalContent }
  | { kind: 'error' }
  | { kind: 'stale' }

export function createInspectionPortalContentLoader(
  loadPortal: (cityCode: string) => Promise<InspectionPortalContent>,
): {
  load: (cityCode: string) => Promise<PortalContentLoadResult>
  invalidate: () => void
} {
  let loadRevision = 0

  return {
    invalidate() {
      loadRevision += 1
    },
    async load(cityCode) {
      const revision = ++loadRevision
      try {
        const content = await loadPortal(cityCode)
        return revision === loadRevision
          ? { kind: 'loaded', content }
          : { kind: 'stale' }
      } catch {
        return revision === loadRevision
          ? { kind: 'error' }
          : { kind: 'stale' }
      }
    },
  }
}

export function resolvePortalContent(
  environment: PortalRuntimeEnvironment,
  developmentContent: InspectionPortalContent,
): InspectionPortalContent {
  return environment === 'development'
    ? developmentContent
    : EMPTY_PORTAL_CONTENT
}

export function composePortalPreviewContent(
  previewEnabled: boolean,
  liveContent: InspectionPortalContent,
  previewContent: InspectionPortalContent,
): InspectionPortalContent {
  if (!previewEnabled) return liveContent

  const liveServiceByCategory = new Map(
    liveContent.hotServices.map((service) => [service.categoryCode, service]),
  )

  return {
    hero: previewContent.hero,
    categories: previewContent.categories,
    guarantees: previewContent.guarantees,
    hotServices: previewContent.hotServices.map(
      (service) => liveServiceByCategory.get(service.categoryCode) ?? service,
    ),
    processSteps: previewContent.processSteps,
  }
}
