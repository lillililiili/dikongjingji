import { describe, expect, it } from 'vitest'

import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import releaseRegistry from '../../../app-shell/release/release-features.json'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import pagesJson from '../../../pages.json?raw'
import flagSource from '../application/serviceorder-preview.ts?raw'
import { resolveServiceOrderPreviewCover } from '../data/preview/serviceorder-preview-covers'
import {
  buildServiceOrderDeliveryUrl,
  buildServiceOrderDetailUrl,
  buildServiceOrderPlanUrl,
  parseServiceOrderId,
} from '../navigation/service-order-routes'

const pageSources = import.meta.glob('../../../pages/service-order/*.vue', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

describe('service order routes', () => {
  it('carries only the order id', () => {
    expect(buildServiceOrderDetailUrl('so 1')).toBe('/pages/service-order/detail?orderId=so%201')
    expect(buildServiceOrderPlanUrl('so-1')).toBe('/pages/service-order/plan?orderId=so-1')
    expect(buildServiceOrderDeliveryUrl('so-1')).toBe('/pages/service-order/delivery?orderId=so-1')
    expect(parseServiceOrderId({ orderId: ' so-1 ' })).toBe('so-1')
    expect(parseServiceOrderId({ orderId: '' })).toBeNull()
    expect(parseServiceOrderId(undefined)).toBeNull()
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    for (const url of [buildServiceOrderDetailUrl('so-1'), buildServiceOrderPlanUrl('so-1'), buildServiceOrderDeliveryUrl('so-1')]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/service-order/plan')).toBe('/pages/home/index')
  })

  it('registers the three pages, each behind the release switch', () => {
    for (const path of ['pages/service-order/detail', 'pages/service-order/plan', 'pages/service-order/delivery']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    expect(Object.keys(pageSources)).toHaveLength(3)
    for (const source of Object.values(pageSources)) {
      expect(source).toContain('v-if="SERVICE_ORDER_PREVIEW_ENABLED && shown"')
      expect(source).toContain('<ServiceOrderUnavailable v-else-if="!SERVICE_ORDER_PREVIEW_ENABLED" />')
    }
  })
})

describe('service order release switch', () => {
  it('reads its own constant and stays closed on public builds by default', () => {
    expect(flagSource).toContain(`export const SERVICE_ORDER_PREVIEW_ENABLED = ${moduleFlagExpression('SERVICE_ORDER')}\n`)
    expect(releaseRegistry.features.find((feature) => feature.key === 'SERVICE_ORDER')).toEqual({
      key: 'SERVICE_ORDER',
      flag: 'SERVICE_ORDER_PREVIEW_ENABLED',
      file: 'src/features/serviceorder/application/serviceorder-preview.ts',
      publicRelease: false,
    })
  })

  it('shows the board pictures only on preview builds', () => {
    expect(resolveServiceOrderPreviewCover('AERIAL', true)).toBe('/static/images/preview/proservice-solar.webp')
    expect(resolveServiceOrderPreviewCover('MAINTENANCE', false)).toBeNull()
  })
})
