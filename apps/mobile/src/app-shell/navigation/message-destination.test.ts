import { describe, expect, it } from 'vitest'

import pages from '../../pages.json'
import messagePageSource from '../../pages/message/index.vue?raw'
import centerSource from '../../features/message/presentation/MessageCenterPage.vue?raw'
import { messageTargetUrl } from './message-destination'
import { noticeLinkDestination } from './notice-link-destination'

const routes = new Set((pages as { pages: Array<{ path: string }> }).pages.map((page) => `/${page.path}`))
const pathOf = (url: string | null) => (url ?? '').split('?')[0]

describe('where a notification opens', () => {
  it('opens the goods order, the sign-up ticket or order, and the venue booking by stable id only (R6-5)', () => {
    expect(messageTargetUrl({ type: 'TRADE_ORDER', id: 'o 1', subId: null })).toBe('/pages/mine/goods-order?orderId=o%201')
    expect(messageTargetUrl({ type: 'ACTIVITY_ENROLLMENT', id: 'e1', subId: null }))
      .toBe('/pages/activity/ticket?enrollmentId=e1')
    expect(messageTargetUrl({ type: 'ACTIVITY_ORDER', id: 'e1', subId: null }))
      .toBe('/pages/activity/order?enrollmentId=e1')
    expect(messageTargetUrl({ type: 'VENUE_BOOKING', id: 'b1', subId: null })).toBe('/pages/venue/booking?bookingId=b1')
  })

  it('keeps the inspection, demand and conversation targets', () => {
    expect(pathOf(messageTargetUrl({ type: 'INSPECTION_PLAN', id: 'p', subId: null })))
      .toBe('/pages/service/inspection/plan-confirmation')
    expect(pathOf(messageTargetUrl({ type: 'INSPECTION_DELIVERY', id: 'o', subId: 'v' })))
      .toBe('/pages/service/inspection/delivery')
    // A delivery without its visit falls back to the fulfilment page, as before.
    expect(pathOf(messageTargetUrl({ type: 'INSPECTION_DELIVERY', id: 'o', subId: null })))
      .toBe('/pages/service/inspection/fulfillment')
    expect(pathOf(messageTargetUrl({ type: 'INSPECTION_FULFILLMENT', id: 'o', subId: null })))
      .toBe('/pages/service/inspection/fulfillment')
    expect(pathOf(messageTargetUrl({ type: 'DEMAND', id: 'd', subId: null }))).toBe('/pages/demand/quotes')
    expect(pathOf(messageTargetUrl({ type: 'CONVERSATION', id: 'c', subId: null }))).toBe('/pages/support/chat')
  })

  // M20 直播提醒: the live room by its id; the room page reads it in the App's current city.
  it('opens the live room of a live reminder', () => {
    expect(messageTargetUrl({ type: 'LIVE_ROOM', id: 'f4000000-0000-4000-8000-000000000003', subId: null }))
      .toBe('/pages/live/room?roomId=f4000000-0000-4000-8000-000000000003')
  })

  it('opens nothing for a target this version does not know', () => {
    expect(messageTargetUrl(null)).toBeNull()
  })

  it('only leads to registered pages', () => {
    for (const type of ['TRADE_ORDER', 'ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING', 'INSPECTION_PLAN',
      'INSPECTION_FULFILLMENT', 'DEMAND', 'CONVERSATION', 'LIVE_ROOM'] as const) {
      expect(routes.has(pathOf(messageTargetUrl({ type, id: 'x', subId: null })))).toBe(true)
    }
  })
})

describe('系统通知 (S5-9)', () => {
  it('opens the announcement by its id on a registered page', () => {
    const url = messageTargetUrl({ type: 'SYSTEM_NOTICE', id: 'c5000000-0000-4000-8000-000000000001', subId: null })
    expect(url).toBe('/pages/message/notice?id=c5000000-0000-4000-8000-000000000001')
    expect(routes.has(pathOf(url))).toBe(true)
  })

  it('turns every notice link into a registered page or a not-yet-open message', () => {
    const pagesFor = [
      noticeLinkDestination({ type: 'AGREEMENT', agreement: 'PRIVACY_POLICY' }),
      noticeLinkDestination({ type: 'ACTIVITY_PARTICIPANTS' }),
      noticeLinkDestination({ type: 'NOTIFICATION_SETTINGS' }),
      noticeLinkDestination({ type: 'PLATFORM_SUPPORT' }),
    ]
    for (const destination of pagesFor) {
      expect(destination.kind).toBe('page')
      if (destination.kind === 'page') expect(routes.has(pathOf(destination.url))).toBe(true)
    }
    expect(pagesFor[0]).toEqual({ kind: 'page', url: '/pages/mine/agreement?doc=PRIVACY_POLICY' })
    expect(noticeLinkDestination({ type: 'PRIVACY_PERMISSIONS' })).toEqual({ kind: 'unavailable', message: '权限管理即将开通' })
    expect(noticeLinkDestination({ type: 'PERSONAL_DATA_EXPORT' }).kind).toBe('unavailable')
  })
})

describe('the message tab', () => {
  // 交易物流 and 活动提醒 only in preview builds; public builds keep 服务进度 alone.
  it('shows the new squares behind the trade and activity previews', () => {
    expect(messagePageSource).toContain("...(TRADE_PREVIEW_ENABLED ? ['TRADE_LOGISTICS' as const] : [])")
    expect(messagePageSource)
      .toContain("...(ACTIVITY_PREVIEW_ENABLED || EXPERIENCE_PREVIEW_ENABLED ? ['ACTIVITY_REMINDER' as const] : [])")
    expect(messagePageSource).toContain(':squares="squares"')
    expect(centerSource).toContain("squares: () => ['SERVICE_PROGRESS']")
    // 系统通知 (S5-9) only in preview builds, which also ask the server for announcements.
    expect(messagePageSource).toContain("...(SYSTEM_NOTICE_PREVIEW_ENABLED ? ['SYSTEM_NOTICE' as const] : [])")
    expect(messagePageSource).toContain('systemNotices: SYSTEM_NOTICE_PREVIEW_ENABLED')
  })

  it('marks an unknown notification read without navigating', () => {
    expect(messagePageSource).toContain('const url = messageTargetUrl(message.target)')
    expect(messagePageSource).toContain('if (url) uni.navigateTo({ url })')
  })
})
