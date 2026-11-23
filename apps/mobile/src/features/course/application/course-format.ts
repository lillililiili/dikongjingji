import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type {
  CourseCard,
  CourseDetail,
  CourseEnrollment,
  CourseEnrollmentStatus,
  CourseLessonState,
  CoursePrice,
  CourseRefundBlock,
  CourseRefundStatus,
  CourseTopic,
} from '../domain/course'

// The words of the course pages, from the boards MallCourse, CourseFly, PdSkuCourseFly, CourseDetail
// and MyCourses. Server enums never reach the screen as they are.

export const COURSE_TOPIC_LABELS: Record<CourseTopic, string> = {
  DRONE: '无人机',
  ROBOT: '机器人',
  PROGRAMMING: '编程',
  CERTIFICATE: '考证',
}

// One account per purchase: the only 规格 phase one sells (family accounts are not in phase one).
export const COURSE_OPTION_NAME = '单人账号'

export function formatCourseAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

/** ¥199、免费、价格面议. */
export function formatCoursePrice(price: CoursePrice | null): string {
  if (!price) return '随商品附带'
  if (price.mode === 'FIXED' && price.amountMinor !== null) return formatCourseAmount(price.amountMinor)
  return price.mode === 'FREE' ? '免费' : '价格面议'
}

/** The grey words after the price on a card: “12 课时” or the class's “8 次课”. */
export function courseCardUnit(card: CourseCard): string {
  if (card.mode === 'ONLINE') return card.lessonCount ? `${card.lessonCount} 课时` : ''
  return card.scheduleLabel ?? ''
}

/** The corner badge: 线上, or “东营开班” with the city's name. */
export function courseCardBadge(card: CourseCard, cityName: string | null): { text: string; online: boolean } {
  if (card.mode === 'ONLINE') return { text: '线上', online: true }
  // The board writes 东营开班, without the 市 of the city's full name.
  const shortName = cityName?.trim().replace(/市$/, '') || '本地'
  return { text: `${shortName}开班`, online: false }
}

/** The card's button: 立即报名, or 联系询价 for a class priced on request. */
export function courseCardAction(card: CourseCard): { text: string; inquiry: boolean } {
  return card.price.mode === 'INQUIRY' ? { text: '联系询价', inquiry: true } : { text: '立即报名', inquiry: false }
}

export function formatMinutes(seconds: number): string {
  return `${Math.max(1, Math.round(seconds / 60))} 分钟`
}

/** “约 12 分钟” for one lesson, the board's 课时 line. */
export function formatLessonLength(seconds: number): string {
  return `约 ${formatMinutes(seconds)}`
}

export function averageLessonMinutes(detail: Pick<CourseDetail, 'lessonCount' | 'totalDurationSeconds'>): number {
  return detail.lessonCount > 0 ? Math.max(1, Math.round(detail.totalDurationSeconds / detail.lessonCount / 60)) : 0
}

export function formatValidity(validityDays: number | null): string {
  return validityDays ? `${validityDays} 天` : '长期有效'
}

/** The line next to the price: 线上 · 12 课时 · 随时学. */
export function courseSummaryLine(detail: CourseDetail): string {
  return `线上 · ${detail.lessonCount} 课时 · 随时学`
}

/** The first-screen row where goods say how they ship: a course opens right away. */
export function courseOpeningLine(detail: CourseDetail): string {
  return ['线上课', '购买后立即开通', detail.validityDays ? `${detail.validityDays} 天有效` : '长期有效'].join(' · ')
}

/** 参数: what the server knows as fields first, then the course's own extra rows. */
export function courseSpecRows(detail: CourseDetail): { label: string; value: string }[] {
  const minutes = averageLessonMinutes(detail)
  return [
    { label: '形式', value: '录播 · 线上' },
    { label: '课时', value: minutes ? `${detail.lessonCount} 节 · 每节约 ${minutes} 分钟` : `${detail.lessonCount} 节` },
    ...(detail.ageNote ? [{ label: '适合', value: detail.ageNote }] : []),
    { label: '有效期', value: formatValidity(detail.validityDays) },
    ...(detail.instructor ? [{ label: '讲师', value: [detail.instructor.name, detail.instructor.title].filter(Boolean).join(' · ') }] : []),
    ...detail.extraSpecs,
  ]
}

export const COURSE_STATUS_LABELS: Record<CourseEnrollmentStatus, string> = {
  PENDING_PAYMENT: '待支付',
  ACTIVE: '学习中',
  EXPIRED: '已过期',
  CLOSED: '已关闭',
  REFUNDED: '已退款',
}

export const LESSON_STATE_LABELS: Record<CourseLessonState, string> = {
  NOT_STARTED: '未开始',
  STARTED: '学习中',
  COMPLETED: '已学完',
}

export const REFUND_STATUS_LABELS: Record<CourseRefundStatus, string> = {
  REQUESTED: '退款处理中',
  PROCESSING: '退款处理中',
  SUCCEEDED: '已原路退回',
  CLOSED: '退款关闭，请联系客服',
  ABNORMAL: '退款异常，请联系客服',
}

/** Why the course cannot be refunded online, in the learning page's words. */
export const REFUND_BLOCK_TEXTS: Record<CourseRefundBlock, string> = {
  BUNDLED: '随商品附带的课程，退款随商品订单规则处理，入口在订单详情',
  NOT_ACTIVE: '课程没有开通，不能退款',
  STARTED: '已开始学习付费课时，不能在线退款；如有问题请联系客服',
  WINDOW_PASSED: '付款已超过 7 天，不能在线退款；如有问题请联系客服',
}

/** MM/DD HH:mm in Beijing time. */
export function formatCourseTime(iso: string | null): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
}

/** YYYY/MM/DD in Beijing time. */
export function formatCourseDate(iso: string | null): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${parts.year}/${padTwo(parts.month)}/${padTwo(parts.day)}`
}

/** 有效期 row: 长期有效, or the last day it can be watched. */
export function formatExpiry(enrollment: Pick<CourseEnrollment, 'expiresAt' | 'status'>): string {
  if (!enrollment.expiresAt) return '长期有效'
  return `${formatCourseDate(enrollment.expiresAt)} 前${enrollment.status === 'EXPIRED' ? '（已过期）' : ''}`
}

/** 第 3 课 · 连接编程环境, the next lesson on a 我的课程 card. */
export function formatResume(resume: { no: number; title: string } | null): string {
  return resume ? `第 ${resume.no} 课 · ${resume.title}` : '已学完全部课时'
}

/** The bars of the progress strip, one per lesson, lit for the lessons already learned. */
export function progressSegments(total: number, done: number): boolean[] {
  return Array.from({ length: total }, (_, index) => index < done)
}
