import { describe, expect, it } from 'vitest'
import pagesSource from '../../../pages.json?raw'
import detailShellSource from '../../../pages/service/inspection/detail.vue?raw'
import detailPageSource from '../presentation/detail/pages/InspectionServiceDetailPage.vue?raw'
import bottomBarSource from '../presentation/detail/components/ServiceDetailBottomBar.vue?raw'
import reviewsSource from '../presentation/detail/components/ServiceReviews.vue?raw'

import {
  createReservationIntent,
  resolveDetailServiceId,
} from '../application/detail/inspection-detail-actions'
import {
  toInspectionServiceDetail,
} from '../application/detail/resolve-inspection-service-detail'
import type { BookingServiceSnapshot } from '../domain/service-flow/booking'

describe('M03 route and data boundary', () => {
  it('registers only one service-detail route with a custom navigation bar', () => {
    const pages = JSON.parse(pagesSource) as {
      pages: Array<{
        path: string
        style?: Record<string, string>
      }>
    }
    const detailRoutes = pages.pages.filter(
      (page) => page.path === 'pages/service/inspection/detail',
    )

    expect(detailRoutes).toHaveLength(1)
    expect(detailRoutes[0]?.style?.navigationStyle).toBe('custom')
  })

  it('uses the incoming serviceId and never manufactures an order', () => {
    expect(resolveDetailServiceId(' power-line-001 ')).toBe('power-line-001')
    expect(resolveDetailServiceId('')).toBe('')
    expect(createReservationIntent('power-line-001')).toEqual({
      type: 'RESERVE_SERVICE',
      serviceId: 'power-line-001',
    })
  })

  it('emits the typed reservation intent to the M03 route shell', () => {
    expect(detailPageSource).toContain("emit('reserve', intent)")
    expect(detailShellSource).toContain('@reserve="handleReserve"')
    expect(detailShellSource).toContain(
      'intent: ReserveServiceIntent',
    )
    expect(detailPageSource).toContain('routeServiceId.value')
    expect(detailPageSource).toContain(':disabled="!canReserve"')
    expect(detailPageSource).toContain('inspectionBookingApi.loadServiceSnapshot')
    expect(detailPageSource).not.toContain('import.meta.env.DEV')
    expect(detailPageSource).not.toContain('data/fixtures')
  })

  it('maps only the real catalog snapshot and leaves unavailable sections empty', () => {
    const snapshot: BookingServiceSnapshot = {
      serviceId: '33333333-3333-3333-3333-333333333333',
      categoryName: '电力巡检',
      title: '输电线路无人机巡检',
      summary: '真实目录摘要',
      coverImageUrl: 'https://assets.example.test/service.webp',
      cityCode: '370500',
      inspectionItems: [{ code: 'TOWER', label: '杆塔设备巡检' }],
      priceMode: 'STARTING_PRICE',
      priceAmountMinor: 69900,
      priceUnitLabel: '公里',
      priceLabel: '¥699/公里',
    }

    expect(toInspectionServiceDetail(snapshot)).toMatchObject({
      serviceId: snapshot.serviceId,
      title: snapshot.title,
      subtitle: snapshot.summary,
      heroImageUrl: snapshot.coverImageUrl,
      priceAmountMinor: 69900,
      processSteps: [],
      deliverables: [],
      guarantees: [],
      explanations: [],
      reviews: [],
    })
  })
})

describe('M03 single-page visual contract', () => {
  it('keeps the approved continuous section order and one guarantee module', () => {
    const expectedOrder = [
      '<ServiceDetailHero',
      '<InspectionScope',
      '<ServiceProcess',
      '<ServiceDeliverables',
      '<ServiceGuarantees',
      '<ServiceExplanation',
      '<ServiceReviews',
    ]
    const positions = expectedOrder.map((marker) =>
      detailPageSource.indexOf(marker),
    )

    expect(positions.every((position) => position >= 0)).toBe(true)
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
    expect(
      detailPageSource.match(/<ServiceGuarantees/g)?.length,
    ).toBe(1)
  })

  it('keeps one review entry point and no duplicate bottom review button', () => {
    expect(reviewsSource.match(/查看全部/g)?.length).toBe(1)
    expect(reviewsSource).not.toContain('查看全部328条评价')
  })

  it('keeps the reservation bar fixed, safe-area aware and subordinate customer service', () => {
    expect(bottomBarSource).toContain('position: fixed')
    expect(bottomBarSource).toContain('env(safe-area-inset-bottom)')
    expect(bottomBarSource).toContain('预约服务')
    expect(bottomBarSource).toContain('客服')
    expect(bottomBarSource).toContain('service-detail-bottom-bar__primary')
    expect(bottomBarSource).toContain('service-detail-bottom-bar__secondary')
  })
})
