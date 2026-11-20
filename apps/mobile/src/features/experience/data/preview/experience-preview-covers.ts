import { EXPERIENCE_PREVIEW_ENABLED } from '../../application/experience-preview'

// Pictures for the sample experience items, venues and devices of the development seed
// (R__dev_experience_catalog.sql), keyed by their ids. From the 2026-11-14 prototype; a picture
// the server returns always wins.
const DIRECTORY = '/static/images/preview'

const COVERS: Record<string, string> = {
  // experience items
  'b1000000-0000-4000-8000-000000000001': 'sim-drone',
  'b1000000-0000-4000-8000-000000000002': 'drone-edu',
  'b1000000-0000-4000-8000-000000000003': 'demo-video',
  'b1000000-0000-4000-8000-000000000004': 'robot-classroom',
  'b1000000-0000-4000-8000-000000000005': 'drone-edu',
  // venues
  'b2000000-0000-4000-8000-000000000001': 'venue-indoor',
  'b2000000-0000-4000-8000-000000000002': 'field-outdoor',
  // venue projects (the confirm page of a venue booking)
  'b4000000-0000-4000-8000-000000000101': 'venue-indoor',
  'b4000000-0000-4000-8000-000000000102': 'sim-drone',
  'b4000000-0000-4000-8000-000000000103': 'robot-classroom',
  // devices on display
  'b5000000-0000-4000-8000-000000000101': 'robot-rover',
  'b5000000-0000-4000-8000-000000000102': 'drone-aerial',
  'b5000000-0000-4000-8000-000000000103': 'robot-dog',
  'b5000000-0000-4000-8000-000000000104': 'parts-battery',
  'b5000000-0000-4000-8000-000000000105': 'venue-indoor',
  'b5000000-0000-4000-8000-000000000106': 'drone-survey',
  // devices of the outdoor camp (VenueOutdoor)
  'b5000000-0000-4000-8000-000000000201': 'robot-rover',
  'b5000000-0000-4000-8000-000000000202': 'drone-aerial',
  'b5000000-0000-4000-8000-000000000203': 'robot-dog',
  'b5000000-0000-4000-8000-000000000204': 'parts-battery',
  'b5000000-0000-4000-8000-000000000205': 'drone-survey',
  'b5000000-0000-4000-8000-000000000206': 'venue-indoor',
}

export function resolveExperiencePreviewCover(
  id: string,
  previewEnabled: boolean = EXPERIENCE_PREVIEW_ENABLED,
): string | null {
  const name = previewEnabled ? COVERS[id] : undefined
  return name ? `${DIRECTORY}/${name}.jpg` : null
}
