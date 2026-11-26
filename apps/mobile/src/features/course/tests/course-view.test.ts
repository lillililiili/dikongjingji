import { describe, expect, it } from 'vitest'

// The platform's request error carries status, code and details (shared/http's ApiRequestError).
class ApiRequestError extends Error {
  constructor(message: string, readonly status: number, readonly code: string | null, readonly details: Record<string, unknown> | null = null) {
    super(message)
  }
}

import { describeCourseFailure } from '../application/course-failures'
import {
  courseCardAction,
  courseCardBadge,
  courseCardUnit,
  courseOpeningLine,
  courseSpecRows,
  formatCoursePrice,
  formatExpiry,
  formatResume,
  progressSegments,
} from '../application/course-format'
import type { CourseCard, CourseDetail } from '../domain/course'

const FLY = 'd7c00000-0000-4000-8000-000000000001'

const card = (overrides: Partial<CourseCard> = {}): CourseCard => ({
  courseId: FLY,
  mode: 'ONLINE',
  topic: 'DRONE',
  title: '无人机飞行原理 线上课',
  coverImage: null,
  price: { mode: 'FIXED', amountMinor: 19900 },
  lessonCount: 12,
  scheduleLabel: null,
  activityId: null,
  cityCode: null,
  ...overrides,
})

const detail = (overrides: Partial<CourseDetail> = {}): CourseDetail => ({
  courseId: FLY,
  topic: 'DRONE',
  title: '无人机飞行原理 线上课',
  subtitle: null,
  coverImage: null,
  gallery: [],
  price: { mode: 'FIXED', amountMinor: 19900 },
  validityDays: 365,
  ageNote: '10 岁以上零基础',
  instructor: null,
  organization: { organizationId: 'o', name: '示例培训机构', qualificationReviewed: false },
  lessonCount: 12,
  totalDurationSeconds: 12 * 900,
  trialLessonCount: 2,
  chapters: [],
  includes: [],
  extraSpecs: [{ label: '证书', value: '完成后发放电子证书' }],
  intro: [],
  purchasable: true,
  onlinePayment: true,
  experienceItemId: null,
  simulationItemId: null,
  ...overrides,
})

describe('course wording (boards MallCourse, CourseFly, MyCourses)', () => {
  it('shows a card like the board: price, 课时 or class label, badge and button', () => {
    expect(formatCoursePrice(card().price)).toBe('¥199')
    expect(courseCardUnit(card())).toBe('12 课时')
    expect(courseCardBadge(card(), '东营')).toEqual({ text: '线上', online: true })
    const offline = card({ mode: 'OFFLINE', lessonCount: null, scheduleLabel: '8 次课', price: { mode: 'INQUIRY', amountMinor: null } })
    expect(courseCardBadge(offline, '东营')).toEqual({ text: '东营开班', online: false })
    expect(courseCardBadge(offline, '东营市')).toEqual({ text: '东营开班', online: false })
    expect(courseCardUnit(offline)).toBe('8 次课')
    expect(formatCoursePrice(offline.price)).toBe('价格面议')
    expect(courseCardAction(offline)).toEqual({ text: '联系询价', inquiry: true })
    expect(formatCoursePrice({ mode: 'FIXED', amountMinor: 128000 })).toBe('¥1,280')
  })

  it('says how a course opens and what is in its 参数', () => {
    expect(courseOpeningLine(detail())).toBe('线上课 · 购买后立即开通 · 365 天有效')
    expect(courseOpeningLine(detail({ validityDays: null }))).toBe('线上课 · 购买后立即开通 · 长期有效')
    expect(courseSpecRows(detail()).map((row) => `${row.label}:${row.value}`)).toEqual([
      '形式:录播 · 线上', '课时:12 节 · 每节约 15 分钟', '适合:10 岁以上零基础', '有效期:365 天', '证书:完成后发放电子证书',
    ])
  })

  it('shows progress and the next lesson', () => {
    expect(progressSegments(4, 2)).toEqual([true, true, false, false])
    expect(formatResume({ no: 3, title: '连接编程环境' })).toBe('第 3 课 · 连接编程环境')
    expect(formatResume(null)).toBe('已学完全部课时')
    expect(formatExpiry({ expiresAt: null, status: 'ACTIVE' })).toBe('长期有效')
    expect(formatExpiry({ expiresAt: '2027-11-22T16:30:00Z', status: 'EXPIRED' })).toBe('2027/11/23 前（已过期）')
  })

  it('turns refusals into words and next steps', () => {
    expect(describeCourseFailure(new ApiRequestError('x', 401, 'COMMON_UNAUTHORIZED'))).toMatchObject({ signIn: true })
    expect(describeCourseFailure(new ApiRequestError('x', 409, 'COURSE_AMOUNT_CHANGED'))).toMatchObject({ reload: true, message: '课程价格有变化，已为你刷新，请核对后再报名' })
    expect(describeCourseFailure(new ApiRequestError('x', 409, 'COURSE_REFUND_NOT_ALLOWED', { reason: 'WINDOW_PASSED' })).message)
      .toBe('付款已超过 7 天，不能在线退款；如有问题请联系客服')
    expect(describeCourseFailure(new Error('offline'))).toMatchObject({ signIn: false, reload: false })
  })
})
