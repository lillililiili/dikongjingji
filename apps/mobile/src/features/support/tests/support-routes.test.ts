import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import settingsFlagSource from '../../settings/application/settings-preview.ts?raw'
import pagesJson from '../../../pages.json?raw'
import supportFlagSource from '../application/support-preview.ts?raw'
import { supportCardTargetUrl, SUPPORT_CARD_LABELS } from '../application/support-card'
import { SUPPORT_CARD_TYPES } from '../domain/support'
import {
  buildSupportChatUrl,
  buildSupportOpenUrl,
  parseSupportChatQuery,
  toCardLine,
} from '../navigation/support-routes'

const CONVERSATION = '6f1b2c3d-1111-4222-8333-444455556666'
const ORG = 'c1000000-0000-4000-8000-000000000001'
const REF = 'c2000000-0000-4000-8000-000000000101'

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

// What uni hands onLoad: the query string split into decoded values.
function queryOf(url: string): Record<string, string> {
  const query = url.slice(url.indexOf('?') + 1)
  return Object.fromEntries(query.split('&').map((pair) => {
    const [key, value] = pair.split('=')
    return [key, decodeURIComponent(value)]
  }))
}

describe('support routes', () => {
  it('opens by counterpart or by conversation, with only stable ids and the card hint', () => {
    expect(buildSupportOpenUrl({ kind: 'PLATFORM' })).toBe('/pages/support/chat?kind=PLATFORM')
    expect(buildSupportOpenUrl({ kind: 'MERCHANT', organizationId: ORG }, { type: 'PRODUCT', refId: REF, title: '编程机器人', subtitle: '¥1,299 起' }))
      .toBe(`/pages/support/chat?kind=MERCHANT&organizationId=${ORG}&cardType=PRODUCT&cardId=${REF}`
        + `&cardTitle=${encodeURIComponent('编程机器人')}&cardSubtitle=${encodeURIComponent('¥1,299 起')}`)
    expect(buildSupportChatUrl(CONVERSATION)).toBe(`/pages/support/chat?conversationId=${CONVERSATION}`)
  })

  it('reads back what it wrote and refuses broken links', () => {
    const card = { type: 'MALL_ORDER' as const, refId: REF, title: '套装', subtitle: null }
    expect(parseSupportChatQuery(queryOf(buildSupportOpenUrl({ kind: 'PROVIDER', organizationId: ORG }, card))))
      .toEqual({ conversationId: null, target: { kind: 'PROVIDER', organizationId: ORG }, card })
    expect(parseSupportChatQuery(queryOf(buildSupportChatUrl(CONVERSATION, card))))
      .toEqual({ conversationId: CONVERSATION, target: null, card })
    expect(parseSupportChatQuery({ kind: 'PLATFORM' })).toEqual({ conversationId: null, target: { kind: 'PLATFORM' }, card: null })
    expect(parseSupportChatQuery({ kind: 'MERCHANT' })).toBeNull()
    expect(parseSupportChatQuery({ kind: 'MERCHANT', organizationId: 'nope' })).toBeNull()
    expect(parseSupportChatQuery({ conversationId: 'nope' })).toBeNull()
    expect(parseSupportChatQuery(undefined)).toBeNull()
    // A card the server would refuse is dropped, not offered.
    expect(parseSupportChatQuery({ kind: 'PLATFORM', cardType: 'COUPON', cardId: REF, cardTitle: 'x' })?.card).toBeNull()
    expect(parseSupportChatQuery({ kind: 'PLATFORM', cardType: 'PRODUCT', cardId: 'x', cardTitle: 'x' })?.card).toBeNull()
  })

  it('keeps card words to one line of at most 60 characters', () => {
    expect(toCardLine(' 第一行\n第二行 ')).toBe('第一行 第二行')
    expect(toCardLine('字'.repeat(80))).toHaveLength(60)
    expect(buildSupportOpenUrl({ kind: 'PLATFORM' }, { type: 'PRODUCT', refId: REF, title: '  ', subtitle: null }))
      .toBe('/pages/support/chat?kind=PLATFORM')
  })

  it('survives the login return with its card', () => {
    const url = buildSupportOpenUrl({ kind: 'MERCHANT', organizationId: ORG }, { type: 'PRODUCT', refId: REF, title: '套装', subtitle: '¥1' })
    expect(normalizeInspectionReturnUrl(url)).toBe(url)
    expect(normalizeInspectionReturnUrl(buildSupportChatUrl(CONVERSATION))).toBe(buildSupportChatUrl(CONVERSATION))
    expect(normalizeInspectionReturnUrl('/pages/mine/settings')).toBe('/pages/mine/settings')
    expect(normalizeInspectionReturnUrl('/pages/mine/help')).toBe('/pages/mine/help')
  })

  it('registers the five new pages', () => {
    for (const path of ['pages/support/chat', 'pages/mine/settings', 'pages/mine/help', 'pages/mine/agreement', 'pages/mine/about']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
  })

  it('maps every card type to a page by its stable id, city pages only with a city', () => {
    const expected: Record<string, string | null> = {
      PRODUCT: `/pages/mall/product?productId=${REF}&cityCode=370500`,
      MALL_ORDER: `/pages/mine/goods-order?orderId=${REF}`,
      MALL_INQUIRY: `/pages/mall/inquiry-quotes?inquiryId=${REF}`,
      INSPECTION_SERVICE: `/pages/service/inspection/detail?serviceId=${REF}&cityCode=370500`,
      INSPECTION_ORDER: `/pages/service/inspection/fulfillment?serviceOrderId=${REF}`,
      PRO_SERVICE: `/pages/proservice/detail?serviceId=${REF}&cityCode=370500`,
      ACTIVITY: `/pages/activity/detail?activityId=${REF}&cityCode=370500`,
      ACTIVITY_ENROLLMENT: `/pages/activity/ticket?enrollmentId=${REF}`,
      VENUE: `/pages/venue/detail?venueId=${REF}&cityCode=370500`,
      VENUE_BOOKING: `/pages/venue/booking?bookingId=${REF}`,
      RENTAL_INQUIRY: `/pages/rental/quote?inquiryId=${REF}`,
      RENTAL_ORDER: `/pages/rental/order?rentalOrderId=${REF}`,
      DEMAND: `/pages/demand/quotes?demandId=${REF}`,
    }
    for (const type of SUPPORT_CARD_TYPES) {
      expect(supportCardTargetUrl({ type, refId: REF }, '370500')).toBe(expected[type])
      expect(SUPPORT_CARD_LABELS[type]).toBeTruthy()
    }
    expect(supportCardTargetUrl({ type: 'PRODUCT', refId: REF }, null)).toBeNull()
    expect(supportCardTargetUrl({ type: 'MALL_ORDER', refId: REF }, null)).toBe(`/pages/mine/goods-order?orderId=${REF}`)
  })
})

describe('G1 preview flags', () => {
  it('use exactly the expression of the shared preview flag', () => {
    const shared = flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED')
    expect(flagExpression(supportFlagSource, 'SUPPORT_PREVIEW_ENABLED')).toBe(shared)
    expect(flagExpression(settingsFlagSource, 'SETTINGS_PREVIEW_ENABLED')).toBe(shared)
  })
})
