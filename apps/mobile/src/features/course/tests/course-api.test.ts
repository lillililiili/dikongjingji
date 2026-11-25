import { describe, expect, it, vi } from 'vitest'

import { createCourseApi, toCourseCard, toCourseDetail, toCourseEnrollment } from '../data/course-api'

const COURSE = 'd7c00000-0000-4000-8000-000000000001'

describe('course api', () => {
  it('asks for the list with only the filters that are set', async () => {
    const request = vi.fn().mockResolvedValue({ hasMore: false, total: 0, items: [] })
    const api = createCourseApi(request)
    await api.loadCourses({ cityCode: '370500', topic: null, keyword: '  ', sort: 'DEFAULT' }, 1, 20)
    expect(request).toHaveBeenLastCalledWith({ url: '/api/v1/courses?cityCode=370500&page=1&size=20', method: 'GET' })
    await api.loadCourses({ cityCode: '370500', topic: 'DRONE', keyword: '航线 规划', sort: 'PRICE_ASC' }, 2, 20)
    expect(request).toHaveBeenLastCalledWith({
      url: `/api/v1/courses?cityCode=370500&topic=DRONE&keyword=${encodeURIComponent('航线 规划')}&sort=PRICE_ASC&page=2&size=20`,
      method: 'GET',
    })
  })

  it('buys with the idempotency key and the amount the page showed', async () => {
    const request = vi.fn().mockResolvedValue({ enrollmentId: 'e-1', courseId: COURSE, status: 'PENDING_PAYMENT', refundability: {} })
    await createCourseApi(request).enroll('token', 'key-1', COURSE, 19900)
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/course-enrollments',
      method: 'POST',
      data: { courseId: COURSE, expectedAmountMinor: 19900 },
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'key-1' },
    })
  })

  it('finds a trial by course or by the experience entry', async () => {
    const request = vi.fn().mockResolvedValue({ courseId: COURSE, lessons: [] })
    const api = createCourseApi(request)
    await api.loadTrial({ experienceItemId: 'b1000000-0000-4000-8000-000000000005' })
    expect(request.mock.calls[0][0].url).toBe('/api/v1/course-trials?experienceItemId=b1000000-0000-4000-8000-000000000005')
    await api.loadTrial({ courseId: COURSE })
    expect(request.mock.calls[1][0].url).toBe(`/api/v1/course-trials?courseId=${COURSE}`)
  })

  it('only links a trial to its configured simulation', async () => {
    const simulation = 'b1000000-0000-4000-8000-000000000002'
    const entry = 'b1000000-0000-4000-8000-000000000005'
    const api = createCourseApi(vi.fn()
      .mockResolvedValueOnce({ courseId: COURSE, experienceItemId: entry, simulationItemId: simulation })
      .mockResolvedValueOnce({ courseId: COURSE, experienceItemId: entry }))
    expect((await api.loadTrial({ experienceItemId: entry })).simulationItemId).toBe(simulation)
    expect((await api.loadTrial({ courseId: COURSE })).simulationItemId).toBeNull()
  })

  it('reads cards defensively: an offline class keeps its activity, unknown prices become 面议', () => {
    expect(toCourseCard({ courseId: 'c', mode: 'OFFLINE', topic: 'ROBOT', title: '机器人研学体验', price: { mode: 'FIXED', amountMinor: 16800 }, activityId: 'a-2', scheduleLabel: '按场次', lessonCount: 3 }))
      .toMatchObject({ mode: 'OFFLINE', activityId: 'a-2', lessonCount: null, price: { mode: 'FIXED', amountMinor: 16800 } })
    expect(toCourseCard({ courseId: 'c', mode: 'ONLINE', topic: 'COOKING', price: { mode: 'FIXED', amountMinor: null }, activityId: 'x' }))
      .toMatchObject({ topic: 'DRONE', activityId: null, price: { mode: 'INQUIRY', amountMinor: null } })
  })

  it('keeps only https pictures and plays', () => {
    const detail = toCourseDetail({ courseId: COURSE, coverImage: { url: 'http://cdn/x.jpg', alt: '' }, gallery: [{ url: 'https://cdn/a.jpg' }], price: null, chapters: [] })
    expect(detail.coverImage).toBeNull()
    expect(detail.gallery).toEqual([{ url: 'https://cdn/a.jpg', alt: '' }])
    expect(detail.price).toBeNull()
  })

  it('reads an enrollment with its refund rules and lesson states', () => {
    const enrollment = toCourseEnrollment({
      enrollmentId: 'e-1',
      courseId: COURSE,
      source: 'PURCHASE',
      status: 'ACTIVE',
      refundability: { refundable: false, deadline: '2026-11-30T00:00:00Z', blockedReason: 'STARTED' },
      chapters: [{ chapterId: 'ch', title: '第 1 章', completedCount: 1, lessons: [{ lessonId: 'l1', no: 1, title: '升力', durationSeconds: 900, trial: true, state: 'COMPLETED' }, { lessonId: 'l2', no: 2, state: 'WEIRD' }] }],
    })
    expect(enrollment.refundability).toEqual({ refundable: false, deadline: '2026-11-30T00:00:00Z', blockedReason: 'STARTED' })
    expect(enrollment.chapters[0].lessons.map((lesson) => lesson.state)).toEqual(['COMPLETED', 'NOT_STARTED'])
  })
})
