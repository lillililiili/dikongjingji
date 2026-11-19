import { describe, expect, it } from 'vitest'

import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import demandFlagSource from '../application/demand-preview.ts?raw'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import pagesJson from '../../../pages.json?raw'
import {
  buildDemandCompareUrl,
  buildDemandPublishUrl,
  buildDemandQuotesUrl,
  buildMyDemandsUrl,
  demandStatusOfFilter,
  parseDemandListFilter,
  parseDemandPublishTarget,
} from '../navigation/demand-routes'

const demandSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('demand routes', () => {
  it('carries only stable ids and the type', () => {
    expect(buildDemandPublishUrl()).toBe('/pages/demand/publish')
    expect(buildDemandPublishUrl({ type: 'AERIAL' })).toBe('/pages/demand/publish?type=AERIAL')
    expect(buildDemandPublishUrl({ demandId: 'd 1', type: 'AERIAL' })).toBe('/pages/demand/publish?demandId=d%201')
    expect(buildDemandPublishUrl({ from: 'd1' })).toBe('/pages/demand/publish?from=d1')
    expect(buildDemandQuotesUrl('d1')).toBe('/pages/demand/quotes?demandId=d1')
    expect(buildDemandCompareUrl('d1')).toBe('/pages/demand/compare?demandId=d1')
    expect(buildMyDemandsUrl()).toBe('/pages/mine/demands')
    expect(buildMyDemandsUrl('DRAFT')).toBe('/pages/mine/demands?status=DRAFT')
  })

  it('reads the publish target and the list filter back, ignoring what it does not know', () => {
    expect(parseDemandPublishTarget({ demandId: 'd1', type: 'AERIAL' })).toEqual({ demandId: 'd1' })
    expect(parseDemandPublishTarget({ type: 'SURVEY' })).toEqual({ type: 'SURVEY' })
    expect(parseDemandPublishTarget({ type: 'SUPPORT' })).toEqual({})
    expect(parseDemandPublishTarget({ from: 'd1', type: 'TECH_SUPPORT' })).toEqual({ type: 'TECH_SUPPORT', from: 'd1' })
    expect(parseDemandPublishTarget(undefined)).toEqual({})
    expect(parseDemandListFilter('QUOTED')).toBe('QUOTED')
    expect(parseDemandListFilter('nope')).toBe('ALL')
    expect(demandStatusOfFilter('ALL')).toBeNull()
    expect(demandStatusOfFilter('DRAFT')).toBe('DRAFT')
  })

  // The login page only returns to URLs it knows.
  it('can be returned to after signing in', () => {
    for (const url of [
      buildDemandPublishUrl(),
      buildDemandPublishUrl({ type: 'MAINTENANCE' }),
      buildDemandPublishUrl({ demandId: 'd1' }),
      buildDemandPublishUrl({ from: 'd1' }),
      buildDemandQuotesUrl('d1'),
      buildDemandCompareUrl('d1'),
      buildMyDemandsUrl(),
      buildMyDemandsUrl('CHOSEN'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/demand/quotes')).toBe('/pages/home/index')
    expect(normalizeInspectionReturnUrl('/pages/demand/quotes?demandId=d1&next=https://evil.example'))
      .toBe('/pages/demand/quotes?demandId=d1')
  })

  it('registers the four pages', () => {
    for (const path of ['pages/demand/publish', 'pages/demand/quotes', 'pages/demand/compare', 'pages/mine/demands']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
  })
})

describe('demand preview flag', () => {
  it('uses exactly the expression of the shared preview flag', () => {
    expect(flagExpression(demandFlagSource, 'DEMAND_PREVIEW_ENABLED'))
      .toBe(flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
  })

  it('keeps the demand feature from importing the shared flag module', () => {
    const offenders = Object.entries(demandSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })
})
