import { describe, expect, it } from 'vitest'

import { describeLoginResume } from '../../inspection/presentation/auth/login-resume'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import {
  buildAftersaleApplyUrl,
  buildAftersaleDetailUrl,
  buildAftersalesUrl,
  parseAftersaleTab,
} from '../navigation/aftersale-routes'
import pagesJson from '../../../pages.json?raw'

describe('aftersale routes', () => {
  it('carries only the order id, the case id or the tab', () => {
    expect(buildAftersaleApplyUrl('o 1')).toBe('/pages/trade/aftersale-apply?orderId=o%201')
    expect(buildAftersaleDetailUrl('as-1')).toBe('/pages/trade/aftersale?aftersaleId=as-1')
    expect(buildAftersaleDetailUrl('as-1', { openWaybill: true })).toBe('/pages/trade/aftersale?aftersaleId=as-1&waybill=1')
    expect(buildAftersalesUrl()).toBe('/pages/mine/aftersales?tab=DOING')
    expect(buildAftersalesUrl('CLOSED')).toBe('/pages/mine/aftersales?tab=CLOSED')
  })

  it('reads a known tab, else 售后中', () => {
    expect(parseAftersaleTab('DONE')).toBe('DONE')
    expect(parseAftersaleTab('ALL')).toBe('DOING')
    expect(parseAftersaleTab(undefined)).toBe('DOING')
  })

  it('can be returned to after signing in, without the waybill switch', () => {
    expect(normalizeInspectionReturnUrl(buildAftersaleApplyUrl('o-1'))).toBe('/pages/trade/aftersale-apply?orderId=o-1')
    expect(normalizeInspectionReturnUrl(buildAftersaleDetailUrl('as-1', { openWaybill: true }))).toBe('/pages/trade/aftersale?aftersaleId=as-1')
    expect(normalizeInspectionReturnUrl(buildAftersalesUrl('DONE'))).toBe('/pages/mine/aftersales?tab=DONE')
    expect(normalizeInspectionReturnUrl('/pages/trade/aftersale')).toBe('/pages/home/index')
    expect(describeLoginResume(buildAftersaleApplyUrl('o-1'))).toBe('申请售后')
  })

  it('is registered in pages.json', () => {
    const paths = (JSON.parse(pagesJson) as { pages: Array<{ path: string }> }).pages.map((page) => page.path)
    expect(paths).toEqual(expect.arrayContaining(['pages/trade/aftersale-apply', 'pages/trade/aftersale', 'pages/mine/aftersales']))
  })
})
