import { describe, expect, it } from 'vitest'

import {
  isRootTabReturnUrl,
  normalizeInspectionReturnUrl,
} from '../../inspection/navigation/inspection-routes'
import {
  ADDRESS_BOOK_PATH,
  CART_TAB_PATH,
  buildAddressBookUrl,
  buildBuyNowCheckoutUrl,
  buildCartCheckoutUrl,
  buildMallInquiryQuotesUrl,
  buildMallInquiryUrl,
  buildMallProductUrl,
  buildQuoteCheckoutUrl,
  parseMallInquiryTarget,
} from '../navigation/mall-routes'
import pagesJson from '../../../pages.json?raw'

describe('mall routes', () => {
  it('carries only stable ids', () => {
    expect(buildCartCheckoutUrl(['c1', 'c2'], '370500')).toBe('/pages/cart/checkout?items=c1,c2&cityCode=370500')
    expect(buildBuyNowCheckoutUrl('o 1', 2, '370500')).toBe('/pages/cart/checkout?offerId=o%201&quantity=2&cityCode=370500')
    expect(buildAddressBookUrl(true)).toBe('/pages/mine/addresses?select=1')
    expect(buildAddressBookUrl(false)).toBe(ADDRESS_BOOK_PATH)
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    expect(normalizeInspectionReturnUrl(buildMallProductUrl('p1', '370500'))).toBe('/pages/mall/product?productId=p1&cityCode=370500')
    expect(normalizeInspectionReturnUrl(buildBuyNowCheckoutUrl('o1', 2, '370500')))
      .toBe('/pages/cart/checkout?offerId=o1&quantity=2&cityCode=370500')
    // The comma between cart lines comes back encoded; the confirm page decodes it.
    expect(normalizeInspectionReturnUrl(buildCartCheckoutUrl(['c1', 'c2'], '370500')))
      .toBe('/pages/cart/checkout?items=c1%2Cc2&cityCode=370500')
    expect(normalizeInspectionReturnUrl(buildAddressBookUrl(true))).toBe('/pages/mine/addresses?select=1')
    expect(normalizeInspectionReturnUrl(CART_TAB_PATH)).toBe(CART_TAB_PATH)
    expect(isRootTabReturnUrl(CART_TAB_PATH)).toBe(true)
  })

  it('refuses a confirm page without a city and drops unknown parameters', () => {
    expect(normalizeInspectionReturnUrl('/pages/cart/checkout?items=c1')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/mall/product?productId=p1&next=https://evil.example'))
      .toBe('/pages/mall/product?productId=p1')
  })
})

describe('goods inquiry routes (F4)', () => {
  it('carries only stable ids and the city', () => {
    expect(buildMallInquiryUrl({ productId: 'p 1', cityCode: '370500' })).toBe('/pages/mall/inquiry?productId=p%201&cityCode=370500')
    expect(buildMallInquiryQuotesUrl('i-1')).toBe('/pages/mall/inquiry-quotes?inquiryId=i-1')
    expect(buildQuoteCheckoutUrl('q-1', 'i-1', '370500')).toBe('/pages/cart/checkout?inquiryQuoteId=q-1&inquiryId=i-1&cityCode=370500')
    expect(parseMallInquiryTarget({ productId: ' p1 ', cityCode: '370500' })).toEqual({ productId: 'p1', cityCode: '370500' })
    expect(parseMallInquiryTarget({ productId: 'p1' })).toBeNull()
  })

  it('can be returned to after signing in', () => {
    for (const url of [
      buildMallInquiryUrl({ productId: 'p1', cityCode: '370500' }),
      buildMallInquiryQuotesUrl('i1'),
      buildQuoteCheckoutUrl('q1', 'i1', '370500'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/mall/inquiry-quotes')).toBe('/pages/home/index')
  })

  it('registers both pages', () => {
    expect(pagesJson).toContain('"path": "pages/mall/inquiry"')
    expect(pagesJson).toContain('"path": "pages/mall/inquiry-quotes"')
  })
})
