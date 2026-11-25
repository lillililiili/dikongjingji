import { buildSimulationUrl } from '../../experience/navigation/experience-routes'
import type { CourseEnrollmentBucket } from '../domain/course'

export const COURSE_DETAIL_PATH = '/pages/course/detail'
export const COURSE_LEARN_PATH = '/pages/course/learn'
export const COURSE_TRIAL_PATH = '/pages/course/trial'
export const MY_COURSES_PATH = '/pages/mine/courses'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// Only stable ids travel; every page reads the course or the enrollment from the server again.
// buy=1 opens the 报名 sheet right away (coming back from the login page).
export function buildCourseDetailUrl(courseId: string, options: { buy?: boolean } = {}): string {
  return `${COURSE_DETAIL_PATH}?courseId=${encodeURIComponent(courseId)}${options.buy ? '&buy=1' : ''}`
}

export function buildCourseLearnUrl(enrollmentId: string): string {
  return `${COURSE_LEARN_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}`
}

export function buildCourseTrialUrl(target: { courseId: string } | { experienceItemId: string }): string {
  return 'courseId' in target
    ? `${COURSE_TRIAL_PATH}?courseId=${encodeURIComponent(target.courseId)}`
    : `${COURSE_TRIAL_PATH}?experienceItemId=${encodeURIComponent(target.experienceItemId)}`
}

export function buildMyCoursesUrl(bucket: CourseEnrollmentBucket = 'ALL'): string {
  return bucket === 'ALL' ? MY_COURSES_PATH : `${MY_COURSES_PATH}?bucket=${bucket}`
}

const read = (query: Record<string, unknown> | undefined, key: string): string => {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return ''
  }
}

export function parseCourseId(query: Record<string, unknown> | undefined): string | null {
  const id = read(query, 'courseId')
  return UUID.test(id) ? id : null
}

export function parseEnrollmentId(query: Record<string, unknown> | undefined): string | null {
  const id = read(query, 'enrollmentId')
  return UUID.test(id) ? id : null
}

export function parseTrialTarget(
  query: Record<string, unknown> | undefined,
): { courseId: string } | { experienceItemId: string } | null {
  const courseId = parseCourseId(query)
  if (courseId) return { courseId }
  const experienceItemId = read(query, 'experienceItemId')
  return UUID.test(experienceItemId) ? { experienceItemId } : null
}

export function parseCourseBucket(query: Record<string, unknown> | undefined): CourseEnrollmentBucket {
  const value = read(query, 'bucket')
  return value === 'LEARNING' || value === 'FINISHED' ? value : 'ALL'
}

export const wantsToBuy = (query: Record<string, unknown> | undefined): boolean => read(query, 'buy') === '1'

export function courseTrialSimulationUrl(simulationItemId: string | null, experienceEnabled: boolean): string | null {
  return simulationItemId && UUID.test(simulationItemId) && experienceEnabled ? buildSimulationUrl(simulationItemId) : null
}
