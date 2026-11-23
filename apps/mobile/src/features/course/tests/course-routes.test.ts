import { describe, expect, it } from 'vitest'

import pagesJson from '../../../pages.json?raw'
import mallChannelSource from '../../mall/presentation/channel/MallChannel.vue?raw'
import mineSource from '../../../pages/mine/index.vue?raw'
import experienceSource from '../../experience/presentation/channel/ExperienceChannel.vue?raw'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import flagSource from '../application/course-preview.ts?raw'
import {
  buildCourseDetailUrl,
  buildCourseLearnUrl,
  buildCourseTrialUrl,
  buildMyCoursesUrl,
  parseCourseBucket,
  parseCourseId,
  parseTrialTarget,
  wantsToBuy,
} from '../navigation/course-routes'

const COURSE = 'd7c00000-0000-4000-8000-000000000001'
const ENROLLMENT = 'd7c90000-0000-4000-8000-000000000001'
const EXPERIENCE = 'b1000000-0000-4000-8000-000000000005'

describe('course routes', () => {
  it('carry only stable ids', () => {
    expect(buildCourseDetailUrl(COURSE)).toBe(`/pages/course/detail?courseId=${COURSE}`)
    expect(buildCourseDetailUrl(COURSE, { buy: true })).toBe(`/pages/course/detail?courseId=${COURSE}&buy=1`)
    expect(buildCourseLearnUrl(ENROLLMENT)).toBe(`/pages/course/learn?enrollmentId=${ENROLLMENT}`)
    expect(buildCourseTrialUrl({ experienceItemId: EXPERIENCE })).toBe(`/pages/course/trial?experienceItemId=${EXPERIENCE}`)
    expect(buildMyCoursesUrl()).toBe('/pages/mine/courses')
    expect(buildMyCoursesUrl('FINISHED')).toBe('/pages/mine/courses?bucket=FINISHED')
  })

  it('read back only well-formed ids', () => {
    expect(parseCourseId({ courseId: COURSE })).toBe(COURSE)
    expect(parseCourseId({ courseId: 'nope' })).toBeNull()
    expect(parseTrialTarget({ experienceItemId: EXPERIENCE })).toEqual({ experienceItemId: EXPERIENCE })
    expect(parseTrialTarget({})).toBeNull()
    expect(parseCourseBucket({ bucket: 'LEARNING' })).toBe('LEARNING')
    expect(parseCourseBucket({ bucket: 'X' })).toBe('ALL')
    expect(wantsToBuy({ buy: '1' })).toBe(true)
  })

  it('are pages and come back after signing in', () => {
    for (const path of ['pages/course/detail', 'pages/course/learn', 'pages/course/trial', 'pages/mine/courses']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    expect(normalizeInspectionReturnUrl(buildCourseDetailUrl(COURSE, { buy: true }))).toBe(buildCourseDetailUrl(COURSE, { buy: true }))
    expect(normalizeInspectionReturnUrl(buildCourseLearnUrl(ENROLLMENT))).toBe(buildCourseLearnUrl(ENROLLMENT))
    expect(normalizeInspectionReturnUrl(buildMyCoursesUrl())).toBe(buildMyCoursesUrl())
  })

  it('stay behind the COURSE release switch everywhere they are entered', () => {
    expect(flagSource).toContain(`export const COURSE_PREVIEW_ENABLED = ${moduleFlagExpression('COURSE')}\n`)
    expect(mallChannelSource).toContain("COURSE_PREVIEW_ENABLED && selectedCategory.value === 'COURSE'")
    expect(mineSource).toContain("COURSE_PREVIEW_ENABLED ? [{ code: 'MY_COURSES', title: '我的课程' }]")
    expect(experienceSource).toContain("item.type === 'COURSE_TRIAL' && COURSE_PREVIEW_ENABLED")
  })
})
