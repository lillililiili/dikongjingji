import { describe, expect, it } from 'vitest'

import {
  PRO_SERVICE_CATEGORY_ICONS,
  PRO_SERVICE_TYPE_INFO,
  avatarText,
  buildProServiceSections,
  citiesText,
  closedCityText,
  formatProServicePrice,
  formatProServiceYuan,
  modeIcon,
  nextPriceSort,
  providerDemandType,
  stepIcon,
} from '../application/proservice-view'
import { PRO_SERVICE_TYPES, type ProviderPage } from '../domain/proservice'
import { detailOf } from './proservice-fixtures'

describe('pro service view', () => {
  it('formats a starting price with its unit and note, and 按项目报价 without an amount', () => {
    expect(formatProServiceYuan(128000)).toBe('¥1,280')
    expect(formatProServiceYuan(380050)).toBe('¥3,800.50')
    expect(formatProServicePrice({ mode: 'PRICE_FROM', amountMinor: 380000, unit: '/平方公里起', note: '含外业采集' }))
      .toEqual({ amount: '¥3,800', unit: '/平方公里起', note: '含外业采集', quote: false })
    expect(formatProServicePrice({ mode: 'PROJECT', amountMinor: null, unit: null, note: null }))
      .toEqual({ amount: '按项目报价', unit: '', note: '方案确认后定价', quote: true })
  })

  it('toggles the price sort low-high, high-low (SvcCatalog)', () => {
    expect(nextPriceSort('DEFAULT')).toBe('PRICE_ASC')
    expect(nextPriceSort('PRICE_ASC')).toBe('PRICE_DESC')
    expect(nextPriceSort('PRICE_DESC')).toBe('PRICE_ASC')
  })

  it('titles the sections by type, in each board order, leaving empty ones out', () => {
    const titles = (type: ProviderPage['proServices'][number]['type']) =>
      buildProServiceSections(detailOf({ type })).map((section) => section.title)
    expect(titles('AERIAL')).toEqual(['拍摄内容', '成果格式'])
    expect(titles('SURVEY')).toEqual(['作业范围', '精度说明', '交付成果'])
    expect(titles('MAINTENANCE')).toEqual(['适用设备', '服务方式', '保修与收费边界'])
    expect(titles('TECH_SUPPORT')).toEqual(['支持内容', '支持方式', '响应说明'])
    expect(buildProServiceSections(detailOf({ type: 'SURVEY', terms: [], outputs: [] })).map((section) => section.title))
      .toEqual(['作业范围'])
    // The board's “也可以上传范围文件” is left out: attachments are not open on the demand form.
    const surveyScope = buildProServiceSections(detailOf({ type: 'SURVEY' }))[0]
    expect(surveyScope).toMatchObject({ kind: 'scope', note: '测区位置和面积在需求里填写' })
    expect(buildProServiceSections(detailOf({ type: 'MAINTENANCE' }))[2].kind).toBe('badges')
  })

  it('names the city that has no service of the type', () => {
    expect(closedCityText('潍坊市', 'AERIAL')).toBe('潍坊还没有开通航拍服务')
    expect(closedCityText(null, 'TECH_SUPPORT')).toBe('这个城市还没有开通技术支持服务')
    expect(citiesText([{ cityName: '济南市' }, { cityName: '东营市' }])).toBe('可服务城市 济南、东营')
    expect(citiesText([])).toBe('')
  })

  it('has a label, colour and icon for every type and scene', () => {
    for (const type of PRO_SERVICE_TYPES) expect(PRO_SERVICE_TYPE_INFO[type].label).toBeTruthy()
    expect(Object.keys(PRO_SERVICE_CATEGORY_ICONS)).toHaveLength(14)
    expect(stepIcon(0, 4)).toBe('compose')
    expect(stepIcon(3, 4)).toBe('auth')
    expect(avatarText(' 云翼科技')).toBe('云')
    expect(['上门服务', '送修服务', '远程支持', '驻场支持'].map(modeIcon))
      .toEqual(['location', 'paperplane', 'headphones', 'person'])
  })

  it('publishes from a provider page as inspection when it has inspection services', () => {
    const page = (inspection: number, types: Array<ProviderPage['proServices'][number]['type']>): ProviderPage => ({
      providerId: 'p1', name: 'n', certificationLabel: null, description: null, cityCode: '370500',
      inspectionServices: Array.from({ length: inspection }, (_, index) => ({
        serviceId: `i${index}`, title: 't', summary: 's', categoryName: 'c',
        price: { mode: 'PROJECT', amountMinor: null, unit: null, note: null },
      })),
      proServices: types.map((type, index) => ({
        serviceId: `s${index}`, type, category: { code: 'GENERAL', label: '综合' }, title: 't', summary: 's',
        tags: [], price: { mode: 'PROJECT', amountMinor: null, unit: null, note: null }, coverUrl: null, sample: false,
      })),
      cities: [],
    })
    expect(providerDemandType(page(1, ['SURVEY']))).toBe('INSPECTION')
    expect(providerDemandType(page(0, ['SURVEY', 'AERIAL']))).toBe('SURVEY')
    expect(providerDemandType(page(0, []))).toBeUndefined()
  })
})
