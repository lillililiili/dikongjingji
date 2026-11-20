import { describe, expect, it } from 'vitest'

import {
  DETAIL_PRIMARY_ACTION_LABELS,
  createDetailPrimaryIntent,
  resolveDetailPrimaryAction,
} from '../application/detail/inspection-detail-actions'
import bottomBarSource from '../presentation/detail/components/ServiceDetailBottomBar.vue?raw'
import detailShellSource from '../../../pages/service/inspection/detail.vue?raw'
import { serviceCitiesLabel } from '../presentation/detail/service-cities-label'

describe('M03 primary action (InspDetailPark)', () => {
  it('is 发布需求 on a project-priced service only while the demand form is open', () => {
    expect(resolveDetailPrimaryAction('REQUEST_QUOTE', true)).toBe('PUBLISH_DEMAND')
    expect(resolveDetailPrimaryAction('REQUEST_QUOTE', false)).toBe('RESERVE_SERVICE')
    expect(resolveDetailPrimaryAction('RESERVE', true)).toBe('RESERVE_SERVICE')
    expect(resolveDetailPrimaryAction('CONSULT', true)).toBe('RESERVE_SERVICE')
    expect(DETAIL_PRIMARY_ACTION_LABELS.PUBLISH_DEMAND).toBe('发布需求')
    expect(DETAIL_PRIMARY_ACTION_LABELS.RESERVE_SERVICE).toBe('立即预约')
  })

  it('builds the typed intent for each action with stable ids only', () => {
    expect(createDetailPrimaryIntent('PUBLISH_DEMAND', ' park-1 ', '370500', 'provider-1')).toEqual({
      type: 'PUBLISH_DEMAND',
      serviceId: 'park-1',
      providerId: 'provider-1',
    })
    expect(createDetailPrimaryIntent('PUBLISH_DEMAND', 'park-1', '370500')).toEqual({
      type: 'PUBLISH_DEMAND',
      serviceId: 'park-1',
    })
    expect(createDetailPrimaryIntent('RESERVE_SERVICE', 'power-1', '370500', 'provider-1')).toEqual({
      type: 'RESERVE_SERVICE',
      serviceId: 'power-1',
      cityCode: '370500',
    })
  })

  it('lets the host open the demand form with 巡检 picked, behind the demand gateway', () => {
    expect(detailShellSource).toContain("PUBLISH_DEMAND: DEMAND_PREVIEW_ENABLED ? 'ENABLED' : 'HIDDEN'")
    expect(detailShellSource).toContain("type: 'INSPECTION'")
    expect(detailShellSource).toContain('@publish-demand="handlePublishDemand"')
    expect(bottomBarSource).toContain('{{ label }}')
  })
})

describe('M03 provider row service cities (InspDetail)', () => {
  const cities = [
    { code: '370500', name: '东营市' },
    { code: '370100', name: '济南市' },
  ]

  it('names only the city the page is open in, without 市', () => {
    expect(serviceCitiesLabel(cities, '370500')).toBe('可服务城市 东营')
    expect(serviceCitiesLabel(cities, '370100')).toBe('可服务城市 济南')
  })

  it('names the first city and the count when the page city is not listed', () => {
    expect(serviceCitiesLabel(cities, '999999')).toBe('可服务城市 东营等 2 城')
    expect(serviceCitiesLabel([cities[1]], '999999')).toBe('可服务城市 济南')
    expect(serviceCitiesLabel([], '370500')).toBe('')
  })
})
