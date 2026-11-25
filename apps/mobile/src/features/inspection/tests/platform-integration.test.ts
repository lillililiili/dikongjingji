import { describe, expect, it } from 'vitest'

import homePageSource from '../../../pages/home/index.vue?raw'
import minePageSource from '../../../pages/mine/index.vue?raw'
import pages from '../../../pages.json'
import packageSource from '../../../../package.json?raw'
import discoverPageSource from '../../../pages/discover/index.vue?raw'
import mallPageSource from '../../../pages/mall/index.vue?raw'
import serviceChannelSource from '../../platform/presentation/service/PlatformServiceChannel.vue?raw'
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

  it('hosts the recommend and service channels on the home root page', () => {
    expect(homePageSource).toContain('PlatformHomePage')
    expect(homePageSource).toContain('PlatformServiceChannel')
    expect(minePageSource).toContain('PlatformMinePage')
  })

  it('uses custom navigation so the approved header is not duplicated', () => {
    for (const path of ['pages/home/index', 'pages/service/index', 'pages/mine/index']) {
      expect(pages.pages.find((page) => page.path === path)?.style.navigationStyle)
        .toBe('custom')
    }
  })

  it('leaves native tab bar handling to the shared root bar', () => {
    for (const source of [homePageSource, discoverPageSource, mallPageSource, minePageSource]) {
      expect(source).not.toContain('uni.hideTabBar')
      expect(source).not.toContain('uni.showTabBar')
    }
  })

  it('uses one explicit preview gate across all three preview routes', () => {
    for (const source of [homePageSource]) {
      expect(source).toContain('PLATFORM_PREVIEW_ENABLED')
      expect(source).not.toContain('import.meta.env.DEV')
    }
    // 发现 (step H1) uses the content feature's copy of the same expression, so the shared flag
    // stays in the home chunk (content-routes.test.ts keeps the expressions identical).
    expect(discoverPageSource).toContain('v-if="CONTENT_PREVIEW_ENABLED"')
    expect(discoverPageSource).not.toContain('import.meta.env.DEV')
  })

  it('keeps ready scroll views mounted while cached root pages refresh', () => {
    expect(homePageSource).toContain(
      "if (state.value.kind !== 'ready') state.value = { kind: 'loading' }",
    )
    expect(homePageSource).toContain('scrollPositions')
    expect(homePageSource).toContain('uni.pageScrollTo')
  })

  it('loads home and service content from the real catalog', () => {
    expect(homePageSource).toContain('inspectionCatalogApi.loadPortal')
    expect(inspectionPortalSource).toContain('inspectionCatalogApi.loadPortal')
    expect(serviceChannelSource).toContain('InspectionPortalPage')
    for (const source of [homePageSource, serviceChannelSource]) {
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

    const content = createContent(portal)
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
