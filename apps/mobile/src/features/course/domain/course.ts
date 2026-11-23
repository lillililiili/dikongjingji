// Course (S5-5, contracts/openapi-course.yaml 1.0.0). Only recorded online courses are sold here;
// an offline class is a card that leads to its activity (M09), where sign-up and payment happen.

export type CourseMode = 'ONLINE' | 'OFFLINE'
export type CourseTopic = 'DRONE' | 'ROBOT' | 'PROGRAMMING' | 'CERTIFICATE'
export type CourseSort = 'DEFAULT' | 'PRICE_ASC' | 'PRICE_DESC'
export type CoursePriceMode = 'FIXED' | 'FREE' | 'INQUIRY'
export type CourseEnrollmentStatus = 'PENDING_PAYMENT' | 'ACTIVE' | 'EXPIRED' | 'CLOSED' | 'REFUNDED'
export type CourseEnrollmentSource = 'PURCHASE' | 'BUNDLE'
export type CourseEnrollmentBucket = 'ALL' | 'LEARNING' | 'FINISHED'
export type CourseLessonState = 'NOT_STARTED' | 'STARTED' | 'COMPLETED'
export type CourseRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'
export type CourseRefundBlock = 'BUNDLED' | 'NOT_ACTIVE' | 'STARTED' | 'WINDOW_PASSED'

export const COURSE_TOPICS: readonly CourseTopic[] = ['DRONE', 'ROBOT', 'PROGRAMMING', 'CERTIFICATE']
export const COURSE_BUCKETS: readonly CourseEnrollmentBucket[] = ['ALL', 'LEARNING', 'FINISHED']
export const COURSE_PAGE_SIZE = 20

export interface CourseImage {
  url: string
  alt: string
}

export interface CoursePrice {
  mode: CoursePriceMode
  amountMinor: number | null
}

export interface CourseCard {
  courseId: string
  mode: CourseMode
  topic: CourseTopic
  title: string
  coverImage: CourseImage | null
  price: CoursePrice
  lessonCount: number | null
  scheduleLabel: string | null
  activityId: string | null
  cityCode: string | null
}

export interface CourseCardQuery {
  cityCode: string
  topic: CourseTopic | null
  keyword: string
  sort: CourseSort
}

export interface CourseOrganization {
  organizationId: string
  name: string
  qualificationReviewed: boolean
}

export interface CourseLesson {
  lessonId: string
  no: number
  title: string
  durationSeconds: number
  trial: boolean
}

export interface CourseChapter {
  chapterId: string
  title: string
  lessons: CourseLesson[]
}

export interface CourseDetail {
  courseId: string
  topic: CourseTopic
  title: string
  subtitle: string | null
  coverImage: CourseImage | null
  gallery: CourseImage[]
  // Null when the course is not sold on its own (it only comes with goods).
  price: CoursePrice | null
  validityDays: number | null
  ageNote: string | null
  instructor: { name: string; title: string | null } | null
  organization: CourseOrganization
  lessonCount: number
  totalDurationSeconds: number
  trialLessonCount: number
  chapters: CourseChapter[]
  includes: { name: string; quantity: number }[]
  extraSpecs: { label: string; value: string }[]
  intro: string[]
  purchasable: boolean
  onlinePayment: boolean
  experienceItemId: string | null
}

export interface CourseMine {
  enrollmentId: string | null
  status: CourseEnrollmentStatus | null
  tradeOrderId: string | null
}

export interface CourseTrialLesson {
  lessonId: string
  no: number
  chapterTitle: string
  title: string
  durationSeconds: number
  playUrl: string | null
}

export interface CourseTrial {
  courseId: string
  title: string
  coverImage: CourseImage | null
  organization: CourseOrganization
  lessonCount: number
  price: CoursePrice | null
  purchasable: boolean
  lessons: CourseTrialLesson[]
}

export interface CourseResume {
  lessonId: string
  no: number
  title: string
}

export interface CourseBundleSource {
  orderId: string
  orderNo: string
  productName: string
  skuName: string
}

export interface CourseRefundability {
  refundable: boolean
  deadline: string | null
  blockedReason: CourseRefundBlock | null
}

export interface CourseProgressLesson {
  lessonId: string
  no: number
  title: string
  durationSeconds: number
  trial: boolean
  state: CourseLessonState
}

export interface CourseProgressChapter {
  chapterId: string
  title: string
  completedCount: number
  lessons: CourseProgressLesson[]
}

export interface CourseEnrollment {
  enrollmentId: string
  courseId: string
  title: string
  coverImage: CourseImage | null
  organization: CourseOrganization
  source: CourseEnrollmentSource
  status: CourseEnrollmentStatus
  lessonCount: number
  completedCount: number
  resume: CourseResume | null
  activatedAt: string | null
  expiresAt: string | null
  payment: { tradeOrderId: string; amountMinor: number; payBefore: string | null; paidAt: string | null } | null
  bundle: CourseBundleSource | null
  refund: { refundId: string; amountMinor: number; status: CourseRefundStatus; requestedAt: string } | null
  refundability: CourseRefundability
  chapters: CourseProgressChapter[]
  version: number
}

export interface CourseEnrollmentCard {
  enrollmentId: string
  courseId: string
  title: string
  coverImage: CourseImage | null
  source: CourseEnrollmentSource
  status: CourseEnrollmentStatus
  lessonCount: number
  completedCount: number
  resume: CourseResume | null
  bundle: CourseBundleSource | null
  expiresAt: string | null
}

export interface CourseTrialCard {
  courseId: string
  title: string
  coverImage: CourseImage | null
  trialLessonCount: number
}

export interface CourseEnrollmentPage {
  hasMore: boolean
  total: number
  items: CourseEnrollmentCard[]
  trials: CourseTrialCard[]
}

export interface CoursePlayback {
  enrollmentId: string
  lessonId: string
  playUrl: string | null
  durationSeconds: number
  refundability: CourseRefundability
}
