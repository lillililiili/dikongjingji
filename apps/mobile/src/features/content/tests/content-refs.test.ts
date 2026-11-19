import { describe, expect, it } from 'vitest'

import type { VenueDetail, VenueSession } from '../../experience/domain/experience'
import type { InspectionServiceDetail } from '../../inspection/domain/inspection-service-detail'
import type { MallProductDetail } from '../../mall/domain/mall'
import type { ProServiceDetail } from '../../proservice/domain/proservice'
import {
  loadTopicActivities,
  loadTopicVenues,
  resolveContentRefs,
  toTopicActivityView,
  type ContentLinkedApis,
} from '../application/content-refs'
import type { ContentRef } from '../domain/content'
import { CITY, activityDetail } from './content-fixtures'

const INSPECTION = '77777777-7777-7777-7777-777777777777'
const PRO = 'f2100000-0000-4000-8000-000000000005'
const PRODUCT = 'c2000000-0000-4000-8000-000000000401'
const ACTIVITY = 'a2000000-0000-4000-8000-000000000002'
const VENUE = 'b2000000-0000-4000-8000-000000000001'

function offer(amountMinor: number | null, unit: 'ITEM' | 'DAY' | 'HALF_DAY' = 'ITEM') {
  return {
    offerId: `o${amountMinor}`,
    skuId: 'sku',
    skuName: '标准版',
    merchantId: 'm',
    merchantName: '示例商家',
    merchantKind: 'LOCAL_STORE' as const,
    amountMinor,
    unit,
    deliveryNote: null,
    shippingNote: null,
    fulfilledFrom: null,
    onlinePayment: true,
  }
}

function product(overrides: Partial<MallProductDetail>): MallProductDetail {
  return {
    productId: PRODUCT,
    category: 'ROBOT',
    name: '巡检机器狗',
    summary: null,
    coverImage: null,
    saleMode: 'RENT',
    experienceBadge: null,
    offers: [offer(38000, 'DAY'), offer(42000, 'DAY')],
    specs: [],
    packItems: [],
    ...overrides,
  } as MallProductDetail
}

function venue(overrides: Partial<VenueDetail> = {}): VenueDetail {
  return {
    venueId: VENUE,
    name: '东营低空与机器人体验馆',
    coverImage: null,
    openingNote: '09:30–18:00',
    address: '东营区',
    latitude: null,
    longitude: null,
    phone: null,
    operatorName: '示例运营',
    projects: [
      { projectId: 'p1', name: '模拟飞行', durationMinutes: 30, minAge: null, capacity: 8, limitNote: null, priceAmountMinor: 4900, onlinePayment: true },
      { projectId: 'p2', name: '机器人闯关', durationMinutes: 30, minAge: null, capacity: 8, limitNote: null, priceAmountMinor: 3900, onlinePayment: true },
    ],
    infoItems: [],
    deviceCount: 0,
    ...overrides,
  }
}

function session(id: string, startsAt: string, status: VenueSession['status'], seatsLeft = 4): VenueSession {
  return { sessionId: id, startsAt, endsAt: startsAt, capacity: 8, seatsLeft, status }
}

function apis(overrides: Partial<ContentLinkedApis> = {}): ContentLinkedApis {
  return {
    inspection: {
      loadDetail: async () => ({
        title: '园区综合巡检服务',
        priceAmountMinor: null,
        priceUnitLabel: '',
        heroImageUrl: '/static/images/inspection/service-power-line.webp',
      } as unknown as InspectionServiceDetail),
    },
    proService: {
      loadService: async () => ({
        title: '无人机航测与三维建模',
        price: { mode: 'PRICE_FROM', amountMinor: 680000, unit: '/项目起', note: null },
        coverUrl: 'https://cdn.example/survey.jpg',
      } as unknown as ProServiceDetail),
    },
    mall: { loadProduct: async () => product({}) },
    activity: { loadDetail: async () => activityDetail({ activityId: ACTIVITY, type: 'STUDY_TOUR', price: { mode: 'PER_PERSON', amountMinor: 16800 } }) },
    experience: {
      loadVenue: async () => venue(),
      loadSessions: async () => [],
    },
    ...overrides,
  }
}

const REFS: ContentRef[] = [
  { type: 'INSPECTION_SERVICE', refId: INSPECTION, title: '园区综合巡检服务' },
  { type: 'PRO_SERVICE', refId: PRO, title: '无人机航测' },
  { type: 'PRODUCT', refId: PRODUCT, title: '巡检机器狗' },
  { type: 'ACTIVITY', refId: ACTIVITY, title: '机器人研学体验' },
  { type: 'VENUE', refId: VENUE, title: '体验馆' },
]

describe('内容中提到', () => {
  it('reads each mention from its own module and points at its real page in the city', async () => {
    const views = await resolveContentRefs(REFS, CITY, apis())

    expect(views.map((view) => [view.tag, view.title, view.price, view.actionLabel, view.primary, view.url])).toEqual([
      ['服务', '园区综合巡检服务', { amount: '按项目报价', unit: '' }, '看服务', true,
        `/pages/service/inspection/detail?serviceId=${INSPECTION}&cityCode=${CITY}`],
      ['服务', '无人机航测与三维建模', { amount: '¥6,800', unit: '/项目起' }, '看服务', true,
        `/pages/proservice/detail?serviceId=${PRO}&cityCode=${CITY}`],
      ['租赁', '巡检机器狗', { amount: '¥380', unit: '/天 起' }, '看设备', false,
        `/pages/mall/product?productId=${PRODUCT}&cityCode=${CITY}`],
      ['活动', '青少年无人机编程挑战赛（东营站）', { amount: '¥168', unit: '/人' }, '去报名', false,
        `/pages/activity/detail?activityId=${ACTIVITY}&cityCode=${CITY}`],
      ['场馆', '东营低空与机器人体验馆', { amount: '¥39', unit: '起' }, '看场馆', false,
        `/pages/venue/detail?venueId=${VENUE}&cityCode=${CITY}`],
    ])
    // The inspection detail's bundled stand-in picture is not a picture of the service.
    expect(views[0].imageUrl).toBeNull()
    expect(views[1].imageUrl).toBe('https://cdn.example/survey.jpg')
  })

  it('leaves out a mention that cannot be read in this city', async () => {
    const views = await resolveContentRefs(REFS, CITY, apis({
      mall: { loadProduct: async () => { throw new Error('404') } },
      inspection: { loadDetail: async () => { throw new Error('offline') } },
    }))
    expect(views.map((view) => view.type)).toEqual(['PRO_SERVICE', 'ACTIVITY', 'VENUE'])
  })

  it('prices goods: one price without 起, several with 起, inquiry goods as 询价', async () => {
    const one = await resolveContentRefs([REFS[2]], CITY, apis({
      mall: { loadProduct: async () => product({ saleMode: 'BUY', offers: [offer(129900)] }) },
    }))
    const several = await resolveContentRefs([REFS[2]], CITY, apis({
      mall: { loadProduct: async () => product({ saleMode: 'BUY', offers: [offer(129900), offer(139900)] }) },
    }))
    const inquiry = await resolveContentRefs([REFS[2]], CITY, apis({
      mall: { loadProduct: async () => product({ saleMode: 'INQUIRY', offers: [offer(null)] }) },
    }))
    expect([one[0].tag, one[0].price, one[0].actionLabel]).toEqual(['商品', { amount: '¥1,299', unit: '' }, '看商品'])
    expect(several[0].price).toEqual({ amount: '¥1,299', unit: '起' })
    expect(inquiry[0].price).toEqual({ amount: '询价', unit: '' })
  })
})

describe('专题的本季活动和到馆体验', () => {
  it('shows an activity with its next open session, price and seats', () => {
    const view = toTopicActivityView(activityDetail(), CITY)
    expect(view).toMatchObject({
      tag: '赛事',
      title: '青少年无人机编程挑战赛（东营站）',
      when: '12/02 09:00 · 东营低空与机器人体验馆',
      price: { amount: '¥120', unit: '/人' },
      status: '余 12/60',
      statusTone: 'ok',
      url: `/pages/activity/detail?activityId=a2000000-0000-4000-8000-000000000001&cityCode=${CITY}`,
    })
    expect(toTopicActivityView(activityDetail({ enrollmentStatus: 'FULL_WAITLIST' }), CITY))
      .toMatchObject({ status: '已满 · 可候补', statusTone: 'warn' })
  })

  it('keeps the topic order and leaves out an activity that cannot be read', async () => {
    const views = await loadTopicActivities(['a1', 'gone', 'a3'], CITY, {
      activity: {
        loadDetail: async (id) => {
          if (id === 'gone') throw new Error('404')
          return activityDetail({ activityId: id, title: id })
        },
      },
    })
    expect(views.map((view) => view.title)).toEqual(['a1', 'a3'])
  })

  it('lists today’s sessions of the venue that have not ended, earliest first, a few of them', async () => {
    const dates: string[] = []
    const views = await loadTopicVenues([VENUE], CITY, {
      experience: {
        loadVenue: async () => venue(),
        loadSessions: async ({ projectId, date }) => {
          dates.push(date)
          if (projectId === 'p1') {
            return [
              session('p1-ended', '2026-11-19T01:00:00Z', 'ENDED'),
              session('p1-late', '2026-11-19T08:00:00Z', 'OPEN', 6),
              session('p1-full', '2026-11-19T06:00:00Z', 'FULL', 0),
            ]
          }
          return [session('p2-early', '2026-11-19T02:00:00Z', 'OPEN', 2), session('p2-last', '2026-11-19T09:00:00Z', 'OPEN')]
        },
      },
    }, new Date('2026-11-19T00:30:00Z'))

    expect(dates).toEqual(['2026-11-19', '2026-11-19'])
    expect(views).toHaveLength(1)
    expect(views[0]).toMatchObject({ name: '东营低空与机器人体验馆', openingNote: '09:30–18:00', url: `/pages/venue/detail?venueId=${VENUE}&cityCode=${CITY}` })
    expect(views[0].sessions.map((item) => [item.projectName, item.when, item.seats])).toEqual([
      ['机器人闯关', '今天 10:00 场', '余 2 位'],
      ['模拟飞行', '今天 14:00 场', '已满'],
      ['模拟飞行', '今天 16:00 场', '余 6 位'],
    ])
  })
})
