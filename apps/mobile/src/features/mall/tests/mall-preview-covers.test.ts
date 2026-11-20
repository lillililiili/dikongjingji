import { describe, expect, it } from 'vitest'

import { resolveActivityPreviewCover } from '../../activity/data/preview/activity-preview-covers'
import { resolveExperiencePreviewCover } from '../../experience/data/preview/experience-preview-covers'
import { resolveMallPreviewCover } from '../data/preview/mall-preview-covers'

// The pictures that are really in the static folder, as "/static/images/preview/<name>.jpg".
const shippedPictures = new Set(
  Object.keys(import.meta.glob('../../../static/images/preview/*.jpg'))
    .map((path) => `/static/images/preview/${path.split('/').pop()}`),
)

describe('preview pictures of the development samples', () => {
  it('gives a sample product its picture in preview builds only', () => {
    const kit = 'c2000000-0000-4000-8000-000000000101'

    expect(resolveMallPreviewCover(kit, true)).toEqual({
      url: '/static/images/preview/robot-rover.jpg',
      cutout: true,
    })
    expect(resolveMallPreviewCover('c2000000-0000-4000-8000-000000000202', true)?.cutout)
      .toBe(false)
    expect(resolveMallPreviewCover(kit, false)).toBeNull()
    expect(resolveMallPreviewCover('some-real-product', true)).toBeNull()
  })

  it('does the same for activities, experience items, venues and devices', () => {
    expect(resolveActivityPreviewCover('a2000000-0000-4000-8000-000000000003', true))
      .toBe('/static/images/preview/camp-yellow-river.jpg')
    expect(resolveActivityPreviewCover('a2000000-0000-4000-8000-000000000003', false)).toBeNull()
    // R2 (2026-11-20): the indoor venue shows its obstacle course, as on the board VenueDetail.
    expect(resolveExperiencePreviewCover('b2000000-0000-4000-8000-000000000001', true))
      .toBe('/static/images/preview/venue-indoor.jpg')
    expect(resolveExperiencePreviewCover('b2000000-0000-4000-8000-000000000001', false))
      .toBeNull()
    expect(resolveExperiencePreviewCover('unknown', true)).toBeNull()
  })

  it('points only at pictures that exist', () => {
    const ids = [
      ...Array.from({ length: 4 }, (_, i) => `c2000000-0000-4000-8000-00000000040${i + 1}`),
      ...Array.from({ length: 3 }, (_, i) => `c2000000-0000-4000-8000-00000000020${i + 1}`),
      ...Array.from({ length: 3 }, (_, i) => `c2000000-0000-4000-8000-00000000030${i + 1}`),
      'c2000000-0000-4000-8000-000000000101',
      'c2000000-0000-4000-8000-000000000102',
    ]
    const urls = [
      ...ids.map((id) => resolveMallPreviewCover(id, true)?.url),
      ...Array.from({ length: 5 }, (_, i) =>
        resolveActivityPreviewCover(`a2000000-0000-4000-8000-00000000000${i + 1}`, true)),
      ...Array.from({ length: 5 }, (_, i) =>
        resolveExperiencePreviewCover(`b1000000-0000-4000-8000-00000000000${i + 1}`, true)),
      ...Array.from({ length: 2 }, (_, i) =>
        resolveExperiencePreviewCover(`b2000000-0000-4000-8000-00000000000${i + 1}`, true)),
      ...Array.from({ length: 6 }, (_, i) =>
        resolveExperiencePreviewCover(`b5000000-0000-4000-8000-00000000010${i + 1}`, true)),
    ]

    expect(urls).toHaveLength(30)
    // Every picture these samples use is in the folder. Since R2 the folder also holds the pictures
    // of other modules (professional services, content), so it is no longer only these.
    for (const url of urls) {
      expect(url, 'every sample has a picture').toBeTruthy()
      expect(shippedPictures.has(url ?? ''), `${url} exists`).toBe(true)
    }
  })
})
