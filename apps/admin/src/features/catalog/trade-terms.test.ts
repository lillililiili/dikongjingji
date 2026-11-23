import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'

import {
  createTradeTermsApi,
  OFFER_TERMS_STATUS_LABELS,
  offerTermsBody,
  offerTermsDirty,
  offerTermsDraft,
  offerTermsError,
  type MallOfferTerms,
} from './trade-terms-api'

const PRODUCT = 'e1000000-0000-4000-8000-000000000001'
const OFFER = 'e2000000-0000-4000-8000-000000000002'

function offer(overrides: Partial<MallOfferTerms> = {}): MallOfferTerms {
  return { offerId: OFFER, skuName: '标准版', merchantId: 'm-1', merchantName: '示例机器人商家', cityCode: null,
    status: 'ACTIVE', invoiceIssuer: null, aftersaleParty: '示例售后中心', effectiveInvoiceIssuer: '示例机器人商家',
    effectiveAftersaleParty: '示例售后中心', ...overrides }
}

describe('商品交易条款接口', () => {
  it('读、改七天无理由、改供给的开票方和售后责任方（留空写 null）', async () => {
    const { http, requests } = recordingClient({ productId: PRODUCT, productName: '套装', noReasonReturn: true, offers: [] })
    const api = createTradeTermsApi(http)
    await api.read(PRODUCT)
    await api.setNoReasonReturn(PRODUCT, false)
    await api.setOfferTerms(PRODUCT, OFFER, { invoiceIssuer: ' 东营示例科技 ', aftersaleParty: ' ' })

    expect(requests[0]?.url).toBe(`/api/v1/ops/catalog/products/${PRODUCT}/trade-terms`)
    expect(requests[0]?.method).toBe('GET')
    expect(requests[1]?.url).toBe(`/api/v1/ops/catalog/products/${PRODUCT}/trade-terms`)
    expect(requests[1]?.method).toBe('PUT')
    expect(jsonBody(requests[1])).toEqual({ noReasonReturn: false })
    expect(requests[2]?.url).toBe(`/api/v1/ops/catalog/products/${PRODUCT}/offers/${OFFER}/trade-terms`)
    expect(requests[2]?.method).toBe('PUT')
    expect(jsonBody(requests[2])).toEqual({ invoiceIssuer: '东营示例科技', aftersaleParty: null })
  })

  it('输入框初值是供给上填的；和存的一样时不能保存', () => {
    const value = offer()
    const draft = offerTermsDraft(value)
    expect(draft).toEqual({ invoiceIssuer: '', aftersaleParty: '示例售后中心' })
    expect(offerTermsDirty(value, draft)).toBe(false)
    expect(offerTermsDirty(value, { ...draft, aftersaleParty: ' 示例售后中心 ' })).toBe(false)
    expect(offerTermsDirty(value, { ...draft, aftersaleParty: '' })).toBe(true)
    expect(offerTermsBody({ invoiceIssuer: '', aftersaleParty: '' })).toEqual({ invoiceIssuer: null, aftersaleParty: null })
  })

  it('开票方、售后责任方各不超过 60 字；状态都有中文', () => {
    expect(offerTermsError({ invoiceIssuer: '票'.repeat(60), aftersaleParty: '' })).toBeNull()
    expect(offerTermsError({ invoiceIssuer: '票'.repeat(61), aftersaleParty: '' })).toContain('60')
    expect(offerTermsError({ invoiceIssuer: '', aftersaleParty: '售'.repeat(61) })).toContain('60')
    expect(Object.keys(OFFER_TERMS_STATUS_LABELS).sort()).toEqual(['ACTIVE', 'DISABLED'])
  })
})
