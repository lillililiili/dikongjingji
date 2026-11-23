import { describe, expect, it, vi } from 'vitest'

import {
  loadProductExperienceView,
  productExperienceVenueDevicesUrl,
  resolveProductExperiencePill,
  toProductExperienceView,
} from '../application/product-experience'
import { createExperienceApi } from '../data/experience-api'
import type { ProductExperiences } from '../domain/experience'

import mallProductPageSource from '../../mall/presentation/product/MallProductPage.vue?raw'

const KIT = 'c2000000-0000-4000-8000-000000000101'
const VENUE = 'b2000000-0000-4000-8000-000000000001'
const CAMP = 'b2000000-0000-4000-8000-000000000002'
const SIMULATION = 'b1000000-0000-4000-8000-000000000001'
const DEMO = 'b1000000-0000-4000-8000-000000000003'

function data(overrides: Partial<ProductExperiences> = {}): ProductExperiences {
  return {
    productId: KIT,
    experiences: [
      { experienceId: SIMULATION, type: 'SIMULATION', title: '机器人避障模拟', summary: '约 3 分钟 · 免登录可试' },
      { experienceId: DEMO, type: 'RECORDED_DEMO', title: '3 分钟看懂编程机器人', summary: null },
      { experienceId: 'b1000000-0000-4000-8000-000000000004', type: 'SOFTWARE_TRIAL', title: '图形化编程软件试用', summary: null },
    ],
    venues: [{ venueId: VENUE, name: '东营低空与机器人体验馆', deviceName: '编程机器人探索套装' }],
    ...overrides,
  }
}

describe('product experience API (1.2.0)', () => {
  it('reads by product and city and keeps known types and one row per venue', async () => {
    const request = vi.fn().mockResolvedValue({
      productId: KIT,
      experiences: [
        { experienceId: SIMULATION, type: 'SIMULATION', title: '机器人避障模拟', summary: ' ' },
        { experienceId: 'x', type: 'GAME', title: '不认识的类型', summary: null },
      ],
      venues: [
        { venueId: VENUE, name: '东营低空与机器人体验馆', deviceName: '编程机器人探索套装' },
        { venueId: VENUE, name: '东营低空与机器人体验馆', deviceName: '编程机器人探索套装' },
      ],
    })

    const loaded = await createExperienceApi({ request }).loadProductExperiences(KIT, '370500')

    expect(request.mock.calls[0][0]).toEqual({
      method: 'GET',
      url: `/api/v1/experience/product-experiences?productId=${KIT}&cityCode=370500`,
    })
    expect(loaded.experiences).toEqual([
      { experienceId: SIMULATION, type: 'SIMULATION', title: '机器人避障模拟', summary: null },
    ])
    expect(loaded.venues).toHaveLength(1)
  })
})

describe('先体验，再决定', () => {
  it('shows the board pills in order, the first one highlighted, and the venue line', () => {
    const view = toProductExperienceView(data())

    expect(view?.pills.map((pill) => [pill.way, pill.label, pill.targetId, pill.highlighted])).toEqual([
      ['SIMULATION', '虚拟仿真', SIMULATION, true],
      ['RECORDED_DEMO', '录播演示', DEMO, false],
      ['VENUE', '到馆体验', VENUE, false],
    ])
    expect(view?.venueNote).toBe('东营低空与机器人体验馆有这款设备')
    expect(view?.venueId).toBe(VENUE)
  })

  it('shows only what there is, and counts several venues', () => {
    const rental = toProductExperienceView(data({
      experiences: [{ experienceId: DEMO, type: 'RECORDED_DEMO', title: '巡检机器狗演示', summary: null }],
      venues: [
        { venueId: VENUE, name: '东营低空与机器人体验馆', deviceName: '巡检机器狗' },
        { venueId: CAMP, name: '黄河口户外飞行营地', deviceName: '巡检机器狗' },
      ],
    }))
    expect(rental?.pills.map((pill) => [pill.label, pill.highlighted])).toEqual([['录播演示', true], ['到馆体验', false]])
    expect(rental?.venueNote).toBe('东营低空与机器人体验馆等 2 处有这款设备')

    const online = toProductExperienceView(data({ venues: [] }))
    expect(online?.pills.map((pill) => pill.way)).toEqual(['SIMULATION', 'RECORDED_DEMO'])
    expect(online?.venueNote).toBeNull()
    expect(online && productExperienceVenueDevicesUrl(online, '370500')).toBeNull()
  })

  it('is hidden without a link, or with only trials that get no pill', () => {
    expect(toProductExperienceView(data({ experiences: [], venues: [] }))).toBeNull()
    expect(toProductExperienceView(data({
      experiences: [{ experienceId: 'c', type: 'COURSE_TRIAL', title: '试听', summary: null }],
      venues: [],
    }))).toBeNull()
  })

  it('opens a venue page or the simulation, and the recorded demo through the experience gate', () => {
    const view = toProductExperienceView(data())!

    expect(resolveProductExperiencePill(view.pills[2], '370500', true)).toEqual({
      kind: 'NAVIGATE',
      url: `/pages/venue/detail?venueId=${VENUE}&cityCode=370500`,
    })
    expect(resolveProductExperiencePill(view.pills[0], '370500', true))
      .toEqual({ kind: 'NAVIGATE', url: `/pages/experience/simulation?experienceId=${view.pills[0].targetId}` })
    expect(resolveProductExperiencePill(view.pills[1], '370500', true))
      .toEqual({ kind: 'NOTICE', message: '录播演示即将开放' })
    expect(resolveProductExperiencePill(view.pills[0], '370500', false)).toEqual({ kind: 'NONE' })
    expect(productExperienceVenueDevicesUrl(view, '370500'))
      .toBe(`/pages/venue/devices?venueId=${VENUE}&cityCode=370500`)
  })

  it('hides the block when the read fails or there is nothing to ask for', async () => {
    const failing = { loadProductExperiences: vi.fn().mockRejectedValue(new Error('offline')) }
    expect(await loadProductExperienceView(failing, KIT, '370500')).toBeNull()

    const api = { loadProductExperiences: vi.fn().mockResolvedValue(data()) }
    expect(await loadProductExperienceView(api, '', '370500')).toBeNull()
    expect(api.loadProductExperiences).not.toHaveBeenCalled()
    expect((await loadProductExperienceView(api, KIT, '370500'))?.pills).toHaveLength(3)
  })
})

describe('product page placement', () => {
  it('puts 先体验 under the price card and 相关内容 after the folds, each behind its module flag', () => {
    const template = mallProductPageSource.slice(mallProductPageSource.indexOf('<template>'))
    const lead = template.indexOf('mall-product__card--lead')
    const tryFirst = template.indexOf('<ProductTryFirst')
    const terms = template.indexOf('mall-product__card--terms')
    const merchants = template.indexOf('选择在售商家')
    const folds = template.indexOf('mall-product__card--folds')
    const related = template.indexOf('<ContentRelatedBlock')
    const bar = template.indexOf('mall-product__actions')

    expect(lead).toBeGreaterThan(0)
    expect(tryFirst).toBeGreaterThan(lead)
    expect(tryFirst).toBeLessThan(terms)
    expect(tryFirst).toBeLessThan(merchants)
    expect(related).toBeGreaterThan(folds)
    expect(related).toBeLessThan(bar)
    expect(template.indexOf('data-testid="mall-product-try-first"')).toBeGreaterThan(bar)
    expect(mallProductPageSource).toContain('if (EXPERIENCE_PREVIEW_ENABLED)')
    expect(mallProductPageSource).toContain('if (CONTENT_PREVIEW_ENABLED)')
  })
})
