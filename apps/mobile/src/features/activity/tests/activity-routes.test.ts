import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import {
  ACTIVITY_PARTICIPANTS_PATH,
  buildActivityDetailUrl,
  buildActivityEnrollUrl,
  buildActivityEnrollmentsUrl,
  buildActivityChangeUrl,
  buildActivityOrderUrl,
  buildActivityRefundUrl,
  buildActivityResultUrl,
  buildActivityTeamOrderUrl,
  buildActivityTeamSignupUrl,
  buildActivityTicketUrl,
  buildActivityWaitlistUrl,
} from '../navigation/activity-routes'

describe('activity routes', () => {
  it('carries only stable ids', () => {
    expect(buildActivityDetailUrl('a 1', '370500')).toBe('/pages/activity/detail?activityId=a%201&cityCode=370500')
    expect(buildActivityEnrollUrl('a1', 's1', '370500'))
      .toBe('/pages/activity/enroll?activityId=a1&sessionId=s1&cityCode=370500')
    expect(buildActivityTicketUrl('e1')).toBe('/pages/activity/ticket?enrollmentId=e1')
    expect(buildActivityEnrollmentsUrl()).toBe('/pages/mine/activity-enrollments?status=UPCOMING')
    expect(buildActivityEnrollmentsUrl('CANCELLED')).toBe('/pages/mine/activity-enrollments?status=CANCELLED')
    expect(buildActivityOrderUrl('e 1')).toBe('/pages/activity/order?enrollmentId=e%201')
    expect(buildActivityRefundUrl('e1')).toBe('/pages/activity/refund?enrollmentId=e1')
    expect(buildActivityRefundUrl('e1', 't1')).toBe('/pages/activity/refund?enrollmentId=e1&ticketId=t1')
  })

  // The login page only returns to URLs it knows; every sign-up page must be one of them.
  it('can be returned to after signing in', () => {
    for (const url of [
      buildActivityEnrollUrl('a1', 's1', '370500'),
      buildActivityTicketUrl('e1'),
      buildActivityEnrollmentsUrl('FINISHED'),
      ACTIVITY_PARTICIPANTS_PATH,
      // S5-3
      buildActivityWaitlistUrl('w1'),
      buildActivityTeamSignupUrl('a1', 's1', '370500'),
      buildActivityTeamOrderUrl('e1'),
      buildActivityResultUrl('e1'),
      buildActivityChangeUrl('e1', 'c1'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
  })

  it('drops parameters the pages do not take, and refuses a form without an activity', () => {
    expect(normalizeInspectionReturnUrl('/pages/activity/ticket?enrollmentId=e1&next=https://evil.example'))
      .toBe('/pages/activity/ticket?enrollmentId=e1')
    expect(normalizeInspectionReturnUrl('/pages/activity/enroll?sessionId=s1')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/activity/ticket')).toBe('/pages/home/index')
  })
})
