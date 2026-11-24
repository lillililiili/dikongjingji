import { afterAll, describe, expect, it, vi } from 'vitest'

import { resolveActivityPreviewCover } from '../../features/activity/data/preview/activity-preview-covers'
import { coursePlaySource } from '../../features/course/data/preview/course-preview-media'
import { acceptPlayUrl } from '../../features/live/data/preview/live-preview-covers'

// An operator can release these features before providing the first real media.
vi.hoisted(() => {
  vi.stubEnv('DEV', false)
  vi.stubGlobal('__RELEASE_PREVIEW_BUILD__', false)
})
afterAll(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})
vi.mock('../../features/activity/application/activity-preview', () => ({ ACTIVITY_PREVIEW_ENABLED: true }))
vi.mock('../../features/course/application/course-preview', () => ({ COURSE_PREVIEW_ENABLED: true }))
vi.mock('../../features/live/application/live-preview', () => ({ LIVE_PREVIEW_ENABLED: true }))

describe('media in a public build with released features', () => {
  it('leaves missing course videos, live streams and activity art empty', () => {
    expect(coursePlaySource('d7c00000-0000-4000-8000-000000000001', 1, null)).toBeNull()
    expect(acceptPlayUrl('/static/videos/preview/live-example.mp4')).toBeNull()
    expect(resolveActivityPreviewCover('a2000000-0000-4000-8000-000000000001')).toBeNull()
  })

  it('plays the media URLs supplied by the service', () => {
    const realVideo = 'https://media.example.org/lesson.mp4'
    expect(coursePlaySource('course', 1, realVideo)).toBe(realVideo)
    expect(acceptPlayUrl(realVideo)).toBe(realVideo)
  })
})
