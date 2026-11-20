import type { ActivityCard, ActivityDetail } from '../../activity/domain/activity'
import type { ContentArticleCard } from '../domain/content'

export const CITY = '370500'

export function articleCard(overrides: Partial<ContentArticleCard> = {}): ContentArticleCard {
  return {
    articleId: 'f3000000-0000-4000-8000-000000000001',
    kind: 'CASE',
    title: '一台机器狗如何完成园区夜间巡逻',
    author: { kind: 'PROVIDER', organizationId: 'f2000000-0000-4000-8000-000000000001', name: '鹰眼智能巡检' },
    video: null,
    sponsored: false,
    cityCode: CITY,
    coverImage: null,
    sample: true,
    publishedAt: '2026-10-28T02:00:00Z',
    ...overrides,
  }
}

export function articleCardDto(overrides: Record<string, unknown> = {}) {
  return {
    articleId: 'f3000000-0000-4000-8000-000000000001',
    kind: 'CASE',
    title: '一台机器狗如何完成园区夜间巡逻',
    author: { kind: 'PROVIDER', organizationId: 'f2000000-0000-4000-8000-000000000001', name: '鹰眼智能巡检' },
    sponsored: false,
    cityCode: CITY,
    coverImage: null,
    sample: true,
    publishedAt: '2026-10-28T02:00:00Z',
    ...overrides,
  }
}

export function activityCard(id: string, type: ActivityCard['type']): ActivityCard {
  return {
    activityId: id,
    type,
    title: `活动 ${id}`,
    coverImage: null,
    venueName: '东营低空与机器人体验馆',
    scheduleNote: null,
    price: { mode: 'FREE', amountMinor: null },
    ageRange: { minAge: null, maxAge: null },
    enrollmentStatus: 'OPEN',
    nextSession: null,
  }
}

export function activityDetail(overrides: Partial<ActivityDetail> = {}): ActivityDetail {
  return {
    activityId: 'a2000000-0000-4000-8000-000000000001',
    type: 'CONTEST',
    title: '青少年无人机编程挑战赛（东营站）',
    subtitle: null,
    coverImage: null,
    price: { mode: 'PER_PERSON', amountMinor: 12000 },
    ageRange: { minAge: 8, maxAge: 16 },
    enrollmentStatus: 'OPEN',
    organizer: { organizationId: 'o1', name: '东营城市运营中心' },
    coOrganizer: null,
    location: { name: '东营低空与机器人体验馆', address: '东营区', latitude: null, longitude: null },
    scheduleNote: null,
    eligibilityNote: null,
    seatNote: null,
    refundNote: null,
    waitlistAllowed: false,
    teamEnrollmentAllowed: false,
    sessions: [
      {
        sessionId: 's-ended',
        label: null,
        startsAt: '2026-11-10T01:00:00Z',
        endsAt: null,
        checkInOpensAt: null,
        enrollmentClosesAt: '2026-11-09T01:00:00Z',
        capacity: 60,
        seatsLeft: 0,
        status: 'ENDED',
      },
      {
        sessionId: 's-open',
        label: '上午',
        startsAt: '2026-12-02T01:00:00Z',
        endsAt: null,
        checkInOpensAt: null,
        enrollmentClosesAt: '2026-12-01T01:00:00Z',
        capacity: 60,
        seatsLeft: 12,
        status: 'OPEN',
      },
    ],
    onlinePayment: true,
    ...overrides,
  }
}
