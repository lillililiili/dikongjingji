import { describe, expect, it, vi } from 'vitest'

import { createInspectionBookingApi } from '../data/service-flow/inspection-booking-api'

const SERVICE_ID = '33333333-3333-3333-3333-333333333333'

function serviceDetail(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    serviceId: SERVICE_ID,
    category: { code: 'POWER', name: '电力' },
    name: '输电线路无人机巡检',
    summary: '专业无人机电力巡检服务',
    coverImage: { url: 'https://cdn.example.com/cover.png', alt: '封面' },
    price: { mode: 'STARTING_PRICE', startingAmountMinor: 69_900, unitLabel: '公里' },
    inspectionItems: [
      { code: 'LINE', name: '线路本体巡检', description: '线路本体' },
      { code: 'TOWER', name: '杆塔设备巡检', description: '杆塔设备' },
    ],
    ...overrides,
  }
}

function bookingApiAnswering(reply: unknown) {
  const request = vi.fn(async (_input: { url: string; method: string }) => reply)
  return { api: createInspectionBookingApi({ request } as never), request }
}

describe('M04 service lookup', () => {
  it('asks for the service by id, so its rank in the city list cannot hide it', async () => {
    const { api, request } = bookingApiAnswering(serviceDetail())

    await api.loadServiceSnapshot(SERVICE_ID, '370500')

    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith({
      url: `/api/v1/inspection/services/${SERVICE_ID}?cityCode=370500`,
      method: 'GET',
    })
  })

  it('escapes the route values it puts in the path', async () => {
    const { api, request } = bookingApiAnswering(serviceDetail())

    await api.loadServiceSnapshot('a/b', '37 05')

    expect(request.mock.calls[0]?.[0]).toMatchObject({
      url: '/api/v1/inspection/services/a%2Fb?cityCode=37%2005',
    })
  })

  it('offers the items the reservation accepts, in the order they arrive', async () => {
    const { api } = bookingApiAnswering(serviceDetail())

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370500')).resolves.toMatchObject({
      inspectionItems: [
        { code: 'LINE', label: '线路本体巡检' },
        { code: 'TOWER', label: '杆塔设备巡检' },
      ],
    })
  })

  it('keeps every other field the booking card and the reservation use', async () => {
    const { api } = bookingApiAnswering(serviceDetail())

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370100')).resolves.toEqual({
      serviceId: SERVICE_ID,
      categoryName: '电力',
      title: '输电线路无人机巡检',
      summary: '专业无人机电力巡检服务',
      coverImageUrl: 'https://cdn.example.com/cover.png',
      cityCode: '370100',
      inspectionItems: [
        { code: 'LINE', label: '线路本体巡检' },
        { code: 'TOWER', label: '杆塔设备巡检' },
      ],
      priceMode: 'STARTING_PRICE',
      priceAmountMinor: 69_900,
      priceUnitLabel: '公里',
      priceLabel: '¥699/公里',
    })
  })

  it('shows a project quote without an amount or a cover', async () => {
    const { api } = bookingApiAnswering(serviceDetail({
      coverImage: null,
      price: { mode: 'PROJECT_QUOTE', startingAmountMinor: null, unitLabel: null },
    }))

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370500')).resolves.toMatchObject({
      coverImageUrl: null,
      priceMode: 'PROJECT_QUOTE',
      priceAmountMinor: null,
      priceLabel: '按项目报价',
    })
  })

  it('answers null for a service with nothing left to book', async () => {
    const { api } = bookingApiAnswering(serviceDetail({ inspectionItems: [] }))

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370500')).resolves.toBeNull()
  })

  it('leaves a failed read for the page to classify', async () => {
    const failure = Object.assign(new Error('资源不存在'), { status: 404 })
    const request = vi.fn(async () => {
      throw failure
    })
    const api = createInspectionBookingApi({ request } as never)

    await expect(api.loadServiceSnapshot(SERVICE_ID, '370200')).rejects.toBe(failure)
  })
})
