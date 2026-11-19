import { describe, expect, it } from 'vitest'

import { buildShareLink } from '../../../shared/share/share-link'
import { AGREEMENT_BODY_PLACEHOLDER, AGREEMENT_DOCS, AGREEMENT_DOCUMENTS } from '../domain/agreement'
import { buildAgreementUrl, parseAgreementDoc } from '../navigation/settings-routes'

describe('agreement routes', () => {
  it('names the document by a stable code and falls back to the privacy policy', () => {
    expect(buildAgreementUrl('TRADE_AGREEMENT')).toBe('/pages/mine/agreement?doc=TRADE_AGREEMENT')
    expect(parseAgreementDoc('ENROLLMENT_NOTICE')).toBe('ENROLLMENT_NOTICE')
    expect(parseAgreementDoc('nope')).toBe('PRIVACY_POLICY')
    expect(parseAgreementDoc(undefined)).toBe('PRIVACY_POLICY')
  })

  it('has the five documents of design assumption 22 with placeholder text', () => {
    expect(AGREEMENT_DOCS.map((doc) => AGREEMENT_DOCUMENTS[doc].title))
      .toEqual(['隐私政策', '用户协议', '儿童个人信息保护规则', '平台交易协议', '报名须知与照片使用说明'])
    expect(AGREEMENT_BODY_PLACEHOLDER).toBe('[协议正文待确认]')
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
