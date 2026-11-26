import { resolveBusinessMediaUrl } from '../../../shared/http/business-media'
import {
  COURSE_TOPICS,
  type CourseCard,
  type CourseCardQuery,
  type CourseDetail,
  type CourseEnrollment,
  type CourseEnrollmentBucket,
  type CourseEnrollmentCard,
  type CourseEnrollmentPage,
  type CourseEnrollmentSource,
  type CourseEnrollmentStatus,
  type CourseImage,
  type CourseLessonState,
  type CourseMine,
  type CourseOrganization,
  type CoursePlayback,
  type CoursePrice,
  type CourseRefundability,
  type CourseRefundBlock,
  type CourseRefundStatus,
  type CourseTopic,
  type CourseTrial,
} from '../domain/course'

type CourseRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The DTOs are read defensively (contracts/openapi-course.yaml): an unknown value falls back to the
// plainest reading instead of drawing something wrong. Every field goes through one of the readers
// below (text, int, oneOf, …) before it reaches a domain object; the loose type only lets those
// readers reach into nested objects without a cast at every step.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Dto = Record<string, any>

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value.trim() : null)
const int = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0)
const intOrNull = (value: unknown): number | null => (typeof value === 'number' && Number.isFinite(value) ? Math.floor(value) : null)
const oneOf = <T extends string>(value: unknown, values: readonly T[], fallback: T): T =>
  typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : fallback
const list = (value: unknown): Dto[] => (Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') : [])

const STATUSES: readonly CourseEnrollmentStatus[] = ['PENDING_PAYMENT', 'ACTIVE', 'EXPIRED', 'CLOSED', 'REFUNDED']
const SOURCES: readonly CourseEnrollmentSource[] = ['PURCHASE', 'BUNDLE']
const LESSON_STATES: readonly CourseLessonState[] = ['NOT_STARTED', 'STARTED', 'COMPLETED']
const REFUND_STATUSES: readonly CourseRefundStatus[] = ['REQUESTED', 'PROCESSING', 'SUCCEEDED', 'CLOSED', 'ABNORMAL']
const REFUND_BLOCKS: readonly CourseRefundBlock[] = ['BUNDLED', 'NOT_ACTIVE', 'STARTED', 'WINDOW_PASSED']

function toImage(dto: Dto | null | undefined): CourseImage | null {
  const url = resolveBusinessMediaUrl(dto?.url)
  return url ? { url, alt: text(dto?.alt) ?? '' } : null
}

function toPrice(dto: Dto | null | undefined): CoursePrice | null {
  if (!dto) return null
  const mode = oneOf(dto.mode, ['FIXED', 'FREE', 'INQUIRY'] as const, 'INQUIRY')
  const amount = intOrNull(dto.amountMinor)
  return mode === 'FIXED' && amount !== null && amount > 0 ? { mode, amountMinor: amount } : { mode: mode === 'FIXED' ? 'INQUIRY' : mode, amountMinor: null }
}

function toOrganization(dto: Dto | undefined): CourseOrganization {
  return {
    organizationId: text(dto?.organizationId) ?? '',
    name: text(dto?.name) ?? '开课机构',
    qualificationReviewed: dto?.qualificationReviewed === true,
  }
}

const toTopic = (value: unknown): CourseTopic => oneOf(value, COURSE_TOPICS, 'DRONE')

export function toCourseCard(dto: Dto): CourseCard {
  const mode = dto.mode === 'OFFLINE' ? 'OFFLINE' : 'ONLINE'
  return {
    courseId: String(dto.courseId),
    mode,
    topic: toTopic(dto.topic),
    title: text(dto.title) ?? '课程',
    coverImage: toImage(dto.coverImage),
    price: toPrice(dto.price) ?? { mode: 'INQUIRY', amountMinor: null },
    lessonCount: mode === 'ONLINE' ? intOrNull(dto.lessonCount) : null,
    scheduleLabel: text(dto.scheduleLabel),
    activityId: mode === 'OFFLINE' ? text(dto.activityId) : null,
    cityCode: text(dto.cityCode),
  }
}

export function toCourseDetail(dto: Dto): CourseDetail {
  return {
    courseId: String(dto.courseId),
    topic: toTopic(dto.topic),
    title: text(dto.title) ?? '课程',
    subtitle: text(dto.subtitle),
    coverImage: toImage(dto.coverImage),
    gallery: list(dto.gallery).flatMap((item) => {
      const image = toImage(item)
      return image ? [image] : []
    }),
    price: toPrice(dto.price),
    validityDays: intOrNull(dto.validityDays),
    ageNote: text(dto.ageNote),
    instructor: text(dto.instructor?.name) ? { name: text(dto.instructor.name) as string, title: text(dto.instructor.title) } : null,
    organization: toOrganization(dto.organization),
    lessonCount: int(dto.lessonCount),
    totalDurationSeconds: int(dto.totalDurationSeconds),
    trialLessonCount: int(dto.trialLessonCount),
    chapters: list(dto.chapters).map((chapter) => ({
      chapterId: String(chapter.chapterId),
      title: text(chapter.title) ?? '',
      lessons: list(chapter.lessons).map((lesson) => ({
        lessonId: String(lesson.lessonId),
        no: int(lesson.no),
        title: text(lesson.title) ?? '',
        durationSeconds: int(lesson.durationSeconds),
        trial: lesson.trial === true,
      })),
    })),
    includes: list(dto.includes).flatMap((item) => (text(item.name) ? [{ name: text(item.name) as string, quantity: int(item.quantity) }] : [])),
    extraSpecs: list(dto.extraSpecs).flatMap((item) => (text(item.label) && text(item.value)
      ? [{ label: text(item.label) as string, value: text(item.value) as string }]
      : [])),
    intro: (Array.isArray(dto.intro) ? dto.intro : []).flatMap((item: unknown) => (text(item) ? [text(item) as string] : [])),
    purchasable: dto.purchasable === true,
    onlinePayment: dto.onlinePayment === true,
    experienceItemId: text(dto.experienceItemId),
    simulationItemId: text(dto.simulationItemId),
  }
}

function toRefundability(dto: Dto | undefined): CourseRefundability {
  return {
    refundable: dto?.refundable === true,
    deadline: text(dto?.deadline),
    blockedReason: dto?.blockedReason ? oneOf(dto.blockedReason, REFUND_BLOCKS, 'NOT_ACTIVE') : null,
  }
}

function toResume(dto: Dto | null | undefined) {
  return dto && text(dto.lessonId) ? { lessonId: String(dto.lessonId), no: int(dto.no), title: text(dto.title) ?? '' } : null
}

function toBundle(dto: Dto | null | undefined) {
  return dto && text(dto.orderId)
    ? { orderId: String(dto.orderId), orderNo: text(dto.orderNo) ?? '', productName: text(dto.productName) ?? '', skuName: text(dto.skuName) ?? '' }
    : null
}

export function toCourseEnrollment(dto: Dto): CourseEnrollment {
  return {
    enrollmentId: String(dto.enrollmentId),
    courseId: String(dto.courseId),
    title: text(dto.title) ?? '课程',
    coverImage: toImage(dto.coverImage),
    organization: toOrganization(dto.organization),
    source: oneOf(dto.source, SOURCES, 'PURCHASE'),
    status: oneOf(dto.status, STATUSES, 'CLOSED'),
    lessonCount: int(dto.lessonCount),
    completedCount: int(dto.completedCount),
    resume: toResume(dto.resume),
    activatedAt: text(dto.activatedAt),
    expiresAt: text(dto.expiresAt),
    payment: dto.payment && text(dto.payment.tradeOrderId)
      ? {
          tradeOrderId: String(dto.payment.tradeOrderId),
          amountMinor: int(dto.payment.amountMinor),
          payBefore: text(dto.payment.payBefore),
          paidAt: text(dto.payment.paidAt),
        }
      : null,
    bundle: toBundle(dto.bundle),
    refund: dto.refund && text(dto.refund.refundId)
      ? {
          refundId: String(dto.refund.refundId),
          amountMinor: int(dto.refund.amountMinor),
          status: oneOf(dto.refund.status, REFUND_STATUSES, 'REQUESTED'),
          requestedAt: text(dto.refund.requestedAt) ?? '',
        }
      : null,
    refundability: toRefundability(dto.refundability),
    chapters: list(dto.chapters).map((chapter) => ({
      chapterId: String(chapter.chapterId),
      title: text(chapter.title) ?? '',
      completedCount: int(chapter.completedCount),
      lessons: list(chapter.lessons).map((lesson) => ({
        lessonId: String(lesson.lessonId),
        no: int(lesson.no),
        title: text(lesson.title) ?? '',
        durationSeconds: int(lesson.durationSeconds),
        trial: lesson.trial === true,
        state: oneOf(lesson.state, LESSON_STATES, 'NOT_STARTED'),
      })),
    })),
    version: int(dto.version),
  }
}

function toEnrollmentCard(dto: Dto): CourseEnrollmentCard {
  return {
    enrollmentId: String(dto.enrollmentId),
    courseId: String(dto.courseId),
    title: text(dto.title) ?? '课程',
    coverImage: toImage(dto.coverImage),
    source: oneOf(dto.source, SOURCES, 'PURCHASE'),
    status: oneOf(dto.status, STATUSES, 'ACTIVE'),
    lessonCount: int(dto.lessonCount),
    completedCount: int(dto.completedCount),
    resume: toResume(dto.resume),
    bundle: toBundle(dto.bundle),
    expiresAt: text(dto.expiresAt),
  }
}

function toTrial(dto: Dto): CourseTrial {
  return {
    courseId: String(dto.courseId),
    simulationItemId: text(dto.simulationItemId),
    title: text(dto.title) ?? '课程',
    coverImage: toImage(dto.coverImage),
    organization: toOrganization(dto.organization),
    lessonCount: int(dto.lessonCount),
    price: toPrice(dto.price),
    purchasable: dto.purchasable === true,
    lessons: list(dto.lessons).map((lesson) => ({
      lessonId: String(lesson.lessonId),
      no: int(lesson.no),
      chapterTitle: text(lesson.chapterTitle) ?? '',
      title: text(lesson.title) ?? '',
      durationSeconds: int(lesson.durationSeconds),
      playUrl: resolveBusinessMediaUrl(lesson.playUrl),
    })),
  }
}

export interface CourseApi {
  loadCourses(query: CourseCardQuery, page: number, size: number): Promise<{ hasMore: boolean; total: number; items: CourseCard[] }>
  loadCourse(courseId: string): Promise<CourseDetail>
  loadMine(accessToken: string, courseId: string): Promise<CourseMine>
  loadTrial(target: { courseId: string } | { experienceItemId: string }): Promise<CourseTrial>
  enroll(accessToken: string, idempotencyKey: string, courseId: string, expectedAmountMinor: number): Promise<CourseEnrollment>
  loadEnrollments(accessToken: string, bucket: CourseEnrollmentBucket, page: number, size: number): Promise<CourseEnrollmentPage>
  loadEnrollment(accessToken: string, enrollmentId: string): Promise<CourseEnrollment>
  playback(accessToken: string, enrollmentId: string, lessonId: string): Promise<CoursePlayback>
  complete(accessToken: string, enrollmentId: string, lessonId: string): Promise<CourseEnrollment>
  refund(accessToken: string, enrollmentId: string): Promise<CourseEnrollment>
}

export function createCourseApi(request: CourseRequest): CourseApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const enrollment = (enrollmentId: string) => `/api/v1/course-enrollments/${encodeURIComponent(enrollmentId)}`
  return {
    async loadCourses(query, page, size) {
      const parts = [
        `cityCode=${encodeURIComponent(query.cityCode)}`,
        ...(query.topic ? [`topic=${query.topic}`] : []),
        ...(query.keyword.trim() ? [`keyword=${encodeURIComponent(query.keyword.trim().slice(0, 30))}`] : []),
        ...(query.sort !== 'DEFAULT' ? [`sort=${query.sort}`] : []),
        `page=${page}`,
        `size=${size}`,
      ]
      const dto = await request<Dto>({ url: `/api/v1/courses?${parts.join('&')}`, method: 'GET' })
      return { hasMore: dto?.hasMore === true, total: int(dto?.total), items: list(dto?.items).map(toCourseCard) }
    },
    async loadCourse(courseId) {
      return toCourseDetail(await request<Dto>({ url: `/api/v1/courses/${encodeURIComponent(courseId)}`, method: 'GET' }))
    },
    async loadMine(accessToken, courseId) {
      const dto = await request<Dto>({ url: `/api/v1/courses/${encodeURIComponent(courseId)}/me`, method: 'GET', headers: auth(accessToken) })
      return {
        enrollmentId: text(dto?.enrollmentId),
        status: dto?.status ? oneOf(dto.status, STATUSES, 'CLOSED') : null,
        tradeOrderId: text(dto?.tradeOrderId),
      }
    },
    async loadTrial(target) {
      const query = 'courseId' in target
        ? `courseId=${encodeURIComponent(target.courseId)}`
        : `experienceItemId=${encodeURIComponent(target.experienceItemId)}`
      return toTrial(await request<Dto>({ url: `/api/v1/course-trials?${query}`, method: 'GET' }))
    },
    async enroll(accessToken, idempotencyKey, courseId, expectedAmountMinor) {
      return toCourseEnrollment(await request<Dto>({
        url: '/api/v1/course-enrollments',
        method: 'POST',
        data: { courseId, expectedAmountMinor },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async loadEnrollments(accessToken, bucket, page, size) {
      const dto = await request<Dto>({
        url: `/api/v1/course-enrollments?bucket=${bucket}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        hasMore: dto?.hasMore === true,
        total: int(dto?.total),
        items: list(dto?.items).map(toEnrollmentCard),
        trials: list(dto?.trials).map((trial) => ({
          courseId: String(trial.courseId),
          title: text(trial.title) ?? '课程',
          coverImage: toImage(trial.coverImage),
          trialLessonCount: int(trial.trialLessonCount),
        })),
      }
    },
    async loadEnrollment(accessToken, enrollmentId) {
      return toCourseEnrollment(await request<Dto>({ url: enrollment(enrollmentId), method: 'GET', headers: auth(accessToken) }))
    },
    async playback(accessToken, enrollmentId, lessonId) {
      const dto = await request<Dto>({
        url: `${enrollment(enrollmentId)}/lessons/${encodeURIComponent(lessonId)}/playback`,
        method: 'POST',
        headers: auth(accessToken),
      })
      return {
        enrollmentId: String(dto.enrollmentId),
        lessonId: String(dto.lessonId),
        playUrl: resolveBusinessMediaUrl(dto.playUrl),
        durationSeconds: int(dto.durationSeconds),
        refundability: toRefundability(dto.refundability),
      }
    },
    async complete(accessToken, enrollmentId, lessonId) {
      return toCourseEnrollment(await request<Dto>({
        url: `${enrollment(enrollmentId)}/lessons/${encodeURIComponent(lessonId)}/completion`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async refund(accessToken, enrollmentId) {
      return toCourseEnrollment(await request<Dto>({ url: `${enrollment(enrollmentId)}/refund`, method: 'POST', headers: auth(accessToken) }))
    },
  }
}
