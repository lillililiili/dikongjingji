import { describe, expect, it, vi } from 'vitest'

import { createActivityApi } from '../data/activity-api'
import type { ActivityListQuery } from '../domain/activity'

const QUERY: ActivityListQuery = {
  cityCode: '370500',
  type: null,
  date: null,
  enrollingOnly: false,
  freeOnly: false,
}

const CARD_DTO = {
  activityId: 'a2000000-0000-4000-8000-000000000001',
  type: 'CONTEST',
  title: '青少年无人机编程挑战赛（东营站）',
  coverImage: null,
  venueName: '东营低空与机器人体验馆',
  scheduleNote: null,
  price: { mode: 'PER_PERSON', amountMinor: 12000, currency: 'CNY' },
  ageRange: { minAge: 8, maxAge: 15 },
  enrollmentStatus: 'OPEN',
  nextSession: {
    sessionId: 'a3000000-0000-4000-8000-000000000101',
    startsAt: '2026-11-28T01:00:00Z',
    capacity: 60,
    seatsLeft: 12,
  },
}

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createActivityApi({ request }), request }
}

describe('activity api', () => {
  it('asks for a page with only the filters that are switched on', async () => {
    const { api, request } = apiReturning({ page: 1, size: 10, total: 0, hasMore: false, items: [] })

    await api.loadPage(QUERY, 1, 10)

    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: '/api/v1/activities?cityCode=370500&page=1&size=10',
    })
  })

  it('sends type, date and both switches when they are set', async () => {
    const { api, request } = apiReturning({ page: 2, size: 10, total: 0, hasMore: false, items: [] })

    await api.loadPage(
      { cityCode: '370500', type: 'STUDY_TOUR', date: '2026-11-28', enrollingOnly: true, freeOnly: true },
      2,
      10,
    )

    expect(request.mock.calls[0][0].url).toBe(
      '/api/v1/activities?cityCode=370500&type=STUDY_TOUR&date=2026-11-28&enrolling=true&free=true&page=2&size=10',
    )
  })

  it('maps a card and keeps the server status untouched', async () => {
    const { api } = apiReturning({ page: 1, size: 10, total: 1, hasMore: false, items: [CARD_DTO] })

    const page = await api.loadPage(QUERY, 1, 10)

    expect(page).toMatchObject({ page: 1, total: 1, hasMore: false })
    expect(page.items[0]).toEqual({
      activityId: CARD_DTO.activityId,
      type: 'CONTEST',
      title: CARD_DTO.title,
      coverImage: null,
      venueName: '东营低空与机器人体验馆',
      scheduleNote: null,
      price: { mode: 'PER_PERSON', amountMinor: 12000 },
      ageRange: { minAge: 8, maxAge: 15 },
      enrollmentStatus: 'OPEN',
      nextSession: CARD_DTO.nextSession,
    })
  })

  it('drops an amount that does not belong to the price mode and an image that is not https', async () => {
    const { api } = apiReturning({
      page: 1,
      size: 10,
      total: 1,
      hasMore: false,
      items: [{
        ...CARD_DTO,
        price: { mode: 'FREE', amountMinor: 9900, currency: 'CNY' },
        coverImage: { url: 'http://insecure.example/a.jpg', alt: '封面' },
      }],
    })

    const card = (await api.loadPage(QUERY, 1, 10)).items[0]

    expect(card.price).toEqual({ mode: 'FREE', amountMinor: null })
    expect(card.coverImage).toBeNull()
  })

  it('keeps an https cover image', async () => {
    const { api } = apiReturning({
      page: 1,
      size: 10,
      total: 1,
      hasMore: false,
      items: [{ ...CARD_DTO, coverImage: { url: 'https://media.example.com/a.jpg', alt: '封面' } }],
    })

    expect((await api.loadPage(QUERY, 1, 10)).items[0].coverImage)
      .toEqual({ url: 'https://media.example.com/a.jpg', alt: '封面' })
  })

  it('loads the dates that have sessions', async () => {
    const { api, request } = apiReturning({ dates: ['2026-11-17', '2026-11-28'] })

    const dates = await api.loadSessionDates({
      cityCode: '370500',
      type: 'CONTEST',
      from: '2026-11-16',
      to: '2026-11-29',
    })

    expect(dates).toEqual(['2026-11-17', '2026-11-28'])
    expect(request.mock.calls[0][0].url).toBe(
      '/api/v1/activities/session-dates?cityCode=370500&type=CONTEST&from=2026-11-16&to=2026-11-29',
    )
  })

  it('loads a detail by stable ids only', async () => {
    const detailDto = {
      activityId: CARD_DTO.activityId,
      type: 'CONTEST',
      title: CARD_DTO.title,
      subtitle: '图形化编程 · 现场闯关',
      coverImage: null,
      price: CARD_DTO.price,
      ageRange: CARD_DTO.ageRange,
      enrollmentStatus: 'OPEN',
      organizer: { organizationId: 'a1000000-0000-4000-8000-000000000001', name: '示例青少年科技机构' },
      coOrganizer: null,
      location: { name: '东营低空与机器人体验馆', address: '东营区示例路 66 号', latitude: 37.4487, longitude: 118.5823 },
      scheduleNote: null,
      eligibilityNote: '8–15 岁，由监护人代报名并到场',
      seatNote: null,
      refundNote: null,
      waitlistAllowed: true,
      teamEnrollmentAllowed: true,
      sessions: [{
        sessionId: 'a3000000-0000-4000-8000-000000000101',
        label: '上午',
        startsAt: '2026-11-28T01:00:00Z',
        endsAt: null,
        checkInOpensAt: '2026-11-28T00:30:00Z',
        enrollmentClosesAt: '2026-11-27T10:00:00Z',
        capacity: 60,
        seatsLeft: 12,
        status: 'OPEN',
      }],
    }
    const { api, request } = apiReturning(detailDto)

    const detail = await api.loadDetail(CARD_DTO.activityId, '370500')

    expect(request).toHaveBeenCalledWith({
      method: 'GET',
      url: `/api/v1/activities/${CARD_DTO.activityId}?cityCode=370500`,
    })
    expect(detail.organizer.name).toBe('示例青少年科技机构')
    expect(detail.coOrganizer).toBeNull()
    expect(detail.sessions[0]).toEqual(detailDto.sessions[0])
    expect(detail.price).toEqual({ mode: 'PER_PERSON', amountMinor: 12000 })
    // A server before 1.2.0 says nothing about online payment: none.
    expect(detail.onlinePayment).toBe(false)

    const paid = await apiReturning({ ...detailDto, price: { ...CARD_DTO.price, onlinePayment: true } }).api
      .loadDetail(CARD_DTO.activityId, '370500')
    expect(paid.onlinePayment).toBe(true)
    // Only a per-person price is ever paid online, whatever the flag says.
    const free = await apiReturning({ ...detailDto, price: { mode: 'FREE', amountMinor: null, currency: 'CNY', onlinePayment: true } }).api
      .loadDetail(CARD_DTO.activityId, '370500')
    expect(free.onlinePayment).toBe(false)
  })
})
