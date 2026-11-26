import { describe, expect, it } from 'vitest'

import { buildShareLink, describeShareSubtitle } from '../../../shared/share/share-link'
import { AGREEMENT_DOCS, AGREEMENT_DOCUMENTS } from '../domain/agreement'
import { buildAgreementUrl, parseAgreementDoc } from '../navigation/settings-routes'

describe('agreement routes', () => {
  it('names the document by a stable code and falls back to the privacy policy', () => {
    expect(buildAgreementUrl('TRADE_AGREEMENT')).toBe('/pages/mine/agreement?doc=TRADE_AGREEMENT')
    expect(parseAgreementDoc('ENROLLMENT_NOTICE')).toBe('ENROLLMENT_NOTICE')
    expect(parseAgreementDoc('nope')).toBe('PRIVACY_POLICY')
    expect(parseAgreementDoc(undefined)).toBe('PRIVACY_POLICY')
  })

  it('has the five documents of design assumption 22 by their stable names', () => {
    expect(AGREEMENT_DOCS.map((doc) => AGREEMENT_DOCUMENTS[doc].title))
      .toEqual(['隐私政策', '用户协议', '儿童个人信息保护规则', '平台交易协议', '报名须知与照片使用说明'])
  })
})

describe('share link', () => {
  it('copies this site with the hash route, or the route alone without a web address', () => {
    expect(buildShareLink('/pages/mall/product?productId=p1&cityCode=370500', { origin: 'https://app.example.test', pathname: '/' }))
      .toBe('https://app.example.test/#/pages/mall/product?productId=p1&cityCode=370500')
    expect(buildShareLink('pages/venue/detail?venueId=v1&cityCode=370500', null))
      .toBe('/pages/venue/detail?venueId=v1&cityCode=370500')
  })
})

describe('share subtitle', () => {
  const target = { subject: '商品' as const, subtitle: '¥1,299 起', path: '/pages/mall/product?productId=p&cityCode=370500' }

  it('says where a product is sold when the link is for the current city', () => {
    expect(describeShareSubtitle(target, { code: '370500', name: '东营市' })).toBe('¥1,299 起 · 东营可售')
    expect(describeShareSubtitle(target, { code: '370100', name: '济南市' })).toBe('¥1,299 起')
    expect(describeShareSubtitle({ ...target, subject: '活动' }, { code: '370500', name: '东营市' })).toBe('¥1,299 起')
    expect(describeShareSubtitle(target, null)).toBe('¥1,299 起')
  })
})
