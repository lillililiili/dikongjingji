import { MALL_PREVIEW_ENABLED } from '../../application/mall-preview'

// Pictures for the sample products of the development seed (R__dev_mall_catalog.sql), keyed by
// product id. They come from the 2026-11-14 prototype and only stand in until products carry
// licensed pictures of their own; a cover the server returns always wins.
const DIRECTORY = '/static/images/preview'

const COVERS: Record<string, string> = {
  'c2000000-0000-4000-8000-000000000101': 'robot-rover',
  'c2000000-0000-4000-8000-000000000102': 'robot-arm',
  'c2000000-0000-4000-8000-000000000201': 'drone-edu',
  'c2000000-0000-4000-8000-000000000202': 'drone-aerial',
  'c2000000-0000-4000-8000-000000000203': 'drone-survey',
  'c2000000-0000-4000-8000-000000000301': 'parts-battery',
  'c2000000-0000-4000-8000-000000000302': 'venue-indoor',
  'c2000000-0000-4000-8000-000000000303': 'parts-battery',
  'c2000000-0000-4000-8000-000000000401': 'robot-dog',
  'c2000000-0000-4000-8000-000000000402': 'drone-aerial',
  'c2000000-0000-4000-8000-000000000403': 'drone-survey',
  'c2000000-0000-4000-8000-000000000404': 'field-outdoor',
}

// Product shots on a plain backdrop: shown whole on a tinted background instead of cropped.
const CUTOUTS = new Set(['robot-rover', 'robot-arm'])

export interface MallPreviewCover {
  url: string
  cutout: boolean
}

export function resolveMallPreviewCover(
  productId: string,
  previewEnabled: boolean = MALL_PREVIEW_ENABLED,
): MallPreviewCover | null {
  const name = previewEnabled ? COVERS[productId] : undefined
  return name
    ? { url: `${DIRECTORY}/${name}.jpg`, cutout: CUTOUTS.has(name) }
    : null
}
