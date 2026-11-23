import { describe, expect, it, vi } from 'vitest'

import { createExperienceApi } from '../data/experience-api'

const VENUE_ID = 'b2000000-0000-4000-8000-000000000001'
const PROJECT_ID = 'b4000000-0000-4000-8000-000000000101'

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createExperienceApi({ request }), request }
}

const page = (items: unknown[]) => ({ page: 1, size: 20, total: items.length, hasMore: false, items })

describe('experience api', () => {
  it('lists online items, with the type only when one is chosen', async () => {
    const { api, request } = apiReturning(page([{
      experienceId: 'b1000000-0000-4000-8000-000000000001',
      type: 'SIMULATION',
      title: '机器人避障模拟',
      summary: '约 3 分钟',
      coverImage: { url: 'http://insecure.example/a.jpg', alt: '封面' },
      loginRequired: false,
    }]))

    const all = await api.loadExperienceItems(null, 1, 20)
    await api.loadExperienceItems('SIMULATION', 2, 20)

    expect(request.mock.calls[0][0]).toEqual({ method: 'GET', url: '/api/v1/experiences?page=1&size=20' })
    expect(request.mock.calls[1][0].url).toBe('/api/v1/experiences?type=SIMULATION&page=2&size=20')
    expect(all.items[0]).toEqual({
      experienceId: 'b1000000-0000-4000-8000-000000000001',
      type: 'SIMULATION',
      title: '机器人避障模拟',
      summary: '约 3 分钟',
      coverImage: null,
      loginRequired: false,
    })
  })

  it('lists the venues of a city', async () => {
    const { api, request } = apiReturning(page([{
      venueId: VENUE_ID,
      name: '东营低空与机器人体验馆',
      coverImage: { url: 'https://media.example.com/v.jpg', alt: '场馆' },
      openingNote: '09:30–18:00 · 周一闭馆',
      address: '东营区示例路 66 号',
      openSessionsToday: 3,
      projectSummary: ' 模拟飞行 · 机器人编程 · 厂家展区 ',
    }]))

    const venues = await api.loadVenues('370500', 1, 20)

    expect(request.mock.calls[0][0].url).toBe('/api/v1/venues?cityCode=370500&page=1&size=20')
    expect(venues.items[0].coverImage).toEqual({ url: 'https://media.example.com/v.jpg', alt: '场馆' })
    expect(venues.items[0].openSessionsToday).toBe(3)
    expect(venues.items[0].projectSummary).toBe('模拟飞行 · 机器人编程 · 厂家展区')
  })

  it('loads a venue by stable ids and flattens the operator', async () => {
    const { api, request } = apiReturning({
      venueId: VENUE_ID,
      name: '东营低空与机器人体验馆',
      coverImage: null,
      openingNote: null,
      address: '东营区示例路 66 号',
      latitude: 37.4487,
      longitude: 118.5823,
      phone: '0546-8000 0000',
      operator: { organizationId: 'a1000000-0000-4000-8000-000000000004', name: '东营低空与机器人体验馆' },
      projects: [{
        projectId: PROJECT_ID,
        name: '机器人避障闯关',
        durationMinutes: 30,
        minAge: 6,
        capacity: 8,
        limitNote: '未满 8 岁需 1 名成人陪同',
        priceAmountMinor: 3900,
        currency: 'CNY',
        onlinePayment: true,
      }],
      infoItems: [{ label: '到馆须知', content: '请提前 10 分钟到馆' }],
      deviceCount: 6,
    })

    const venue = await api.loadVenue(VENUE_ID, '370500')

    expect(request).toHaveBeenCalledWith({ method: 'GET', url: `/api/v1/venues/${VENUE_ID}?cityCode=370500` })
    expect(venue.operatorName).toBe('东营低空与机器人体验馆')
    expect(venue.projects[0]).toEqual({
      projectId: PROJECT_ID,
      name: '机器人避障闯关',
      durationMinutes: 30,
      minAge: 6,
      capacity: 8,
      limitNote: '未满 8 岁需 1 名成人陪同',
      priceAmountMinor: 3900,
      onlinePayment: true,
    })
    expect(venue.deviceCount).toBe(6)
  })

  it('loads the sessions of one project on one day', async () => {
    const session = {
      sessionId: 's1',
      startsAt: '2026-11-17T02:00:00Z',
      endsAt: '2026-11-17T02:30:00Z',
      capacity: 8,
      seatsLeft: 5,
      status: 'OPEN',
    }
    const { api, request } = apiReturning({ date: '2026-11-17', sessions: [session] })

    const sessions = await api.loadSessions({
      venueId: VENUE_ID,
      cityCode: '370500',
      projectId: PROJECT_ID,
      date: '2026-11-17',
    })

    expect(request.mock.calls[0][0].url).toBe(
      `/api/v1/venues/${VENUE_ID}/sessions?cityCode=370500&projectId=${PROJECT_ID}&date=2026-11-17`,
    )
    expect(sessions).toEqual([session])
  })

  it('lists devices with an optional category', async () => {
    const { api, request } = apiReturning(page([{
      deviceId: 'd1',
      category: 'ROBOT',
      name: '巡检机器狗',
      summary: null,
      image: null,
      trialMode: 'DISPLAY_ONLY',
      product: {
        productId: 'c2000000-0000-4000-8000-000000000401',
        saleMode: 'RENT',
        fromAmountMinor: 38000,
        priceUnit: 'DAY',
        multiplePrices: true,
        sellerCount: 1,
      },
    }, {
      deviceId: 'd2',
      category: 'PARTS',
      name: '无人机配件套装',
      summary: null,
      image: null,
      trialMode: 'DISPLAY_ONLY',
    }]))

    await api.loadDevices({ venueId: VENUE_ID, cityCode: '370500', category: null }, 1, 20)
    const robots = await api.loadDevices({ venueId: VENUE_ID, cityCode: '370500', category: 'ROBOT' }, 1, 20)

    expect(request.mock.calls[0][0].url).toBe(`/api/v1/venues/${VENUE_ID}/devices?cityCode=370500&page=1&size=20`)
    expect(request.mock.calls[1][0].url)
      .toBe(`/api/v1/venues/${VENUE_ID}/devices?cityCode=370500&category=ROBOT&page=1&size=20`)
    expect(robots.items[0].trialMode).toBe('DISPLAY_ONLY')
    // 1.5.0: the device's product as it sells in the city; a server before 1.5.0 sends none.
    expect(robots.items[0].product).toMatchObject({ saleMode: 'RENT', fromAmountMinor: 38000, sellerCount: 1 })
    expect(robots.items[1].product).toBeNull()
  })
})
