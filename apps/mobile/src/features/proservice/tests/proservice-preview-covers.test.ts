import { describe, expect, it } from 'vitest'

import {
  inspectionServicePicture,
  resolveProServicePreviewCover,
} from '../data/preview/proservice-preview-covers'
import { outputIcon, scopeIconPath, stepIconPath } from '../presentation/shared/proservice-board-icons'

describe('proservice preview pictures', () => {
  it('gives each scene its board picture in preview builds only', () => {
    expect(resolveProServicePreviewCover('AERIAL', 'ENERGY', false, true)).toBe('/static/images/preview/proservice-solar.webp')
    expect(resolveProServicePreviewCover('AERIAL', 'ENGINEERING', false, true)).toBe('/static/images/preview/proservice-park-aerial.webp')
    // The SvcAerial hero is the drone over the city, not the card picture.
    expect(resolveProServicePreviewCover('AERIAL', 'ENGINEERING', true, true)).toBe('/static/images/preview/proservice-drone-city.jpg')
    expect(resolveProServicePreviewCover('SURVEY', 'MODELING', false, false)).toBeNull()
  })

  it('takes the inspection photos for the inspection rows of a provider page', () => {
    expect(inspectionServicePicture('光伏', false)).toBe('/static/images/inspection/service-photovoltaic.webp')
    expect(inspectionServicePicture('热力', true)).toBe('/static/images/preview/proservice-inspection-heat.webp')
    expect(inspectionServicePicture('热力', false)).toBeNull()
  })
})

describe('proservice board icons', () => {
  it('colours the deliverable tiles by format and keeps step icons by name', () => {
    expect(outputIcon('视频素材', 0).tone).toBe('violet')
    expect(outputIcon('照片', 1).tone).toBe('orange')
    expect(outputIcon('正射影像', 0).tone).toBe('blue')
    expect(scopeIconPath('SURVEY', 0)).not.toBe(scopeIconPath('SURVEY', 1))
    expect(stepIconPath('成果验收', 3, 4)).toBe(stepIconPath('验收确认', 3, 4))
  })
})
