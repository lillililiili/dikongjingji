import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import pagesJson from '../../../pages.json?raw'
import catalogHostSource from '../../../pages/proservice/catalog.vue?raw'
import detailHostSource from '../../../pages/proservice/detail.vue?raw'
import providerHostSource from '../../../pages/provider/detail.vue?raw'
import inspectionDetailHostSource from '../../../pages/service/inspection/detail.vue?raw'
import heroSource from '../../inspection/presentation/detail/components/ServiceDetailHero.vue?raw'
import proserviceFlagSource from '../application/proservice-preview.ts?raw'
import {
  buildProServiceCatalogUrl,
  buildProServiceDetailUrl,
  buildProviderPageUrl,
  parseProServiceCatalogTarget,
  parseProServiceDetailTarget,
  parseProviderPageTarget,
} from '../navigation/proservice-routes'

const proserviceSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('pro service routes', () => {
  it('carries only the type, the city, a keyword and stable ids', () => {
    expect(buildProServiceCatalogUrl({ type: 'AERIAL' })).toBe('/pages/proservice/catalog?type=AERIAL')
    expect(buildProServiceCatalogUrl({ type: 'TECH_SUPPORT', cityCode: '370500', keyword: ' 无人机 ' }))
      .toBe('/pages/proservice/catalog?type=TECH_SUPPORT&cityCode=370500&keyword=%E6%97%A0%E4%BA%BA%E6%9C%BA')
    expect(buildProServiceDetailUrl('s 1', '370500')).toBe('/pages/proservice/detail?serviceId=s%201&cityCode=370500')
    expect(buildProviderPageUrl('p1', '370500')).toBe('/pages/provider/detail?providerId=p1&cityCode=370500')
  })

  it('reads them back, falling back to aerial for an unknown type', () => {
    expect(parseProServiceCatalogTarget({ type: 'SURVEY', cityCode: '370500', keyword: '建模' }))
      .toEqual({ type: 'SURVEY', cityCode: '370500', keyword: '建模' })
    expect(parseProServiceCatalogTarget({ type: 'SUPPORT' })).toEqual({ type: 'AERIAL', cityCode: null, keyword: '' })
    expect(parseProServiceCatalogTarget({ type: 'AERIAL', keyword: '%E8%88%AA%E6%8B%8D' }).keyword).toBe('航拍')
    expect(parseProServiceCatalogTarget(undefined).type).toBe('AERIAL')
    expect(parseProServiceDetailTarget({ serviceId: 's1', cityCode: '370500' })).toEqual({ serviceId: 's1', cityCode: '370500' })
    expect(parseProviderPageTarget({ providerId: 'p1' })).toEqual({ providerId: 'p1', cityCode: '' })
  })

  it('registers the three pages', () => {
    for (const path of ['pages/proservice/catalog', 'pages/proservice/detail', 'pages/provider/detail']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
  })

  // The login page only returns to URLs it knows; 发布需求 from a service page carries the provider.
  it('lets a guest come back to a form that invites the provider', () => {
    const url = '/pages/demand/publish?type=AERIAL&providerId=22222222-2222-2222-2222-222222222222'
    expect(normalizeInspectionReturnUrl(url)).toBe(url)
  })
})

describe('pro service preview gate', () => {
  it('uses exactly the expression of the shared preview flag, without importing it', () => {
    expect(flagExpression(proserviceFlagSource, 'PROSERVICE_PREVIEW_ENABLED'))
      .toBe(flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
    const offenders = Object.entries(proserviceSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('shows only a notice on every page in public builds', () => {
    for (const source of [catalogHostSource, detailHostSource, providerHostSource]) {
      expect(source).toContain('v-if="PROSERVICE_PREVIEW_ENABLED && target"')
      expect(source).toContain('<ProServiceUnavailable v-else-if="!PROSERVICE_PREVIEW_ENABLED" />')
    }
  })

  it('links M03 to the provider page only where the provider page is open', () => {
    expect(inspectionDetailHostSource).toContain(':provider-page-enabled="PROSERVICE_PREVIEW_ENABLED"')
    expect(heroSource).toContain('v-if="providerLinkEnabled"')
  })

  it('writes no payment promise the four types cannot keep yet', () => {
    const pages = Object.entries(proserviceSources).filter(([path]) => path.endsWith('.vue'))
    for (const [, source] of pages) {
      expect(source).not.toContain('付首付')
      expect(source).not.toContain('付尾款')
    }
  })
})
