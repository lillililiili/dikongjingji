import { describe, expect, it, vi } from 'vitest'

import type { CityDirectory } from '../../../shared/city/domain/city-directory'
import {
  cityLookupFrom,
  merchantActivityPrice,
  merchantActivityWhen,
  merchantContentTags,
  merchantGlyph,
  merchantInfoRows,
  merchantKindLabel,
  merchantQualRows,
  merchantTabLabel,
  saleCitiesText,
} from '../application/merchant-view'
import { productAfterSales } from '../application/product-aftersales'
import { createMallMerchantApi, toMallMerchantPage } from '../data/merchant-api'
import type { MallMerchantPage } from '../domain/merchant'
import { buildMallMerchantUrl, parseMallMerchantTarget } from '../navigation/mall-routes'
import pagesJson from '../../../pages.json?raw'

const MERCHANT = 'c1000000-0000-4000-8000-000000000001'

function city(code: string, name: string, serviceStatus: 'OPEN' | 'PREPARING' | 'CLOSED') {
  return { code, name, pinyin: '', initials: '', provinceCode: '37', provinceName: '山东省', serviceStatus }
}

const DIRECTORY: CityDirectory = {
  hotCities: [],
  provinces: [{
    code: '37',
    name: '山东省',
    pinyin: '',
    initials: '',
    cities: [city('370500', '东营', 'OPEN'), city('370100', '济南', 'PREPARING')],
  }],
}

const DTO = {
  merchantId: MERCHANT,
  name: '示例机器人商家',
  kind: 'FACTORY_DIRECT',
  qualificationReviewed: true,
  profile: {
    businessEntity: '示例机器人科技有限公司',
    intro: null,
    saleScopeNote: null,
    authorizationUntil: '2027-10-31',
    categoryNote: '教育机器人',
    invoiceIssuer: '示例机器人商家',
    aftersaleParty: '示例机器人商家 · 厂家保修',
    approvedAt: '2026-11-20T02:00:00Z',
  },
  saleCityCodes: ['370500', '370100', '999999'],
  nationwide: true,
  productCount: 8,
  activityCount: 2,
  activities: [
    { activityId: 'a-1', title: '机器人研学体验', coverImage: null, venueName: '东营低空与机器人体验馆', startsAt: '2026-11-08T02:00:00Z', priceMode: 'PER_PERSON', priceAmountMinor: 16800 },
    { activityId: 'a-2', title: '无人机开放体验日', coverImage: { url: 'http://insecure.example/x.jpg', alt: 'x' }, venueName: '', startsAt: '2026-11-14T02:00:00Z', priceMode: 'FREE', priceAmountMinor: null },
    { activityId: 'a-3', title: '未知', coverImage: null, venueName: '', startsAt: '2026-11-14T02:00:00Z', priceMode: 'AUCTION', priceAmountMinor: null },
  ],
  contentCount: 5,
  contents: [
    { contentId: 'v-1', kind: 'VIDEO', title: '3 分钟看懂编程机器人', coverImage: null, sponsored: true, sample: true, publishedAt: '2026-11-01T00:00:00Z' },
    { contentId: 'c-1', kind: 'CASE', title: '校园机器人社团', coverImage: null, sponsored: false, sample: false, publishedAt: '2026-11-01T00:00:00Z' },
  ],
}

function page(overrides: Partial<MallMerchantPage> = {}): MallMerchantPage {
  return { ...toMallMerchantPage(DTO), ...overrides }
}

describe('merchant page api (contract openapi-mall-merchant 1.0.0)', () => {
  it('reads the page by merchant and city', async () => {
    const request = vi.fn().mockResolvedValue(DTO)
    await createMallMerchantApi({ request }).load(MERCHANT, '370500')
    expect(request).toHaveBeenCalledWith({ method: 'GET', url: `/api/v1/mall/merchants/${MERCHANT}?cityCode=370500` })
  })

  it('maps the profile, keeps only HTTPS pictures and drops kinds it does not know', () => {
    const mapped = toMallMerchantPage(DTO)
    expect(mapped.kind).toBe('FACTORY_DIRECT')
    expect(mapped.profile?.businessEntity).toBe('示例机器人科技有限公司')
    expect(mapped.activities.map((item) => item.activityId)).toEqual(['a-1', 'a-2'])
    expect(mapped.activities[1].coverImage).toBeNull()
    expect(mapped.contents.map((item) => item.kind)).toEqual(['VIDEO', 'CASE'])
  })

  it('reads a merchant without an approved profile or offers', () => {
    const bare = toMallMerchantPage({ merchantId: MERCHANT, name: '示例商家', kind: null, qualificationReviewed: false, profile: null, saleCityCodes: [], nationwide: false, productCount: 0, activityCount: 0, activities: [], contentCount: 0, contents: [] })
    expect(bare.profile).toBeNull()
    expect(merchantKindLabel(bare)).toBeNull()
    expect(merchantInfoRows(bare, cityLookupFrom(DIRECTORY))).toEqual([])
    expect(merchantQualRows(bare)).toEqual([{ key: 'license', label: '营业执照', value: '未审核', tone: 'MUTED' }])
  })
})

describe('merchant page view (board Merchant, merchant frame)', () => {
  it('writes the three info rows from the profile and the city directory', () => {
    expect(merchantInfoRows(page(), cityLookupFrom(DIRECTORY))).toEqual([
      { key: 'entity', label: '经营主体', value: '示例机器人科技有限公司' },
      { key: 'cities', label: '可售城市', value: '东营、济南（筹备） · 全国发货' },
      { key: 'until', label: '授权有效期', value: '至 2027-10-31' },
    ])
  })

  it('prefers the profile’s 可售说明 and never shows a bare city code', () => {
    const withNote = page({ profile: { ...page().profile!, saleScopeNote: '山东省内送装' } })
    expect(saleCitiesText(withNote, cityLookupFrom(DIRECTORY))).toBe('东营、济南（筹备） · 山东省内送装')
    // Without the directory no names are known: only the scope is written.
    expect(saleCitiesText(page(), cityLookupFrom(null))).toBe('全国发货')
    expect(saleCitiesText(page({ nationwide: false }), cityLookupFrom(null))).toBeNull()
  })

  it('has the glyph, the kind and the four tabs with their counts', () => {
    expect(merchantGlyph('示例机器人商家')).toBe('示')
    expect(merchantKindLabel(page())).toBe('厂家直营')
    expect((['GOODS', 'ACTS', 'POSTS', 'QUAL'] as const).map((tab) => merchantTabLabel(tab, page())))
      .toEqual(['在售 8', '活动 2', '内容 5', '资质'])
  })

  it('lists the qualifications that are known', () => {
    expect(merchantQualRows(page()).map((row) => [row.label, row.value, row.tone])).toEqual([
      ['经营主体', '示例机器人科技有限公司', 'PLAIN'],
      ['营业执照', '已审核', 'GREEN'],
      ['授权有效期', '至 2027-10-31', 'PLAIN'],
      ['品类', '教育机器人', 'PLAIN'],
    ])
  })

  it('writes activity and content cards', () => {
    const [study, openDay] = page().activities
    expect(merchantActivityWhen(study)).toBe('11/08 10:00 · 东营低空与机器人体验馆')
    expect(merchantActivityPrice(study)).toEqual({ text: '¥168/人', free: false })
    expect(merchantActivityWhen(openDay)).toBe('11/14 10:00')
    expect(merchantActivityPrice(openDay)).toEqual({ text: '免费', free: true })
    expect(merchantContentTags(page().contents[0])).toEqual(['视频', '广告', '示例'])
    expect(merchantContentTags(page().contents[1])).toEqual(['案例'])
  })
})

describe('merchant route', () => {
  it('carries the merchant id and the city only, and is registered', () => {
    expect(buildMallMerchantUrl(MERCHANT, '370500')).toBe(`/pages/mall/merchant?merchantId=${MERCHANT}&cityCode=370500`)
    expect(parseMallMerchantTarget({ merchantId: MERCHANT, cityCode: '370500' })).toEqual({ merchantId: MERCHANT, cityCode: '370500' })
    expect(parseMallMerchantTarget({ merchantId: MERCHANT })).toBeNull()
    expect(pagesJson).toContain('"path": "pages/mall/merchant"')
  })
})

describe('product 售后与保障 (board PdFull)', () => {
  it('promises 7 天无理由 only when the product takes it, and names the after-sales party', () => {
    expect(productAfterSales({ saleMode: 'BUY', noReasonReturn: true, aftersaleParty: '示例机器人商家 · 厂家保修' })).toEqual([
      { title: '7 天无理由退货', text: '未拆封，按销售方规则执行' },
      { title: '保修', text: '由售后责任方 示例机器人商家 · 厂家保修 负责' },
      { title: '平台协助', text: '与商家协商不成时，可联系平台客服协助沟通' },
      { title: '发票', text: '由销售方开具，平台不代开' },
    ])
    expect(productAfterSales({ saleMode: 'BUY', noReasonReturn: false, aftersaleParty: null }).map((line) => line.title))
      .toEqual(['平台协助', '发票'])
    expect(productAfterSales({ saleMode: 'RENT', noReasonReturn: true, aftersaleParty: '示例' })).toEqual([
      { title: '平台协助', text: '与商家协商不成时，可联系平台客服协助沟通' },
      { title: '发票', text: '由出租方开具，平台不代开' },
    ])
  })
})
