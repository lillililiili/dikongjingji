import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import pagesJson from '../../../pages.json?raw'
import mineSource from '../../../pages/mine/index.vue?raw'
import rentalFlagSource from '../application/rental-preview.ts?raw'
import { resolveRentalIntent } from '../application/rental-intent'
import {
  buildMyRentalOrdersUrl,
  buildRentalContractUrl,
  buildRentalInquiryUrl,
  buildRentalOrderUrl,
  buildRentalQuoteUrl,
  parseRentalInquiryTarget,
  parseRentalOrderTab,
  rentalOrderStatusesOfTab,
} from '../navigation/rental-routes'

const rentalSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('rental routes', () => {
  it('carries only stable ids and the city', () => {
    expect(buildRentalInquiryUrl({ offerId: 'o 1', productId: 'p1', cityCode: '370500' }))
      .toBe('/pages/rental/inquiry?offerId=o%201&productId=p1&cityCode=370500')
    expect(buildRentalQuoteUrl('i1')).toBe('/pages/rental/quote?inquiryId=i1')
    expect(buildRentalOrderUrl('r1')).toBe('/pages/rental/order?rentalOrderId=r1')
    expect(buildRentalContractUrl('r1')).toBe('/pages/rental/contract?rentalOrderId=r1')
    expect(buildMyRentalOrdersUrl()).toBe('/pages/mine/rental-orders')
    expect(buildMyRentalOrdersUrl('IN_USE')).toBe('/pages/mine/rental-orders?status=IN_USE')
  })

  it('reads the form target and the tab back, refusing what is incomplete', () => {
    expect(parseRentalInquiryTarget({ offerId: 'o1', productId: 'p1', cityCode: '370500' }))
      .toEqual({ offerId: 'o1', productId: 'p1', cityCode: '370500' })
    expect(parseRentalInquiryTarget({ offerId: 'o1', cityCode: '370500' })).toBeNull()
    expect(parseRentalInquiryTarget(undefined)).toBeNull()
    expect(parseRentalOrderTab('TO_HANDOVER')).toBe('TO_HANDOVER')
    expect(parseRentalOrderTab('nope')).toBe('ALL')
    expect(rentalOrderStatusesOfTab('ALL')).toEqual([null])
    expect(rentalOrderStatusesOfTab('TO_HANDOVER')).toEqual(['AWAITING_HANDOVER', 'HANDOVER_RECORDED'])
    expect(rentalOrderStatusesOfTab('CLOSED')).toEqual(['CANCELLED', 'CLOSED'])
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    for (const url of [
      buildRentalInquiryUrl({ offerId: 'o1', productId: 'p1', cityCode: '370500' }),
      buildRentalQuoteUrl('i1'),
      buildRentalOrderUrl('r1'),
      buildRentalContractUrl('r1'),
      buildMyRentalOrdersUrl(),
      buildMyRentalOrdersUrl('ENDED'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/rental/order')).toBe('/pages/home/index')
  })

  it('registers the five pages and the Mine entry', () => {
    for (const path of ['pages/rental/inquiry', 'pages/rental/quote', 'pages/rental/order', 'pages/rental/contract', 'pages/mine/rental-orders']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    expect(mineSource).toContain("RENTAL_PREVIEW_ENABLED ? [{ code: 'RENTAL_ORDERS', title: '租赁订单'")
  })

  it('explains the unbuilt contact entry through the typed gate', () => {
    expect(resolveRentalIntent({ type: 'CONTACT_MERCHANT' })).toEqual({ action: 'SHOW_NOTICE', message: '咨询功能即将开放' })
  })
})

describe('rental preview flag', () => {
  it('uses exactly the expression of the shared preview flag', () => {
    expect(flagExpression(rentalFlagSource, 'RENTAL_PREVIEW_ENABLED'))
      .toBe(flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
  })

  it('keeps the rental feature from importing the shared flag module', () => {
    const offenders = Object.entries(rentalSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })
})
