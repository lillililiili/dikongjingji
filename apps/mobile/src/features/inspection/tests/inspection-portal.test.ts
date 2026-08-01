import { describe, expect, it } from 'vitest'
import { nextTick, ref, watch } from 'vue'
import tabBarSource from '../../../app-shell/components/RootAppTabBar.vue?raw'
import headerSource from '../presentation/portal/components/PortalServiceHeader.vue?raw'
import categoryGridSource from '../presentation/portal/components/InspectionCategoryGrid.vue?raw'
import guaranteeSource from '../presentation/portal/components/InspectionGuaranteeStrip.vue?raw'
import hotServicesSource from '../presentation/portal/components/HotInspectionServices.vue?raw'
import inspectionHeroSource from '../presentation/portal/components/InspectionHero.vue?raw'
import portalPageSource from '../presentation/portal/pages/InspectionPortalPage.vue?raw'
import platformHomePageSource from '../presentation/platform/home/PlatformHomePage.vue?raw'
import portalPreviewSource from '../data/preview/inspection-portal-preview?raw'

import {
  buildPortalCategoryUrl,
  buildPortalHotServiceUrl,
  buildPortalSearchUrl,
  buildPortalViewAllUrl,
} from '../application/portal/build-portal-navigation'
import {
  EMPTY_PORTAL_CONTENT,
  composePortalPreviewContent,
  createInspectionPortalContentLoader,
  resolvePortalContent,
} from '../application/portal/resolve-portal-content'
import { DEVELOPMENT_PORTAL_CONTENT } from '../data/fixtures/inspection-portal.fixture'
import type { InspectionPortalContent } from '../domain/inspection-portal-content'

const fixtureContent: InspectionPortalContent = {
  hero: {
    title: '专业巡检',
    accent: '隐患早发现',
    description: '无人机与智能设备，为您提供安全高效的巡检服务',
    highlights: ['专业团队', '安全合规', '报告清晰'],
    imageUrl: '/static/images/inspection/inspection-hero-v1.png',
  },
  categories: [
    {
      code: 'POWER',
      name: '电力巡检',
      subtitle: '线路·杆塔·变电站',
      imageUrl: '/static/images/inspection/category-power.webp',
    },
  ],
  guarantees: [],
  hotServices: [],
  processSteps: [],
}

function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

function portalContent(title: string): InspectionPortalContent {
  return {
    ...fixtureContent,
    hero: {
      ...fixtureContent.hero!,
      title,
    },
  }
}

describe('inspection portal city refresh', () => {
  it('reloads portal content when the shared current city code changes', async () => {
    const cityCode = ref<string | undefined>('370500')
    const requestedCities: string[] = []
    const loader = createInspectionPortalContentLoader(async (code) => {
      requestedCities.push(code)
      return portalContent(code)
    })

    await loader.load(cityCode.value!)
    const stopWatching = watch(cityCode, (code) => {
      if (code) void loader.load(code)
    })
    cityCode.value = '370100'
    await nextTick()
    await Promise.resolve()
    stopWatching()

    expect(requestedCities).toEqual(['370500', '370100'])
    expect(portalPageSource).toContain(
      'createInspectionPortalContentLoader(',
    )
    expect(portalPageSource).toMatch(
      /watch\(\s*\(\) => currentCity\.value\?\.code/,
    )
  })

  it('does not let a slow old-city response replace the new-city portal', async () => {
    const cityCode = ref<string | undefined>('370500')
    const oldCity = deferred<InspectionPortalContent>()
    const newCity = deferred<InspectionPortalContent>()
    const loader = createInspectionPortalContentLoader(
      (code) => code === '370500' ? oldCity.promise : newCity.promise,
    )

    const oldRequest = loader.load('370500')
    cityCode.value = '370100'
    const newRequest = loader.load(cityCode.value)
    newCity.resolve(portalContent('济南巡检'))
    const newResult = await newRequest
    expect(newResult).toMatchObject({
      kind: 'loaded',
      content: { hero: { title: '济南巡检' } },
    })

    oldCity.resolve(portalContent('东营巡检'))
    await expect(oldRequest).resolves.toEqual({ kind: 'stale' })
  })

  it.each(['success', 'failure'] as const)(
    'keeps cleared-city page state unchanged when an old request finishes with %s',
    async (outcome) => {
      const pendingCity = deferred<InspectionPortalContent>()
      const loader = createInspectionPortalContentLoader(
        () => pendingCity.promise,
      )
      const oldRequest = loader.load('370500')

      const pageState = {
        content: portalContent('城市已清空'),
        error: '城市已清空',
        loading: false,
      }
      const clearedState = { ...pageState }
      loader.invalidate()
      if (outcome === 'success') {
        pendingCity.resolve(portalContent('东营巡检'))
      } else {
        pendingCity.reject(new Error('old city failed'))
      }

      const result = await oldRequest
      if (result.kind === 'loaded') {
        pageState.content = result.content
        pageState.error = ''
        pageState.loading = false
      } else if (result.kind === 'error') {
        pageState.content = EMPTY_PORTAL_CONTENT
        pageState.error = '巡检入口加载失败，请稍后重试'
        pageState.loading = false
      }

      expect(result).toEqual({ kind: 'stale' })
      expect(pageState).toEqual(clearedState)
      expect(portalPageSource).toMatch(
        /if \(!cityCode\) \{\s*portalContentLoader\.invalidate\(\)/,
      )
    },
  )
})

describe('inspection portal navigation', () => {
  it('trims and limits a portal search keyword before opening the catalog', () => {
    const keyword = `  ${'巡'.repeat(55)}  `

    expect(buildPortalSearchUrl('370500', keyword)).toBe(
      `/pages/service/inspection/catalog?cityCode=370500&keyword=${encodeURIComponent(
        '巡'.repeat(50),
      )}&source=SEARCH`,
    )
  })

  it('opens a normal category with its stable category code', () => {
    expect(buildPortalCategoryUrl('370500', 'POWER')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER&source=CATEGORY',
    )
  })

  it('opens the filter sheet instead of treating MORE as a business category', () => {
    expect(buildPortalCategoryUrl('370500', 'MORE')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&openFilter=true&source=CATEGORY',
    )
  })

  it('keeps hot-service and view-all navigation inside the catalog scope', () => {
    expect(buildPortalHotServiceUrl('370500', 'POWER')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER&source=HOT',
    )
    expect(buildPortalViewAllUrl('370500')).toBe(
      '/pages/service/inspection/catalog?cityCode=370500&source=VIEW_ALL',
    )
  })
})

describe('inspection portal development content boundary', () => {
  it('fills the approved eight-category layout only in preview mode', () => {
    const preview = composePortalPreviewContent(
      true,
      fixtureContent,
      DEVELOPMENT_PORTAL_CONTENT,
    )
    const production = composePortalPreviewContent(
      false,
      fixtureContent,
      DEVELOPMENT_PORTAL_CONTENT,
    )

    expect(preview.categories).toHaveLength(8)
    expect(preview.guarantees).toHaveLength(4)
    expect(preview.hotServices).toHaveLength(4)
    expect(preview.processSteps).toHaveLength(4)
    expect(preview.hero?.highlights).toEqual([
      '专业团队',
      '安全合规',
      '报告清晰',
    ])
    expect(production).toBe(fixtureContent)
    expect(portalPreviewSource).toContain('composePortalPreviewContent')
    expect(portalPageSource).toContain('VITE_PLATFORM_PREVIEW_ENABLED')
    expect(portalPageSource).toContain('composeInspectionPortalContentForRuntime')
    expect(portalPageSource).not.toContain('data/fixtures')
  })

  it('exposes development content only in the development environment', () => {
    expect(resolvePortalContent('development', fixtureContent)).toBe(
      fixtureContent,
    )
    expect(resolvePortalContent('production', fixtureContent)).toEqual(
      EMPTY_PORTAL_CONTENT,
    )
  })

  it('uses approved image assets for every portal category', () => {
    expect(DEVELOPMENT_PORTAL_CONTENT.categories).toHaveLength(8)
    expect(
      DEVELOPMENT_PORTAL_CONTENT.categories.every(
        (category) =>
          'imageUrl' in category &&
          typeof category.imageUrl === 'string' &&
          category.imageUrl.startsWith('/static/images/inspection/category-'),
      ),
    ).toBe(true)
  })

  it('provides complete decision data for four hot-service cards', () => {
    expect(DEVELOPMENT_PORTAL_CONTENT.hotServices).toHaveLength(4)
    expect(
      DEVELOPMENT_PORTAL_CONTENT.hotServices.every(
        (service) =>
          service.imageUrl?.startsWith(
            '/static/images/inspection/service-',
          ) &&
          typeof service.rating === 'number' &&
          Boolean(service.servedCountLabel),
      ),
    ).toBe(true)
  })
})

describe('inspection portal header visual contract', () => {
  it('keeps the PDF-measured tab proportions and approved header icons', () => {
    expect(headerSource).toContain(
      'grid-template-columns: 148fr 155fr 196fr 190fr 149fr',
    )
    expect(headerSource).toContain('weatherIconUrl?: string')
    expect(headerSource).toContain(':src="weatherIconUrl"')
    expect(headerSource).toContain('type="chat"')
    expect(headerSource).toContain(
      '/static/images/inspection/category-more.webp',
    )
    expect(headerSource).toContain(
      '.service-tabs__item--active::after',
    )
    expect(headerSource).toMatch(
      /\.service-tabs__item \{[\s\S]*?width: 100%;[\s\S]*?margin: 0;/,
    )
    expect(headerSource).toContain(
      'margin-top: clamp(8px, 1.8vw, 11px)',
    )
    expect(headerSource).toContain(
      'font-size: clamp(11px, 2.55vw, 15px)',
    )
    expect(headerSource).toContain(
      'font-size: clamp(11px, 2.5vw, 15px)',
    )
    expect(headerSource).toContain(
      'width: clamp(20px, 4.3vw, 26px)',
    )
    expect(headerSource).toContain(
      'height: clamp(20px, 4.3vw, 26px)',
    )
    expect(headerSource).toMatch(
      /\.portal-header__city \{[\s\S]*?width: 100%;[\s\S]*?margin: 0;/,
    )
    expect(headerSource).toContain(
      'margin-inline: clamp(-9px, -1.5vw, -6px)',
    )
    expect(headerSource).toContain(
      'transform: translateX(clamp(6px, 1.7vw, 10px))',
    )
  })
})

describe('inspection portal relaxed layout contract', () => {
  it('uses the same generous icon and text scale as aerial service', () => {
    expect(categoryGridSource).toContain('gap: 12px 9px')
    expect(categoryGridSource).toContain('height: 73px')
    expect(categoryGridSource).toContain('font-size: 14px')
    expect(categoryGridSource).toContain('font-size: 10px')
    expect(guaranteeSource).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))')
    expect(guaranteeSource).toContain('width: 34px')
    expect(guaranteeSource).not.toContain('text-overflow: ellipsis')
    expect(hotServicesSource).toContain('height: 82px')
    expect(hotServicesSource).toContain('font-size: 12px')
  })

  it('reuses the shared compact Banner without changing the portal skeleton', () => {
    expect(inspectionHeroSource).toContain('<PlatformHeroBanner')
    expect(inspectionHeroSource).toContain(':highlights="hero.highlights"')
    expect(inspectionHeroSource).toContain("@action=\"$emit('request')\"")
    expect(platformHomePageSource).toContain('<PlatformHeroBanner')
    expect(portalPageSource).toMatch(
      /\.portal-skeleton__hero\s*\{[\s\S]*?height:\s*248rpx;/,
    )
  })
})

describe('inspection portal live weather contract', () => {
  it('uses real weather below the city instead of development display data', () => {
    expect(portalPageSource).toContain('createCurrentCityWeatherLoader')
    expect(portalPageSource).toContain(
      ':weather-label="weather.available ? weather.text : undefined"',
    )
    expect(portalPageSource).toContain(
      ':weather-icon-url="weather.available ? weather.iconUrl : undefined"',
    )
    expect(portalPageSource).not.toContain('developmentWeatherLabel')
  })
})

describe('root tab bar visual contract', () => {
  it('uses the approved icon library instead of text-glyph placeholders', () => {
    expect(tabBarSource).toContain(
      "import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'",
    )
    expect(tabBarSource).toContain("HOME: 'home'")
    expect(tabBarSource).toContain("SERVICE: 'smallcircle-filled'")
    expect(tabBarSource).toContain("DISCOVER: 'navigate'")
    expect(tabBarSource).toContain("MINE: 'person'")
    expect(tabBarSource).toContain('v-for="dot in 4"')
    expect(tabBarSource).toContain("MALL: 'shop'")
    expect(tabBarSource).toContain("MALL: 'shop-filled'")
    expect(tabBarSource).not.toContain('/static/icons/root-tabs/shopping-bag.png')
    expect(tabBarSource).toContain('class="root-tab-bar__icon"')
    expect(tabBarSource).toContain('size="30"')
    expect(tabBarSource).toContain('size="14"')
    expect(tabBarSource).toContain(
      'min-height: calc(58px + env(safe-area-inset-bottom))',
    )
    expect(tabBarSource).toMatch(
      /\.root-tab-bar__icon \{[\s\S]*?width: 30px;[\s\S]*?height: 30px;/,
    )
    expect(tabBarSource).not.toContain('{{ TAB_ICONS[tab.code] }}')
    expect(portalPageSource).toContain(
      'calc(clamp(58px, 11.5vw, 68px) + env(safe-area-inset-bottom))',
    )
    expect(portalPageSource).toContain(
      '.inspection-portal :deep(.root-tab-bar__label)',
    )
  })
})
