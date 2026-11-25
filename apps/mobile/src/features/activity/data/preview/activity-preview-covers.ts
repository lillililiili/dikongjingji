const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Pictures for the sample activities of the development seed (R__dev_activity_catalog.sql),
// keyed by activity id. From the 2026-11-14 prototype; a cover the server returns always wins.
const DIRECTORY = '/static/images/preview'

const COVERS: Record<string, string> = {
  'a2000000-0000-4000-8000-000000000001': 'drone-edu',
  'a2000000-0000-4000-8000-000000000002': 'robot-workshop',
  'a2000000-0000-4000-8000-000000000003': 'camp-yellow-river',
  'a2000000-0000-4000-8000-000000000004': 'drone-aerial',
  'a2000000-0000-4000-8000-000000000005': 'venue-exterior',
}

export function resolveActivityPreviewCover(
  activityId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  const name = previewEnabled ? COVERS[activityId] : undefined
  return name ? `${DIRECTORY}/${name}.jpg` : null
}
