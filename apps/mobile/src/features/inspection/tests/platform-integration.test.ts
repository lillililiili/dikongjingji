import { describe, expect, it } from 'vitest'

import homePageSource from '../../../pages/home/index.vue?raw'
import minePageSource from '../../../pages/mine/index.vue?raw'
import pages from '../../../pages.json'
import packageSource from '../../../../package.json?raw'
import discoverPageSource from '../../../pages/discover/index.vue?raw'
import mallPageSource from '../../../pages/mall/index.vue?raw'
import servicePageSource from '../../../pages/service/index.vue?raw'
import weatherClientSource from '../../../shared/weather/uni-platform-weather-http-client.ts?raw'
import type { InspectionPortalContent } from '../domain/inspection-portal-content'
import * as homeModel from '../presentation/platform/home/platform-home'
import * as serviceModel from '../presentation/platform/service/platform-service'
import inspectionPortalSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'

const weather = {
  available: false,
  text: '天气暂不可用',
  iconUrl: '/static/images/inspection/platform/weather-unknown.png',
  conditionCode: 'UNKNOWN' as const,
  observedAt: null,
}

const portal: InspectionPortalContent = {
  hero: {
    title: '专业巡检',
    accent: '隐患早发现',
    description: '真实服务目录',
    highlights: [],
    imageUrl: '/static/images/inspection/inspection-hero-v1.png',
  },
  categories: [{
    code: 'POWER',
    name: '电力',
    subtitle: '输电线路巡检',
    imageUrl: '/static/images/inspection/category-power.webp',
  }],
  guarantees: [],
  hotServices: [{
    id: 'service-1',
    categoryCode: 'POWER',
    title: '输电线路无人机巡检',
    tag: '电力',
    priceLabel: '¥699/公里',
  }],
  processSteps: [],
}

describe('platform root page integration', () => {
  it('offers a standalone public-production fixture guard without rebuilding during pnpm test', () => {
    const packageJson = JSON.parse(packageSource) as {
      scripts?: Record<string, string>
    }

    expect(packageJson.scripts?.['verify:public-production-preview'])
      .toBe('node scripts/verify-public-production-preview.mjs')
  })

  it('wires the approved platform components into the three root pages', () => {
    expect(homePageSource).toContain('PlatformHomePage')
    expect(servicePageSource).toContain('PlatformServicePage')
    expect(minePageSource).toContain('PlatformMinePage')
  })

  it('uses custom navigation so the approved header is not duplicated', () => {
    for (const path of ['pages/home/index', 'pages/service/index', 'pages/mine/index']) {
      expect(pages.pages.find((page) => page.path === path)?.style.navigationStyle)
        .toBe('custom')
    }
  })

  it('handles native tab bar restoration failures during H5 tab switches', () => {
    for (const source of [homePageSource, servicePageSource, minePageSource]) {
      expect(source).toMatch(/uni\.showTabBar\(\{\s*animation: false,\s*fail: \(\) => undefined,\s*\}\)/)
    }
  })

  it('uses one explicit preview gate across all three preview routes', () => {
    for (const source of [servicePageSource, discoverPageSource, mallPageSource]) {
      expect(source).toContain('PLATFORM_PREVIEW_ENABLED')
      expect(source).not.toContain('import.meta.env.DEV')
    }
  })

  it('keeps ready scroll views mounted while cached root pages refresh', () => {
    expect(homePageSource).toContain(
      "if (state.value.kind !== 'ready') state.value = { kind: 'loading' }",
    )
    expect(servicePageSource).toContain('scrollPositions')
    expect(servicePageSource).toContain('uni.pageScrollTo')
  })

  it('loads home and service content from the real catalog and current-city weather', () => {
    expect(homePageSource).toContain('inspectionCatalogApi.loadPortal')
    expect(inspectionPortalSource).toContain('inspectionCatalogApi.loadPortal')
    expect(servicePageSource).toContain('InspectionPortalPage')
    for (const source of [homePageSource, servicePageSource]) {
      expect(source).toContain('createCurrentCityWeatherLoader')
      expect(source).not.toMatch(/unreadCount:\s*3/)
      expect(source).not.toMatch(/rating:\s*4\.9/)
      expect(source).not.toContain('8560')
    }
  })

  it('shares the LAN-aware API URL resolver with the weather client', () => {
    expect(weatherClientSource).toContain('resolveInspectionApiRequestUrl')
    expect(weatherClientSource).not.toContain('function apiUrl(')
  })
})

describe('real portal to platform content mapping', () => {
  it('does not invent unread, rating or sales metrics for the home page', () => {
    const createContent = Reflect.get(homeModel, 'createPlatformHomeContentFromPortal')
    expect(typeof createContent).toBe('function')
    if (typeof createContent !== 'function') return

    const content = createContent('东营市', weather, portal)
    expect(content.unreadCount).toBeUndefined()
    expect(content.featuredServices[0]).toMatchObject({
      id: 'service-1',
      quoteLabel: '¥699/公里',
    })
    expect(content.featuredServices[0].rating).toBeUndefined()
    expect(content.featuredServices[0].soldCountText).toBeUndefined()
  })

  it('does not invent unread, rating or sales metrics for the service page', () => {
    const createContent = Reflect.get(serviceModel, 'createPlatformServiceContentFromPortal')
    expect(typeof createContent).toBe('function')
    if (typeof createContent !== 'function') return

    const content = createContent('东营市', weather, portal)
    expect(content.unreadCount).toBeUndefined()
    expect(content.recommendedServices[0]).toMatchObject({
      id: 'service-1',
      quoteLabel: '¥699/公里',
    })
    expect(content.recommendedServices[0].rating).toBeUndefined()
    expect(content.recommendedServices[0].soldCountText).toBeUndefined()
  })
})
