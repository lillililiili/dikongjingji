import type { CourseRefundBlock } from '../domain/course'
import { REFUND_BLOCK_TEXTS } from './course-format'

export interface CourseFailure {
  message: string
  // The account must sign in (again).
  signIn: boolean
  // What the page shows is out of date: read it again.
  reload: boolean
}

const MESSAGES: Record<string, string> = {
  COURSE_NOT_PURCHASABLE: '这门课现在不能报名，请稍后再试',
  COURSE_PAYMENT_UNAVAILABLE: '开课机构暂未开通在线支付',
  COURSE_ALREADY_OWNED: '你已经开通了这门课，可以直接去学习',
  COURSE_AMOUNT_CHANGED: '课程价格有变化，已为你刷新，请核对后再报名',
  COURSE_IDEMPOTENCY_KEY_REUSED: '请求重复，请重新提交',
  COURSE_NOT_LEARNABLE: '课程没有开通或已过期',
  COURSE_LESSON_NOT_STARTED: '这节课还没开始播放',
  COURSE_REFUND_NOT_ALLOWED: '这门课现在不能在线退款',
}

const RELOAD = new Set(['COURSE_NOT_PURCHASABLE', 'COURSE_ALREADY_OWNED', 'COURSE_AMOUNT_CHANGED', 'COURSE_NOT_LEARNABLE', 'COURSE_REFUND_NOT_ALLOWED'])

// The shape of the platform's request error (shared/http's ApiRequestError). Read by shape so this
// file stays free of the HTTP client and can be tested on its own.
interface RequestFailure {
  status: number
  code: string | null
  details?: Record<string, unknown> | null
}

function asRequestFailure(error: unknown): RequestFailure | null {
  if (!(error instanceof Error) || !('status' in error) || typeof error.status !== 'number') return null
  const code = 'code' in error && typeof error.code === 'string' ? error.code : null
  const details = 'details' in error && error.details && typeof error.details === 'object'
    ? error.details as Record<string, unknown>
    : null
  return { status: error.status, code, details }
}

/** What to tell the customer about a refused course request, and what the page should do next. */
export function describeCourseFailure(raw: unknown): CourseFailure {
  const error = asRequestFailure(raw)
  if (!error) return { message: '网络不太稳定，请稍后重试', signIn: false, reload: false }
  if (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED') return { message: '请先登录', signIn: true, reload: false }
  if (error.status === 404) return { message: '课程不存在或已下架', signIn: false, reload: true }
  const code = error.code ?? ''
  if (code === 'COURSE_REFUND_NOT_ALLOWED') {
    const reason = error.details?.reason
    const text = typeof reason === 'string' && reason in REFUND_BLOCK_TEXTS ? REFUND_BLOCK_TEXTS[reason as CourseRefundBlock] : MESSAGES[code]
    return { message: text, signIn: false, reload: true }
  }
  return { message: MESSAGES[code] ?? '操作没有成功，请稍后重试', signIn: false, reload: RELOAD.has(code) }
}
